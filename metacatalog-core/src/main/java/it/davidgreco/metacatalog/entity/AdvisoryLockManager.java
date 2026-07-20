package it.davidgreco.metacatalog.entity;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages PostgreSQL advisory locks for coordinating concurrent access to shared resources.
 *
 * <p>This component provides transaction-level advisory locks that are automatically released when
 * the transaction ends. Advisory locks are useful for preventing concurrent modifications to shared
 * resources across multiple application instances.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryLockManager {

  /** The JPA entity manager used to execute native PostgreSQL queries. */
  private final EntityManager entityManager;

  /**
   * Acquires a transaction-level advisory lock. The lock is automatically released when the
   * transaction ends.
   *
   * <p>Must be called within an active transaction ({@link Propagation#MANDATORY}); otherwise the
   * transaction-scoped lock would be released immediately in auto-commit mode and would protect
   * nothing.
   *
   * @param lockIdentifier the lock identifier
   * @return true if the lock was acquired, false otherwise
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean acquireLock(int lockIdentifier) {
    return (Boolean)
        entityManager
            .createNativeQuery("SELECT pg_try_advisory_xact_lock(:lockId)")
            .setParameter("lockId", lockIdentifier)
            .getSingleResult();
  }
}
