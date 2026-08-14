package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.context.ApplicationContext;

@SpringBootTest
class AggregateServiceTests extends CommonServiceTestingSupport {

  public AggregateServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreateAndRead() throws IOException, ServiceError {
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

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

    // The three classes of the aggregate model: a root type carries only Aggregate, an
    // intermediate type both traits, a leaf type only AggregateElement.
    var aggregateRootType =
        entityTypeService.create(
            "AggregateRootType",
            List.of("Aggregate", "NamedTrait"),
            Optional.empty(),
            EMPTY_SCHEMA);

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

    traitService.link("AggregateElement", DEPENDS_ON, "AggregateElement");

    var root =
        new Entity(
            aggregateRootType,
            jsonMapper.readTree(
                """
                        {"name": "root"}"""));

    var leaf1 =
        new Entity(
            aggregateElementType,
            jsonMapper.readTree(
                """
                        {"name": "leaf1"}"""));

    var leaf2 =
        new Entity(
            aggregateElementType,
            jsonMapper.readTree(
                """
                        {"name": "leaf2"}"""));

    var subRoot =
        new Entity(
            aggregateType,
            jsonMapper.readTree(
                """
                        {"name": "subRoot"}"""));

    var leaf3 =
        new Entity(
            aggregateElementType,
            jsonMapper.readTree(
                """
                        {"name": "leaf3"}"""));

    var aggregate =
        new AggregateService.Aggregate(
            root,
            List.of(
                new AggregateService.AggregateElement(leaf1, List.of()),
                new AggregateService.AggregateElement(leaf2, List.of(leaf1)),
                new AggregateService.Aggregate(
                    subRoot, List.of(new AggregateService.AggregateElement(leaf3)))));

    var result = (AggregateService.Aggregate) aggregateService.create(aggregate);

    Assertions.assertEquals(
        Set.of("leaf1", "leaf2", "subRoot"),
        entityService.linked(result.entity().getId(), RelationType.HAS_PART).stream()
            .map(entity -> entity.getValues().get("name").asText())
            .collect(Collectors.toSet()));

    var retrievedSubRoot =
        entityService.linked(result.entity().getId(), RelationType.HAS_PART).stream()
            .filter(entity -> entity.getValues().get("name").asText().equals("subRoot"))
            .findFirst()
            .get();

    Assertions.assertEquals(
        Set.of("leaf3"),
        entityService.linked(retrievedSubRoot.getId(), RelationType.HAS_PART).stream()
            .map(entity -> entity.getValues().get("name").asText())
            .collect(Collectors.toSet()));

    var retrievedAggregate = aggregateService.read(result.entity().getId(), false);

    Assertions.assertEquals(result.toString(), retrievedAggregate.toString());

    Assertions.assertThrows(
        ServiceError.class, () -> aggregateService.read(retrievedSubRoot.getId(), false));
  }

  /**
   * The type model may declare a self-referential composition (a folder contains folders — the type
   * carries both built-ins, its container role via one trait and its element role via another), but
   * at the instance level containment must stay acyclic: {@code AggregateService.read} recurses
   * along {@code HAS_PART} with no visited set, so a loop — including the degenerate self-link —
   * would never terminate. Link creation is where that is refused.
   */
  @Test
  void testContainmentCannotFormALoop() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    traitService.create(
        "LoopFolderTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create(
        "LoopFolderContainerTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.link("LoopFolderContainerTrait", RelationType.HAS_PART, "LoopFolderTrait");
    traitService.create("LoopRootTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.link("LoopRootTrait", RelationType.HAS_PART, "LoopFolderTrait");
    entityTypeService.create(
        "LoopFolderType",
        List.of("LoopFolderContainerTrait", "LoopFolderTrait"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "LoopRootType", List.of("LoopRootTrait"), Optional.empty(), EMPTY_SCHEMA);

    var root = entityService.create("LoopRootType", "{}");
    var a = entityService.create("LoopFolderType", "{}");
    var b = entityService.create("LoopFolderType", "{}");
    var c = entityService.create("LoopFolderType", "{}");
    entityService.link(root.getId(), RelationType.HAS_PART, a.getId());
    entityService.link(a.getId(), RelationType.HAS_PART, b.getId());
    entityService.link(b.getId(), RelationType.HAS_PART, c.getId());

    // Closing the cycle is refused at any length, self-link included.
    for (var attempt :
        List.of(
            (org.junit.jupiter.api.function.Executable)
                () -> entityService.link(c.getId(), RelationType.HAS_PART, a.getId()),
            () -> entityService.link(b.getId(), RelationType.HAS_PART, a.getId()),
            () -> entityService.link(a.getId(), RelationType.HAS_PART, a.getId()))) {
      var error = Assertions.assertThrows(ServiceError.class, attempt);
      Assertions.assertTrue(error.getMessage().contains("Loops"), error.getMessage());
    }

    // The chain stays readable as an aggregate: the root contains a contains b contains c, and
    // the walk ends.
    var read = aggregateService.read(root.getId(), false);
    Assertions.assertEquals(root.getId(), read.entity().getId());
  }
}
