package it.davidgreco.metacatalog.entity;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdvisoryLockManager {

  private final EntityManager entityManager;

  public boolean acquireLock(int lockIdentifier) {
    String pgLockQuery = String.format("SELECT pg_try_advisory_lock(%s)", lockIdentifier);
    return (Boolean) entityManager.createNativeQuery(pgLockQuery).getSingleResult();
  }
}
