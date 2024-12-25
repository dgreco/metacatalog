package it.witboost.dataplatformshaper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MappingServiceTests extends CommonServiceTests {

    @Autowired
    private TraitService traitService;

    @Test
    void testCreateDeleteExists() throws JsonProcessingException, SchemaValidationError, ServiceError {
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

        System.out.println(mapping1);
        System.out.println(mapping2);
    }
}
