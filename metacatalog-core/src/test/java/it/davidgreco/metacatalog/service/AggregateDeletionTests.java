package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.RelationType;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Covers {@link AggregateService#delete(String)}. The database is reset once per test class, not
 * per method, so the model (traits and entity types) is created by whichever test runs first and
 * merely looked up by the rest; every test then builds its own entities on top of it.
 */
@SpringBootTest
class AggregateDeletionTests extends CommonServiceTestingSupport {

  private EntityType aggregateRootType;

  private EntityType aggregateType;

  private EntityType aggregateElementType;

  public AggregateDeletionTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @BeforeEach
  void createModel() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

    if (traitService.exists("NamedTrait")) {
      aggregateRootType = entityTypeService.read("AggregateRootType");
      aggregateType = entityTypeService.read("AggregateType");
      aggregateElementType = entityTypeService.read("AggregateElementType");
      return;
    }

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

    // Root carries only Aggregate, intermediate both traits, leaf only AggregateElement.
    aggregateRootType =
        entityTypeService.create(
            "AggregateRootType",
            List.of("Aggregate", "NamedTrait"),
            Optional.empty(),
            EMPTY_SCHEMA);

    aggregateType =
        entityTypeService.create(
            "AggregateType",
            List.of("Aggregate", "AggregateElement", "NamedTrait"),
            Optional.empty(),
            EMPTY_SCHEMA);

    aggregateElementType =
        entityTypeService.create(
            "AggregateElementType",
            List.of("AggregateElement", "NamedTrait"),
            Optional.empty(),
            EMPTY_SCHEMA);

    traitService.link("AggregateElement", DEPENDS_ON, "AggregateElement");
  }

  @Test
  void testDeleteRemovesTheWholeTree() throws IOException, ServiceError {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var root = entity(aggregateRootType, "root");
    var leaf1 = entity(aggregateElementType, "leaf1");
    var leaf2 = entity(aggregateElementType, "leaf2");
    var subRoot = entity(aggregateType, "subRoot");
    var leaf3 = entity(aggregateElementType, "leaf3");

    var created =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    root,
                    List.of(
                        new AggregateService.AggregateElement(leaf1, List.of()),
                        // leaf2 depends on leaf1: the delete has to drop that edge too
                        new AggregateService.AggregateElement(leaf2, List.of(leaf1)),
                        new AggregateService.Aggregate(
                            subRoot, List.of(new AggregateService.AggregateElement(leaf3))))));

    var allIds =
        List.of(
            created.entity().getId(), leaf1.getId(), leaf2.getId(), subRoot.getId(), leaf3.getId());

    aggregateService.delete(created.entity().getId());

    allIds.forEach(id -> Assertions.assertFalse(entityService.exists(id), id + " still exists"));
    Assertions.assertThrows(
        NotFoundException.class, () -> aggregateService.read(created.entity().getId(), false));
  }

  @Test
  void testDeleteIsRefusedWhenAMemberIsLinkedOutsideTheAggregate() throws IOException {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var root = entity(aggregateRootType, "outsideRoot");
    var leaf = entity(aggregateElementType, "outsideLeaf");

    var created =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    root, List.of(new AggregateService.AggregateElement(leaf))));

    var outsider =
        entityService.create(
            "AggregateElementType",
            """
            {"name": "outsider"}""");
    entityService.link(outsider.getId(), DEPENDS_ON, leaf.getId());

    var error =
        Assertions.assertThrows(
            ServiceError.class, () -> aggregateService.delete(created.entity().getId()));
    Assertions.assertTrue(error.getMessage().contains("outside the aggregate"), error.getMessage());

    // Nothing was removed.
    Assertions.assertTrue(entityService.exists(created.entity().getId()));
    Assertions.assertTrue(entityService.exists(leaf.getId()));
    Assertions.assertTrue(entityService.exists(outsider.getId()));
  }

  @Test
  void testDeleteIsRefusedForAnEntityThatIsNotAnAggregateRoot() throws IOException {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var root = entity(aggregateRootType, "nestedRoot");
    var subRoot = entity(aggregateType, "nestedSubRoot");
    var leaf = entity(aggregateElementType, "nestedLeaf");

    var created =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    root,
                    List.of(
                        new AggregateService.Aggregate(
                            subRoot, List.of(new AggregateService.AggregateElement(leaf))))));

    var error =
        Assertions.assertThrows(ServiceError.class, () -> aggregateService.delete(subRoot.getId()));
    Assertions.assertTrue(error.getMessage().contains("is not an aggregate"), error.getMessage());

    Assertions.assertTrue(entityService.exists(created.entity().getId()));
    Assertions.assertTrue(entityService.exists(subRoot.getId()));
    Assertions.assertTrue(entityService.exists(leaf.getId()));
  }

  /**
   * A part cannot be unlinked from its aggregate in either direction. Left possible, it would
   * strand the part: the aggregate delete only sees what is reachable from the root, and the entity
   * delete refuses anything still linked.
   */
  @Test
  void testAPartCannotBeUnlinkedFromItsAggregate() throws IOException {
    var entityService = getApplicationContext().getBean(EntityService.class);
    var aggregateService = getApplicationContext().getBean(AggregateService.class);

    var root = entity(aggregateRootType, "structuralRoot");
    var kept = entity(aggregateElementType, "structuralKept");
    var part = entity(aggregateElementType, "structuralPart");

    var created =
        (AggregateService.Aggregate)
            aggregateService.create(
                new AggregateService.Aggregate(
                    root,
                    List.of(
                        new AggregateService.AggregateElement(kept, List.of()),
                        new AggregateService.AggregateElement(part, List.of(kept)))));
    var rootId = created.entity().getId();

    for (var attempt :
        List.of(
            (Executable) () -> entityService.unlink(rootId, RelationType.HAS_PART, part.getId()),
            () -> entityService.unlink(part.getId(), RelationType.IS_PART_OF, rootId))) {
      var error = Assertions.assertThrows(ServiceError.class, attempt);
      Assertions.assertTrue(
          error.getMessage().contains("Delete the aggregate"), error.getMessage());
    }

    // The dependency between two parts is not containment, so it stays removable.
    entityService.unlink(part.getId(), DEPENDS_ON, kept.getId());

    // The aggregate is still intact and still deletes as a whole.
    aggregateService.delete(rootId);
    Assertions.assertFalse(entityService.exists(rootId));
    Assertions.assertFalse(entityService.exists(kept.getId()));
    Assertions.assertFalse(entityService.exists(part.getId()));
  }

  private Entity entity(EntityType entityType, String name) throws IOException {
    var jsonMapper = getApplicationContext().getBean(ObjectMapper.class);
    return new Entity(entityType, jsonMapper.readTree("{\"name\": \"" + name + "\"}"));
  }
}
