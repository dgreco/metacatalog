package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.service.MappingValueEvaluator.generateMappedValues;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.networknt.schema.JsonSchemaFactory;
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

    var mapping2 = mappingService.create("SimpleSourceType", "SimpleTargetType", "{}", List.of());

    Assertions.assertThrows(ServiceError.class, () -> entityTypeService.delete("SimpleSourceType"));

    mappingService.delete(mapping1.getId());
    mappingService.delete(mapping2.getId());

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
    var jsonSchemaFactory = getApplicationContext().getBean(JsonSchemaFactory.class);
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
        jsonSchemaFactory.getSchema(
            """
                { "type": "object", "properties": { "b": { "type": "integer" } } }
                """);

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

    await()
        .atMost(Durations.TEN_SECONDS)
        .pollDelay(Durations.ONE_SECOND)
        .until(() -> !entityRepository.findByEntityType(targeType).isEmpty());

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
    var mapping2 = mappingService.create("ListSourceType", "ListTargetType", "{}", List.of());

    var allMappings = mappingService.list();

    Assertions.assertTrue(allMappings.size() >= 2);

    var mappingIds =
        allMappings.stream().map(MappingEntityTypeRelationship::getId).collect(Collectors.toSet());

    Assertions.assertTrue(mappingIds.contains(mapping1.getId()));
    Assertions.assertTrue(mappingIds.contains(mapping2.getId()));

    mappingService.delete(mapping1.getId());
    mappingService.delete(mapping2.getId());
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
   * When a target type is versioned after a mapped entity has been generated, regenerating the
   * mapped values on source update must validate against the mapped entity's PINNED snapshot (the
   * one it was created against), not the new live type schema. Otherwise the new live schema could
   * reject values that the mapped expression still produces according to the old schema.
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

    // Version the target type: v2 forbids "b" and requires "c" instead. The mapped entity is still
    // pinned to v1, so its regeneration must keep producing {"b": ...} and validate against v1.
    entityTypeService.createVersion(
        "PinDstType",
        List.of(),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "c": { "type": "integer" } },
          "required": ["c"],
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
