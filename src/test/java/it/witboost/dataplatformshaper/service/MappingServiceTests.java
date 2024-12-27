package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.entity.RelationType.DEPENDS_ON;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class MappingServiceTests extends CommonServiceTests {

    @Test
    void testCreateDelete() throws ServiceError {
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var mappingService = new MappingService(entityTypeRepository, mappingEntityTypeRelationshipRepository);

        entityTypeService.create(
                "SourceType",
                List.of(),
                Optional.empty(),
                """
                { "type": "object", "properties": {} }""");

        entityTypeService.create(
                "TargetType",
                List.of(),
                Optional.empty(),
                """
                { "type": "object", "properties": {} }""");

        var mapping1 = mappingService.create("SourceType", "TargetType", "{}", List.of());

        var mapping2 = mappingService.create("SourceType", "TargetType", "{}", List.of());

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> entityTypeService.delete("SourceType"));
        Assertions.assertThrows(DataIntegrityViolationException.class, () -> entityTypeService.delete("TargetType"));

        mappingService.delete(mapping1.getId());
        mappingService.delete(mapping2.getId());

        entityTypeService.delete("SourceType");
        entityTypeService.delete("TargetType");
    }

    @Test
    void testCheckLoopsAndIsSourceAndIsTarget() throws ServiceError {
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var mappingService = new MappingService(entityTypeRepository, mappingEntityTypeRelationshipRepository);

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
    @Transactional
    void testGraphPath() throws ServiceError {

        var traitService = new TraitService(traitRepository, traitRelationshipRepository);
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var entityService = new EntityService(
                entityTypeRepository, entityRepository, traitRelationshipRepository, entityRelationshipRepository);
        var mappingService = new MappingService(entityTypeRepository, mappingEntityTypeRelationshipRepository);

        traitService.create("Trait_A", Optional.empty());

        traitService.create("Trait_B", Optional.empty());

        traitService.create("Trait_C", Optional.empty());

        traitService.create("Trait_D", Optional.empty());

        traitService.link("Trait_A", DEPENDS_ON, "Trait_B");

        traitService.link("Trait_B", DEPENDS_ON, "Trait_C");

        traitService.link("Trait_C", DEPENDS_ON, "Trait_D");

        entityTypeService.create(
                "A",
                List.of("Trait_A"),
                Optional.empty(),
                """
                { "type": "object", "properties": { "a": { "type": "integer" } } }""");

        entityTypeService.create(
                "B",
                List.of("Trait_B"),
                Optional.empty(),
                """
                { "type": "object", "properties": { "b": { "type": "number" }} }""");

        entityTypeService.create(
                "C",
                List.of("Trait_C"),
                Optional.empty(),
                """
                { "type": "object", "properties": { "c": { "type": "string" }} }""");

        entityTypeService.create(
                "D",
                List.of("Trait_D"),
                Optional.empty(),
                """
                { "type": "object", "properties": { "d": { "type": "string" }} }""");

        var a = entityService.create("A", """
               { "a": 1 }""");

        var b1 = entityService.create("B", """
               { "b": 0.1 }""");

        var b2 = entityService.create("B", """
               { "b": 0.2 }""");

        var c1 = entityService.create("C", """
               { "c": "c1" }""");

        var c2 = entityService.create("C", """
               { "c": "c2" }""");

        var d = entityService.create("D", """
               { "d": "d" }""");

        entityService.link(a.getId(), DEPENDS_ON, b1.getId());

        entityService.link(a.getId(), DEPENDS_ON, b2.getId());

        entityService.link(b1.getId(), DEPENDS_ON, c1.getId());

        entityService.link(b1.getId(), DEPENDS_ON, c2.getId());

        entityService.link(c1.getId(), DEPENDS_ON, d.getId());
    }
}
