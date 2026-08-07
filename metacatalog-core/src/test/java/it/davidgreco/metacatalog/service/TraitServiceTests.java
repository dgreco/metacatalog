package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.entity.RelationType.IS_PART_OF;
import static it.davidgreco.metacatalog.entity.RelationType.IS_REQUIRED_BY;

import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class TraitServiceTests extends CommonServiceTestingSupport {

  public TraitServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void testCreation() {

    final TraitService traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("trait1", Optional.empty(), Optional.empty());
    traitService.create("trait2", Optional.empty(), Optional.empty());
    traitService.create("trait3", Optional.empty(), Optional.empty());

    traitService.link("trait1", DEPENDS_ON, "trait2");

    traitService.link("trait2", DEPENDS_ON, "trait3");

    Assertions.assertThrows(
        ServiceError.class, () -> traitService.link("trait3", DEPENDS_ON, "trait1"));

    var list = traitService.linked("trait1", DEPENDS_ON);

    Assertions.assertEquals(1, list.size());

    Assertions.assertEquals(
        Set.of("trait2"), list.stream().map(Trait::getName).collect(Collectors.toSet()));

    Assertions.assertThrows(
        DataIntegrityViolationException.class, () -> traitService.delete("trait1"));

    traitService.unlink("trait1", DEPENDS_ON, "trait2");
    traitService.unlink("trait2", DEPENDS_ON, "trait3");

    traitService.delete("trait1");
    traitService.delete("trait2");
    traitService.delete("trait3");
  }

  @Test
  void testSelfReferentialLinkIsAllowed() {

    final TraitService traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("selfLinkTrait", Optional.empty(), Optional.empty());

    // A trait may relate to itself (e.g. DataProductComponent IS_REQUIRED_BY DataProductComponent);
    // this is a supported construct and must not be rejected as a loop.
    traitService.link("selfLinkTrait", DEPENDS_ON, "selfLinkTrait");

    var linked = traitService.linked("selfLinkTrait", DEPENDS_ON);
    Assertions.assertEquals(
        Set.of("selfLinkTrait"), linked.stream().map(Trait::getName).collect(Collectors.toSet()));

    traitService.unlink("selfLinkTrait", DEPENDS_ON, "selfLinkTrait");
    traitService.delete("selfLinkTrait");
  }

  @Test
  void testLoopDetectionExploresAllBranches() {

    final TraitService traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("loopA", Optional.empty(), Optional.empty());
    traitService.create("loopB", Optional.empty(), Optional.empty());
    traitService.create("loopC", Optional.empty(), Optional.empty());
    traitService.create("loopD", Optional.empty(), Optional.empty());

    // loopA has two outgoing edges; the cycle runs through the second branch (loopC -> loopD).
    traitService.link("loopA", DEPENDS_ON, "loopB");
    traitService.link("loopA", DEPENDS_ON, "loopC");
    traitService.link("loopC", DEPENDS_ON, "loopD");

    // loopD -> loopA would close the cycle loopA -> loopC -> loopD -> loopA and must be rejected.
    Assertions.assertThrows(
        ServiceError.class, () -> traitService.link("loopD", DEPENDS_ON, "loopA"));

    // A link that does not close a cycle is still allowed even though the graph now branches.
    traitService.link("loopB", DEPENDS_ON, "loopD");

    traitService.unlink("loopA", DEPENDS_ON, "loopB");
    traitService.unlink("loopA", DEPENDS_ON, "loopC");
    traitService.unlink("loopC", DEPENDS_ON, "loopD");
    traitService.unlink("loopB", DEPENDS_ON, "loopD");

    traitService.delete("loopA");
    traitService.delete("loopB");
    traitService.delete("loopC");
    traitService.delete("loopD");
  }

  /**
   * A trait relationship an existing instance link relies on cannot be removed. The link is only
   * admitted because the relationship sanctions it, so taking the relationship away would leave the
   * link in the graph with nothing justifying it. It becomes removable once the link is gone.
   *
   * <p>The check is exact rather than type-level: types participate by mixing traits in, so a link
   * two trait relationships sanction survives the removal of either one — and only becomes a
   * blocker for the survivor once it is the last justification left.
   */
  @Test
  void testTraitRelationshipAnInstanceLinkReliesOnCannotBeRemoved() {
    final TraitService traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var emptySchema =
        """
        { "type": "object", "properties": {} }
        """;

    traitService.create("RelyUpstream", Optional.empty(), Optional.empty());
    traitService.create("RelyDownstream", Optional.empty(), Optional.empty());
    // A second, independent pair sanctioning the same shape of link.
    traitService.create("RelyAltUpstream", Optional.empty(), Optional.empty());
    traitService.create("RelyAltDownstream", Optional.empty(), Optional.empty());
    traitService.link("RelyUpstream", DEPENDS_ON, "RelyDownstream");
    traitService.link("RelyAltUpstream", DEPENDS_ON, "RelyAltDownstream");

    // These two types carry one trait each, so a single relationship sanctions their links...
    entityTypeService.create(
        "RelySourceType", List.of("RelyUpstream"), Optional.empty(), emptySchema);
    entityTypeService.create(
        "RelyTargetType", List.of("RelyDownstream"), Optional.empty(), emptySchema);
    // ...while these mix in both pairs, so two relationships sanction theirs.
    entityTypeService.create(
        "RelyBothSourceType",
        List.of("RelyUpstream", "RelyAltUpstream"),
        Optional.empty(),
        emptySchema);
    entityTypeService.create(
        "RelyBothTargetType",
        List.of("RelyDownstream", "RelyAltDownstream"),
        Optional.empty(),
        emptySchema);

    var source = entityService.create("RelySourceType", "{}");
    var target = entityService.create("RelyTargetType", "{}");
    entityService.link(source.getId(), DEPENDS_ON, target.getId());

    var bothSource = entityService.create("RelyBothSourceType", "{}");
    var bothTarget = entityService.create("RelyBothTargetType", "{}");
    entityService.link(bothSource.getId(), DEPENDS_ON, bothTarget.getId());

    // The singly-sanctioned link blocks the removal, whichever row of the pair is named.
    var error =
        Assertions.assertThrows(
            ServiceError.class,
            () -> traitService.unlink("RelyUpstream", DEPENDS_ON, "RelyDownstream"));
    Assertions.assertTrue(
        error.getMessage().contains(source.getId()) && error.getMessage().contains(target.getId()),
        "the error must name the link relying on the relationship, got: " + error.getMessage());
    Assertions.assertThrows(
        ServiceError.class,
        () -> traitService.unlink("RelyDownstream", IS_REQUIRED_BY, "RelyUpstream"));

    // The alternative pair is removable even though bothSource -> bothTarget was created under it:
    // the first pair sanctions that link too, so it keeps a justification.
    traitService.unlink("RelyAltUpstream", DEPENDS_ON, "RelyAltDownstream");

    // With the singly-sanctioned link gone, the first pair is still held by bothSource ->
    // bothTarget — which the removal above left with only this one justification.
    entityService.unlink(source.getId(), DEPENDS_ON, target.getId());
    Assertions.assertThrows(
        ServiceError.class,
        () -> traitService.unlink("RelyUpstream", DEPENDS_ON, "RelyDownstream"));

    // Once no link relies on it either, it comes free.
    entityService.unlink(bothSource.getId(), DEPENDS_ON, bothTarget.getId());
    traitService.unlink("RelyUpstream", DEPENDS_ON, "RelyDownstream");

    entityService.delete(source.getId());
    entityService.delete(target.getId());
    entityService.delete(bothSource.getId());
    entityService.delete(bothTarget.getId());
    entityTypeService.delete("RelySourceType");
    entityTypeService.delete("RelyTargetType");
    entityTypeService.delete("RelyBothSourceType");
    entityTypeService.delete("RelyBothTargetType");
    traitService.delete("RelyUpstream");
    traitService.delete("RelyDownstream");
    traitService.delete("RelyAltUpstream");
    traitService.delete("RelyAltDownstream");
  }

  /**
   * The same refusal protects composition, which is the case that matters most: {@code HAS_PART}
   * between traits is what makes a type able to contain another, and what {@code
   * AggregateSchemaService} projects onto the types carrying those traits to derive the aggregate
   * model. Removing it under a live containment would silently drop a branch of that model.
   *
   * <p>Neither type carries the {@code Aggregate} built-in trait, so the containment link itself
   * stays removable ({@code EntityServiceImpl.checkIsNotAggregateContainment} does not apply) and
   * the trait relationship can be released afterwards.
   */
  @Test
  void testTraitContainmentAnInstanceLinkReliesOnCannotBeRemoved() {
    final TraitService traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    var emptySchema =
        """
        { "type": "object", "properties": {} }
        """;

    traitService.create("PartWhole", Optional.empty(), Optional.empty());
    traitService.create("PartPiece", Optional.empty(), Optional.empty());
    traitService.link("PartWhole", HAS_PART, "PartPiece");

    entityTypeService.create("PartWholeType", List.of("PartWhole"), Optional.empty(), emptySchema);
    entityTypeService.create("PartPieceType", List.of("PartPiece"), Optional.empty(), emptySchema);

    var whole = entityService.create("PartWholeType", "{}");
    var piece = entityService.create("PartPieceType", "{}");
    entityService.link(whole.getId(), HAS_PART, piece.getId());

    var error =
        Assertions.assertThrows(
            ServiceError.class, () -> traitService.unlink("PartWhole", HAS_PART, "PartPiece"));
    Assertions.assertTrue(
        error.getMessage().contains(whole.getId()) && error.getMessage().contains(piece.getId()),
        "the error must name the containment relying on the relationship, got: "
            + error.getMessage());
    Assertions.assertThrows(
        ServiceError.class, () -> traitService.unlink("PartPiece", IS_PART_OF, "PartWhole"));

    entityService.unlink(whole.getId(), HAS_PART, piece.getId());
    traitService.unlink("PartWhole", HAS_PART, "PartPiece");

    entityService.delete(whole.getId());
    entityService.delete(piece.getId());
    entityTypeService.delete("PartWholeType");
    entityTypeService.delete("PartPieceType");
    traitService.delete("PartWhole");
    traitService.delete("PartPiece");
  }

  @Test
  void testListTraits() {

    final TraitService traitService = getApplicationContext().getBean(TraitService.class);

    traitService.create("listTestTrait1", Optional.empty(), Optional.empty());
    traitService.create("listTestTrait2", Optional.empty(), Optional.empty());
    traitService.create("listTestTrait3", Optional.empty(), Optional.empty());

    var allTraits = traitService.list();

    Assertions.assertTrue(allTraits.size() >= 3);

    var traitNames = allTraits.stream().map(Trait::getName).collect(Collectors.toSet());

    Assertions.assertTrue(traitNames.contains("listTestTrait1"));
    Assertions.assertTrue(traitNames.contains("listTestTrait2"));
    Assertions.assertTrue(traitNames.contains("listTestTrait3"));

    traitService.delete("listTestTrait1");
    traitService.delete("listTestTrait2");
    traitService.delete("listTestTrait3");
  }

  @Test
  void testCreateVersionSnapshotsAndReadsHistory() {
    final TraitService traitService = getApplicationContext().getBean(TraitService.class);

    var schemaV1 =
        """
        {
          "type": "object",
          "properties": { "field1": { "type": "string" } }
        }
        """;
    var schemaV2 =
        """
        {
          "type": "object",
          "properties": { "field1": { "type": "string" }, "field2": { "type": "number" } }
        }
        """;

    traitService.create("VersionedTrait", Optional.of(schemaV1), Optional.empty());

    var liveV1 = traitService.read("VersionedTrait");
    Assertions.assertEquals(1, liveV1.getVersion());
    Assertions.assertNotNull(liveV1.getVersionGroupId());

    var liveV2 =
        traitService.createVersion("VersionedTrait", Optional.of(schemaV2), Optional.empty());
    Assertions.assertEquals(2, liveV2.getVersion());
    Assertions.assertEquals(liveV1.getVersionGroupId(), liveV2.getVersionGroupId());
    Assertions.assertTrue(liveV2.getSchema().get("properties").has("field2"));

    var readLive = traitService.read("VersionedTrait");
    Assertions.assertEquals(2, readLive.getVersion());

    var historyV1 = traitService.readVersion("VersionedTrait", 1);
    Assertions.assertTrue(historyV1 instanceof VersionResult.Snapshot);
    var snapshot = (TraitVersion) ((VersionResult.Snapshot<?, ?>) historyV1).value();
    Assertions.assertEquals(1, snapshot.getVersion());
    Assertions.assertFalse(snapshot.getSchema().get("properties").has("field2"));

    var historyV2 = traitService.readVersion("VersionedTrait", 2);
    Assertions.assertTrue(historyV2 instanceof VersionResult.Live);
    Assertions.assertEquals(
        2, ((Trait) ((VersionResult.Live<?, ?>) historyV2).value()).getVersion());

    var versions = traitService.listVersions("VersionedTrait");
    Assertions.assertEquals(2, versions.size());
    Assertions.assertTrue(versions.get(0) instanceof VersionResult.Snapshot);
    Assertions.assertTrue(versions.get(1) instanceof VersionResult.Live);

    Assertions.assertThrows(
        ServiceError.class, () -> traitService.deleteVersion("VersionedTrait", 2));

    traitService.deleteVersion("VersionedTrait", 1);
    Assertions.assertEquals(1, traitService.listVersions("VersionedTrait").size());

    Assertions.assertThrows(
        ServiceError.class, () -> traitService.readVersion("VersionedTrait", 1));

    traitService.delete("VersionedTrait");
  }
}
