package it.witboost.dataplatformshaper.service;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

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
}
