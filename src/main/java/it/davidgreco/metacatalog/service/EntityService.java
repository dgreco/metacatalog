package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;
import static it.davidgreco.metacatalog.service.CommonTypeService.commonTraitService;
import static it.davidgreco.metacatalog.service.CommonTypeService.commonTypeService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.ValidationMessage;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityTypeRepository;
import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Getter
@Setter
@RequiredArgsConstructor
public class EntityService implements CommonService<Entity, String> {

    private final EntityTypeRepository entityTypeRepository;

    private final EntityRepository entityRepository;

    private final EntityRelationshipRepository entityRelationshipRepository;

    private final TraitRelationshipRepository traitRelationshipRepository;

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
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

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public Entity read(String entityId) throws ServiceError {
        return entityRepository
                .findById(entityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + entityId + " not found"));
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void update(String entityId, String values) throws ServiceError {
        try {
            var entity = entityRepository
                    .findById(entityId)
                    .orElseThrow(() -> new ServiceError("Entity with id " + entityId + " not found"));
            var valuesJsonNode = jsonFactory.readTree(values);
            var validationMessages = jsonSchemaFactory
                    .getSchema(entity.getEntityType().getSchema())
                    .validate(valuesJsonNode);
            if (!validationMessages.isEmpty()) {
                var errorMessages = validationMessages.stream()
                        .map(ValidationMessage::getMessage)
                        .toList();
                throw new SchemaValidationError(errorMessages);
            }
            entity.setValues(valuesJsonNode);
            entityRepository.save(entity);
        } catch (JsonProcessingException e) {
            throw new ServiceError(e.getMessage());
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void delete(String entityId) throws ServiceError {
        var entity = entityRepository
                .findById(entityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + entityId + " not found"));
        entityRepository.delete(entity);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean exists(String entityId) {
        return entityRepository.existsById(entityId);
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public List<Entity> list(String queryPath) throws ServiceError {
        try {
            var qp = queryPath.trim();
            if (qp.isEmpty()) return entityRepository.findAll();
            else return entityRepository.findByJsonPath(queryPath);
        } catch (com.jayway.jsonpath.InvalidPathException e) {
            throw new ServiceError(e.getMessage());
        }
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void link(String sourceId, RelationType relType, String targetId) throws ServiceError {
        // Check loops
        if (checkLoops(targetId, new HashSet<>(), sourceId, relType)) throw new ServiceError("Loops are not allowed");

        // Check if the relationship is legit
        if (!checkRelIsLegit(sourceId, relType, targetId)) throw new ServiceError("Relationship is not legit");

        var rel1 = new EntityRelationship();
        var source = entityRepository
                .findById(sourceId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceId + " not found"));
        var target = entityRepository
                .findById(targetId)
                .orElseThrow(() -> new ServiceError("Entity with id " + targetId + " not found"));

        if (entityRelationshipRepository // TODO the index is not working
                .findBySourceAndRelationTypeAndTarget(source, relType, target)
                .isPresent())
            throw new ServiceError("Entity with id " + sourceId + " is already linked with entity with id " + targetId
                    + "with relation type " + relType);

        rel1.setSource(source);
        rel1.setTarget(target);
        rel1.setRelationType(relType);
        entityRelationshipRepository.save(rel1);
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
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

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public List<Entity> linked(String sourceId, RelationType relType) throws ServiceError {
        var source = entityRepository
                .findById(sourceId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceId + " not found"));
        return entityRelationshipRepository.findBySourceAndRelationType(source, relType).stream()
                .map(EntityRelationship::getTarget)
                .toList();
    }

    private boolean checkLoops(
            String sourceEntityId, Set<String> entityIdsVisited, String targetEntityId, RelationType relationType) {
        var sourceEntity = entityRepository.findById(sourceEntityId).get();
        var mappings = entityRelationshipRepository.findBySourceAndRelationType(sourceEntity, relationType);

        var targetIds = mappings.stream()
                .map(EntityRelationship::getTarget)
                .map(Entity::getId)
                .toList();
        for (var targetId : targetIds) {
            if (entityIdsVisited.contains(targetEntityId)) return true;
            else {
                entityIdsVisited.add(targetId);
                return checkLoops(targetId, entityIdsVisited, targetEntityId, relationType);
            }
        }
        return entityIdsVisited.contains(targetEntityId);
    }

    private boolean checkRelIsLegit(String sourceEntityId, RelationType relType, String targetEntityId)
            throws ServiceError {

        var sourceEntity = entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + sourceEntityId + " not found"));
        var targetEntity = entityRepository
                .findById(targetEntityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + targetEntityId + " not found"));
        var sourceEntityType = sourceEntity.getEntityType();
        var targetEntityType = targetEntity.getEntityType();

        var allTheTraitsForTheSourceType = commonTypeService.loadInheritanceChain(sourceEntityType).stream()
                .flatMap(entityType -> entityType.getTraits().stream()
                        .flatMap(trait -> commonTraitService.loadInheritanceChain(trait).stream()))
                .toList();

        var allTheTraitsNamesForTheTargetType = commonTypeService.loadInheritanceChain(targetEntityType).stream()
                .flatMap(entityType -> entityType.getTraits().stream()
                        .flatMap(trait -> commonTraitService.loadInheritanceChain(trait).stream()))
                .map(Trait::getName)
                .collect(Collectors.toSet());

        var sourceRelationships = allTheTraitsForTheSourceType.stream()
                .flatMap(sourceTrait ->
                        traitRelationshipRepository.findBySourceAndRelationType(sourceTrait, relType).stream())
                .toList();

        for (var sourceRelationship : sourceRelationships) {
            var targetTrait = sourceRelationship.getTarget();
            if (allTheTraitsNamesForTheTargetType.contains(targetTrait.getName())) return true;
        }
        return false;
    }
}
