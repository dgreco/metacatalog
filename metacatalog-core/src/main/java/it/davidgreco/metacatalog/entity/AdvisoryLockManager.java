package it.davidgreco.metacatalog.entity;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdvisoryLockManager {

  private final EntityManager entityManager;

  /**
   * Acquires a transaction-level advisory lock. The lock is automatically released when the
   * transaction ends.
   *
   * @param lockIdentifier the lock identifier
   * @return true if the lock was acquired, false otherwise
   */
  public boolean acquireLock(int lockIdentifier) {
    String pgLockQuery = String.format("SELECT pg_try_advisory_xact_lock(%s)", lockIdentifier);
    return (Boolean) entityManager.createNativeQuery(pgLockQuery).getSingleResult();
  }
}
