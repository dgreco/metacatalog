package it.davidgreco.metacatalog.bootstrap;

/**
 * Extension point for a Java module that owns part of the catalog model and needs it to exist,
 * immutably, before anything else runs.
 *
 * <p>Implement it, publish it as a Spring bean, and {@link ImmutableModelInstaller} will invoke it
 * once at startup. Every declaration made through the {@link ImmutableModelRegistry} is created
 * immutable, so nothing the module depends on can later be deleted or re-versioned through the API.
 * This is the only authoring channel for immutability besides the Flyway seed: the flag is
 * deliberately absent from the REST contract.
 *
 * <p>Contributions are declarative and <strong>idempotent</strong>: the installer creates only what
 * is missing, so a restart against a database that already has the model is a no-op. Write {@code
 * contribute} as a plain list of what must exist, not as a migration — there is no "already ran"
 * bookkeeping to reason about.
 *
 * <p>Declaration order is application order, and nothing is sorted for you: declare a father before
 * the traits inheriting from it, and both traits of a relationship before the link between them.
 * Across modules the order is bean order, which can be pinned with {@link
 * org.springframework.core.annotation.Order} when one module builds on another's traits.
 *
 * <pre>{@code
 * @Component
 * class GovernanceModel implements ImmutableModelContributor {
 *   @Override
 *   public void contribute(ImmutableModelRegistry registry) {
 *     registry.trait("Owned", """
 *         {"type":"object","properties":{"owner":{"type":"string"}}}""");
 *     registry.trait("Classified");
 *     registry.link("Owned", RelationType.HAS_PART, "Classified");
 *   }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ImmutableModelContributor {

  /**
   * Declares the traits, entity types and trait relationships this module requires.
   *
   * <p>Called inside the installer's transaction. Throwing aborts startup, which is the intended
   * behaviour for a module whose model could not be established.
   *
   * @param registry the registry to declare into
   */
  void contribute(ImmutableModelRegistry registry);
}
