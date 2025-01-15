package it.davidgreco.metacatalog.entity;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdvisoryLockManager {

    private final EntityManager entityManager;

    @Autowired
    public AdvisoryLockManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void acquireLock(int lockIdentifier) {
        String pgLockQuery = String.format("SELECT pg_advisory_lock(%s)", String.valueOf(lockIdentifier));
        entityManager.createNativeQuery(pgLockQuery).getSingleResult();
    }
}
