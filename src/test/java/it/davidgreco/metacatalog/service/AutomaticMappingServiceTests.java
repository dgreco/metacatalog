package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AutomaticMappingServiceTests extends CommonServiceTests {

    @Test
    void testCreateAndUpdateAndDeleteMappedEntities() throws ServiceError, InterruptedException {
        var traitService = new TraitService(traitRepository, traitRelationshipRepository);
        var entityTypeService = new EntityTypeService(entityTypeRepository, traitRepository);
        var entityService = new EntityService(
                entityTypeRepository,
                entityRepository,
                entityRelationshipRepository,
                traitRelationshipRepository,
                mappingEntityTypeRelationshipRepository,
                entityLifeCycleEventRepository);
        var mappingService = new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                mappingEntityRelationshipRepository,
                entityRelationshipRepository,
                entityLifeCycleEventRepository,
                transactionManager,
                advisoryLockManager);

        mappingService.automaticEntitiesMapping.set(true);

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

        var targeType = entityTypeService.create(
                "TargetType",
                List.of(),
                Optional.empty(),
                """
                    { "type": "object", "properties": { "b": { "type": "integer" } } }""");

        var anotherTargetType = entityTypeService.create(
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
                List.of(new MappingEntityTypeRelationship.EntityPathReference("ai", "DEPENDS_ON{$}")));

        mappingService.create(
                "TargetType",
                "AnotherTargetType",
                """
                            {"d": "#source.getValue('$.b').intValue() + #ai.getValue('$.c').intValue()*10"}""",
                List.of(new MappingEntityTypeRelationship.EntityPathReference("ai", "MAPPED_TO{$}/DEPENDS_ON{$}")));

        var anotherInstance =
                entityService.create("AnotherType", """
                    {"c": 1}
                    """);

        var sourceInstance =
                entityService.create("SourceType", """
                    {"a": 1}
                    """);

        entityService.link(anotherInstance.getId(), DEPENDS_ON, sourceInstance.getId());

        mappingService.createMappedEntities(sourceInstance.getId());

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
