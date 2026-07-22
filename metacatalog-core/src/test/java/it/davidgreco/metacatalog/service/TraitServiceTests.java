package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitVersion;
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
