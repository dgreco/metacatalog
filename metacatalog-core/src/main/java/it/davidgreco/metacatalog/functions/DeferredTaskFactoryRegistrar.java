package it.davidgreco.metacatalog.functions;

/**
 * A registrar that cannot register its task factories at startup, because the entity types it works
 * from are catalog data created at runtime.
 *
 * <p>{@code TaskManager.registerTaskFactory} refuses a name no entity type has, which is what
 * catches typos — but it also means a registrar driven by configuration has nothing to register
 * against when the application boots. Such a registrar implements this interface instead and is
 * asked again by {@link ProcedureExecutor} before every procedure run, which is a moment when the
 * types involved certainly exist and a transaction is open.
 *
 * <p>Implementations must be <strong>idempotent and cheap</strong>: this runs on every procedure
 * execution, so skip what is already registered rather than re-registering it, and do not throw for
 * a type that still does not exist — a name that never resolves belongs in a warning, not in a
 * failure of an unrelated aggregate's provisioning. A resource whose type genuinely has no factory
 * still fails its own run, with {@code No factory for name: ...}.
 */
@FunctionalInterface
public interface DeferredTaskFactoryRegistrar {

  /**
   * Registers whatever can now be registered. Called before each procedure execution, inside the
   * plan-building transaction.
   */
  void ensureRegistered();
}
