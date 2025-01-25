package it.davidgreco.metacatalog.repository;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EntityRepository extends JpaRepository<Entity, String> {
  long countByEntityType(EntityType entityType);

  List<Entity> findByEntityType(EntityType entityType);

  @Query(
      value =
          """
               SELECT *, jsonb_path_query(values, CAST(:jsonPath AS jsonpath)) FROM entity
               """,
      nativeQuery = true)
  List<Entity> findByJsonPath(String jsonPath);
}
