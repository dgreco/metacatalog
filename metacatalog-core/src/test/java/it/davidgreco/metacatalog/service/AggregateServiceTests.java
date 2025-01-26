package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.RelationType;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AggregateServiceTests extends CommonServiceTests {

  @Test
  void testCreateAndRead() throws IOException, ServiceError {
    var traitService = applicationContext.getBean(TraitService.class);
    var entityService = applicationContext.getBean(EntityService.class);
    var entityTypeService = applicationContext.getBean(EntityTypeService.class);
    var aggregateService = applicationContext.getBean(AggregateService.class);

    traitService.create(
        "NamedTrait",
        Optional.of(
            """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string"
                    }
                  }
                }
                """),
        Optional.empty());

    var aggregateType =
        entityTypeService.create(
            "AggregateType",
            List.of("Aggregate", "AggregateElement", "NamedTrait"),
            Optional.empty(),
            EMPTY_SCHEMA);

    var aggregateElementType =
        entityTypeService.create(
            "AggregateElementType",
            List.of("AggregateElement", "NamedTrait"),
            Optional.empty(),
            EMPTY_SCHEMA);

    var aggregate =
        new AggregateService.Aggregate(
            new Entity(
                aggregateType,
                jsonFactory.readTree(
                    """
                        {"name": "root"}""")),
            List.of(
                new AggregateService.AggregateElement(
                    new Entity(
                        aggregateElementType,
                        jsonFactory.readTree(
                            """
                        {"name": "leaf1"}"""))),
                new AggregateService.AggregateElement(
                    new Entity(
                        aggregateElementType,
                        jsonFactory.readTree(
                            """
                        {"name": "leaf2"}"""))),
                new AggregateService.Aggregate(
                    new Entity(
                        aggregateType,
                        jsonFactory.readTree(
                            """
                                          {"name": "subRoot"}""")),
                    List.of(
                        new AggregateService.AggregateElement(
                            new Entity(
                                aggregateElementType,
                                jsonFactory.readTree(
                                    """
                                              {"name": "leaf3"}""")))))));

    var result = aggregateService.create(aggregate);

    Assertions.assertEquals(
        Set.of("leaf1", "leaf2", "subRoot"),
        entityService.linked(result.entity().getId(), RelationType.HAS_PART).stream()
            .map(entity -> entity.getValues().get("name").asText())
            .collect(Collectors.toSet()));

    var subRoot =
        entityService.linked(result.entity().getId(), RelationType.HAS_PART).stream()
            .filter(entity -> entity.getValues().get("name").asText().equals("subRoot"))
            .findFirst()
            .get();

    Assertions.assertEquals(
        Set.of("leaf3"),
        entityService.linked(subRoot.getId(), RelationType.HAS_PART).stream()
            .map(entity -> entity.getValues().get("name").asText())
            .collect(Collectors.toSet()));

    var retrievedAggregate = aggregateService.read(result.entity().getId());

    Assertions.assertEquals(result.toString(), retrievedAggregate.toString());

    Assertions.assertThrows(ServiceError.class, () -> aggregateService.read(subRoot.getId()));
  }
}
