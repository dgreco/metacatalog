package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;

import it.davidgreco.metacatalog.entity.Entity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Covers {@link AggregateService#update}, through the YAML entry point that reaches it ({@link
 * BulkLoaderService#aggregateUpdate}), and the matching change to aggregate creation that lets a
 * downloaded document be uploaded again. The database is reset once per test class, so the model is
 * created by whichever test runs first and every test builds its own entities on top of it.
 */
@SpringBootTest
class AggregateUpdateTests extends CommonServiceTestingSupport {

  public AggregateUpdateTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @BeforeEach
  void createModel() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    if (traitService.exists("AuNamed")) return;

    traitService.create(
        "AuNamed",
        Optional.of(
            """
            { "type": "object", "properties": { "name": { "type": "string" } } }
            """),
        Optional.empty());
    // Anything named may depend on anything named: parts on parts, and a root on another root.
    traitService.link("AuNamed", DEPENDS_ON, "AuNamed");

    // Versionable, so a test can count how many versions an update records.
    entityTypeService.create(
        "AuRootType",
        List.of("Aggregate", "Versionable", "AuNamed"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "AuGroupType",
        List.of("Aggregate", "AggregateElement", "AuNamed"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "AuLeafType", List.of("AggregateElement", "AuNamed"), Optional.empty(), EMPTY_SCHEMA);
    // An asset root carries the fields a provisioning or authorization run writes.
    entityTypeService.create(
        "AuAssetType", List.of("Asset", "AuNamed"), Optional.empty(), EMPTY_SCHEMA);

    // A leaf whose instances a mapping derives a resource from.
    entityTypeService.create(
        "AuMappedLeafType", List.of("AggregateElement", "AuNamed"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create("AuResourceType", List.of("AuNamed"), Optional.empty(), EMPTY_SCHEMA);
    mappingService.create(
        "AuMappedLeafType",
        "AuResourceType",
        """
        { "name": "#source.getValue('$.name').asText() + '-resource'" }""",
        List.of());
  }

  /**
   * One update changes values, adds parts, removes parts and rewires dependencies, and a
   * versionable root gains exactly one version for all of it.
   */
  @Test
  void testUpdateReshapesTheAggregateInOneVersion() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    var rootId =
        create(
            """
            entityType: AuRootType
            values: { name: reshape }
            parts:
              - ref: a
                entityType: AuLeafType
                values: { name: a }
              - entityType: AuLeafType
                values: { name: b }
                dependsOn: [a]
              - entityType: AuGroupType
                values: { name: g }
                parts:
                  - entityType: AuLeafType
                    values: { name: g1 }
            """);
    var a = partNamed(rootId, "a");
    var b = partNamed(rootId, "b");
    var g = partNamed(rootId, "g");
    var g1 = partNamed(rootId, "g1");
    Assertions.assertEquals(1, entityService.listVersions(rootId).size());

    update(
        rootId,
        """
        id: %s
        entityType: AuRootType
        values: { name: reshaped }
        parts:
          - id: %s
            entityType: AuLeafType
            values: { name: a2 }
          - id: %s
            entityType: AuGroupType
            values: { name: g }
            parts:
              - entityType: AuLeafType
                values: { name: g2 }
                dependsOn: [%s]
          - ref: c
            entityType: AuLeafType
            values: { name: c }
        """
            .formatted(rootId, a.getId(), g.getId(), a.getId()));

    Assertions.assertEquals(Set.of("reshaped", "a2", "g", "g2", "c"), names(rootId));
    Assertions.assertEquals("a2", entityService.read(a.getId()).getValues().get("name").asText());
    // b and g1 were not listed, so they are gone — not merely detached.
    Assertions.assertFalse(entityService.exists(b.getId()));
    Assertions.assertFalse(entityService.exists(g1.getId()));

    var g2 = partNamed(rootId, "g2");
    Assertions.assertEquals(
        List.of(a.getId()),
        entityService.linked(g2.getId(), DEPENDS_ON).stream().map(Entity::getId).toList());

    Assertions.assertEquals(2, entityService.listVersions(rootId).size());
  }

  /**
   * Saying nothing about a node's dependencies leaves them alone; an explicit empty list removes
   * them. A document built without knowledge of a dependency must not drop it by omission.
   */
  @Test
  void testUndeclaredDependenciesAreKeptAndDeclaredOnesAreExact() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    var rootId =
        create(
            """
            entityType: AuRootType
            values: { name: deps }
            parts:
              - ref: a
                entityType: AuLeafType
                values: { name: deps-a }
              - entityType: AuLeafType
                values: { name: deps-b }
                dependsOn: [a]
            """);
    var a = partNamed(rootId, "deps-a");
    var b = partNamed(rootId, "deps-b");
    var document =
        """
        entityType: AuRootType
        values: { name: deps }
        parts:
          - id: %s
            entityType: AuLeafType
            values: { name: deps-a }
          - id: %s
            entityType: AuLeafType
            values: { name: deps-b }
        """
            .formatted(a.getId(), b.getId());

    update(rootId, document);
    Assertions.assertEquals(1, entityService.linked(b.getId(), DEPENDS_ON).size());

    update(rootId, document + "    dependencies: []\n");
    Assertions.assertTrue(entityService.linked(b.getId(), DEPENDS_ON).isEmpty());
  }

  /**
   * The fields a run writes come from the entity, never from the document: a document edited from
   * an earlier download would otherwise undo a run that completed in between.
   */
  @Test
  void testRunFieldsAreNeverTakenFromTheDocument() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    var rootId =
        create(
            """
            entityType: AuAssetType
            values: { name: run-fields }
            """);
    entityService.updateValues(
        rootId,
        """
        { "name": "run-fields", "provisioningStatus": "PROVISIONED" }""");

    update(
        rootId,
        """
        entityType: AuAssetType
        values: { name: run-fields-2, provisioningStatus: FAILED }
        """);
    var values = entityService.read(rootId).getValues();
    Assertions.assertEquals("run-fields-2", values.get("name").asText());
    Assertions.assertEquals("PROVISIONED", values.get("provisioningStatus").asText());

    // Leaving the status out does not clear it either.
    update(
        rootId,
        """
        entityType: AuAssetType
        values: { name: run-fields-3 }
        """);
    Assertions.assertEquals(
        "PROVISIONED", entityService.read(rootId).getValues().get("provisioningStatus").asText());
  }

  /** Every refusal leaves the aggregate exactly as it was: the update is one transaction. */
  @Test
  void testARefusedUpdateChangesNothing() {
    var rootId =
        create(
            """
            entityType: AuRootType
            values: { name: refuse }
            parts:
              - ref: a
                entityType: AuLeafType
                values: { name: refuse-a }
              - entityType: AuLeafType
                values: { name: refuse-b }
                dependsOn: [a]
              - entityType: AuGroupType
                values: { name: refuse-g }
            """);
    var otherRootId =
        create(
            """
            entityType: AuRootType
            values: { name: refuse-other }
            parts:
              - entityType: AuLeafType
                values: { name: refuse-foreign }
            """);
    var a = partNamed(rootId, "refuse-a");
    var b = partNamed(rootId, "refuse-b");
    var g = partNamed(rootId, "refuse-g");
    var foreign = partNamed(otherRootId, "refuse-foreign");
    var before = names(rootId);

    // The document is about another aggregate.
    assertRefused(
        rootId,
        "not the aggregate",
        """
        id: %s
        entityType: AuRootType
        values: { name: changed }
        """
            .formatted(otherRootId));
    // A node naming an entity that belongs to another aggregate.
    assertRefused(
        rootId,
        "is not part of the aggregate",
        """
        entityType: AuRootType
        values: { name: changed }
        parts:
          - id: %s
            entityType: AuLeafType
            values: { name: changed }
        """
            .formatted(foreign.getId()));
    // A type change.
    assertRefused(
        rootId,
        "cannot change it",
        """
        entityType: AuRootType
        values: { name: changed }
        parts:
          - id: %s
            entityType: AuGroupType
            values: { name: changed }
        """
            .formatted(a.getId()));
    // A part moved under another container.
    assertRefused(
        rootId,
        "cannot move a part",
        """
        entityType: AuRootType
        values: { name: changed }
        parts:
          - id: %s
            entityType: AuGroupType
            values: { name: refuse-g }
            parts:
              - id: %s
                entityType: AuLeafType
                values: { name: refuse-a }
          - id: %s
            entityType: AuLeafType
            values: { name: refuse-b }
        """
            .formatted(g.getId(), a.getId(), b.getId()));
    // Removing a part that a remaining part still depends on, without saying so.
    assertRefused(
        rootId,
        "stays in the aggregate",
        """
        entityType: AuRootType
        values: { name: changed }
        parts:
          - id: %s
            entityType: AuLeafType
            values: { name: refuse-b }
        """
            .formatted(b.getId()));
    // A dependency on something that is neither in the document nor in the catalog.
    assertRefused(
        rootId,
        "declares a dependency on 'nowhere'",
        """
        entityType: AuRootType
        values: { name: changed }
        dependsOn: [nowhere]
        """);

    Assertions.assertEquals(before, names(rootId));
  }

  /**
   * A removed part takes the entity a mapping derived from it along, and a document that lists such
   * a derived entity — downloaded with the mapped instances included — is still accepted.
   */
  @Test
  @org.junit.jupiter.api.Timeout(60)
  void testDerivedEntitiesFollowTheirPartAndAreIgnoredInTheDocument() {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    var rootId =
        create(
            """
            entityType: AuRootType
            values: { name: derived }
            parts:
              - entityType: AuMappedLeafType
                values: { name: derived-leaf }
            """);
    var leaf = partNamed(rootId, "derived-leaf");
    var resourceId = new String[1];
    Awaitility.await()
        .atMost(Durations.TEN_SECONDS)
        .until(
            () -> {
              mappingService.listAllEntityRelationships().stream()
                  .filter(mapping -> mapping.getRelationType() == MAPPED_TO)
                  .filter(mapping -> mapping.getSource().getId().equals(leaf.getId()))
                  .findFirst()
                  .ifPresent(mapping -> resourceId[0] = mapping.getTarget().getId());
              return resourceId[0] != null;
            });

    update(
        rootId,
        """
        entityType: AuRootType
        values: { name: derived }
        parts:
          - id: %s
            entityType: AuMappedLeafType
            values: { name: derived-leaf }
            parts:
              - id: %s
                entityType: AuResourceType
                values: { name: whatever-the-mapping-says }
        """
            .formatted(leaf.getId(), resourceId[0]));
    Assertions.assertTrue(entityService.exists(resourceId[0]));

    update(
        rootId,
        """
        entityType: AuRootType
        values: { name: derived }
        """);
    Assertions.assertFalse(entityService.exists(leaf.getId()));
    Assertions.assertFalse(entityService.exists(resourceId[0]));
  }

  /**
   * A document in the shape {@code GET /aggregate/{id}/yaml} serves creates a faithful copy: the
   * dependencies between its nodes point at the copies, and a dependency on an entity outside the
   * document is kept on that same entity.
   */
  @Test
  void testCreatingFromADownloadedDocumentKeepsItsDependencies() {
    var entityService = getApplicationContext().getBean(EntityService.class);

    var externalId =
        create(
            """
            entityType: AuRootType
            values: { name: copy-external }
            """);

    var copyId =
        create(
            """
            id: 11111111-aaaa-bbbb-cccc-000000000001
            entityType: AuRootType
            values: { name: copy }
            dependencies: [%s]
            parts:
              - id: 11111111-aaaa-bbbb-cccc-000000000002
                entityType: AuLeafType
                values: { name: copy-a }
              - id: 11111111-aaaa-bbbb-cccc-000000000003
                entityType: AuLeafType
                values: { name: copy-b }
                dependencies: [11111111-aaaa-bbbb-cccc-000000000002]
            """
                .formatted(externalId));

    // The ids of the document name its nodes; the created entities get ids of their own.
    Assertions.assertNotEquals("11111111-aaaa-bbbb-cccc-000000000001", copyId);
    Assertions.assertEquals(
        List.of(externalId),
        entityService.linked(copyId, DEPENDS_ON).stream().map(Entity::getId).toList());
    Assertions.assertEquals(
        List.of(partNamed(copyId, "copy-a").getId()),
        entityService.linked(partNamed(copyId, "copy-b").getId(), DEPENDS_ON).stream()
            .map(Entity::getId)
            .toList());
  }

  private String create(String yaml) {
    return getApplicationContext()
        .getBean(BulkLoaderService.class)
        .bulkAggregateCreation(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
        .getFirst();
  }

  private void update(String aggregateId, String yaml) {
    getApplicationContext()
        .getBean(BulkLoaderService.class)
        .aggregateUpdate(
            aggregateId, new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  private void assertRefused(String aggregateId, String expectedMessage, String yaml) {
    var error = Assertions.assertThrows(ServiceError.class, () -> update(aggregateId, yaml));
    Assertions.assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
  }

  /** The names of every member of the aggregate, root included. */
  private Set<String> names(String aggregateId) {
    var names = new TreeSet<String>();
    members(aggregateId).forEach(member -> names.add(member.getValues().get("name").asText()));
    return names;
  }

  private Entity partNamed(String aggregateId, String name) {
    return members(aggregateId).stream()
        .filter(member -> name.equals(member.getValues().get("name").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No member named " + name));
  }

  private List<Entity> members(String aggregateId) {
    var members = new ArrayList<Entity>();
    collect(
        getApplicationContext().getBean(AggregateService.class).read(aggregateId, false), members);
    return members;
  }

  private static void collect(AggregateService.AggregatePart part, List<Entity> into) {
    into.add(part.entity());
    if (part instanceof AggregateService.Aggregate aggregate) {
      aggregate.elements().forEach(element -> collect(element, into));
    }
  }
}
