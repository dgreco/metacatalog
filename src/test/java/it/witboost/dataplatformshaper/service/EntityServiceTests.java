package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;
import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class EntityServiceTests extends CommonServiceTests {

    @Test
    void testCreationAndValidation() throws IOException, ServiceError {
        final EntityTypeService entityTypeService = new EntityTypeService(entityTypeRepository);
        final EntityService entityService = new EntityService(entityTypeRepository, typedEntityRepository);

        var schema = jsonSchemaFactory
                .getSchema(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/simple_schema.json"))
                .getSchemaNode()
                .toPrettyString();

        var values = jsonFactory
                .readTree(Thread.currentThread().getContextClassLoader().getResourceAsStream("jsons/simple_doc.json"))
                .toPrettyString();

        var invalidValues = jsonFactory
                .readTree(Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("jsons/invalid_simple_doc.json"))
                .toPrettyString();

        entityTypeService.create("TestType", schema, Optional.empty());

        var entity = entityService.create("TestType", values);

        SchemaValidationError exception =
                assertThrows(SchemaValidationError.class, () -> entityService.create("TestType", invalidValues));

        Assertions.assertEquals("$.price: must have an exclusive minimum value of 0", exception.errors.get(0));

        Assertions.assertEquals(1, entityService.countEntitiesByEntityType("TestType"));

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> entityTypeService.delete("TestType"));

        entityService.delete(entity);

        entityTypeService.delete("TestType");

        Assertions.assertEquals(0, entityService.countEntitiesByEntityType("TestType"));
    }
}
