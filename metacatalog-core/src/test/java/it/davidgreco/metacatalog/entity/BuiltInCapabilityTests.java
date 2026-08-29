package it.davidgreco.metacatalog.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.service.AccessPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the schemas {@link BuiltInCapability} declares for its two traits, and — the point of most
 * of this — that what the machinery <em>writes</em> is exactly what those schemas <em>admit</em>.
 *
 * <p>That pairing is not decoration. A derived schema carries {@code additionalProperties: false},
 * so a field name the writer spells differently from the schema is refused by validation at the end
 * of an otherwise successful run. The traits are installed immutable, so at that point the schema
 * cannot be corrected and the only remedy is recreating the database. Every test here that
 * validates a rendered document against a derived schema exists to make that failure a red build
 * instead.
 */
class BuiltInCapabilityTests {

  private static final JsonUtils JSON_UTILS =
      new JsonUtils(
          new ObjectMapper(),
          new ObjectMapper(new YAMLFactory()),
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012));

  /**
   * The schema an entity of a type carrying only this trait would actually be validated against.
   */
  private static JsonSchema derivedSchemaOf(String traitSchema) {
    var parsed = JSON_UTILS.stringToJsonSchema(traitSchema);
    assertTrue(parsed.isRight(), () -> "not a valid schema: " + parsed.getLeft());
    var merged = JSON_UTILS.mergeSchemas(List.of(parsed.get().getSchemaNode()));
    assertTrue(merged.isRight(), () -> "not mergeable: " + merged.getLeft());
    assertFalse(
        merged.get().get("additionalProperties").asBoolean(true),
        "mergeSchemas is expected to close the schema");
    return JSON_UTILS.jsonSchemaFactory().getSchema(merged.get());
  }

  private static List<String> propertyNames(String schema) {
    var parsed = JSON_UTILS.stringToJsonSchema(schema);
    assertTrue(parsed.isRight(), () -> "not a valid schema: " + parsed.getLeft());
    var names = new ArrayList<String>();
    parsed.get().getSchemaNode().get("properties").fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static List<String> validate(JsonSchema schema, String document) {
    return schema.validate(document, InputFormat.JSON).stream().map(Object::toString).toList();
  }

  @Test
  void everyCapabilityDeclaresValidSchemasOnBothSides() {
    for (var capability : BuiltInCapability.values()) {
      assertTrue(
          JSON_UTILS.stringToJsonSchema(capability.rootSchema()).isRight(),
          capability + " root schema");
      assertTrue(
          JSON_UTILS.stringToJsonSchema(capability.resourceSchema()).isRight(),
          capability + " resource schema");
    }
  }

  @Test
  void provisioningStillCarriesTheSameSchemaOnBothSides() {
    assertEquals(
        BuiltInCapability.PROVISIONING.rootSchema(),
        BuiltInCapability.PROVISIONING.resourceSchema());
    assertEquals(
        List.of("provisioningStatus", "provisioningResult"),
        propertyNames(BuiltInCapability.PROVISIONING.rootSchema()));
    assertEquals(Optional.empty(), BuiltInCapability.PROVISIONING.grantsField());
  }

  @Test
  void authorizationPutsThePolicyOnTheRootAndTheAnswerOnTheResource() {
    assertEquals(
        List.of(
            "authorizationStatus",
            "authorizationResult",
            AccessControl.PRINCIPALS,
            AccessControl.PERMISSIONS,
            AccessControl.GRANTS),
        propertyNames(BuiltInCapability.AUTHORIZATION.rootSchema()));
    assertEquals(
        List.of("authorizationStatus", "authorizationResult", AccessControl.EFFECTIVE_GRANTS),
        propertyNames(BuiltInCapability.AUTHORIZATION.resourceSchema()));
    assertEquals(
        Optional.of(AccessControl.EFFECTIVE_GRANTS), BuiltInCapability.AUTHORIZATION.grantsField());
  }

  @Test
  void aWellFormedPolicyValidatesAgainstTheRootSchema() {
    assertEquals(
        List.of(),
        validate(
            derivedSchemaOf(BuiltInCapability.AUTHORIZATION.rootSchema()),
            """
            {
              "authorizationStatus": "AUTHORIZED",
              "principals": [
                {"id": "sales-analysts", "type": "GROUP", "description": "BI team"},
                {"id": "crm-pipeline", "type": "SERVICE"}
              ],
              "permissions": [{"name": "READ", "description": "Query it"}, {"name": "WRITE"}],
              "grants": [
                {"principals": ["sales-analysts"], "permissions": ["READ"]},
                {"principals": ["crm-pipeline"], "permissions": ["READ", "WRITE"],
                 "resources": {"entityTypes": ["PortType"], "values": {"namespace": "sales"}}}
              ]
            }
            """));
  }

  @Test
  void anUnknownPrincipalTypeIsRefusedByTheSchemaToo() {
    assertFalse(
        validate(
                derivedSchemaOf(BuiltInCapability.AUTHORIZATION.rootSchema()),
                "{\"principals\": [{\"id\": \"a\", \"type\": \"ROBOT\"}]}")
            .isEmpty());
  }

  @Test
  void aGrantNamingAFieldTheSelectorDoesNotHaveIsRefused() {
    assertFalse(
        validate(
                derivedSchemaOf(BuiltInCapability.AUTHORIZATION.rootSchema()),
                """
                {"grants": [{"principals": ["a"], "permissions": ["READ"],
                             "resources": {"entityTpes": ["X"]}}]}
                """)
            .isEmpty());
  }

  /**
   * The one that pays for the rest: what {@link AccessPolicy#toJson} produces is validated against
   * the schema an {@code AuthorizableResource} entity is actually written through. A field renamed
   * on one side and not the other fails here rather than at the end of a real run.
   */
  @Test
  void whatTheMachineryWritesIsWhatTheResourceSchemaAdmits() {
    var grants =
        List.of(
            new AccessPolicy.EffectiveGrant(
                new AccessPolicy.Principal(
                    "sales-analysts", AccessControl.PrincipalType.GROUP, Optional.of("BI team")),
                List.of("READ", "WRITE")),
            new AccessPolicy.EffectiveGrant(
                new AccessPolicy.Principal(
                    "crm-pipeline", AccessControl.PrincipalType.SERVICE, Optional.empty()),
                List.of("WRITE")));
    JsonNode document =
        JSON_UTILS
            .jsonMapper()
            .createObjectNode()
            .put("authorizationStatus", "AUTHORIZED")
            .put("authorizationResult", "done")
            .set(AccessControl.EFFECTIVE_GRANTS, AccessPolicy.toJson(grants));
    assertEquals(
        List.of(),
        validate(
            derivedSchemaOf(BuiltInCapability.AUTHORIZATION.resourceSchema()),
            document.toString()));
  }

  /** A resource no grant reached records an empty array, and that has to be admissible. */
  @Test
  void anEmptyGrantListIsAdmissible() {
    assertEquals(
        List.of(),
        validate(
            derivedSchemaOf(BuiltInCapability.AUTHORIZATION.resourceSchema()),
            "{\"" + AccessControl.EFFECTIVE_GRANTS + "\": []}"));
  }
}
