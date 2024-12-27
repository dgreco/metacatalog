package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.entity.RelationType.DEPENDS_ON;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class MappingServiceTests extends CommonServiceTests {

    @Autowired
    private EntityTypeService entityTypeService;

    @Test
    void testCreateDelete() throws ServiceError {
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var mappingService = new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                entityRelationshipRepository);

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
        Assertions.assertThrows(
                DataIntegrityViolationException.class, () -> entityTypeService.delete("SimpleTargetType"));

        mappingService.delete(mapping1.getId());
        mappingService.delete(mapping2.getId());

        entityTypeService.delete("SimpleSourceType");
        entityTypeService.delete("SimpleTargetType");
    }

    @Test
    void testCheckLoopsAndIsSourceAndIsTarget() throws ServiceError {
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var mappingService = new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                entityRelationshipRepository);

        var typeA = entityTypeService.create(
                "A", List.of(), Optional.empty(), """
                { "type": "object", "properties": {} }""");

        var typeB = entityTypeService.create(
                "B", List.of(), Optional.empty(), """
                { "type": "object", "properties": {} }""");

        entityTypeService.create(
                "C", List.of(), Optional.empty(), """
                { "type": "object", "properties": {} }""");

        entityTypeService.create(
                "D", List.of(), Optional.empty(), """
                { "type": "object", "properties": {} }""");

        mappingService.create("A", "B", "{}", List.of());

        Assertions.assertTrue(mappingService.isSourceEntityType(typeA));

        Assertions.assertFalse(mappingService.isSourceEntityType(typeB));

        Assertions.assertTrue(mappingService.isTargetEntityType(typeB));

        Assertions.assertThrows(ServiceError.class, () -> mappingService.create("B", "A", "{}", List.of()));

        mappingService.create("B", "C", "{}", List.of());

        Assertions.assertTrue(mappingService.isSourceEntityType(typeB));

        Assertions.assertTrue(mappingService.isTargetEntityType(typeB));

        Assertions.assertThrows(ServiceError.class, () -> mappingService.create("C", "A", "{}", List.of()));

        mappingService.create("C", "D", "{}", List.of());

        mappingService.create("C", "D", "{}", List.of());

        Assertions.assertThrows(ServiceError.class, () -> mappingService.create("D", "A", "{}", List.of()));
    }

    @Test
    void testGraphPath() throws ServiceError {

        var traitService = new TraitService(traitRepository, traitRelationshipRepository);
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var entityService = new EntityService(
                entityTypeRepository, entityRepository, traitRelationshipRepository, entityRelationshipRepository);
        var mappingService = new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                entityRelationshipRepository);

        traitService.create("Trait_NA", Optional.empty());

        traitService.create("Trait_NB", Optional.empty());

        traitService.create("Trait_NC", Optional.empty());

        traitService.create("Trait_ND", Optional.empty());

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

        var a = entityService.create("NA", """
               { "a": 1 }""");

        var b1 = entityService.create("NB", """
               { "b": 0.1 }""");

        var b2 = entityService.create("NB", """
               { "b": 0.2 }""");

        var c1 = entityService.create("NC", """
               { "c": "c1" }""");

        var c2 = entityService.create("NC", """
               { "c": "c2" }""");

        var d = entityService.create("ND", """
               { "d": "d" }""");

        entityService.link(a.getId(), DEPENDS_ON, b1.getId());

        entityService.link(a.getId(), DEPENDS_ON, b2.getId());

        entityService.link(b1.getId(), DEPENDS_ON, c1.getId());

        entityService.link(b1.getId(), DEPENDS_ON, c2.getId());

        entityService.link(c2.getId(), DEPENDS_ON, d.getId());

        entityService.link(c1.getId(), DEPENDS_ON, d.getId());

        {
            var retrievedEntity = mappingService.retrieveEntityByPath(
                    d.getId(), "DEPENDS_ON{$.[?(@.c == 'c1')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$}");

            Assertions.assertTrue(
                    retrievedEntity.isPresent() && retrievedEntity.get().getId().equals(a.getId()));
        }

        {
            var retrievedEntity = mappingService.retrieveEntityByPath(
                    b1.getId(),
                    "DEPENDS_ON{$.[?(@.c == 'c3')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}");
            Assertions.assertTrue(retrievedEntity.isEmpty());
        }

        {
            var retrievedEntity = mappingService.retrieveEntityByPath(
                    d.getId(),
                    "DEPENDS_ON{$.[?(@.c == 'c3')]}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}");

            Assertions.assertTrue(retrievedEntity.isEmpty());
        }

        {
            Assertions.assertThrows(
                    ServiceError.class,
                    () -> mappingService.retrieveEntityByPath(
                            d.getId(), "DEPENDS_ON{$}/DEPENDS_ON{$.[?(@.b == 0.1)]}/DEPENDS_ON{$.[?(@.a == 1)]}"));
        }
    }

    @Test
    void testCreateAndUpdateMappingEntities() throws ServiceError {
        var traitService = new TraitService(traitRepository, traitRelationshipRepository);
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var entityService = new EntityService(
                entityTypeRepository, entityRepository, traitRelationshipRepository, entityRelationshipRepository);
        var mappingService = new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                entityRelationshipRepository);

        traitService.create("DependingRelSourceTrait", Optional.empty());

        traitService.create("DependingRelTargetTrait", Optional.empty());

        traitService.link("DependingRelSourceTrait", DEPENDS_ON, "DependingRelTargetTrait");

        entityTypeService.create(
                "AnotherType",
                List.of("DependingRelSourceTrait"),
                Optional.empty(),
                """
                { "type": "object", "properties": { "c": { "type": "integer" } } }""");

        entityTypeService.create(
                "SourceType",
                List.of("DependingRelTargetTrait"),
                Optional.empty(),
                """
                { "type": "object", "properties": { "a": { "type": "integer" } } }""");

        entityTypeService.create(
                "TargetType",
                List.of(),
                Optional.empty(),
                """
                { "type": "object", "properties": { "b": { "type": "integer" } } }""");

        mappingService.create("SourceType", "TargetType", """
                        {"b": ""}""", List.of());

        var anotherInstance = entityService.create("AnotherType", """
                {"c": 1}
                """);

        var sourceInstance = entityService.create("SourceType", """
                {"a": 1}
                """);

        entityService.link(anotherInstance.getId(), DEPENDS_ON, sourceInstance.getId());

        mappingService.createMappedEntities(sourceInstance.getId());
    }
}
