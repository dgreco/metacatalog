package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.TypedEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TypedEntityRepository extends JpaRepository<TypedEntity, String> {
    @Override
    Optional<TypedEntity> findById(String id);
}
