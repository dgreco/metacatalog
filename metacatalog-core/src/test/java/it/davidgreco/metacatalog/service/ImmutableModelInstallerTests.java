package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.bootstrap.ImmutableModelRegistry.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.entity.RelationType.IS_PART_OF;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelContributor;
import it.davidgreco.metacatalog.bootstrap.ImmutableModelInstaller;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.Trait;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

  /**
   * The row is installed, then a contributor asks for the same name with a different schema — what
   * a release that edits a declaration does to every database created before it. It cannot be
   * migrated (immutable rows refuse {@code createVersion}), so the installer refuses to boot on it
   * instead of leaving the application running against a model that does not match its code.
   */
  @Test
  void testAChangedSchemaOnAnInstalledRowAbortsStartup() {
    var error =
        Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                install(
                    registry ->
                        registry.trait(
                            "InstallerBeta",
                            """
                            { "type": "object", "properties": { "tag": { "type": "number" } } }
                            """)));

    Assertions.assertTrue(error.getMessage().contains("InstallerBeta"), error.getMessage());
    Assertions.assertTrue(error.getMessage().contains("base schema"), error.getMessage());
    // It names the remedy, because there is only one and it is not obvious.
    Assertions.assertTrue(error.getMessage().contains("Recreate"), error.getMessage());
  }

  /**
   * The father is part of the declaration too: re-parenting an installed trait is the same fault.
   */
  @Test
  void testAChangedFatherOnAnInstalledRowAbortsStartup() {
    var error =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> install(registry -> registry.trait("InstallerAlpha", null, "InstallerBeta")));

    Assertions.assertTrue(error.getMessage().contains("InstallerAlpha"), error.getMessage());
    Assertions.assertTrue(error.getMessage().contains("father"), error.getMessage());
  }

  /**
   * And so is an entity type's trait set, which is what decides the schema it validates against.
   */
  @Test
  void testAChangedTraitSetOnAnInstalledTypeAbortsStartup() {
    var error =
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> install(registry -> registry.entityType("InstallerType", null, List.of())));

    Assertions.assertTrue(error.getMessage().contains("InstallerType"), error.getMessage());
    Assertions.assertTrue(error.getMessage().contains("trait set"), error.getMessage());
  }

  /**
   * The guard must not fire on the ordinary case. The installed row has been through {@code jsonb},
   * which reorders keys and discards the formatting the declaration was written with, so comparing
   * the stored text against the declared text would report drift on every restart. Comparing parsed
   * nodes is what makes the check survive the round trip.
   */
  @Test
  void testAnIdenticalRedeclarationIsNotMistakenForDrift() {
    install(new ContributorConfig().testContributor());

    Assertions.assertTrue(
        getApplicationContext().getBean(TraitService.class).read("InstallerBeta").isImmutable());
  }

  /**
   * A mutable row of the same name is not the contributor's and was never expected to match it, so
   * it keeps the warn-and-carry-on treatment. Aborting here would strand the application on data
   * its operator can simply delete through the API.
   */
  @Test
  void testDriftAgainstAMutableRowStillOnlyWarns() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    traitService.create("PlainDrift", Optional.of(EMPTY_SCHEMA), Optional.empty());

    install(
        registry ->
            registry.trait(
                "PlainDrift",
                """
                { "type": "object", "properties": { "whatever": { "type": "string" } } }
                """));

    Assertions.assertFalse(traitService.read("PlainDrift").isImmutable());
    traitService.delete("PlainDrift");
  }

  /**
   * The verification is what decides whether this instance may run at all, so losing the race to
   * install must not exempt an instance from it. Were it skipped along with the creating, a rolling
   * restart would leave the lock holder crash-looping on the drift while every other replica booted
   * happily against the same database.
   */
  @Test
  void testDriftIsDetectedEvenWithoutTheInstallationLock() {
    withInstallationLockHeldElsewhere(
        () -> {
          var error =
              Assertions.assertThrows(
                  IllegalStateException.class,
                  () ->
                      install(
                          registry ->
                              registry.trait(
                                  "InstallerBeta",
                                  """
                                  { "type": "object", "properties": { "tag": { "type": "number" } } }
                                  """)));
          Assertions.assertTrue(error.getMessage().contains("InstallerBeta"), error.getMessage());
        });
  }

  /**
   * What such an instance must <em>not</em> do is create anything, or complain about a declaration
   * whose row the holder has not committed yet — including a link, whose source trait it cannot
   * see.
   */
  @Test
  void testWithoutTheLockMissingDeclarationsAreLeftToTheHolder() {
    var traitService = getApplicationContext().getBean(TraitService.class);

    withInstallationLockHeldElsewhere(
        () ->
            install(
                registry -> {
                  registry.trait("NotYetInstalledAlpha");
                  registry.trait("NotYetInstalledBeta");
                  registry.link("NotYetInstalledAlpha", HAS_PART, "NotYetInstalledBeta");
                  registry.entityType("NotYetInstalledType", null, List.of("NotYetInstalledAlpha"));
                }));

    Assertions.assertFalse(traitService.exists("NotYetInstalledAlpha"));
    Assertions.assertFalse(traitService.exists("NotYetInstalledBeta"));
    Assertions.assertFalse(
        getApplicationContext().getBean(EntityTypeService.class).exists("NotYetInstalledType"));
  }

  /**
   * Takes the installer's advisory lock on a connection of its own, so the installer under test
   * genuinely loses the race. A session-level lock is used rather than a transaction-scoped one
   * because it has to outlive the caller's statement; the two share a lock space, so {@code
   * pg_try_advisory_xact_lock} on the same id fails against it.
   */
  private void withInstallationLockHeldElsewhere(Runnable body) {
    var dataSource = getApplicationContext().getBean(DataSource.class);
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(2)");
      try {
        body.run();
      } finally {
        statement.execute("SELECT pg_advisory_unlock(2)");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Runs a one-off installer carrying just this contributor, so a test can apply a declaration that
   * differs from the one the context was built with. Wrapped in a transaction because the instance
   * is constructed here rather than by the container: the {@code @Transactional} on {@code run} is
   * applied by the bean's proxy, and the advisory lock it takes is transaction-scoped.
   */
  private void install(ImmutableModelContributor contributor) {
    var ctx = getApplicationContext();
    var installer =
        new ImmutableModelInstaller(
            providerOf(contributor),
            ctx.getBean(ImmutableTraitWriter.class),
            ctx.getBean(ImmutableEntityTypeWriter.class),
            ctx.getBean(TraitService.class),
            ctx.getBean(EntityTypeService.class),
            ctx.getBean(AdvisoryLockManager.class),
            ctx.getBean(JsonUtils.class));
    new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(status -> installer.run(null));
  }

  private static ObjectProvider<ImmutableModelContributor> providerOf(
      ImmutableModelContributor contributor) {
    return new ObjectProvider<>() {
      @Override
      public Stream<ImmutableModelContributor> orderedStream() {
        return Stream.of(contributor);
      }

      @Override
      public ImmutableModelContributor getObject() {
        return contributor;
      }
    };
  }
}
