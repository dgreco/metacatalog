package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.entity.RelationType.IS_PART_OF;

import it.davidgreco.metacatalog.entity.Trait;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The aggregate-model containment rules, enforced wherever a {@code HAS_PART} can come into
 * existence or change meaning: a trait/type carrying {@code Aggregate} may only have parts carrying
 * {@code AggregateElement}; a trait/type carrying only {@code AggregateElement} may have no parts
 * at all; a carrier of neither stays free — containment outside the aggregate model (an Iceberg
 * namespace containing tables) is not the model's business. An intermediate node carries both, its
 * Aggregate role being what grants it parts.
 *
 * <p>Enforcement points under test: {@code TraitServiceImpl.doLink} (trait relationships, including
 * the immutable contributor path), {@code EntityServiceImpl.link} (instance links, whose sanction
 * is existential and therefore cannot be trusted to carry the rules), and the two {@code
 * createVersion} methods (the only ways a trait's or type's built-in carriage can change after
 * creation).
 */
@SpringBootTest
class AggregateModelRuleTests extends CommonServiceTestingSupport {

  public AggregateModelRuleTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  /**
   * Rule 1: a trait carrying {@code Aggregate} may declare {@code HAS_PART} only towards a trait
   * carrying {@code AggregateElement}. A neutral part and an Aggregate-only part (rule 4's
   * corollary: an Aggregate-only carrier can never be a part of the model) are both refused; an
   * element-carrying part is accepted.
   */
  @Test
  void aggregateCarrierPartsMustCarryAggregateElement() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("R1WholeTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("R1NeutralTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("R1AggOnlyTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("R1ElemTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));

    var neutral =
        Assertions.assertThrows(
            ServiceError.class,
            () -> traitService.link("R1WholeTrait", HAS_PART, "R1NeutralTrait"));
    Assertions.assertTrue(
        neutral.getMessage().contains("must carry AggregateElement"), neutral.getMessage());

    var aggOnly =
        Assertions.assertThrows(
            ServiceError.class,
            () -> traitService.link("R1WholeTrait", HAS_PART, "R1AggOnlyTrait"));
    Assertions.assertTrue(
        aggOnly.getMessage().contains("must carry AggregateElement"), aggOnly.getMessage());

    traitService.link("R1WholeTrait", HAS_PART, "R1ElemTrait");
    Assertions.assertEquals(
        List.of("R1ElemTrait"),
        traitService.linked("R1WholeTrait", HAS_PART).stream().map(Trait::getName).toList());
  }

  /**
   * Rule 2, read with rule 3: only the Aggregate role grants parts, so a trait carrying only {@code
   * AggregateElement} can never be a {@code HAS_PART} source — not towards another element, not
   * towards an Aggregate carrier, and not even towards a neutral trait. Leaves are leaves.
   */
  @Test
  void elementOnlyTraitCannotHaveParts() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("R2ElemTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create("R2ElemTarget", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create("R2AggTarget", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("R2NeutralTarget", Optional.of(EMPTY_SCHEMA), Optional.empty());

    for (var target : List.of("R2ElemTarget", "R2AggTarget", "R2NeutralTarget")) {
      var error =
          Assertions.assertThrows(
              ServiceError.class, () -> traitService.link("R2ElemTrait", HAS_PART, target));
      Assertions.assertTrue(error.getMessage().contains("cannot have parts"), error.getMessage());
    }
  }

  /**
   * What rule 4 deliberately leaves open: a trait carrying neither built-in is outside the
   * aggregate model and may contain anything — including an Aggregate-only carrier. This is the
   * Iceberg pattern (a namespace trait containing the table trait) and must keep working, since a
   * root does not stop being a root by being contained from outside the model.
   */
  @Test
  void neutralTraitMayContainAnything() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("R4NeutralWhole", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("R4AggOnlyTarget", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("R4NeutralTarget", Optional.of(EMPTY_SCHEMA), Optional.empty());

    traitService.link("R4NeutralWhole", HAS_PART, "R4AggOnlyTarget");
    traitService.link("R4NeutralWhole", HAS_PART, "R4NeutralTarget");
    Assertions.assertEquals(
        List.of("R4AggOnlyTarget", "R4NeutralTarget"),
        traitService.linked("R4NeutralWhole", HAS_PART).stream()
            .map(Trait::getName)
            .sorted()
            .toList());
  }

  /**
   * Carriage is inherited: a trait two father-hops away from {@code Aggregate} is as much an
   * Aggregate carrier as a direct child, so the rules cannot be sidestepped by inserting an
   * intermediate father.
   */
  @Test
  void carriageFlowsThroughTraitInheritance() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("InhAggChild", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("InhAggGrandchild", Optional.of(EMPTY_SCHEMA), Optional.of("InhAggChild"));
    traitService.create("InhNeutralTarget", Optional.of(EMPTY_SCHEMA), Optional.empty());

    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () -> traitService.link("InhAggGrandchild", HAS_PART, "InhNeutralTarget"));
    Assertions.assertTrue(
        error.getMessage().contains("must carry AggregateElement"), error.getMessage());
  }

  /**
   * The rules are written in terms of (whole, part), not (source, target): a containment declared
   * through the {@code IS_PART_OF} entry point is the same pair with the sides swapped, and must be
   * judged identically. The legal direction (element {@code IS_PART_OF} aggregate) passes; the
   * reversed one puts the element on the whole side and is refused.
   */
  @Test
  void isPartOfEntryPointIsNormalized() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("NormElem", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create("NormAgg", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("NormAgg2", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));

    traitService.link("NormElem", IS_PART_OF, "NormAgg");

    var error =
        Assertions.assertThrows(
            ServiceError.class, () -> traitService.link("NormAgg2", IS_PART_OF, "NormElem"));
    Assertions.assertTrue(error.getMessage().contains("cannot have parts"), error.getMessage());
  }

  /**
   * The startup-contributor path funnels through the same {@code doLink} body, so a module
   * declaring an immutable model cannot ship a violating containment either — it aborts
   * installation instead of freezing an illegal relationship nobody could ever remove.
   */
  @Test
  void immutableContributorPathIsSubjectToTheRules() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var immutableTraitWriter = getApplicationContext().getBean(ImmutableTraitWriter.class);

    traitService.create("ImmWholeTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create("ImmNeutralTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());

    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () -> immutableTraitWriter.linkImmutable("ImmWholeTrait", HAS_PART, "ImmNeutralTrait"));
    Assertions.assertTrue(
        error.getMessage().contains("must carry AggregateElement"), error.getMessage());
  }

  /**
   * The rules govern containment only. Any other relation type between the same carriers — an
   * element depending on an aggregate, say — stays legal.
   */
  @Test
  void otherRelationTypesAreUnaffected() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("DepElemTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create("DepAggTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));

    traitService.link("DepElemTrait", DEPENDS_ON, "DepAggTrait");
    Assertions.assertEquals(
        List.of("DepAggTrait"),
        traitService.linked("DepElemTrait", DEPENDS_ON).stream().map(Trait::getName).toList());
  }

  /**
   * A link's sanction is existential — one trait relationship anywhere between the two types'
   * traits is enough — so a relationship between two neutral traits can sanction a link whose types
   * carry the built-ins through other mixins. The entity-level check must judge the types' actual
   * carriage: here the whole's type also carries {@code Aggregate} and the part's type carries it
   * too (an Aggregate-only part), so the link is refused despite being sanctioned, while the same
   * link towards an element-carrying part passes.
   */
  @Test
  void sanctionDoesNotOverrideTypeCarriage() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    traitService.create("LoopholeContainerTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("LoopholeContainedTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.link("LoopholeContainerTrait", HAS_PART, "LoopholeContainedTrait");

    entityTypeService.create(
        "LoopholeWholeType",
        List.of("LoopholeContainerTrait", "Aggregate"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "LoopholeAggPartType",
        List.of("LoopholeContainedTrait", "Aggregate"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "LoopholeElemPartType",
        List.of("LoopholeContainedTrait", "AggregateElement"),
        Optional.empty(),
        EMPTY_SCHEMA);

    var whole = entityService.create("LoopholeWholeType", "{}");
    var aggPart = entityService.create("LoopholeAggPartType", "{}");
    var elemPart = entityService.create("LoopholeElemPartType", "{}");

    var error =
        Assertions.assertThrows(
            ServiceError.class, () -> entityService.link(whole.getId(), HAS_PART, aggPart.getId()));
    Assertions.assertTrue(
        error.getMessage().contains("must carry AggregateElement")
            && error.getMessage().contains(aggPart.getId()),
        error.getMessage());

    entityService.link(whole.getId(), HAS_PART, elemPart.getId());
  }

  /**
   * The entity-level twin of rule 2's total ban: an entity whose type carries only {@code
   * AggregateElement} can contain nothing, not even a neutral-typed entity a neutral trait pair
   * would happily sanction. Without this, {@code AggregateService.read} would classify the entity
   * as a leaf and silently drop its parts from the returned tree.
   */
  @Test
  void elementOnlyTypedEntityCannotContain() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    traitService.create("LeafSrcTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("LeafDstTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.link("LeafSrcTrait", HAS_PART, "LeafDstTrait");

    entityTypeService.create(
        "LeafWholeType",
        List.of("LeafSrcTrait", "AggregateElement"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "LeafPartType", List.of("LeafDstTrait"), Optional.empty(), EMPTY_SCHEMA);

    var whole = entityService.create("LeafWholeType", "{}");
    var part = entityService.create("LeafPartType", "{}");

    var error =
        Assertions.assertThrows(
            ServiceError.class, () -> entityService.link(whole.getId(), HAS_PART, part.getId()));
    Assertions.assertTrue(
        error.getMessage().contains("carries only AggregateElement")
            && error.getMessage().contains(whole.getId()),
        error.getMessage());
  }

  /**
   * The entity-level counterpart of the Iceberg pattern: an entity of a neutral type (a namespace)
   * may contain an entity whose type is an aggregate root (a table). Rule 4 restricts only sources
   * inside the model, and the root does not stop being a root for it.
   */
  @Test
  void neutralTypedWholeMayContainAggregateRoot() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    traitService.create("NsTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("TblTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.link("NsTrait", HAS_PART, "TblTrait");

    entityTypeService.create("NsType", List.of("NsTrait"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create(
        "TblType", List.of("TblTrait", "Aggregate"), Optional.empty(), EMPTY_SCHEMA);

    var namespace = entityService.create("NsType", "{}");
    var table = entityService.create("TblType", "{}");

    entityService.link(namespace.getId(), HAS_PART, table.getId());
    Assertions.assertEquals(
        List.of(table.getId()),
        entityService.linked(namespace.getId(), HAS_PART).stream()
            .map(it.davidgreco.metacatalog.entity.Entity::getId)
            .toList());
  }

  /**
   * Entity links too are judged as (whole, part) whichever way they are expressed: an {@code
   * IS_PART_OF} whose target's type carries only {@code AggregateElement} puts an element on the
   * whole side and is refused, while the properly-oriented pair passes.
   */
  @Test
  void entityIsPartOfEntryPointIsNormalized() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    traitService.create("NrmA", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("NrmB", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.link("NrmA", HAS_PART, "NrmB");

    entityTypeService.create(
        "NrmElemWholeType", List.of("NrmA", "AggregateElement"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create(
        "NrmAggWholeType", List.of("NrmA", "Aggregate"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create(
        "NrmPartType", List.of("NrmB", "AggregateElement"), Optional.empty(), EMPTY_SCHEMA);

    var legalWhole = entityService.create("NrmAggWholeType", "{}");
    var illegalWhole = entityService.create("NrmElemWholeType", "{}");
    var part = entityService.create("NrmPartType", "{}");

    entityService.link(part.getId(), IS_PART_OF, legalWhole.getId());

    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () -> entityService.link(part.getId(), IS_PART_OF, illegalWhole.getId()));
    Assertions.assertTrue(
        error.getMessage().contains("carries only AggregateElement")
            && error.getMessage().contains(illegalWhole.getId()),
        error.getMessage());
  }

  /**
   * A type version that drops {@code AggregateElement} while an entity of the type is still a part
   * of an aggregate would retroactively violate rule 1, silently — the link already exists and
   * nothing would ever re-check it. {@code createVersion} therefore refuses, naming the offending
   * containment, exactly as it already refuses a version that breaks a mapping targeting the type.
   * A version that keeps the carriage untouched still goes through.
   */
  @Test
  void typeVersionCannotDropElementRoleWhileContained() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create("VerWholeType", List.of("Aggregate"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create(
        "VerPartType", List.of("AggregateElement"), Optional.empty(), EMPTY_SCHEMA);

    var whole = entityService.create("VerWholeType", "{}");
    var part = entityService.create("VerPartType", "{}");
    entityService.link(whole.getId(), HAS_PART, part.getId());

    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () ->
                entityTypeService.createVersion(
                    "VerPartType", List.of(), Optional.empty(), EMPTY_SCHEMA));
    Assertions.assertTrue(
        error.getMessage().contains("Versioning EntityType VerPartType")
            && error.getMessage().contains(part.getId()),
        error.getMessage());

    // Carriage unchanged — the version is none of the aggregate model's business.
    entityTypeService.createVersion(
        "VerPartType", List.of("AggregateElement"), Optional.empty(), EMPTY_SCHEMA);
  }

  /**
   * The whole side is re-validated too: a version that leaves a containing type element-only turns
   * its entities into leaves with parts and is refused, while a version that drops it to neutral is
   * legal — a neutral whole containing an element carrier violates nothing, only the model's
   * classification stops applying to it.
   */
  @Test
  void typeVersionCannotLeaveElementOnlyWholeWithParts() {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    entityTypeService.create(
        "Ver2WholeType", List.of("Aggregate", "AggregateElement"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create(
        "Ver2PartType", List.of("AggregateElement"), Optional.empty(), EMPTY_SCHEMA);

    var whole = entityService.create("Ver2WholeType", "{}");
    var part = entityService.create("Ver2PartType", "{}");
    entityService.link(whole.getId(), HAS_PART, part.getId());

    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () ->
                entityTypeService.createVersion(
                    "Ver2WholeType", List.of("AggregateElement"), Optional.empty(), EMPTY_SCHEMA));
    Assertions.assertTrue(
        error.getMessage().contains("Versioning EntityType Ver2WholeType")
            && error.getMessage().contains("cannot have parts"),
        error.getMessage());

    entityTypeService.createVersion("Ver2WholeType", List.of(), Optional.empty(), EMPTY_SCHEMA);
  }

  /**
   * A trait version can change the father, and with it the carriage of every trait and type
   * downstream — the only way a trait's built-in carriage can change after creation. Clearing the
   * element trait's father here would leave the existing {@code VerAggTrait HAS_PART VerElemTrait}
   * relationship with an Aggregate carrier containing a neutral trait, so the version is refused; a
   * version that keeps the father is untouched by the check.
   */
  @Test
  void traitVersionCannotChangeCarriageBreakingContainments() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("VerElemTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create("VerAggTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.link("VerAggTrait", HAS_PART, "VerElemTrait");

    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () ->
                traitService.createVersion(
                    "VerElemTrait", Optional.of(EMPTY_SCHEMA), Optional.empty()));
    Assertions.assertTrue(
        error.getMessage().contains("Versioning Trait VerElemTrait")
            && error.getMessage().contains("must carry AggregateElement"),
        error.getMessage());

    traitService.createVersion(
        "VerElemTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
  }
}
