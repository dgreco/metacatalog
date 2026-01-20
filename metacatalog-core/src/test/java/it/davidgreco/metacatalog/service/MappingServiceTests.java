package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.service.MappingService.generateMappedValues;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.IntNode;
import it.davidgreco.metacatalog.common.WrappedJsonNode;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.repository.EntityRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class MappingServiceTests extends CommonServiceTestingSupport {

  public MappingServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreateDelete() throws ServiceError {
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

    Assertions.assertThrows(
        DataIntegrityViolationException.class, () -> entityTypeService.delete("SimpleSourceType"));

    mappingService.delete(mapping1.getId());
    mappingService.delete(mapping2.getId());

    entityTypeService.delete("SimpleSourceType");
    entityTypeService.delete("SimpleTargetType");
  }

  @Test
  void testCheckLoopsAndIsSourceAndIsTarget() throws ServiceError {
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
  void testGraphPath() throws ServiceError {

    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

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
          mappingService.retrieveEntityByPath(
              d.getId(),
              "DEPENDS_ON{$.[?(@.c == 'c1')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$}");

      Assertions.assertTrue(
          retrievedEntity.isPresent() && retrievedEntity.get().getId().equals(a.getId()));
    }

    {
      var retrievedEntity =
          mappingService.retrieveEntityByPath(
              b1.getId(),
              "DEPENDS_ON{$.[?(@.c == 'c3')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}");
      Assertions.assertTrue(retrievedEntity.isEmpty());
    }

    {
      var retrievedEntity =
          mappingService.retrieveEntityByPath(
              d.getId(),
              "DEPENDS_ON{$.[?(@.c == 'c3')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}");

      Assertions.assertTrue(retrievedEntity.isEmpty());
    }

    {
      Assertions.assertThrows(
          ServiceError.class,
          () ->
              mappingService.retrieveEntityByPath(
                  d.getId(),
                  "DEPENDS_ON{$}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}"));
    }
  }

  @Test
  void testGenerateMappedValues() throws JsonProcessingException, ServiceError {
    var sourceValues =
        jsonFactory.readTree(
            """
                {"a": 1}
                """);
    var externalValues =
        Map.of(
            "as",
            jsonFactory.readTree(
                """
                {"c": 1}"""));
    var mappingValues =
        jsonFactory.readTree(
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
  void testAutomaticCreateAndUpdateAndDeleteMappedEntities() throws ServiceError {
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
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .until(
            () -> {
              var entities = entityRepository.findByEntityType(targeType);
              if (entities.isEmpty()) return false;
              var int1 =
                  new WrappedJsonNode(entities.getFirst().getValues())
                      .getValue(Integer.class, "$.b");
              return int1 == 11;
            });

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
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(500))
        .until(
            () -> {
              var int1 =
                  new WrappedJsonNode(
                          entityRepository.findByEntityType(targeType).getFirst().getValues())
                      .getValue(Integer.class, "$.b");
              return int1 == 12;
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

  @Test
  void testCreateAndUpdateAndDeleteMappedEntities() throws ServiceError {
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var mappingService = getApplicationContext().getBean(MappingService.class);

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

    mappingService.createMappedEntities(sourceInstance.getId());

    Assertions.assertEquals(1, entityRepository.countByEntityType(targeType));

    Assertions.assertEquals(1, entityRepository.countByEntityType(anotherTargetType));

    // Check that idempotency works
    mappingService.createMappedEntities(sourceInstance.getId());

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
            mappingService.createMappedEntities(
                entityRepository.findByEntityType(targeType).getFirst().getId()));

    // Update the source
    entityService.update(
        sourceInstance.getId(),
        """
                    {"a": 2}
                    """);

    mappingService.updateMappedEntities(sourceInstance.getId());

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
            mappingService.updateMappedEntities(
                entityRepository.findByEntityType(targeType).getFirst().getId()));

    // I cannot delete the source entity
    Assertions.assertThrows(ServiceError.class, () -> entityService.delete(sourceInstance.getId()));

    mappingService.deleteMappedEntities(sourceInstance.getId());

    Assertions.assertEquals(0, entityRepository.countByEntityType(targeType));

    Assertions.assertEquals(0, entityRepository.countByEntityType(anotherTargetType));

    // Check that idempotency works
    mappingService.deleteMappedEntities(sourceInstance.getId());
  }
}
