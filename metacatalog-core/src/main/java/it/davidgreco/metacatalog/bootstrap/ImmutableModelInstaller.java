package it.davidgreco.metacatalog.bootstrap;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ImmutableEntityTypeWriter;
import it.davidgreco.metacatalog.service.ImmutableTraitWriter;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies every {@link ImmutableModelContributor} on the classpath once at startup.
 *
 * <p>The whole installation runs in one transaction, so a module's model is established completely
 * or not at all — a contributor that throws halfway leaves nothing behind and aborts startup rather
 * than leaving a half-built model that later declarations would silently build on.
 *
 * <p><strong>Idempotent by construction.</strong> Each declaration is a check-then-create against
 * the live catalog: what exists is left untouched, what is missing is created immutable. There is
 * no ledger of applied contributions and none is needed, so restarts, rollbacks and re-deploys all
 * converge on the same state.
 *
 * <p>A PostgreSQL advisory lock serialises instances booting together. An instance that does not
 * get the lock skips installation rather than waiting: the instance holding it is doing the same
 * work, and blocking every replica behind one transaction would turn a rolling restart into a
 * queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImmutableModelInstaller implements ApplicationRunner {

  /** Distinct from {@code MappingUpdaterService}'s lock id, which is 1. */
  private static final int INSTALL_IMMUTABLE_MODEL_LOCK_ID = 2;

  /**
   * An {@link ObjectProvider} rather than a {@code List}: injecting a list when no contributor bean
   * exists fails outright, and no contributors is the default for an application that has not
   * adopted this. It also gives {@link org.springframework.core.annotation.Order} support, which is
   * how one module's traits are guaranteed to be in place before another builds on them.
   */
  private final ObjectProvider<ImmutableModelContributor> contributors;

  /**
   * The narrow write contracts, injected only here. This is what makes "immutable rows come from a
   * startup contributor and nowhere else" true by construction: everything else in the application
   * injects {@link TraitService} / {@link EntityTypeService}, which expose no method capable of
   * producing an immutable row.
   */
  private final ImmutableTraitWriter traitWriter;

  private final ImmutableEntityTypeWriter entityTypeWriter;

  /** Read-only lookups, to decide what is already in place. */
  private final TraitService traitService;

  private final EntityTypeService entityTypeService;
  private final AdvisoryLockManager advisoryLockManager;

  /**
   * {@inheritDoc}
   *
   * <p>{@code @Transactional} sits here rather than on a helper method because Spring invokes this
   * through the bean's proxy; a helper called from inside would be a self-invocation and would run
   * with no transaction at all, which {@link AdvisoryLockManager#acquireLock} rejects outright.
   */
  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    var ordered = contributors.orderedStream().toList();
    if (ordered.isEmpty()) {
      log.debug("No immutable model contributors registered; nothing to install");
      return;
    }
    if (!advisoryLockManager.acquireLock(INSTALL_IMMUTABLE_MODEL_LOCK_ID)) {
      log.info(
          "Immutable model installation skipped: another instance holds the lock and is applying"
              + " the same contributions");
      return;
    }
    var registry = new Registry();
    for (var contributor : ordered) {
      log.debug("Applying immutable model contributor {}", contributor.getClass().getName());
      contributor.contribute(registry);
    }
    log.info(
        "Immutable model installed from {} contributor(s): {} created, {} already present",
        ordered.size(),
        registry.created,
        registry.skipped);
  }

  /**
   * Applies declarations eagerly, as they are made, so a contributor can declare a father and then
   * its children in one pass.
   */
  private final class Registry implements ImmutableModelRegistry {

    private int created;
    private int skipped;

    @Override
    public void trait(String name, String schema, String fatherName) {
      if (traitService.exists(name)) {
        warnIfMutable(name, "Trait", traitService.read(name).isImmutable());
        skipped++;
        return;
      }
      traitWriter.createImmutable(
          name,
          Optional.of(schema == null ? EMPTY_SCHEMA : schema),
          Optional.ofNullable(fatherName));
      created++;
      log.debug("Created immutable trait {}", name);
    }

    @Override
    public void entityType(String name, String schema, List<String> traits, String fatherName) {
      if (entityTypeService.exists(name)) {
        warnIfMutable(name, "EntityType", entityTypeService.read(name).isImmutable());
        skipped++;
        return;
      }
      entityTypeWriter.createImmutable(
          name,
          traits == null ? List.of() : traits,
          Optional.ofNullable(fatherName),
          schema == null ? EMPTY_SCHEMA : schema);
      created++;
      log.debug("Created immutable entity type {}", name);
    }

    @Override
    public void link(String sourceTrait, RelationType relType, String targetTrait) {
      // linked() throws if the source is unknown, which is the right outcome: the contributor
      // declared a link before the trait it starts from.
      var alreadyLinked =
          traitService.linked(sourceTrait, relType).stream().map(Trait::getName).toList();
      if (alreadyLinked.contains(targetTrait)) {
        skipped++;
        return;
      }
      traitWriter.linkImmutable(sourceTrait, relType, targetTrait);
      created++;
      log.debug("Created immutable trait relationship {} {} {}", sourceTrait, relType, targetTrait);
    }

    /**
     * A name already taken by a mutable row means someone created it through the API before this
     * module was deployed. Installation does not freeze it: the flag is only ever set at creation,
     * and silently converting live catalog data would be a bigger surprise than the warning.
     */
    private void warnIfMutable(String name, String kind, boolean immutable) {
      if (immutable) return;
      log.warn(
          "{} {} already exists but is mutable; a contributor declared it immutable. It is left as"
              + " it is — delete it and restart if it must be frozen.",
          kind,
          name);
    }
  }
}
