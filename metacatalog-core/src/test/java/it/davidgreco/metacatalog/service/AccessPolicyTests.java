package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.AccessControl;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AccessPolicy}: parsing a root's declaration, refusing the ways it can be
 * wrong, and resolving it into per-resource grants.
 *
 * <p>The refusals are the point of most of these. Every one of them describes a policy that would
 * otherwise run to completion and report {@code AUTHORIZED} while granting nobody anything —
 * silence being exactly what an authorization model must not produce.
 */
class AccessPolicyTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static Entity resource(String id, String typeName, String values) {
    var type = new EntityType();
    type.setName(typeName);
    var entity = new Entity(type, json(values));
    entity.setId(id);
    return entity;
  }

  private static Entity root(String values) {
    return resource("root-1", "DataProductType", values);
  }

  private static final String TWO_PRINCIPALS_TWO_PERMISSIONS =
      """
      "principals": [
        {"id": "sales-analysts", "type": "GROUP", "description": "BI team"},
        {"id": "crm-pipeline", "type": "SERVICE"}
      ],
      "permissions": [
        {"name": "READ"},
        {"name": "WRITE"}
      ]""";

  @Test
  void aRootWithNoPolicyDeclaresNothing() {
    assertEquals(AccessPolicy.EMPTY, AccessPolicy.of(root("{\"name\": \"sales\"}")));
  }

  @Test
  void principalsPermissionsAndGrantsAreParsed() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [
                    {"principals": ["sales-analysts"], "permissions": ["READ"]},
                    {"principals": ["crm-pipeline"], "permissions": ["READ", "WRITE"],
                     "resources": {"entityTypes": ["PortType"], "values": {"namespace": "sales"}}}
                  ]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    assertEquals(2, policy.principals().size());
    assertEquals(AccessControl.PrincipalType.GROUP, policy.principals().getFirst().type());
    assertEquals("BI team", policy.principals().getFirst().description().orElseThrow());
    assertTrue(policy.principals().get(1).description().isEmpty());
    assertEquals(
        List.of("READ", "WRITE"),
        policy.permissions().stream().map(AccessPolicy.Permission::name).toList());
    assertEquals(AccessPolicy.ResourceSelector.ALL, policy.grants().getFirst().resources());
    assertEquals(List.of("PortType"), policy.grants().get(1).resources().entityTypes());
    assertEquals(json("\"sales\""), policy.grants().get(1).resources().values().get("namespace"));
  }

  @Test
  void anUnknownPrincipalTypeIsRefusedNamingIt() {
    var error =
        assertThrows(
            ServiceError.class,
            () ->
                AccessPolicy.of(root("{\"principals\": [{\"id\": \"a\", \"type\": \"ROBOT\"}]}")));
    assertTrue(error.getMessage().contains("ROBOT"), error.getMessage());
    assertTrue(error.getMessage().contains("SERVICE"), error.getMessage());
  }

  @Test
  void duplicatePrincipalIdsAreRefused() {
    var error =
        assertThrows(
            ServiceError.class,
            () ->
                AccessPolicy.of(
                    root(
                        """
                        {"principals": [{"id": "a", "type": "USER"}, {"id": "a", "type": "GROUP"}]}
                        """)));
    assertTrue(error.getMessage().contains("Duplicate principal name [a]"), error.getMessage());
  }

  @Test
  void aGrantNamingAnUndeclaredPrincipalIsRefused() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analyst"], "permissions": ["READ"]}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var error =
        assertThrows(
            ServiceError.class, () -> policy.resolve(List.of(resource("r1", "PortType", "{}"))));
    assertTrue(error.getMessage().contains("'sales-analyst'"), error.getMessage());
    assertTrue(error.getMessage().contains("sales-analysts"), error.getMessage());
  }

  @Test
  void aGrantNamingAnUndeclaredPermissionIsRefused() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["DELETE"]}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var error =
        assertThrows(
            ServiceError.class, () -> policy.resolve(List.of(resource("r1", "PortType", "{}"))));
    assertTrue(error.getMessage().contains("'DELETE'"), error.getMessage());
  }

  @Test
  void aSelectorMatchingNoResourceIsRefused() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["READ"],
                              "resources": {"entityTypes": ["TypoType"]}}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var error =
        assertThrows(
            ServiceError.class, () -> policy.resolve(List.of(resource("r1", "PortType", "{}"))));
    assertTrue(error.getMessage().contains("TypoType"), error.getMessage());
    assertTrue(error.getMessage().contains("matches none"), error.getMessage());
  }

  /**
   * An aggregate with nothing to authorize is a run with nothing to do, not a mistyped selector.
   * Refusing here would make an aggregate whose resources have not been modelled yet impossible to
   * write a policy for.
   */
  @Test
  void aSelectorMatchingNothingIsToleratedWhenThereIsNothingToMatch() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["READ"],
                              "resources": {"entityTypes": ["TypoType"]}}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    assertEquals(Map.of(), policy.resolve(List.of()));
  }

  @Test
  void aGrantWithNoSelectorReachesEveryResource() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["READ"]}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var resolved =
        policy.resolve(
            List.of(resource("r1", "IcebergPort", "{}"), resource("r2", "HivePort", "{}")));
    assertEquals(List.of("READ"), resolved.get("r1").getFirst().permissions());
    assertEquals(List.of("READ"), resolved.get("r2").getFirst().permissions());
  }

  @Test
  void selectorsNarrowByEntityTypeAndByValue() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [
                    {"principals": ["sales-analysts"], "permissions": ["READ"],
                     "resources": {"entityTypes": ["IcebergPort"]}},
                    {"principals": ["crm-pipeline"], "permissions": ["WRITE"],
                     "resources": {"values": {"namespace": "sales"}}}
                  ]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var iceberg = resource("r1", "IcebergPort", "{\"namespace\": \"sales\"}");
    var hive = resource("r2", "HivePort", "{\"namespace\": \"crm\"}");
    var resolved = policy.resolve(List.of(iceberg, hive));
    assertEquals(
        List.of("crm-pipeline", "sales-analysts"),
        resolved.get("r1").stream().map(g -> g.principal().id()).toList());
    assertEquals(List.of(), resolved.get("r2"));
  }

  @Test
  void numbersMatchRegardlessOfHowTheyWereSpelled() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["READ"],
                              "resources": {"values": {"version": 1}}}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var resolved = policy.resolve(List.of(resource("r1", "PortType", "{\"version\": 1.0}")));
    assertEquals(1, resolved.get("r1").size());
  }

  @Test
  void permissionsFromSeveralGrantsMergeSortedAndDeduplicated() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [
                    {"principals": ["sales-analysts"], "permissions": ["WRITE", "READ"]},
                    {"principals": ["sales-analysts"], "permissions": ["READ"]}
                  ]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var resolved = policy.resolve(List.of(resource("r1", "PortType", "{}")));
    assertEquals(1, resolved.get("r1").size());
    assertEquals(List.of("READ", "WRITE"), resolved.get("r1").getFirst().permissions());
  }

  @Test
  void aResourceNoGrantReachesResolvesToAnEmptyListRatherThanBeingAbsent() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["READ"],
                              "resources": {"entityTypes": ["IcebergPort"]}}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var resolved =
        policy.resolve(
            List.of(resource("r1", "IcebergPort", "{}"), resource("r2", "HivePort", "{}")));
    assertTrue(resolved.containsKey("r2"));
    assertEquals(List.of(), resolved.get("r2"));
  }

  @Test
  void resolvedGrantsRenderAsTheDeclaredJsonShape() {
    var policy =
        AccessPolicy.of(
            root(
                """
                {
                  %s,
                  "grants": [{"principals": ["sales-analysts"], "permissions": ["READ"]}]
                }
                """
                    .formatted(TWO_PRINCIPALS_TWO_PERMISSIONS)));
    var resolved = policy.resolve(List.of(resource("r1", "PortType", "{}")));
    assertEquals(
        json(
            """
            [{"principal": {"id": "sales-analysts", "type": "GROUP", "description": "BI team"},
              "permissions": ["READ"]}]
            """),
        AccessPolicy.toJson(resolved.get("r1")));
  }
}
