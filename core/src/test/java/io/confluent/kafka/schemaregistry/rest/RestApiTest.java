/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafka.schemaregistry.rest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.Response;

import io.confluent.kafka.schemaregistry.ClusterTestHarness;
import io.confluent.kafka.schemaregistry.avro.AvroCompatibilityLevel;
import io.confluent.kafka.schemaregistry.avro.AvroUtils;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.client.rest.utils.RestUtils;
import io.confluent.kafka.schemaregistry.rest.exceptions.Errors;
import io.confluent.kafka.schemaregistry.rest.exceptions.RestInvalidVersionException;
import io.confluent.kafka.schemaregistry.utils.TestUtils;

import static io.confluent.kafka.schemaregistry.avro.AvroCompatibilityLevel.FORWARD;
import static io.confluent.kafka.schemaregistry.avro.AvroCompatibilityLevel.NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RestApiTest extends ClusterTestHarness {

  public RestApiTest() {
    super(1, true);
  }

  @Test
  public void testBasic() throws Exception {
    String subject1 = "testTopic1";
    String subject2 = "testTopic2";
    int schemasInSubject1 = 10;
    List<Integer> allVersionsInSubject1 = new ArrayList<Integer>();
    List<String> allSchemasInSubject1 = TestUtils.getRandomCanonicalAvroString(schemasInSubject1);
    int schemasInSubject2 = 5;
    List<Integer> allVersionsInSubject2 = new ArrayList<Integer>();
    List<String> allSchemasInSubject2 = TestUtils.getRandomCanonicalAvroString(schemasInSubject2);
    List<String> allSubjects = new ArrayList<String>();

    // test getAllVersions with no existing data
    try {
      RestUtils.getAllVersions(restApp.restConnect, RestUtils.DEFAULT_REQUEST_PROPERTIES,
                               subject1);
      fail("Getting all versions from non-existing subject1 should throw a 404");
    } catch (RestClientException rce) {
      assertEquals("Should get a 404 status for non-existing subject",
                   Errors.SUBJECT_NOT_FOUND_ERROR_CODE,
                   rce.getErrorCode());
    }

    // test getAllSubjects with no existing data
    assertEquals("Getting all subjects should return empty",
                 allSubjects,
                 RestUtils
                     .getAllSubjects(restApp.restConnect, RestUtils.DEFAULT_REQUEST_PROPERTIES));

    // test registering and verifying new schemas in subject1
    int schemaIdCounter = 1;
    for (int i = 0; i < schemasInSubject1; i++) {
      String schema = allSchemasInSubject1.get(i);
      int expectedVersion = i + 1;
      TestUtils.registerAndVerifySchema(restApp.restConnect, schema, schemaIdCounter,
                                        subject1);
      schemaIdCounter++;
      allVersionsInSubject1.add(expectedVersion);
    }
    allSubjects.add(subject1);

    // test re-registering existing schemas
    for (int i = 0; i < schemasInSubject1; i++) {
      int expectedId = i + 1;
      String schemaString = allSchemasInSubject1.get(i);
      int foundId = TestUtils.registerSchema(restApp.restConnect, schemaString, subject1);
      assertEquals("Re-registering an existing schema should return the existing version",
                   expectedId, foundId);
    }

    // test registering schemas in subject2
    for (int i = 0; i < schemasInSubject2; i++) {
      String schema = allSchemasInSubject2.get(i);
      int expectedVersion = i + 1;
      TestUtils.registerAndVerifySchema(restApp.restConnect, schema, schemaIdCounter,
                                        subject2);
      schemaIdCounter++;
      allVersionsInSubject2.add(expectedVersion);
    }
    allSubjects.add(subject2);

    // test getAllVersions with existing data
    assertEquals("Getting all versions from subject1 should match all registered versions",
                 allVersionsInSubject1,
                 RestUtils.getAllVersions(restApp.restConnect, RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                          subject1));
    assertEquals("Getting all versions from subject2 should match all registered versions",
                 allVersionsInSubject2,
                 RestUtils.getAllVersions(restApp.restConnect, RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                          subject2));

    // test getAllSubjects with existing data
    assertEquals("Getting all subjects should match all registered subjects",
                 allSubjects,
                 RestUtils
                     .getAllSubjects(restApp.restConnect, RestUtils.DEFAULT_REQUEST_PROPERTIES));
  }

  @Test
  public void testRegisterSameSchemaOnDifferentSubject() throws Exception {
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    int id1 = TestUtils.registerSchema(restApp.restConnect, schema, "subject1");
    int id2 = TestUtils.registerSchema(restApp.restConnect, schema, "subject2");
    assertEquals("Registering the same schema under different subjects should return the same id",
                 id1, id2);
  }

  @Test
  public void testCompatibleSchemaLookupBySubject() throws Exception {
    String subject = "testSubject";
    int numRegisteredSchemas = 0;
    int numSchemas = 10;

    List<String> allSchemas = TestUtils.getRandomCanonicalAvroString(numSchemas);
    TestUtils.changeCompatibility(restApp.restConnect, NONE, subject);

    TestUtils.registerSchema(restApp.restConnect, allSchemas.get(0), subject);
    numRegisteredSchemas++;

    // test compatibility of this schema against the latest version under the subject
    String schema1 = allSchemas.get(0);
    boolean isCompatible =
        TestUtils.testCompatibility(restApp.restConnect, schema1, subject, "latest");
    assertTrue("First schema registered should be compatible", isCompatible);

    for (int i = 0; i < numSchemas; i++) {
      // Test that compatibility check doesn't change the number of versions
      String schema = allSchemas.get(i);
      isCompatible = TestUtils.testCompatibility(restApp.restConnect, schema, subject, "latest");
      TestUtils.checkNumberOfVersions(restApp.restConnect, numRegisteredSchemas, subject);
    }
  }

  @Test
  public void testIncompatibleSchemaLookupBySubject() throws Exception {
    String subject = "testSubject";

    // Make two incompatible schemas - field 'f' has different types
    String schema1String = "{\"type\":\"record\","
                           + "\"name\":\"myrecord\","
                           + "\"fields\":"
                           + "[{\"type\":\"string\",\"name\":"
                           + "\"f" + "\"}]}";
    String schema1 = AvroUtils.parseSchema(schema1String).canonicalString;

    String schema2String = "{\"type\":\"record\","
                           + "\"name\":\"myrecord\","
                           + "\"fields\":"
                           + "[{\"type\":\"int\",\"name\":"
                           + "\"f" + "\"}]}";
    String schema2 = AvroUtils.parseSchema(schema2String).canonicalString;

    // ensure registering incompatible schemas will raise an error
    TestUtils.changeCompatibility(
        restApp.restConnect, AvroCompatibilityLevel.FULL, subject);

    // test that compatibility check for incompatible schema returns false and the appropriate 
    // error response from Avro
    TestUtils.registerSchema(restApp.restConnect, schema1, subject);
    int versionOfRegisteredSchema =
        TestUtils.lookUpSubjectVersion(restApp.restConnect, schema1, subject).getVersion();
    boolean isCompatible = TestUtils.testCompatibility(restApp.restConnect, schema2, subject,
                                                       String.valueOf(versionOfRegisteredSchema));
    assertFalse("Schema should be incompatible with specified version", isCompatible);
  }

  @Test
  public void testSchemaRegistrationUnderDiffSubjects() throws Exception {
    String subject1 = "testSubject1";
    String subject2 = "testSubject2";

    // Make two incompatible schemas - field 'f' has different types
    String schemaString1 = "{\"type\":\"record\","
                           + "\"name\":\"myrecord\","
                           + "\"fields\":"
                           + "[{\"type\":\"string\",\"name\":"
                           + "\"f" + "\"}]}";
    String schema1 = AvroUtils.parseSchema(schemaString1).canonicalString;
    String schemaString2 = "{\"type\":\"record\","
                           + "\"name\":\"myrecord\","
                           + "\"fields\":"
                           + "[{\"type\":\"int\",\"name\":"
                           + "\"foo" + "\"}]}";
    String schema2 = AvroUtils.parseSchema(schemaString2).canonicalString;

    TestUtils.changeCompatibility(
        restApp.restConnect, AvroCompatibilityLevel.NONE, subject1);
    TestUtils.changeCompatibility(
        restApp.restConnect, AvroCompatibilityLevel.NONE, subject2);

    int idOfRegisteredSchema1Subject1 =
        TestUtils.registerSchema(restApp.restConnect, schema1, subject1);
    int versionOfRegisteredSchema1Subject1 =
        TestUtils.lookUpSubjectVersion(restApp.restConnect, schema1, subject1).getVersion();
    assertEquals("1st schema under subject1 should have version 1", 1,
                 versionOfRegisteredSchema1Subject1);
    assertEquals("1st schema registered globally should have id 1", 1,
                 idOfRegisteredSchema1Subject1);

    int idOfRegisteredSchema2Subject1 =
        TestUtils.registerSchema(restApp.restConnect, schema2, subject1);
    int versionOfRegisteredSchema2Subject1 =
        TestUtils.lookUpSubjectVersion(restApp.restConnect, schema2, subject1).getVersion();
    assertEquals("2nd schema under subject1 should have version 2", 2,
                 versionOfRegisteredSchema2Subject1);
    assertEquals("2nd schema registered globally should have id 2", 2,
                 idOfRegisteredSchema2Subject1);

    int idOfRegisteredSchema2Subject2 =
        TestUtils.registerSchema(restApp.restConnect, schema2, subject2);
    int versionOfRegisteredSchema2Subject2 =
        TestUtils.lookUpSubjectVersion(restApp.restConnect, schema2, subject2).getVersion();
    assertEquals(
        "2nd schema under subject1 should still have version 1 as the first schema under subject2",
        1,
        versionOfRegisteredSchema2Subject2);
    assertEquals("Since schema is globally registered but not under subject2, id should not change",
                 2,
                 idOfRegisteredSchema2Subject2);
  }

  @Test
  public void testConfigDefaults() throws Exception {
    String subject = "testSubject";
    assertEquals("Default compatibility level should be none for this test instance",
                 NONE.name,
                 RestUtils.getConfig(restApp.restConnect,
                                     RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                     null).getCompatibilityLevel());

    // change it to forward
    TestUtils.changeCompatibility(restApp.restConnect, AvroCompatibilityLevel.FORWARD, null);

    assertEquals("New compatibility level should be forward for this test instance",
                 FORWARD.name,
                 RestUtils.getConfig(restApp.restConnect,
                                     RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                     null).getCompatibilityLevel());

    assertNull("Default compatibility level should not match current top level config for this "
               + "subject",
               RestUtils.getConfig(restApp.restConnect,
                                   RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                   subject).getCompatibilityLevel());

  }

  @Test
  public void testNonExistentSubjectConfigChange() throws Exception {
    String subject = "testSubject";
    try {
      TestUtils.changeCompatibility(restApp.restConnect, AvroCompatibilityLevel.FORWARD, subject);
    } catch (RestClientException e) {
      fail("Changing config for an invalid subject should succeed");
    }
    assertEquals("New compatibility level for this subject should be forward",
                 FORWARD.name,
                 RestUtils.getConfig(restApp.restConnect,
                                     RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                     subject).getCompatibilityLevel());
  }

  @Test
  public void testSubjectConfigChange() throws Exception {
    String subject = "testSubject";
    String schemaString = TestUtils.getRandomCanonicalAvroString(1).get(0);
    assertEquals("Default compatibility level should be none for this test instance",
                 NONE.name,
                 RestUtils.getConfig(restApp.restConnect,
                                     RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                     null).getCompatibilityLevel());

    // change subject compatibility to forward
    TestUtils.changeCompatibility(restApp.restConnect, AvroCompatibilityLevel.FORWARD, subject);

    assertEquals("Global compatibility level should remain none for this test instance",
                 NONE.name,
                 RestUtils.getConfig(restApp.restConnect,
                                     RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                     null).getCompatibilityLevel());

    assertEquals("New compatibility level for this subject should be forward",
                 FORWARD.name,
                 RestUtils.getConfig(restApp.restConnect,
                                     RestUtils.DEFAULT_REQUEST_PROPERTIES,
                                     subject).getCompatibilityLevel());

  }
  
  @Test
  public void testGetSchemaNonExistingId() throws Exception {
    try {
      TestUtils.getId(restApp.restConnect, 100);
      fail("Schema lookup by non-existing id should fail with a 404");
    } catch (RestClientException rce) {
      // this is expected.
      assertEquals("Should get a 404 status for non-existing id",
                   Errors.SCHEMA_NOT_FOUND_ERROR_CODE,
                   rce.getErrorCode());
    }
  }
  
  @Test
  public void testListVersionsNonExistingSubject() throws Exception {
    try {
      TestUtils.getSubjectVersions(restApp.restConnect, "Invalid");
    } catch (RestClientException rce) {
      // this is expected.
      assertEquals("Should get a 404 status for non-existing subject",
                   Errors.SUBJECT_NOT_FOUND_ERROR_CODE,
                   rce.getErrorCode());
    }
  }

  @Test
  public void testGetVersionNonExistentSubject() throws Exception {
    // test getVersion on a non-existing subject
    try {
      RestUtils.getVersion(restApp.restConnect, RestUtils.DEFAULT_REQUEST_PROPERTIES,
                           "non-existing-subject", 1);
    } catch (RestClientException e) {
      // this is expected.
      assertEquals("Unregistered subject shouldn't be found in getVersion()",
                   Errors.SUBJECT_NOT_FOUND_ERROR_CODE,
                   e.getErrorCode());
    }
  }

  @Test
  public void testGetNonExistingVersion() throws Exception {
    // test getVersion on a non-existing version
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    String subject = "test";
    TestUtils.registerAndVerifySchema(restApp.restConnect, schema, 1, subject);
    try {
      RestUtils.getVersion(restApp.restConnect,
                           RestUtils.DEFAULT_REQUEST_PROPERTIES, subject,
                           200);
    } catch (RestClientException e) {
      // this is expected.
      assertEquals("Unregistered version shouldn't be found", 
                   Errors.VERSION_NOT_FOUND_ERROR_CODE, e.getErrorCode());
    }
  }

  @Test
  public void testGetInvalidVersion() throws Exception {
    // test getVersion on a non-existing version
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    String subject = "test";
    TestUtils.registerAndVerifySchema(restApp.restConnect, schema, 1, subject);
    try {
      RestUtils.getVersion(restApp.restConnect,
                           RestUtils.DEFAULT_REQUEST_PROPERTIES, subject,
                           0);
    } catch (RestClientException e) {
      // this is expected.
      assertEquals("Invalid version shouldn't be found",
                   RestInvalidVersionException.ERROR_CODE,
                   e.getErrorCode());
    }
  }

  @Test
  public void testGetVersion() throws Exception {
    List<String> schemas = TestUtils.getRandomCanonicalAvroString(2);
    String subject = "test";
    TestUtils.registerAndVerifySchema(restApp.restConnect, schemas.get(0), 1, subject);
    TestUtils.registerAndVerifySchema(restApp.restConnect, schemas.get(1), 2, subject);

    assertEquals("Version 1 schema should match",
                 schemas.get(0),
                 RestUtils.getVersion(restApp.restConnect,
                                      RestUtils.DEFAULT_REQUEST_PROPERTIES, subject,
                                      1).getSchema());

    assertEquals("Version 2 schema should match",
                 schemas.get(1),
                 RestUtils.getVersion(restApp.restConnect,
                                      RestUtils.DEFAULT_REQUEST_PROPERTIES, subject,
                                      2).getSchema());
    assertEquals("Latest schema should be the same as version 2",
                 schemas.get(1),
                 RestUtils.getLatestVersion(restApp.restConnect,
                                      RestUtils.DEFAULT_REQUEST_PROPERTIES, subject).getSchema());
  }

  @Test
  public void testLookUpSchemaUnderNonExistentSubject() throws Exception {
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    try {
      TestUtils.lookUpSubjectVersion(restApp.restConnect, schema, "non-existent-subject");  
    } catch (RestClientException rce) {
      assertEquals("Subject not found", 
                   Errors.SUBJECT_NOT_FOUND_ERROR_CODE, 
                   rce.getErrorCode());
    }
  }

  @Test
  public void testLookUpNonExistentSchemaUnderSubject() throws Exception {
    String subject = "test";
    List<String> schemas = TestUtils.getRandomCanonicalAvroString(2);
    TestUtils.registerAndVerifySchema(restApp.restConnect, schemas.get(0), 1, subject);
    TestUtils.changeCompatibility(restApp.restConnect, AvroCompatibilityLevel.NONE, subject);

    try {
      TestUtils.lookUpSubjectVersion(restApp.restConnect, schemas.get(1), subject);
    } catch (RestClientException rce) {
      assertEquals("Schema not found", Errors.SCHEMA_NOT_FOUND_ERROR_CODE, rce.getErrorCode());
    }
  }

  @Test
  public void testCompatibilityNonExistentSubject() throws Exception {
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    try {
      TestUtils.testCompatibility(restApp.restConnect, schema, "non-existent-subject", "latest");
    } catch (RestClientException rce) {
      assertEquals("Subject not found", Errors.SUBJECT_NOT_FOUND_ERROR_CODE, rce.getErrorCode());
    }
  }

  @Test
  public void testCompatibilityNonExistentVersion() throws Exception {
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    String subject = "test";
    TestUtils.registerAndVerifySchema(restApp.restConnect, schema, 1, subject);
    try {
      TestUtils.testCompatibility(restApp.restConnect, schema, subject, "100");
    } catch (RestClientException rce) {
      assertEquals("Version not found", Errors.VERSION_NOT_FOUND_ERROR_CODE, rce.getErrorCode());
    }
  }

  @Test
  public void testCompatibilityInvalidVersion() throws Exception {
    String schema = TestUtils.getRandomCanonicalAvroString(1).get(0);
    String subject = "test";
    TestUtils.registerAndVerifySchema(restApp.restConnect, schema, 1, subject);
    try {
      TestUtils.testCompatibility(restApp.restConnect, schema, subject, "earliest");
    } catch (RestClientException rce) {
      assertEquals("Version not found",
                   RestInvalidVersionException.ERROR_CODE,
                   rce.getErrorCode());
    }
  }

  @Test
  public void testGetConfigNonExistentSubject() throws Exception {
    try {
      RestUtils.getConfig(restApp.restConnect,
                          RestUtils.DEFAULT_REQUEST_PROPERTIES,
                          "non-existent-subject");      
    } catch (RestClientException rce) {
      assertEquals("Subject not found",
                   Response.Status.NOT_FOUND.getStatusCode(),
                   rce.getErrorCode());
    }
  }

  @Test
  public void testCanonicalization() throws Exception {
    // schema string with extra white space
    String schema = "{   \"type\":   \"string\"}";
    String subject = "test";
    assertEquals("Registering a new schema should succeed",
                 1,
                 TestUtils.registerSchema(restApp.restConnect, schema, subject));

    assertEquals("Registering the same schema should get back the same id",
                 1,
                 TestUtils.registerSchema(restApp.restConnect, schema, subject));

    assertEquals("Lookup the same schema should get back the same id",
                 1,
                 TestUtils.lookUpSubjectVersion(restApp.restConnect, schema, subject)
                     .getId().intValue());
  }
}

