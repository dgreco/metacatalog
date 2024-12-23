package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;
import static it.witboost.dataplatformshaper.common.JsonUtils.jsonSchemaFactory;
import static it.witboost.dataplatformshaper.service.CommonTypeService.loadInheritanceChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.ValidationMessage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.*;
import it.witboost.dataplatformshaper.repository.EntityRelationshipRepository;
import it.witboost.dataplatformshaper.repository.EntityRepository;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityService {

    private final EntityTypeRepository entityTypeRepository;

    private final EntityRepository entityRepository;

    private final EntityRelationshipRepository entityRelationshipRepository;

    @SuppressFBWarnings
    public EntityService(
            EntityTypeRepository entityTypeRepository,
            EntityRepository typedEntityRepository,
            EntityRelationshipRepository entityRelationshipRepository) {
        this.entityTypeRepository = entityTypeRepository;
        this.entityRepository = typedEntityRepository;
        this.entityRelationshipRepository = entityRelationshipRepository;
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public Entity create(String typeName, String values) throws ServiceError {
        try {
            var entityType = entityTypeRepository
                    .findByName(typeName)
                    .orElseThrow(() -> new ServiceError("Entity type " + typeName + " not found"));
            var valuesJsonNode = jsonFactory.readTree(values);
            var validationMessages =
                    jsonSchemaFactory.getSchema(entityType.getSchema()).validate(valuesJsonNode);
            if (!validationMessages.isEmpty()) {
                var errorMessages = validationMessages.stream()
                        .map(ValidationMessage::getMessage)
                        .toList();
                throw new SchemaValidationError(errorMessages);
            }
            var typedEntity = new Entity();
            typedEntity.setEntityType(entityType);
            typedEntity.setValues(valuesJsonNode);
            return entityRepository.save(typedEntity);
        } catch (JsonProcessingException e) {
            throw new ServiceError(e.getMessage());
        }
    }

    @Transactional
    public void delete(Entity entity) {
        entityRepository.delete(entity);
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public void link(String sourceId, RelationType relType, String targetId) throws ServiceError {
        // Check if the relationship is legit
        checkRelIsLegit(sourceId, relType, targetId);

        var rel1 = new EntityRelationship();
        var source = entityRepository
                .findById(sourceId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceId + " not found"));
        var target = entityRepository
                .findById(targetId)
                .orElseThrow(() -> new ServiceError("Entity with id " + targetId + " not found"));
        rel1.setSource(source);
        rel1.setTarget(target);
        rel1.setRelationType(relType);
        entityRelationshipRepository.save(rel1);
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public void unlink(String sourceId, RelationType relType, String targetId) throws ServiceError {
        var source = entityRepository
                .findById(sourceId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceId + " not found"));
        var target = entityRepository
                .findById(targetId)
                .orElseThrow(() -> new ServiceError("Entity with id " + targetId + " not found"));
        var rel = entityRelationshipRepository
                .findBySourceAndRelationTypeAndTarget(source, relType, target)
                .orElseThrow(() -> new ServiceError("Entity with id " + source + " does not have a relationship "
                        + relType + " with Entity with id" + target));
        entityRelationshipRepository.delete(rel);
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public List<Entity> linked(String sourceId, RelationType relType) throws ServiceError {
        var source = entityRepository
                .findById(sourceId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceId + " not found"));
        return entityRelationshipRepository.findBySourceAndRelationType(source, relType).stream()
                .map(EntityRelationship::getTarget)
                .toList();
    }

    @Transactional
    public long countEntitiesByEntityType(String name) {
        return entityTypeRepository
                .findByName(name)
                .map(entityRepository::countTypedEntityByEntityType)
                .orElse(0L);
    }

    private List<EntityType> checkRelIsLegit(String sourceEntityId, RelationType relType, String targetEntityId)
            throws ServiceError {
        var sourceEntity = entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceEntityId + " not found"));
        var targetEntity = entityRepository
                .findById(targetEntityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + targetEntityId + " not found"));
        var sourceEntityType = sourceEntity.getEntityType();
        var targetEntityType = targetEntity.getEntityType();

        var allTheTraitsForTheSourceType = loadInheritanceChain(sourceEntityType).stream()
                .flatMap(entityType ->
                        entityType.getTraits().stream().flatMap(trait -> loadInheritanceChain(trait).stream()))
                .toList();

        var allTheTraitsNamesForTheTargetType = loadInheritanceChain(targetEntityType).stream()
                .flatMap(entityType ->
                        entityType.getTraits().stream().flatMap(trait -> loadInheritanceChain(trait).stream()))
                .map(Trait::getName)
                .collect(Collectors.toSet());

        return List.of();
    }
}
