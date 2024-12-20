package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;
import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.ValidationMessage;
import it.witboost.dataplatformshaper.entity.TypedEntity;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.TypedEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityService {

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

    @Transactional(rollbackFor = {SchemaValidationError.class})
    public TypedEntity create(String typeName, String values) throws JsonProcessingException, SchemaValidationError {
        var maybeEntityType = entityTypeRepository.findByName(typeName);
        var entityType =
                maybeEntityType.orElseThrow(() -> new RuntimeException("Entity type " + typeName + " not found"));
        var valuesJsonNode = jsonFactory.readTree(values);
        var validationMessages =
                jsonSchemaFactory.getSchema(entityType.getSchema()).validate(valuesJsonNode);
        if (!validationMessages.isEmpty()) {
            var errorMessages = validationMessages.stream()
                    .map(ValidationMessage::getMessage)
                    .toList();
            throw new SchemaValidationError(errorMessages);
        }
        var typedEntity = new TypedEntity();
        typedEntity.setEntityType(entityType);
        typedEntity.setValues(valuesJsonNode);
        return typedEntityRepository.save(typedEntity);
    }

    @Transactional
    public long countEntitiesByEntityType(String name) {
        return entityTypeRepository
                .findByName(name)
                .map(typedEntityRepository::countTypedEntityByEntityType)
                .orElse(0L);
    }
}
