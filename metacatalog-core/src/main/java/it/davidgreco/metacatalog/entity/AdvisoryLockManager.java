package it.davidgreco.metacatalog.entity;

import jakarta.persistence.EntityManager;
import java.time.Duration;
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

  /**
   * Acquires the same transaction-level lock, but waits for the current holder instead of giving up
   * — for the caller that has established it genuinely has work the holder is not going to do.
   *
   * <p>Exceeding {@code timeout} raises {@code lock_not_available}, which <strong>aborts the
   * transaction</strong>: PostgreSQL leaves no way to carry on after a failed statement, so there
   * is no "returned false, continue anyway" path here and none would be wanted. A caller waiting on
   * this lock cannot do its work without it.
   *
   * @param lockIdentifier the lock identifier
   * @param timeout how long to wait before giving up
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acquireLockWaiting(int lockIdentifier, Duration timeout) {
    // SET takes no bind parameters, so the value is interpolated. It is a Duration rendered as a
    // long, never caller-supplied text.
    entityManager
        .createNativeQuery("SET LOCAL lock_timeout = " + timeout.toMillis())
        .executeUpdate();
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
        .setParameter("lockId", lockIdentifier)
        .getResultList();
    // Back to the session default now the lock is held: the timeout was for this wait, and leaving
    // it in force would silently apply to every later statement in the same transaction.
    entityManager.createNativeQuery("SET LOCAL lock_timeout = DEFAULT").executeUpdate();
  }
}
