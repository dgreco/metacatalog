package it.witboost.dataplatformshaper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityTypeService {

    private static ObjectMapper mapper = new ObjectMapper();

    private final EntityTypeRepository entityTypeRepository;

    public EntityTypeService(EntityTypeRepository entityTypeRepository) {
        this.entityTypeRepository = entityTypeRepository;
    }

    @Transactional
    public EntityType create(EntityType entityType) {
        return entityTypeRepository.save(entityType);
    }

    @Transactional(rollbackFor = {JsonProcessingException.class})
    public EntityType create(String name, final String schema, Optional<String> fatherName)
            throws JsonProcessingException {
        JsonNode schemaNode = mapper.readTree(schema);
        var entityType = new EntityType();
        entityType.setName(name);
        entityType.setSchema(schemaNode);
        var father = fatherName.flatMap(entityTypeRepository::findByName);
        father.ifPresent(entityType::setFather);
        return entityTypeRepository.save(entityType);
    }

    @Transactional(rollbackFor = Throwable.class)
    public Optional<EntityType> read(String name) {
        return entityTypeRepository.findByName(name);
    }
}
