package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import it.davidgreco.metacatalog.entity.Trait;
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
  void testCreation() throws ServiceError {

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
  void testSelfReferentialLinkIsAllowed() throws ServiceError {

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
  void testLoopDetectionExploresAllBranches() throws ServiceError {

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
  void testListTraits() throws ServiceError {

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
}
