package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.EntityLifeCycleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityLifeCycleEventRepository extends JpaRepository<EntityLifeCycleEvent, Long> {}
