package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.service.MappingValueEvaluator.generateMappedValues;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.common.WrappedJsonNode;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.repository.EntityRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.awaitility.Durations;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class MappingServiceTests extends CommonServiceTestingSupport {

  public MappingServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreateDelete() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    entityTypeService.create(
        "SimpleSourceType",
        List.of(),
        Optional.empty(),
        """
                { "type": "object", "properties": {} }""");

    entityTypeService.create(
        "SimpleTargetType",
        List.of(),
        Optional.empty(),
        """
                { "type": "object", "properties": {} }""");

    var mapping1 = mappingService.create("SimpleSourceType", "SimpleTargetType", "{}", List.of());

    // Creating the same pair again replaces the mapping in place rather than adding a second one.
    var mapping2 = mappingService.create("SimpleSourceType", "SimpleTargetType", "{}", List.of());
    Assertions.assertEquals(mapping1.getId(), mapping2.getId());

    Assertions.assertThrows(ServiceError.class, () -> entityTypeService.delete("SimpleSourceType"));

    mappingService.delete(mapping1.getId());

    entityTypeService.delete("SimpleSourceType");
    entityTypeService.delete("SimpleTargetType");
  }

  @Test
  void testCheckLoopsAndIsSourceAndIsTarget() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    var typeA =
        entityTypeService.create(
            "A",
            List.of(),
            Optional.empty(),
            """
                { "type": "object", "properties": {} }""");

    var typeB =
        entityTypeService.create(
            "B",
            List.of(),
            Optional.empty(),
            """
                { "type": "object", "properties": {} }""");

    entityTypeService.create(
        "C",
        List.of(),
        Optional.empty(),
        """
                { "type": "object", "properties": {} }""");

    entityTypeService.create(
        "D",
        List.of(),
        Optional.empty(),
        """
                { "type": "object", "properties": {} }""");

    mappingService.create("A", "B", "{}", List.of());

    Assertions.assertTrue(mappingService.isSourceEntityType(typeA));

    Assertions.assertFalse(mappingService.isSourceEntityType(typeB));

    Assertions.assertTrue(mappingService.isTargetEntityType(typeB));

    Assertions.assertThrows(
        ServiceError.class, () -> mappingService.create("B", "A", "{}", List.of()));

    mappingService.create("B", "C", "{}", List.of());

    Assertions.assertTrue(mappingService.isSourceEntityType(typeB));

    Assertions.assertTrue(mappingService.isTargetEntityType(typeB));

    Assertions.assertThrows(
        ServiceError.class, () -> mappingService.create("C", "A", "{}", List.of()));

    mappingService.create("C", "D", "{}", List.of());

    mappingService.create("C", "D", "{}", List.of());

    Assertions.assertThrows(
        ServiceError.class, () -> mappingService.create("D", "A", "{}", List.of()));
  }

  @Test
  void testGraphPath() {

    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var entityPathResolver = getApplicationContext().getBean(EntityPathResolver.class);

    traitService.create("Trait_NA", Optional.empty(), Optional.empty());

    traitService.create("Trait_NB", Optional.empty(), Optional.empty());

    traitService.create("Trait_NC", Optional.empty(), Optional.empty());

    traitService.create("Trait_ND", Optional.empty(), Optional.empty());

    traitService.link("Trait_NA", DEPENDS_ON, "Trait_NB");

    traitService.link("Trait_NB", DEPENDS_ON, "Trait_NC");

    traitService.link("Trait_NC", DEPENDS_ON, "Trait_ND");

    entityTypeService.create(
        "NA",
        List.of("Trait_NA"),
        Optional.empty(),
        """
                { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    entityTypeService.create(
        "NB",
        List.of("Trait_NB"),
        Optional.empty(),
        """
                { "type": "object", "properties": { "b": { "type": "number" }} }""");

    entityTypeService.create(
        "NC",
        List.of("Trait_NC"),
        Optional.empty(),
        """
                { "type": "object", "properties": { "c": { "type": "string" }} }""");

    entityTypeService.create(
        "ND",
        List.of("Trait_ND"),
        Optional.empty(),
        """
                { "type": "object", "properties": { "d": { "type": "string" }} }""");

    var a =
        entityService.create(
            "NA",
            """
               { "a": 1 }""");

    var b1 =
        entityService.create(
            "NB",
            """
               { "b": 0.1 }""");

    var b2 =
        entityService.create(
            "NB",
            """
               { "b": 0.2 }""");

    var c1 =
        entityService.create(
            "NC",
            """
               { "c": "c1" }""");

    var c2 =
        entityService.create(
            "NC",
            """
               { "c": "c2" }""");

    var d =
        entityService.create(
            "ND",
            """
               { "d": "d" }""");

    entityService.link(a.getId(), DEPENDS_ON, b1.getId());

    entityService.link(a.getId(), DEPENDS_ON, b2.getId());

    entityService.link(b1.getId(), DEPENDS_ON, c1.getId());

    entityService.link(b1.getId(), DEPENDS_ON, c2.getId());

    entityService.link(c2.getId(), DEPENDS_ON, d.getId());

    entityService.link(c1.getId(), DEPENDS_ON, d.getId());

    {
      var retrievedEntity =
          entityPathResolver.retrieveEntityByPath(
              d.getId(),
              "DEPENDS_ON{$.[?(@.c == 'c1')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$}");

      Assertions.assertTrue(
          retrievedEntity.isPresent() && retrievedEntity.get().getId().equals(a.getId()));
    }

    {
      var retrievedEntity =
          entityPathResolver.retrieveEntityByPath(
              b1.getId(),
              "DEPENDS_ON{$.[?(@.c == 'c3')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}");
      Assertions.assertTrue(retrievedEntity.isEmpty());
    }

    {
      var retrievedEntity =
          entityPathResolver.retrieveEntityByPath(
              d.getId(),
              "DEPENDS_ON{$.[?(@.c == 'c3')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}");

      Assertions.assertTrue(retrievedEntity.isEmpty());
    }

    {
      Assertions.assertThrows(
          ServiceError.class,
          () ->
              entityPathResolver.retrieveEntityByPath(
                  d.getId(),
                  "DEPENDS_ON{$}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}"));
    }
  }

  @Test
  void testGenerateMappedValues() throws JsonProcessingException, ServiceError {
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);
    var jsonUtils = getApplicationContext().getBean(JsonUtils.class);
    var sourceValues =
        jsonMapper.readTree(
            """
                {"a": 1}
                """);
    var externalValues =
        Map.of(
            "as",
            jsonMapper.readTree(
                """
                {"c": 1}"""));
    var mappingValues =
        jsonMapper.readTree(
            """
                        {"b": "#source.getValue('$.a').intValue() + #as.getValue('$.c').intValue()*10"}""");
    var targetSchema =
        jsonUtils.schemaOf(
            jsonMapper.readTree(
                """
                { "type": "object", "properties": { "b": { "type": "integer" } } }
                """));

    var wnode =
        new WrappedJsonNode(
            generateMappedValues(sourceValues, externalValues, mappingValues, targetSchema));

    Assertions.assertEquals(11, ((IntNode) wnode.getValue("$.b")).intValue());
  }

  @Test
  void testAutomaticCreateAndUpdateAndDeleteMappedEntities() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappingUpdaterService = getApplicationContext().getBean(MappingUpdaterService.class);
    mappingUpdaterService.setAutomaticEntitiesMapping(true);

    traitService.create("DependingRelSourceTrait1", Optional.empty(), Optional.empty());

    traitService.create("DependingRelTargetTrait1", Optional.empty(), Optional.empty());

    traitService.link("DependingRelSourceTrait1", DEPENDS_ON, "DependingRelTargetTrait1");

    entityTypeService.create(
        "AnotherType1",
        List.of("DependingRelTargetTrait1"),
        Optional.empty(),
        """
                    { "type": "object", "properties": { "c": { "type": "integer" } } }""");

    entityTypeService.create(
        "SourceType1",
        List.of("DependingRelSourceTrait1"),
        Optional.empty(),
        """
                    { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    var targeType =
        entityTypeService.create(
            "TargetType1",
            List.of(),
            Optional.empty(),
            """
                    { "type": "object", "properties": { "b": { "type": "integer" } } }""");

    var anotherTargetType =
        entityTypeService.create(
            "AnotherTargetType1",
            List.of(),
            Optional.empty(),
            """
                    { "type": "object", "properties": { "d": { "type": "integer" } } }""");

    mappingService.create(
        "SourceType1",
        "TargetType1",
        """
                            {"b": "#source.getValue('$.a').intValue() + #ai.getValue('$.c').intValue()*10"}""",
        List.of(new MappingEntityTypeRelationship.EntityPathReference("ai", "IS_REQUIRED_BY{$}")));

    mappingService.create(
        "TargetType1",
        "AnotherTargetType1",
        """
                            {"d": "#source.getValue('$.b').intValue() + #ai.getValue('$.c').intValue()*10"}""",
        List.of(
            new MappingEntityTypeRelationship.EntityPathReference(
                "ai", "IS_MAPPED_BY{$}/IS_REQUIRED_BY{$}")));

    var anotherInstance =
        entityService.create(
            "AnotherType1",
            """
                    {"c": 1}
                    """);

    var sourceInstance =
        entityService.create(
            "SourceType1",
            """
                    {"a": 1}
                    """);

    entityService.link(sourceInstance.getId(), DEPENDS_ON, anotherInstance.getId());

    await()
        .atMost(Duration.ofSeconds(30))
        .pollDelay(Durations.ONE_SECOND)
        .until(() -> !entityRepository.findByEntityType(targeType).isEmpty());

    {
      var int1 =
          new WrappedJsonNode(entityRepository.findByEntityType(targeType).getFirst().getValues())
              .getValue(Integer.class, "$.b");
      Assertions.assertEquals(11, int1);

      var int2 =
          new WrappedJsonNode(
                  entityRepository.findByEntityType(anotherTargetType).getFirst().getValues())
              .getValue(Integer.class, "$.d");
      Assertions.assertEquals(21, int2);
    }

    entityService.update(
        sourceInstance.getId(),
        """
                    {"a": 2}
                    """);

    // Both targets already exist from the first round, so waiting for them to exist returned at
    // once and raced the scheduler: a slow runner saw the old values. Wait for the update itself
    // to come through both mappings of the chain.
    await()
        .atMost(Durations.TEN_SECONDS)
        .until(
            () -> {
              var targets = entityRepository.findByEntityType(targeType);
              var anotherTargets = entityRepository.findByEntityType(anotherTargetType);
              if (targets.isEmpty() || anotherTargets.isEmpty()) return false;
              var b =
                  new WrappedJsonNode(targets.getFirst().getValues())
                      .getValue(Integer.class, "$.b");
              var d =
                  new WrappedJsonNode(anotherTargets.getFirst().getValues())
                      .getValue(Integer.class, "$.d");
              return b == 12 && d == 22;
            });

    {
      var int1 =
          new WrappedJsonNode(entityRepository.findByEntityType(targeType).getFirst().getValues())
              .getValue(Integer.class, "$.b");
      Assertions.assertEquals(12, int1);

      var int2 =
          new WrappedJsonNode(
                  entityRepository.findByEntityType(anotherTargetType).getFirst().getValues())
              .getValue(Integer.class, "$.d");
      Assertions.assertEquals(22, int2);
    }
  }

  /**
   * Verifies that updating a non-source entity that is referenced by a mapping's
   * entityPathReferences triggers re-evaluation of the mapped entities.
   *
   * <p>The test sets up: SourceType2 (mapping source, DEPENDS_ON → AnotherType2) with a mapping to
   * TargetType2 whose expression references the depended-on entity via {@code IS_REQUIRED_BY{$}}.
   * After the initial mapping is created and verified, the non-source entity (AnotherType2) is
   * updated — changing its {@code c} value from 1 to 5. The mapped TargetType2 entity should then
   * be re-evaluated to reflect the new value of {@code c}.
   */
  @Test
  void testAutomaticMappingPropagationOnIndirectSourceUpdate() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappingUpdaterService = getApplicationContext().getBean(MappingUpdaterService.class);
    mappingUpdaterService.setAutomaticEntitiesMapping(true);

    traitService.create("IndirectSrcTrait", Optional.empty(), Optional.empty());
    traitService.create("IndirectRefTrait", Optional.empty(), Optional.empty());
    traitService.link("IndirectSrcTrait", DEPENDS_ON, "IndirectRefTrait");

    entityTypeService.create(
        "AnotherType2",
        List.of("IndirectRefTrait"),
        Optional.empty(),
        """
                    { "type": "object", "properties": { "c": { "type": "integer" } } }""");

    entityTypeService.create(
        "SourceType2",
        List.of("IndirectSrcTrait"),
        Optional.empty(),
        """
                    { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    var targetType2 =
        entityTypeService.create(
            "TargetType2",
            List.of(),
            Optional.empty(),
            """
                    { "type": "object", "properties": { "b": { "type": "integer" } } }""");

    mappingService.create(
        "SourceType2",
        "TargetType2",
        """
                    {"b": "#source.getValue('$.a').intValue() + #ai.getValue('$.c').intValue()*10"}""",
        List.of(new MappingEntityTypeRelationship.EntityPathReference("ai", "IS_REQUIRED_BY{$}")));

    var anotherInstance =
        entityService.create(
            "AnotherType2",
            """
                    {"c": 1}
                    """);

    var sourceInstance =
        entityService.create(
            "SourceType2",
            """
                    {"a": 1}
                    """);

    entityService.link(sourceInstance.getId(), DEPENDS_ON, anotherInstance.getId());

    await()
        .atMost(Duration.ofSeconds(30))
        .pollDelay(Durations.ONE_SECOND)
        .until(() -> !entityRepository.findByEntityType(targetType2).isEmpty());

    {
      var int1 =
          new WrappedJsonNode(entityRepository.findByEntityType(targetType2).getFirst().getValues())
              .getValue(Integer.class, "$.b");
      Assertions.assertEquals(11, int1);
    }

    entityService.update(
        anotherInstance.getId(),
        """
                {"c": 5}
                """);

    await()
        .atMost(Durations.TEN_SECONDS)
        .pollDelay(Durations.ONE_SECOND)
        .until(
            () -> {
              var entities = entityRepository.findByEntityType(targetType2);
              if (entities.isEmpty()) return false;
              var b =
                  new WrappedJsonNode(entities.getFirst().getValues())
                      .getValue(Integer.class, "$.b");
              return b == 51;
            });

    {
      var int1 =
          new WrappedJsonNode(entityRepository.findByEntityType(targetType2).getFirst().getValues())
              .getValue(Integer.class, "$.b");
      Assertions.assertEquals(51, int1);
    }
  }

  @Test
  void testCreateAndUpdateAndDeleteMappedEntities() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);

    traitService.create("DependingRelSourceTrait", Optional.empty(), Optional.empty());

    traitService.create("DependingRelTargetTrait", Optional.empty(), Optional.empty());

    traitService.link("DependingRelSourceTrait", DEPENDS_ON, "DependingRelTargetTrait");

    entityTypeService.create(
        "AnotherType",
        List.of("DependingRelTargetTrait"),
        Optional.empty(),
        """
                    { "type": "object", "properties": { "c": { "type": "integer" } } }""");

    entityTypeService.create(
        "SourceType",
        List.of("DependingRelSourceTrait"),
        Optional.empty(),
        """
                    { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    var targeType =
        entityTypeService.create(
            "TargetType",
            List.of(),
            Optional.empty(),
            """
                    { "type": "object", "properties": { "b": { "type": "integer" } } }""");

    var anotherTargetType =
        entityTypeService.create(
            "AnotherTargetType",
            List.of(),
            Optional.empty(),
            """
                    { "type": "object", "properties": { "d": { "type": "integer" } } }""");

    mappingService.create(
        "SourceType",
        "TargetType",
        """
                            {"b": "#source.getValue('$.a').intValue() + #ai.getValue('$.c').intValue()*10"}""",
        List.of(new MappingEntityTypeRelationship.EntityPathReference("ai", "IS_REQUIRED_BY{$}")));

    mappingService.create(
        "TargetType",
        "AnotherTargetType",
        """
                            {"d": "#source.getValue('$.b').intValue() + #ai.getValue('$.c').intValue()*10"}""",
        List.of(
            new MappingEntityTypeRelationship.EntityPathReference(
                "ai", "IS_MAPPED_BY{$}/IS_REQUIRED_BY{$}")));

    var anotherInstance =
        entityService.create(
            "AnotherType",
            """
                    {"c": 1}
                    """);

    var sourceInstance =
        entityService.create(
            "SourceType",
            """
                    {"a": 1}
                    """);

    entityService.link(sourceInstance.getId(), DEPENDS_ON, anotherInstance.getId());

    mappedEntityService.createMappedEntities(sourceInstance.getId());

    Assertions.assertEquals(1, entityRepository.countByEntityType(targeType));

    Assertions.assertEquals(1, entityRepository.countByEntityType(anotherTargetType));

    {
      var mapped = entityRepository.findByEntityType(targeType).getFirst();
      Assertions.assertNotNull(mapped.getEntityTypeVersion());
      Assertions.assertEquals(targeType.getVersion(), mapped.getEntityTypeVersion().getVersion());
    }

    // Check that idempotency works
    mappedEntityService.createMappedEntities(sourceInstance.getId());

    {
      var int1 =
          new WrappedJsonNode(entityRepository.findByEntityType(targeType).getFirst().getValues())
              .getValue(Integer.class, "$.b");
      Assertions.assertEquals(11, int1);

      var int2 =
          new WrappedJsonNode(
                  entityRepository.findByEntityType(anotherTargetType).getFirst().getValues())
              .getValue(Integer.class, "$.d");
      Assertions.assertEquals(21, int2);
    }

    // Check that checking the entity is only a source works
    Assertions.assertThrows(
        ServiceError.class,
        () ->
            mappedEntityService.createMappedEntities(
                entityRepository.findByEntityType(targeType).getFirst().getId()));

    // Update the source
    entityService.update(
        sourceInstance.getId(),
        """
                    {"a": 2}
                    """);

    mappedEntityService.updateMappedEntities(sourceInstance.getId());

    {
      var int1 =
          new WrappedJsonNode(entityRepository.findByEntityType(targeType).getFirst().getValues())
              .getValue(Integer.class, "$.b");
      Assertions.assertEquals(12, int1);

      var int2 =
          new WrappedJsonNode(
                  entityRepository.findByEntityType(anotherTargetType).getFirst().getValues())
              .getValue(Integer.class, "$.d");
      Assertions.assertEquals(22, int2);
    }

    // Creating a target entity is not allowed
    {
      entityRepository.findByEntityType(targeType).getFirst();
      Assertions.assertThrows(
          ServiceError.class,
          () ->
              entityService.create(
                  "TargetType",
                  """
                        {"b: "40"}"""));
    }

    // Updating a target entity is not allowed
    {
      var entity = entityRepository.findByEntityType(targeType).getFirst();
      Assertions.assertThrows(
          ServiceError.class,
          () ->
              entityService.update(
                  entity.getId(),
                  """
                        {"b: "10"}"""));
    }

    // Deleting a target entity is not allowed
    {
      var entity = entityRepository.findByEntityType(targeType).getFirst();
      Assertions.assertThrows(ServiceError.class, () -> entityService.delete(entity.getId()));
    }

    // Check that checking the entity is only a source works
    Assertions.assertThrows(
        ServiceError.class,
        () ->
            mappedEntityService.updateMappedEntities(
                entityRepository.findByEntityType(targeType).getFirst().getId()));

    // I cannot delete the source entity
    Assertions.assertThrows(ServiceError.class, () -> entityService.delete(sourceInstance.getId()));

    mappedEntityService.deleteMappedEntities(sourceInstance.getId());

    Assertions.assertEquals(0, entityRepository.countByEntityType(targeType));

    Assertions.assertEquals(0, entityRepository.countByEntityType(anotherTargetType));

    // Check that idempotency works
    mappedEntityService.deleteMappedEntities(sourceInstance.getId());
  }

  @Test
  void testListMappings() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    entityTypeService.create(
        "ListSourceType",
        List.of(),
        Optional.empty(),
        """
                { "type": "object", "properties": {} }""");

    entityTypeService.create(
        "ListTargetType",
        List.of(),
        Optional.empty(),
        """
                { "type": "object", "properties": {} }""");

    var mapping1 = mappingService.create("ListSourceType", "ListTargetType", "{}", List.of());
    // Re-creating the pair replaces the mapping in place, so the list holds it exactly once.
    var mapping2 = mappingService.create("ListSourceType", "ListTargetType", "{}", List.of());
    Assertions.assertEquals(mapping1.getId(), mapping2.getId());

    var allMappings = mappingService.list();

    var mappingIds =
        allMappings.stream().map(MappingEntityTypeRelationship::getId).collect(Collectors.toSet());

    Assertions.assertTrue(mappingIds.contains(mapping1.getId()));
    Assertions.assertEquals(
        1,
        allMappings.stream().filter(m -> m.getId().equals(mapping1.getId())).count(),
        "a replaced mapping must not appear twice");

    mappingService.delete(mapping1.getId());
    entityTypeService.delete("ListSourceType");
    entityTypeService.delete("ListTargetType");
  }

  /**
   * Verifies that the mapping values JSON passed to {@code MappingService.create} are actually
   * persisted and can be read back unchanged from {@code MappingService.read}.
   *
   * <p>The mapping values document holds the SpEL expressions that drive value generation for
   * mapped entities. A round-trip test guards against any code path that silently drops or
   * overwrites these values (for example by defaulting to an empty object on write, or by failing
   * to deserialize the column on read). The test creates a mapping with a non-trivial expression,
   * reads it back, and asserts the parsed JSON tree is equal to the one supplied.
   */
  @Test
  void createPersistsMappingValuesForRoundTrip() throws JsonProcessingException {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);

    entityTypeService.create(
        "RoundTripSrc",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": {} }");
    entityTypeService.create(
        "RoundTripDst",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": { \"b\": { \"type\": \"integer\" } } }");

    var mappingValues = "{\"b\": \"#source.getValue('$.a').intValue()\"}";
    var created = mappingService.create("RoundTripSrc", "RoundTripDst", mappingValues, List.of());

    var readBack = mappingService.read(created.getId());
    var expectedNode = jsonMapper.readTree(mappingValues);
    Assertions.assertEquals(expectedNode, readBack.getMappingValues());

    mappingService.delete(created.getId());
    entityTypeService.delete("RoundTripSrc");
    entityTypeService.delete("RoundTripDst");
  }

  /**
   * When a target type is versioned after a mapped entity has been generated but the mapping is
   * left untouched, regenerating the mapped values on source update must keep validating against
   * the version the MAPPING was defined against (v1 here), not the new live type schema — and the
   * mapped entity must stay pinned to it rather than silently migrating to the new live version.
   *
   * <p>The new version is a compatible, additive change (it keeps {@code b} and adds an optional
   * {@code c}) — {@code createVersion} refuses a schema the existing mapping no longer satisfies,
   * so an incompatible v2 can no longer arise while the mapping exists (see {@code
   * createVersionRefusesSchemaThatBreaksTargetMapping}). Moving the mapped entity forward requires
   * replacing the mapping (see {@code
   * updateAfterMappingReplacementRepinsTheMappedEntityToTheMappingVersion}).
   */
  @Test
  void testUpdateMappedEntitiesUsesPinnedSchemaAfterTargetVersioned() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);

    // Source type carries one integer "a"; target type v1 has one integer "b" (no "extra").
    entityTypeService.create(
        "PinSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } }, "required": ["a"] }""");

    var targetTypeV1 =
        entityTypeService.create(
            "PinDstType",
            List.of(),
            Optional.empty(),
            """
            {
              "type": "object",
              "properties": { "b": { "type": "integer" } },
              "required": ["b"],
              "additionalProperties": false
            }
            """);

    mappingService.create(
        "PinSrcType", "PinDstType", "{\"b\": \"#source.getValue('$.a').intValue()\"}", List.of());

    var source =
        entityService.create(
            "PinSrcType",
            """
            {"a": 5}
            """);

    mappedEntityService.createMappedEntities(source.getId());

    var mapped = entityRepository.findByEntityType(targetTypeV1).getFirst();
    Assertions.assertNotNull(mapped.getEntityTypeVersion());
    Assertions.assertEquals(1, mapped.getEntityTypeVersion().getVersion());
    Assertions.assertEquals(
        5, new WrappedJsonNode(mapped.getValues()).getValue(Integer.class, "$.b").intValue());

    // Version the target type: v2 keeps "b" and adds an optional "c". The mapped entity is still
    // pinned to v1, so its regeneration must keep validating against v1 and stay on version 1.
    entityTypeService.createVersion(
        "PinDstType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "b": { "type": "integer" }, "c": { "type": "integer" } },
          "required": ["b"],
          "additionalProperties": false
        }
        """);

    entityService.update(
        source.getId(),
        """
        {"a": 7}
        """);

    mappedEntityService.updateMappedEntities(source.getId());

    var regenerated = entityRepository.findByEntityType(targetTypeV1).getFirst();
    Assertions.assertEquals(
        1, regenerated.getEntityTypeVersion().getVersion(), "mapped entity must stay pinned to v1");
    Assertions.assertEquals(
        7, new WrappedJsonNode(regenerated.getValues()).getValue(Integer.class, "$.b").intValue());
    Assertions.assertFalse(
        regenerated.getValues().has("c"), "regenerated values must follow v1 schema, not v2");

    mappedEntityService.deleteMappedEntities(source.getId());
    var mappings =
        mappingService.list().stream()
            .filter(m -> m.getSource().getName().equals("PinSrcType"))
            .toList();
    for (var m : mappings) mappingService.delete(m.getId());
    entityRepository.findById(source.getId()).ifPresent(entityRepository::delete);
    entityTypeService.delete("PinSrcType");
    entityTypeService.delete("PinDstType");
  }

  /**
   * The createVersion guard constrains only the TARGET side of a mapping: a mapping's SpEL reads
   * source values, not the source schema, so versioning a source type — even to a completely
   * different schema — must go through while the type has an outgoing mapping.
   */
  @Test
  void createVersionOfASourceTypeIsNotConstrainedByItsMappings() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    entityTypeService.create(
        "GuardFreeSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    entityTypeService.create(
        "GuardFreeDstType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": {} }""");

    var mapping = mappingService.create("GuardFreeSrcType", "GuardFreeDstType", "{}", List.of());

    var v2 =
        entityTypeService.createVersion(
            "GuardFreeSrcType",
            List.of(),
            Optional.empty(),
            """
            { "type": "object", "properties": { "renamed": { "type": "string" } } }""");
    Assertions.assertEquals(2, v2.getVersion());

    mappingService.delete(mapping.getId());
    entityTypeService.delete("GuardFreeSrcType");
    entityTypeService.delete("GuardFreeDstType");
  }

  /**
   * A replacement that fails validation must leave the existing mapping untouched: create is
   * transactional, so the in-place mutation of the managed row rolls back and the previous values —
   * and version stamps — survive.
   */
  @Test
  void failedReplacementLeavesTheExistingMappingUntouched() throws JsonProcessingException {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);

    entityTypeService.create(
        "IntactSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    entityTypeService.create(
        "IntactDstType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "b": { "type": "integer" } },
          "required": ["b"],
          "additionalProperties": false
        }
        """);

    var originalValues = "{\"b\": \"#source.getValue('$.a').intValue()\"}";
    var mapping =
        mappingService.create("IntactSrcType", "IntactDstType", originalValues, List.of());

    // The replacement omits the required "b" and adds a property the target schema forbids.
    Assertions.assertThrows(
        ServiceError.class,
        () ->
            mappingService.create(
                "IntactSrcType", "IntactDstType", "{\"c\": \"#source.whatever\"}", List.of()));

    var survivor = mappingService.read(mapping.getId());
    Assertions.assertEquals(jsonMapper.readTree(originalValues), survivor.getMappingValues());
    Assertions.assertEquals(1, survivor.getTargetEntityTypeVersion());

    mappingService.delete(mapping.getId());
    entityTypeService.delete("IntactSrcType");
    entityTypeService.delete("IntactDstType");
  }

  /**
   * A mapped entity created AFTER the target type moved forward — with the mapping left untouched —
   * is validated against and pinned to the mapping's stamped version, not the newer live one: the
   * mapping is the authority for the shape of what it derives.
   */
  @Test
  void newMappedEntityPinsToTheMappingVersionNotTheLiveOne() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);

    entityTypeService.create(
        "StampSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");
    var dstType =
        entityTypeService.create(
            "StampDstType",
            List.of(),
            Optional.empty(),
            """
            {
              "type": "object",
              "properties": { "b": { "type": "integer" } },
              "required": ["b"],
              "additionalProperties": false
            }
            """);

    mappingService.create(
        "StampSrcType",
        "StampDstType",
        "{\"b\": \"#source.getValue('$.a').intValue()\"}",
        List.of());

    // Move the target forward compatibly; the mapping stays stamped against v1.
    var liveV2 =
        entityTypeService.createVersion(
            "StampDstType",
            List.of(),
            Optional.empty(),
            """
            {
              "type": "object",
              "properties": { "b": { "type": "integer" }, "c": { "type": "integer" } },
              "required": ["b"],
              "additionalProperties": false
            }
            """);
    Assertions.assertEquals(2, liveV2.getVersion());

    var source =
        entityService.create(
            "StampSrcType",
            """
            {"a": 9}
            """);
    mappedEntityService.createMappedEntities(source.getId());

    var mapped = entityRepository.findByEntityType(dstType).getFirst();
    Assertions.assertEquals(
        1,
        mapped.getEntityTypeVersion().getVersion(),
        "a new mapped entity must pin to the mapping's stamped version, not the live one");

    mappedEntityService.deleteMappedEntities(source.getId());
    mappingService.list().stream()
        .filter(m -> m.getSource().getName().equals("StampSrcType"))
        .forEach(m -> mappingService.delete(m.getId()));
    entityRepository.findById(source.getId()).ifPresent(entityRepository::delete);
    entityTypeService.delete("StampSrcType");
    entityTypeService.delete("StampDstType");
  }

  /**
   * The scenario that used to fail: a mapped entity was derived through a mapping defined against
   * target v1; the target type is then versioned (compatibly, so the guard accepts it) and the
   * mapping is replaced, re-authored for v2 with a property v1 forbids. Updating the source must
   * not validate the new values against the entity's old v1 pin — a mapped entity follows its
   * mapping, so it is re-validated against v2 and re-pinned to it.
   */
  @Test
  void updateAfterMappingReplacementRepinsTheMappedEntityToTheMappingVersion() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);

    entityTypeService.create(
        "RepinSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    var dstType =
        entityTypeService.create(
            "RepinDstType",
            List.of(),
            Optional.empty(),
            """
            {
              "type": "object",
              "properties": { "b": { "type": "integer" } },
              "required": ["b"],
              "additionalProperties": false
            }
            """);

    mappingService.create(
        "RepinSrcType",
        "RepinDstType",
        "{\"b\": \"#source.getValue('$.a').intValue()\"}",
        List.of());

    var source =
        entityService.create(
            "RepinSrcType",
            """
            {"a": 4}
            """);

    mappedEntityService.createMappedEntities(source.getId());
    var mapped = entityRepository.findByEntityType(dstType).getFirst();
    Assertions.assertEquals(1, mapped.getEntityTypeVersion().getVersion());

    // Compatible v2: keeps "b" and adds an optional "c" — the guard accepts it, and the mapping
    // stays stamped against v1.
    entityTypeService.createVersion(
        "RepinDstType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "b": { "type": "integer" }, "c": { "type": "integer" } },
          "required": ["b"],
          "additionalProperties": false
        }
        """);

    // Replace the mapping, re-authored for v2: it now produces "c", which v1 forbids
    // (additionalProperties: false), so validating against the old pin would fail.
    mappingService.create(
        "RepinSrcType",
        "RepinDstType",
        "{\"b\": \"#source.getValue('$.a').intValue()\","
            + " \"c\": \"#source.getValue('$.a').intValue() * 10\"}",
        List.of());

    entityService.update(
        source.getId(),
        """
        {"a": 5}
        """);

    mappedEntityService.updateMappedEntities(source.getId());

    var regenerated = entityRepository.findByEntityType(dstType).getFirst();
    Assertions.assertEquals(
        2,
        regenerated.getEntityTypeVersion().getVersion(),
        "the mapped entity must be re-pinned to the version its mapping was defined against");
    Assertions.assertEquals(5, regenerated.getValues().get("b").asInt());
    Assertions.assertEquals(50, regenerated.getValues().get("c").asInt());

    mappedEntityService.deleteMappedEntities(source.getId());
    mappingService.list().stream()
        .filter(m -> m.getSource().getName().equals("RepinSrcType"))
        .forEach(m -> mappingService.delete(m.getId()));
    entityRepository.findById(source.getId()).ifPresent(entityRepository::delete);
    entityTypeService.delete("RepinSrcType");
    entityTypeService.delete("RepinDstType");
  }

  /**
   * Replacing a mapping (re-creating the same source/target pair) must not derive a second mapped
   * instance: the existing mapped entity stays attached to the same relationship id and is
   * re-evaluated with the new values on the next update. Two independent mappings of the same pair
   * used to each derive their own instance of the target type.
   */
  @Test
  void replacingAMappingDoesNotDeriveASecondInstance() {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var mappedEntityService = getApplicationContext().getBean(MappedEntityService.class);

    entityTypeService.create(
        "ReplSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    var targetType =
        entityTypeService.create(
            "ReplDstType",
            List.of(),
            Optional.empty(),
            """
            { "type": "object", "properties": { "b": { "type": "integer" } } }""");

    mappingService.create(
        "ReplSrcType", "ReplDstType", "{\"b\": \"#source.getValue('$.a').intValue()\"}", List.of());

    var source =
        entityService.create(
            "ReplSrcType",
            """
            {"a": 3}
            """);

    mappedEntityService.createMappedEntities(source.getId());
    Assertions.assertEquals(1, entityRepository.countByEntityType(targetType));

    // Replace the mapping with different values, then run the engine again: still one instance.
    var replaced =
        mappingService.create(
            "ReplSrcType",
            "ReplDstType",
            "{\"b\": \"#source.getValue('$.a').intValue() * 2\"}",
            List.of());
    mappedEntityService.createMappedEntities(source.getId());
    Assertions.assertEquals(1, entityRepository.countByEntityType(targetType));

    // The surviving instance is re-evaluated with the replaced mapping's values on update.
    mappedEntityService.updateMappedEntities(source.getId());
    Assertions.assertEquals(
        6, entityRepository.findByEntityType(targetType).getFirst().getValues().get("b").asInt());

    mappedEntityService.deleteMappedEntities(source.getId());
    mappingService.delete(replaced.getId());
    entityRepository.findById(source.getId()).ifPresent(entityRepository::delete);
    entityTypeService.delete("ReplSrcType");
    entityTypeService.delete("ReplDstType");
  }

  /**
   * Creating a new version of a type that is the target of a mapping is refused when the existing
   * mapping's values no longer satisfy the new schema, and accepted when they still do.
   *
   * <p>Without this guard the incompatibility would be accepted silently and surface only
   * asynchronously — as FAILED lifecycle events — the next time the mapping engine generated a
   * mapped entity from a source.
   */
  @Test
  void createVersionRefusesSchemaThatBreaksTargetMapping() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

    entityTypeService.create(
        "GuardSrcType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "a": { "type": "integer" } } }""");

    entityTypeService.create(
        "GuardDstType",
        List.of(),
        Optional.empty(),
        """
        { "type": "object", "properties": { "b": { "type": "integer" } }, "required": ["b"] }""");

    var mapping =
        mappingService.create(
            "GuardSrcType",
            "GuardDstType",
            "{\"b\": \"#source.getValue('$.a').intValue()\"}",
            List.of());

    // The mapping records the type versions it was defined against.
    Assertions.assertEquals(1, mapping.getSourceEntityTypeVersion());
    Assertions.assertEquals(1, mapping.getTargetEntityTypeVersion());

    // v2 drops "b" and requires "c": the existing mapping produces only "b", so it is refused and
    // the error names the offending mapping.
    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () ->
                entityTypeService.createVersion(
                    "GuardDstType",
                    List.of(),
                    Optional.empty(),
                    """
                    {
                      "type": "object",
                      "properties": { "c": { "type": "integer" } },
                      "required": ["c"],
                      "additionalProperties": false
                    }
                    """));
    Assertions.assertTrue(error.getMessage().contains("GuardSrcType"));
    Assertions.assertTrue(error.getMessage().contains("GuardDstType"));

    // A compatible v2 (keeps "b", adds an optional property) is accepted — and its version number
    // proves the refused attempt was fully rolled back.
    var v2 =
        entityTypeService.createVersion(
            "GuardDstType",
            List.of(),
            Optional.empty(),
            """
            {
              "type": "object",
              "properties": { "b": { "type": "integer" }, "c": { "type": "integer" } },
              "required": ["b"]
            }
            """);
    Assertions.assertEquals(2, v2.getVersion());

    // Re-creating the mapping for the same pair after the version bump replaces it in place:
    // same id, values re-validated, and versions re-stamped against the new target version.
    var replaced =
        mappingService.create(
            "GuardSrcType",
            "GuardDstType",
            "{\"b\": \"#source.getValue('$.a').intValue()\"}",
            List.of());
    Assertions.assertEquals(mapping.getId(), replaced.getId());
    Assertions.assertEquals(1, replaced.getSourceEntityTypeVersion());
    Assertions.assertEquals(2, replaced.getTargetEntityTypeVersion());

    mappingService.delete(replaced.getId());
    entityTypeService.delete("GuardSrcType");
    entityTypeService.delete("GuardDstType");
  }

  /**
   * Verifies that {@link MappingService#read(String)} throws {@link NotFoundException} (mapped to
   * HTTP 404) and not a generic {@link ServiceError} (mapped to HTTP 400) when the mapping ID does
   * not exist. This ensures the API layer can distinguish "resource not found" from "invalid input"
   * for mapping reads.
   */
  @Test
  void readThrowsNotFoundExceptionForMissingMapping() {
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var ex =
        Assertions.assertThrows(
            NotFoundException.class, () -> mappingService.read("nonexistent-mapping-id"));
    Assertions.assertTrue(ex.getMessage().contains("nonexistent-mapping-id"));
  }

  /**
   * Verifies that {@link MappingService#create(String, String, String, List)} throws {@link
   * NotFoundException} when the source entity type does not exist. This is consistent with the
   * other services ({@link EntityService}, {@link EntityTypeService}, {@link TraitService}) which
   * all throw {@link NotFoundException} for missing referenced resources, so the API layer returns
   * 404 instead of 400.
   */
  @Test
  void createThrowsNotFoundExceptionForMissingSourceType() {
    var mappingService = getApplicationContext().getBean(MappingService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

    // The target type must exist so the failure is attributable to the missing source type.
    entityTypeService.create(
        "ExistingTargetForNotFoundTest",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": {} }");
    try {
      var ex =
          Assertions.assertThrows(
              NotFoundException.class,
              () ->
                  mappingService.create(
                      "NonExistentSource", "ExistingTargetForNotFoundTest", "{}", List.of()));
      Assertions.assertTrue(ex.getMessage().contains("NonExistentSource"));
    } finally {
      entityTypeService.delete("ExistingTargetForNotFoundTest");
    }
  }
}
