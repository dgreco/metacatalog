package it.davidgreco.metacatalog.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.ImmutableEntityTypeWriter;
import it.davidgreco.metacatalog.service.ImmutableTraitWriter;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
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
 * <p><strong>A changed declaration aborts startup.</strong> "Left untouched" is only safe while the
 * row still says what the contributor declares. An immutable row cannot be migrated — the flag is
 * one-way and {@code createVersion} is refused on it — so a declaration that has moved on means the
 * running code expects a model this database does not have, and the only remedy is to recreate the
 * database. Installation therefore compares each existing immutable row against its declaration and
 * fails naming the row, the way Flyway aborts on a changed baseline checksum. Without that check
 * the mismatch surfaces far away and much later: the built-in {@code Provisionable} trait gained a
 * schema after its first release, and on every database created before it the aggregate-root status
 * write failed schema validation on a feature that had nothing to do with startup.
 *
 * <p>A PostgreSQL advisory lock serialises instances booting together. An instance that does not
 * get the lock does not wait: the instance holding it is doing the same work, and blocking every
 * replica behind one transaction would turn a rolling restart into a queue. It skips only the
 * <em>creating</em> — it still applies every declaration and verifies what it finds, because the
 * check above is what decides whether this instance may run at all, and a replica that skipped it
 * would boot happily against the very database the lock holder is refusing. Declarations whose rows
 * do not exist yet are left to the holder, which is creating them.
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
   * Parses a declared schema the same way the services do when they store one, so the comparison
   * against an existing row is between like and like.
   */
  private final JsonUtils jsonUtils;

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
    var holdsLock = advisoryLockManager.acquireLock(INSTALL_IMMUTABLE_MODEL_LOCK_ID);
    if (!holdsLock) {
      log.info(
          "Another instance holds the installation lock and is creating the same contributions;"
              + " verifying what is already there and leaving the rest to it");
    }
    var registry = new Registry(holdsLock);
    for (var contributor : ordered) {
      log.debug("Applying immutable model contributor {}", contributor.getClass().getName());
      contributor.contribute(registry);
    }
    log.info(
        "Immutable model from {} contributor(s): {} created, {} already present, {} left to the"
            + " lock holder",
        ordered.size(),
        registry.created,
        registry.skipped,
        registry.deferred);
  }

  /**
   * Applies declarations eagerly, as they are made, so a contributor can declare a father and then
   * its children in one pass.
   */
  private final class Registry implements ImmutableModelRegistry {

    /**
     * Whether this instance won the installation lock. When it did not, declarations are still
     * applied — every instance must satisfy itself that what is already in the database matches the
     * code it is about to run — but creating what is missing is left to the holder, which is
     * creating it right now.
     */
    private final boolean creates;

    private int created;
    private int skipped;
    private int deferred;

    private Registry(boolean creates) {
      this.creates = creates;
    }

    @Override
    public void trait(String name, String schema, String fatherName) {
      if (traitService.exists(name)) {
        var live = traitService.read(name);
        if (live.isImmutable()) {
          checkStillDeclaredAs(
              "Trait",
              name,
              schema,
              live.getBaseSchema(),
              fatherName,
              live.getFather() == null ? null : live.getFather().getName());
        } else {
          warnMutable("Trait", name);
        }
        skipped++;
        return;
      }
      if (!creates) {
        deferred++;
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
        var live = entityTypeService.read(name);
        if (live.isImmutable()) {
          checkStillDeclaredAs(
              "EntityType",
              name,
              schema,
              live.getBaseSchema(),
              fatherName,
              live.getFather() == null ? null : live.getFather().getName());
          checkTraitsStillDeclaredAs(name, traits, live.getTraits());
        } else {
          warnMutable("EntityType", name);
        }
        skipped++;
        return;
      }
      if (!creates) {
        deferred++;
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
      if (!creates && !traitService.exists(sourceTrait)) {
        // Not the mis-ordered declaration that the throw below catches: the instance holding the
        // lock simply has not created the source trait yet. Its own run is what reports a genuinely
        // bad declaration, and this one has nothing here to verify.
        deferred++;
        return;
      }
      // linked() throws if the source is unknown, which is the right outcome for the instance doing
      // the installing: the contributor declared a link before the trait it starts from.
      var alreadyLinked =
          traitService.linked(sourceTrait, relType).stream().map(Trait::getName).toList();
      if (alreadyLinked.contains(targetTrait)) {
        skipped++;
        return;
      }
      if (!creates) {
        deferred++;
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
     *
     * <p>Such a row is deliberately <em>not</em> checked for drift. It is the user's, not the
     * contributor's, so it was never expected to match the declaration in the first place, and it
     * stays editable through the API — refusing to boot over it would strand the application on
     * data its own operator can fix.
     */
    private void warnMutable(String kind, String name) {
      log.warn(
          "{} {} already exists but is mutable; a contributor declared it immutable. It is left as"
              + " it is — delete it and restart if it must be frozen.",
          kind,
          name);
    }

    /**
     * Verifies an already-installed immutable row still matches what the contributor declares.
     *
     * <p>The comparison is between parsed schema nodes rather than raw text, so the key reordering
     * and whitespace normalisation {@code jsonb} performs on the way in and out are irrelevant. Two
     * schemas differing only in the literal form of a number (1 versus 1.0) would be reported as
     * drift, which nothing declared today does.
     */
    private void checkStillDeclaredAs(
        String kind,
        String name,
        String declaredSchema,
        JsonNode liveSchema,
        String declaredFather,
        String liveFather) {
      var declaredNode = parseDeclaredSchema(kind, name, declaredSchema);
      if (!declaredNode.equals(liveSchema)) {
        throw drifted(kind, name, "base schema", liveSchema, declaredNode);
      }
      if (!Objects.equals(declaredFather, liveFather)) {
        throw drifted(kind, name, "father", liveFather, declaredFather);
      }
    }

    /**
     * Compared as sets, not lists: the declaration orders traits by precedence, but {@code
     * type_traits} carries no order column, so the order a row comes back in means nothing and
     * comparing sequences would report drift that is not there. What matters — a trait added to or
     * removed from the declaration — is caught either way.
     */
    private void checkTraitsStillDeclaredAs(String name, List<String> declared, List<Trait> live) {
      SortedSet<String> declaredNames =
          new TreeSet<>(declared == null ? List.<String>of() : declared);
      SortedSet<String> liveNames = new TreeSet<>(live.stream().map(Trait::getName).toList());
      if (!declaredNames.equals(liveNames)) {
        throw drifted("EntityType", name, "trait set", liveNames, declaredNames);
      }
    }

    private JsonNode parseDeclaredSchema(String kind, String name, String schema) {
      var parsed = jsonUtils.stringToJsonSchema(schema == null ? EMPTY_SCHEMA : schema);
      if (parsed.isLeft()) {
        throw new IllegalStateException(
            "%s %s is declared with an invalid JSON Schema: %s"
                .formatted(kind, name, parsed.getLeft()));
      }
      return parsed.get().getSchemaNode();
    }

    private IllegalStateException drifted(
        String kind, String name, String what, Object live, Object declared) {
      return new IllegalStateException(
          """
          %s %s exists as an immutable row whose %s differs from the one a contributor declares. \
          An immutable row is never migrated in place, so this database cannot satisfy the code \
          running against it. Recreate it — this project does not support migrating an existing \
          database.
            in the database: %s
            declared:        %s"""
              .formatted(kind, name, what, live, declared));
    }
  }
}
