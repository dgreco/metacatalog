package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.bootstrap.ImmutableModelRegistry.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.entity.RelationType.IS_PART_OF;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelContributor;
import it.davidgreco.metacatalog.bootstrap.ImmutableModelInstaller;
import it.davidgreco.metacatalog.entity.Trait;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Covers the {@link ImmutableModelContributor} extension point: a module declares what its model
 * needs, and the installer creates it immutable and converges on the same state however many times
 * it runs.
 */
@SpringBootTest
@Import(ImmutableModelInstallerTests.ContributorConfig.class)
class ImmutableModelInstallerTests extends CommonServiceTestingSupport {

  public ImmutableModelInstallerTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @TestConfiguration
  static class ContributorConfig {
    @Bean
    ImmutableModelContributor testContributor() {
      return registry -> {
        registry.trait("InstallerAlpha");
        registry.trait(
            "InstallerBeta",
            """
            { "type": "object", "properties": { "tag": { "type": "string" } } }
            """);
        registry.link("InstallerAlpha", HAS_PART, "InstallerBeta");
        registry.entityType("InstallerType", null, List.of("InstallerAlpha"));
      };
    }
  }

  /**
   * Asserts the resulting state rather than the installer's created/skipped counters, so the test
   * holds whether or not the framework already ran the runner during context startup.
   */
  @Test
  void testContributionsAreInstalledImmutablyAndIdempotently() {
    var installer = getApplicationContext().getBean(ImmutableModelInstaller.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

    installer.run(null);

    Assertions.assertTrue(traitService.read("InstallerAlpha").isImmutable());
    Assertions.assertTrue(traitService.read("InstallerBeta").isImmutable());
    Assertions.assertTrue(entityTypeService.read("InstallerType").isImmutable());
    Assertions.assertEquals(
        List.of("InstallerBeta"),
        traitService.linked("InstallerAlpha", HAS_PART).stream().map(Trait::getName).toList());

    // The declared father/mixin wiring survived: the type really carries the contributed trait.
    Assertions.assertEquals(
        List.of("InstallerAlpha"),
        entityTypeService.read("InstallerType").getTraits().stream().map(Trait::getName).toList());

    // Running again must change nothing — no duplicate rows, no exception from re-creating a name
    // that now exists.
    installer.run(null);
    installer.run(null);

    Assertions.assertEquals(
        List.of("InstallerBeta"),
        traitService.linked("InstallerAlpha", HAS_PART).stream().map(Trait::getName).toList());
    Assertions.assertEquals(1, traitService.read("InstallerAlpha").getVersion());

    // And everything it installed is genuinely frozen against every mutating operation.
    Assertions.assertThrows(ServiceError.class, () -> traitService.delete("InstallerAlpha"));
    Assertions.assertThrows(
        ServiceError.class,
        () -> traitService.createVersion("InstallerAlpha", Optional.empty(), Optional.empty()));
    Assertions.assertThrows(
        ServiceError.class, () -> traitService.unlink("InstallerAlpha", HAS_PART, "InstallerBeta"));
    // The inverse row was frozen alongside the direct one, so naming it is refused too.
    Assertions.assertThrows(
        ServiceError.class,
        () -> traitService.unlink("InstallerBeta", IS_PART_OF, "InstallerAlpha"));
    Assertions.assertThrows(ServiceError.class, () -> entityTypeService.delete("InstallerType"));
    Assertions.assertThrows(
        ServiceError.class,
        () ->
            entityTypeService.createVersion(
                "InstallerType", List.of(), Optional.empty(), EMPTY_SCHEMA));
  }

  /**
   * The flag freezes the row, not the graph around it. A mutable trait may inherit from an
   * installed one and link to it, and entities of an installed type are created and deleted as
   * usual — none of that writes to the frozen row.
   */
  @Test
  void testInstalledModelDoesNotFreezeWhatMerelyPointsAtIt() {
    var installer = getApplicationContext().getBean(ImmutableModelInstaller.class);
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);

    installer.run(null);

    traitService.create("InstallerChild", Optional.empty(), Optional.of("InstallerAlpha"));
    traitService.link("InstallerChild", DEPENDS_ON, "InstallerAlpha");
    traitService.unlink("InstallerChild", DEPENDS_ON, "InstallerAlpha");
    traitService.delete("InstallerChild");

    var instance = entityService.create("InstallerType", "{}");
    entityService.delete(instance.getId());
  }

  /**
   * The point of routing immutability through the installer: {@link TraitService} and {@link
   * EntityTypeService} offer no way to ask for it, so ordinary creation — the REST delegate, the
   * bulk loader, anything else holding the interfaces — can only ever produce mutable rows.
   */
  @Test
  void testOrdinaryCreationIsAlwaysMutable() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);

    traitService.create("PlainTrait", Optional.empty(), Optional.empty());
    entityTypeService.create("PlainType", List.of(), Optional.empty(), EMPTY_SCHEMA);

    Assertions.assertFalse(traitService.read("PlainTrait").isImmutable());
    Assertions.assertFalse(entityTypeService.read("PlainType").isImmutable());

    // Being mutable, they can be taken away again.
    entityTypeService.delete("PlainType");
    traitService.delete("PlainTrait");
  }
}
