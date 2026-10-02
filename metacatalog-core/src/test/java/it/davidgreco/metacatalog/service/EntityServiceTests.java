package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.repository.EntityRepository;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.json.JsonAssert;

@SpringBootTest
class EntityServiceTests extends CommonServiceTestingSupport {

  public EntityServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreationAndValidationAndUpdate() throws IOException, ServiceError {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);

    var schema = schemaResource("jsons/simple_schema.json").toPrettyString();

    var values =
        jsonMapper
            .readTree(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/simple_doc.json"))
            .toPrettyString();

    var invalidValues =
        jsonMapper
            .readTree(
                Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream("jsons/invalid_simple_doc.json"))
            .toPrettyString();

    var testType = entityTypeService.create("TestType", List.of(), Optional.empty(), schema);

    var entity = entityService.create("TestType", values);

    SchemaValidationError exception =
        assertThrows(
            SchemaValidationError.class, () -> entityService.create("TestType", invalidValues));

    Assertions.assertEquals(
        "$.price: must have an exclusive minimum value of 0", exception.getErrors().getFirst());

    Assertions.assertEquals(1, entityRepository.countByEntityType(testType));

    Assertions.assertThrows(ServiceError.class, () -> entityTypeService.delete("TestType"));

    var updatedValues =
        """
                {
                  "name": "Lampshade",
                  "price": 2
                }
                """;

    entityService.update(entity.getId(), updatedValues);

    var newValues = entityService.read(entity.getId()).getValues().toPrettyString();

    JsonAssert.comparator(JSONCompareMode.NON_EXTENSIBLE).assertIsMatch(newValues, updatedValues);

    entityService.delete(entity.getId());

    entityTypeService.delete("TestType");

    Assertions.assertEquals(0, entityRepository.countByEntityType(testType));
  }

  @Test
  void testLinkUnlinkLinkedEntities() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var emptySchema =
        """
                {
                  "type": "object",
                  "properties": {}
                }
                """;

    var emptyValues =
        """
                {}
                """;

    traitService.create("SourceTrait", Optional.empty(), Optional.empty());

    traitService.create("TargetTrait", Optional.empty(), Optional.empty());

    traitService.create("InheritedSourceTrait", Optional.empty(), Optional.of("SourceTrait"));

    traitService.create("InheritedTargetTrait", Optional.empty(), Optional.of("TargetTrait"));

    entityTypeService.create(
        "SourceEntityType", List.of("InheritedSourceTrait"), Optional.empty(), emptySchema);

    entityTypeService.create(
        "TargetEntityType", List.of("InheritedTargetTrait"), Optional.empty(), emptySchema);

    traitService.link("SourceTrait", DEPENDS_ON, "TargetTrait");

    var sourceEntity = entityService.create("SourceEntityType", emptyValues);

    var targetEntity = entityService.create("TargetEntityType", emptyValues);

    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(sourceEntity.getId(), HAS_PART, targetEntity.getId()));

    entityService.link(sourceEntity.getId(), DEPENDS_ON, targetEntity.getId());

    // The inverse relation should be created automatically
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(targetEntity.getId(), IS_REQUIRED_BY, sourceEntity.getId()));

    // No loops
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(targetEntity.getId(), DEPENDS_ON, sourceEntity.getId()));

    // No multiple link between source and target
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.link(sourceEntity.getId(), DEPENDS_ON, targetEntity.getId()));
  }

  @Test
  void testList() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "TestType",
        List.of(),
        Optional.empty(),
        """
                    {
                      "type": "object",
                      "properties": {
                        "a": {
                          "type": "string"
                        },
                        "b": {
                          "type": "integer"
                        }
                      },
                      "required": ["a", "b"]
                    }
                    """);

    var e1 =
        entityService.create(
            "TestType",
            """
                {
                  "a": "a",
                  "b": 1
                }
                """);

    var e2 =
        entityService.create(
            "TestType",
            """
                {
                  "a": "b",
                  "b": 2
                }
                """);

    var entities1 =
        entityService.list(
            "TestType",
            """
                           $ ? (@.a == "a" && @.b == 1)
                         """);
    Assertions.assertEquals(1, entities1.size());
    Assertions.assertEquals(e1.getId(), entities1.getFirst().getId());

    var entities2 =
        entityService.list(
            "TestType",
            """
                       $ ? (@.a == "b" && @.b > 1)
                     """);
    Assertions.assertEquals(1, entities2.size());
    Assertions.assertEquals(e2.getId(), entities2.getFirst().getId());
  }

  @Test
  void testEntityIsPinnedToCreationVersion() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" } },
          "required": ["name"],
          "additionalProperties": false
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
          "required": ["name", "value"],
          "additionalProperties": false
        }
        """;

    entityTypeService.create("PinnedType", List.of(), Optional.empty(), schemaV1);

    var valuesV1 =
        """
        { "name": "alpha" }
        """;
    var entity = entityService.create("PinnedType", valuesV1);
    Assertions.assertNotNull(entity.getEntityTypeVersion());
    Assertions.assertEquals(1, entity.getEntityTypeVersion().getVersion());
    Assertions.assertFalse(
        entity.getEntityTypeVersion().getSchema().get("properties").has("value"));

    entityTypeService.createVersion("PinnedType", List.of(), Optional.empty(), schemaV2);

    var live = entityTypeService.read("PinnedType");
    Assertions.assertEquals(2, live.getVersion());

    var reloaded = entityService.read(entity.getId());
    Assertions.assertEquals(1, reloaded.getEntityTypeVersion().getVersion());
    Assertions.assertFalse(
        reloaded.getEntityTypeVersion().getSchema().get("properties").has("value"));

    var valuesWithoutValue =
        """
        { "name": "beta" }
        """;
    entityService.update(entity.getId(), valuesWithoutValue);

    var valuesV2 =
        """
        { "name": "delta", "value": 42 }
        """;
    SchemaValidationError extraPropertyRejected =
        assertThrows(
            SchemaValidationError.class, () -> entityService.update(entity.getId(), valuesV2));
    Assertions.assertFalse(extraPropertyRejected.getErrors().isEmpty());

    SchemaValidationError newEntityMissingValueRejected =
        assertThrows(
            SchemaValidationError.class,
            () -> entityService.create("PinnedType", valuesWithoutValue));
    Assertions.assertFalse(newEntityMissingValueRejected.getErrors().isEmpty());

    var entityV2 = entityService.create("PinnedType", valuesV2);
    Assertions.assertEquals(2, entityV2.getEntityTypeVersion().getVersion());

    SchemaValidationError v2UpdateRejected =
        assertThrows(
            SchemaValidationError.class,
            () ->
                entityService.update(
                    entityV2.getId(),
                    """
                    { "name": "epsilon" }
                    """));
    Assertions.assertFalse(v2UpdateRejected.getErrors().isEmpty());

    entityService.delete(entity.getId());
    entityService.delete(entityV2.getId());
    entityTypeService.delete("PinnedType");
  }

  @Test
  void testDeleteVersionRefusedWhenEntityPinned() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" } },
          "required": ["name"]
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
          "required": ["name", "value"]
        }
        """;

    entityTypeService.create("PinnedDeleteType", List.of(), Optional.empty(), schemaV1);
    var entity =
        entityService.create(
            "PinnedDeleteType",
            """
            { "name": "alpha" }
            """);
    Assertions.assertEquals(1, entity.getEntityTypeVersion().getVersion());

    entityTypeService.createVersion("PinnedDeleteType", List.of(), Optional.empty(), schemaV2);

    Assertions.assertThrows(
        ServiceError.class, () -> entityTypeService.deleteVersion("PinnedDeleteType", 1));

    entityService.delete(entity.getId());
    entityTypeService.delete("PinnedDeleteType");
  }

  @Test
  void testDeleteAllVersionsPreservesCurrentSnapshot() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var entityTypeVersionRepository =
        getApplicationContext()
            .getBean(it.davidgreco.metacatalog.repository.EntityTypeVersionRepository.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" } },
          "required": ["name"]
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "name": { "type": "string" }, "value": { "type": "number" } },
          "required": ["name", "value"]
        }
        """;

    entityTypeService.create("DeleteAllVersionsType", List.of(), Optional.empty(), schemaV1);
    entityTypeService.createVersion("DeleteAllVersionsType", List.of(), Optional.empty(), schemaV2);

    var live = entityTypeService.read("DeleteAllVersionsType");
    Assertions.assertEquals(2, live.getVersion());

    var entity =
        entityService.create(
            "DeleteAllVersionsType",
            """
            { "name": "alpha", "value": 1 }
            """);
    Assertions.assertEquals(2, entity.getEntityTypeVersion().getVersion());

    entityTypeService.deleteAllVersions("DeleteAllVersionsType");

    var currentSnapshot =
        entityTypeVersionRepository.findByVersionGroupIdAndVersion(
            live.getVersionGroupId(), live.getVersion());
    Assertions.assertTrue(
        currentSnapshot.isPresent(), "current version snapshot must be preserved");

    var reloaded = entityService.read(entity.getId());
    Assertions.assertNotNull(reloaded.getEntityTypeVersion(), "entity must remain pinned");

    entityService.delete(entity.getId());
    entityTypeService.delete("DeleteAllVersionsType");
  }

  /**
   * Verifies that entities whose type is the target of a mapping relationship cannot be created,
   * updated, or deleted through the user-facing {@code update}/{@code create}/{@code delete}
   * methods — even when the target type implements the {@code ProvisionableResource} trait (which
   * was previously exempted and allowed direct updates, e.g. {@code AthenaTableType}).
   *
   * <p>Also verifies that the internal {@code updateValues} method (used by the provisioning task
   * to write back {@code provisioningStatus}/{@code provisioningResult}) still works on mapped
   * entities.
   */
  @Test
  void testMappingTargetEntityCannotBeUpdatedOrDeletedDirectly() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);

    var emptySchema =
        """
        { "type": "object", "properties": {} }
        """;

    entityTypeService.create("BlockSource", List.of(), Optional.empty(), emptySchema);

    var targetType =
        entityTypeService.create(
            "BlockTarget",
            List.of("ProvisionableResource"),
            Optional.empty(),
            """
            { "type": "object", "properties": {} }
            """);

    mappingService.create("BlockSource", "BlockTarget", "{}", List.of());

    var sourceEntity = entityService.create("BlockSource", "{}");

    mappedEntityService.createMappedEntities(sourceEntity.getId());

    var mappedEntities = entityRepository.findByEntityType(targetType);
    Assertions.assertEquals(1, mappedEntities.size(), "mapped entity must have been created");
    var mappedEntity = mappedEntities.getFirst();

    // Creating a target entity directly is not allowed
    Assertions.assertThrows(ServiceError.class, () -> entityService.create("BlockTarget", "{}"));

    // Updating a target entity is not allowed — even with ProvisionableResource trait
    Assertions.assertThrows(
        ServiceError.class, () -> entityService.update(mappedEntity.getId(), "{}"));

    // Deleting a target entity is not allowed
    Assertions.assertThrows(ServiceError.class, () -> entityService.delete(mappedEntity.getId()));

    // The internal updateValues path still works (used by the provisioning task)
    entityService.updateValues(mappedEntity.getId(), "{}");
    var afterUpdate = entityService.read(mappedEntity.getId());
    Assertions.assertNotNull(afterUpdate, "entity must still exist after updateValues");

    // Cleanup
    mappedEntityService.deleteMappedEntities(sourceEntity.getId());
    entityRepository.delete(sourceEntity);
    mappingService.list().forEach(m -> mappingService.delete(m.getId()));
    entityTypeService.delete("BlockSource");
    entityTypeService.delete("BlockTarget");
  }

  /**
   * Verifies that an entity with relationships cannot be deleted and produces a clear error message
   * instead of a generic data-integrity-violation.
   */
  @Test
  void testEntityWithRelationshipsCannotBeDeleted() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var emptySchema =
        """
        { "type": "object", "properties": {} }
        """;

    traitService.create("RelSourceTrait", Optional.empty(), Optional.empty());
    traitService.create("RelTargetTrait", Optional.empty(), Optional.empty());
    traitService.link("RelSourceTrait", DEPENDS_ON, "RelTargetTrait");

    entityTypeService.create("RelSource", List.of("RelSourceTrait"), Optional.empty(), emptySchema);
    entityTypeService.create("RelTarget", List.of("RelTargetTrait"), Optional.empty(), emptySchema);

    var source = entityService.create("RelSource", "{}");
    var target = entityService.create("RelTarget", "{}");
    entityService.link(source.getId(), DEPENDS_ON, target.getId());

    var ex =
        Assertions.assertThrows(ServiceError.class, () -> entityService.delete(target.getId()));
    Assertions.assertTrue(ex.getMessage().contains("has relationships"));

    entityService.unlink(source.getId(), DEPENDS_ON, target.getId());
    entityService.delete(source.getId());
    entityService.delete(target.getId());
    entityTypeService.delete("RelSource");
    entityTypeService.delete("RelTarget");
    traitService.unlink("RelSourceTrait", DEPENDS_ON, "RelTargetTrait");
    traitService.delete("RelTargetTrait");
    traitService.delete("RelSourceTrait");
  }

  /**
   * Verifies that a DEPENDS_ON link a mapping resolves a path reference through cannot be removed
   * (in either direction of the pair) while a mapped entity is derived through it: removing it
   * would make every later update of that mapped entity fail asynchronously. The check is exact —
   * an identical link no mapping traverses stays removable, and once the mapped entities are gone
   * the guarded link becomes removable too.
   */
  @Test
  void testDependsOnLinkReferredByAMappingCannotBeRemoved() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);

    var emptySchema =
        """
        { "type": "object", "properties": {} }
        """;

    traitService.create("PathSrcTrait", Optional.empty(), Optional.empty());
    traitService.create("PathDepTrait", Optional.empty(), Optional.empty());
    traitService.link("PathSrcTrait", DEPENDS_ON, "PathDepTrait");

    entityTypeService.create("PathSource", List.of("PathSrcTrait"), Optional.empty(), emptySchema);
    entityTypeService.create("PathDep", List.of("PathDepTrait"), Optional.empty(), emptySchema);
    entityTypeService.create("PathMapped", List.of(), Optional.empty(), emptySchema);

    // The mapping resolves an extra entity by walking IS_REQUIRED_BY from the source — i.e.
    // through the source's DEPENDS_ON link.
    mappingService.create(
        "PathSource",
        "PathMapped",
        "{}",
        List.of(
            new it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship.EntityPathReference(
                "dep", "IS_REQUIRED_BY{$}")));

    var source = entityService.create("PathSource", "{}");
    var dependency = entityService.create("PathDep", "{}");
    entityService.link(source.getId(), DEPENDS_ON, dependency.getId());
    mappedEntityService.createMappedEntities(source.getId());

    // The traversed link is not removable, whichever row of the pair is named.
    var ex =
        Assertions.assertThrows(
            ServiceError.class,
            () -> entityService.unlink(source.getId(), DEPENDS_ON, dependency.getId()));
    Assertions.assertTrue(
        ex.getMessage().contains("path reference") && ex.getMessage().contains("PathMapped"),
        "the error must name the mapping and its path reference, got: " + ex.getMessage());
    Assertions.assertThrows(
        ServiceError.class,
        () -> entityService.unlink(dependency.getId(), IS_REQUIRED_BY, source.getId()));

    // An identical link no mapping traverses (its source has no mapped entity) stays removable.
    var otherSource = entityService.create("PathSource", "{}");
    var otherDependency = entityService.create("PathDep", "{}");
    entityService.link(otherSource.getId(), DEPENDS_ON, otherDependency.getId());
    entityService.unlink(otherSource.getId(), DEPENDS_ON, otherDependency.getId());

    // Once the mapped entities are gone, nothing resolves through the link any more.
    mappedEntityService.deleteMappedEntities(source.getId());
    entityService.unlink(source.getId(), DEPENDS_ON, dependency.getId());

    // Cleanup — the mapping first: entities of a mapping-source type refuse a direct delete
    // while the rule exists.
    mappingService.list().forEach(m -> mappingService.delete(m.getId()));
    entityService.delete(otherSource.getId());
    entityService.delete(otherDependency.getId());
    entityService.delete(source.getId());
    entityService.delete(dependency.getId());
    entityTypeService.delete("PathSource");
    entityTypeService.delete("PathDep");
    entityTypeService.delete("PathMapped");
    traitService.unlink("PathSrcTrait", DEPENDS_ON, "PathDepTrait");
    traitService.delete("PathDepTrait");
    traitService.delete("PathSrcTrait");
  }
}
