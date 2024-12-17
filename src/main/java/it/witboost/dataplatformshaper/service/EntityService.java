package it.witboost.dataplatformshaper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.witboost.dataplatformshaper.entity.TypedEntity;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.TypedEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityService {

    private static ObjectMapper mapper = new ObjectMapper();

    private final EntityTypeRepository entityTypeRepository;

    private final TypedEntityRepository typedEntityRepository;

    public EntityService(EntityTypeRepository entityTypeRepository, TypedEntityRepository typedEntityRepository) {
        this.entityTypeRepository = entityTypeRepository;
        this.typedEntityRepository = typedEntityRepository;
    }

    @Transactional
    public TypedEntity create(TypedEntity typedEntity) {
        return typedEntityRepository.save(typedEntity);
    }

    @Transactional(rollbackFor = {JsonProcessingException.class})
    public TypedEntity create(String typeName, String values) throws JsonProcessingException {
        var maybeEntityType = entityTypeRepository.findByName(typeName);
        var entityType =
                maybeEntityType.orElseThrow(() -> new RuntimeException("Entity type " + typeName + " not found"));
        var jsonNode = mapper.readTree(values);
        var typedEntity = new TypedEntity();
        typedEntity.setEntityType(entityType);
        typedEntity.setValues(jsonNode);
        return typedEntityRepository.save(typedEntity);
    }
}
