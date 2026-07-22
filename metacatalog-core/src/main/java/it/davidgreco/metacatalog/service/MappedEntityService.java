package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.IS_MAPPED_BY;
import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;
import static it.davidgreco.metacatalog.service.ServiceUtils.isMappingTargetEntityType;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.CoreConfigProperties;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.time.Instant;
import java.util.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manages the lifecycle of mapped entities — the concrete entity instances that are automatically
 * created, updated, or deleted as a result of mapping relationships.
 *
 * <p>This service handles the recursive cascades that propagate mapping changes through the entity
 * graph: creating a mapped entity may trigger further mapped-entity creation if the newly created
 * entity is itself a mapping source; updating a source entity cascades through all its mapped
 * targets; deleting a source entity cascades through and removes all mapped entities in the
 * subtree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappedEntityService {

  private static final String DOES_NOT_EXIST = " does not exist";

  private static final String ENTITY = "Entity ";

  private final EntityRepository entityRepository;

  private final EntityTypeVersionRepository entityTypeVersionRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  private final PlatformTransactionManager transactionManager;

  private final CoreConfigProperties coreConfigProperties;

  private final EntityPathResolver entityPathResolver;

  private final JsonUtils jsonUtils;

  @Getter(lazy = true)
  private final TransactionTemplate transactionTemplate =
      new TransactionTemplate(transactionManager);

  /**
   * Creates mapped entities for the given event, and then marks the event as processed.
   *
   * @param event the event to process
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void createMappedEntities(EntityLifeCycleEvent event) {
    log.info("Creating mapped entities for event with ID: {}", event.getId());
    createMappedEntities(event.getEntityId());
    event.setEventStatus(EntityLifeCycleEvent.STATUS_PROCESSED);
    event.setProcessTime(Instant.now());
    entityLifeCycleEventRepository.save(event);
    log.info("Created mapped entities for event with ID: {}", event.getId());
  }

  /**
   * Creates mapped entities for the given source entity ID.
   *
   * @param sourceEntityId the ID of the source entity to process
   */
  public void createMappedEntities(String sourceEntityId) {

    class CreateMappedEntities {
      private final Set<String> visited = new HashSet<>();

      private void createMappedEntities(String sourceEntityId) {
        if (!visited.add(sourceEntityId)) return;
        RetryTemplate createRetryTemplate =
            RetryTemplate.builder()
                .maxAttempts(coreConfigProperties.entityPathResolutionMaxAttempts())
                .fixedBackoff(500)
                .retryOn(ServiceError.class)
                .build();

        var sourceEntity =
            entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new NotFoundException(ENTITY + sourceEntityId + DOES_NOT_EXIST));
        var sourceEntityType = sourceEntity.getEntityType();
        var mappingRelationships =
            mappingEntityTypeRelationshipRepository.findMappingEntityTypeRelationshipBySource(
                sourceEntityType);
        for (MappingEntityTypeRelationship mappingRelationship : mappingRelationships) {
          var existingMappingEntityRels =
              mappingEntityRelationshipRepository.findBySourceAndMappingEntityTypeRelationship(
                  sourceEntity, mappingRelationship);
          if (existingMappingEntityRels.isEmpty()) {
            var targetEntityType = mappingRelationship.getTarget();
            var additionalEntitiesValues = new HashMap<String, JsonNode>();
            for (MappingEntityTypeRelationship.EntityPathReference sourceReference :
                mappingRelationship.getEntityPathReferences()) {
              var as = sourceReference.alias();
              var jn =
                  createRetryTemplate.execute(
                      _ ->
                          entityPathResolver
                              .retrieveEntityByPath(sourceEntityId, sourceReference.referencePath())
                              .orElseThrow(() -> new NotFoundException("Entity not found")));
              additionalEntitiesValues.put(as, jn.getValues());
            }
            var mappedValues =
                MappingValueEvaluator.generateMappedValues(
                    sourceEntity.getValues(),
                    additionalEntitiesValues,
                    mappingRelationship.getMappingValues(),
                    jsonUtils.jsonSchemaFactory().getSchema(targetEntityType.getSchema()));
            var targetVersion =
                entityTypeVersionRepository
                    .findByVersionGroupIdAndVersion(
                        targetEntityType.getVersionGroupId(), targetEntityType.getVersion())
                    .orElse(null);
            var mappedEntity = new Entity();
            mappedEntity.setEntityType(targetEntityType);
            if (targetVersion != null) mappedEntity.setEntityTypeVersion(targetVersion);
            mappedEntity.setValues(mappedValues);
            entityRepository.save(mappedEntity);
            entityLifeCycleEventRepository.save(
                new EntityLifeCycleEvent(
                    mappedEntity.getId(),
                    mappedEntity.getEntityType().getName(),
                    EntityLifeCycleEvent.ENTITY_CREATED,
                    EntityLifeCycleEvent.STATUS_NO_PROCESSING));
            var mappingEntityRelationship = new MappingEntityRelationship();
            mappingEntityRelationship.setSource(sourceEntity);
            mappingEntityRelationship.setTarget(mappedEntity);
            mappingEntityRelationship.setMappingEntityTypeRelationship(mappingRelationship);
            mappingEntityRelationship.setRelationType(MAPPED_TO);
            mappingEntityRelationshipRepository.save(mappingEntityRelationship);
            var inverseMappingEntityRelationship = new MappingEntityRelationship();
            inverseMappingEntityRelationship.setSource(mappedEntity);
            inverseMappingEntityRelationship.setTarget(sourceEntity);
            inverseMappingEntityRelationship.setMappingEntityTypeRelationship(mappingRelationship);
            inverseMappingEntityRelationship.setRelationType(IS_MAPPED_BY);
            mappingEntityRelationshipRepository.save(inverseMappingEntityRelationship);
            createMappedEntities(mappedEntity.getId());
          } else {
            createMappedEntities(existingMappingEntityRels.getFirst().getTarget().getId());
          }
        }
      }
    }

    log.info("Creating mapped entities for source entity with ID: {}", sourceEntityId);
    getTransactionTemplate()
        .executeWithoutResult(
            _ -> {
              var sourceEntity =
                  entityRepository
                      .findById(sourceEntityId)
                      .orElseThrow(
                          () -> new NotFoundException(ENTITY + sourceEntityId + DOES_NOT_EXIST));
              var sourceEntityType = sourceEntity.getEntityType();
              if (isMappingTargetEntityType(
                  mappingEntityTypeRelationshipRepository, sourceEntityType))
                throw new ServiceError(
                    "Source entity type "
                        + sourceEntityType.getName()
                        + " is a target entity type");
              new CreateMappedEntities().createMappedEntities(sourceEntityId);
            });
    log.info("Created mapped entities for source entity with ID: {}", sourceEntityId);
  }

  /**
   * Updates mapped entities for the given event.
   *
   * @param event the event to process
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateMappedEntities(EntityLifeCycleEvent event) {
    log.info("Updating mapped entities for event with ID: {}", event.getId());
    updateMappedEntities(event.getEntityId());
    event.setEventStatus(EntityLifeCycleEvent.STATUS_PROCESSED);
    event.setProcessTime(Instant.now());
    entityLifeCycleEventRepository.save(event);
    log.info("Updated mapped entities for event with ID: {}", event.getId());
  }

  /**
   * Updates mapped entities for the given source entity ID.
   *
   * @param sourceEntityId the ID of the source entity to process
   */
  public void updateMappedEntities(String sourceEntityId) {

    class UpdateMappedEntities {
      private final Set<String> visited = new HashSet<>();

      private void updateMappedEntities(String sourceEntityId) {
        if (!visited.add(sourceEntityId)) return;
        RetryTemplate updateRetryTemplate =
            RetryTemplate.builder()
                .maxAttempts(coreConfigProperties.entityPathResolutionMaxAttempts())
                .fixedBackoff(500)
                .retryOn(ServiceError.class)
                .build();

        var sourceEntity =
            entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new NotFoundException(ENTITY + sourceEntityId + DOES_NOT_EXIST));
        var entityMappingRelationships =
            mappingEntityRelationshipRepository.findBySourceAndRelationType(
                sourceEntity, MAPPED_TO);
        for (MappingEntityRelationship entityMappingRelationship : entityMappingRelationships) {
          var mappingTypeRelationship =
              entityMappingRelationship.getMappingEntityTypeRelationship();
          var additionalEntitiesValues = new HashMap<String, JsonNode>();
          for (MappingEntityTypeRelationship.EntityPathReference sourceReference :
              mappingTypeRelationship.getEntityPathReferences()) {
            var as = sourceReference.alias();
            var jn =
                updateRetryTemplate.execute(
                    _ ->
                        entityPathResolver
                            .retrieveEntityByPath(sourceEntityId, sourceReference.referencePath())
                            .orElseThrow(() -> new NotFoundException("Entity not found")));
            additionalEntitiesValues.put(as, jn.getValues());
          }
          var mappedEntity = entityMappingRelationship.getTarget();
          var pinnedTargetVersion = mappedEntity.getEntityTypeVersion();
          var targetSchema =
              pinnedTargetVersion != null
                  ? pinnedTargetVersion.getSchema()
                  : mappingTypeRelationship.getTarget().getSchema();
          var mappedValues =
              MappingValueEvaluator.generateMappedValues(
                  sourceEntity.getValues(),
                  additionalEntitiesValues,
                  mappingTypeRelationship.getMappingValues(),
                  jsonUtils.jsonSchemaFactory().getSchema(targetSchema));
          mappedEntity.setValues(mappedValues);
          entityRepository.save(mappedEntity);
          entityLifeCycleEventRepository.save(
              new EntityLifeCycleEvent(
                  mappedEntity.getId(),
                  mappedEntity.getEntityType().getName(),
                  EntityLifeCycleEvent.ENTITY_UPDATED,
                  EntityLifeCycleEvent.STATUS_NO_PROCESSING));
          updateMappedEntities(mappedEntity.getId());
        }
      }
    }

    log.info("Updating mapped entities for source entity with ID: {}", sourceEntityId);
    getTransactionTemplate()
        .executeWithoutResult(
            _ -> {
              var sourceEntity =
                  entityRepository
                      .findById(sourceEntityId)
                      .orElseThrow(
                          () -> new NotFoundException(ENTITY + sourceEntityId + DOES_NOT_EXIST));
              var sourceEntityType = sourceEntity.getEntityType();
              if (isMappingTargetEntityType(
                  mappingEntityTypeRelationshipRepository, sourceEntityType))
                throw new ServiceError(
                    "Source entity type "
                        + sourceEntityType.getName()
                        + " is a target entity type");
              new UpdateMappedEntities().updateMappedEntities(sourceEntityId);
            });
    log.info("Updated mapped entities for source entity with ID: {}", sourceEntityId);
  }

  /**
   * Deletes all entities mapped from the given source entity.
   *
   * <p>This method only deletes entities that are instances of neither source nor target entity
   * types.
   *
   * @param sourceEntityId the ID of the source entity
   */
  public void deleteMappedEntities(String sourceEntityId) {

    class DeleteMappedEntities {
      private void retrieveMappedEntities(
          String sourceEntityId, Deque<String> retrievedEntitiesIds) {
        var sourceEntity =
            entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new NotFoundException(ENTITY + sourceEntityId + DOES_NOT_EXIST));
        var entityMappingRelationships =
            mappingEntityRelationshipRepository.findBySourceAndRelationType(
                sourceEntity, MAPPED_TO);
        for (MappingEntityRelationship entityMappingRelationship : entityMappingRelationships) {
          var mappedEntity = entityMappingRelationship.getTarget();
          retrievedEntitiesIds.push(mappedEntity.getId());
          mappingEntityRelationshipRepository.delete(entityMappingRelationship);
          mappingEntityRelationshipRepository
              .findBySourceAndRelationTypeAndTarget(
                  entityMappingRelationship.getTarget(),
                  MAPPED_TO.inverse(),
                  entityMappingRelationship.getSource())
              .ifPresent(mappingEntityRelationshipRepository::delete);
          retrieveMappedEntities(mappedEntity.getId(), retrievedEntitiesIds);
        }
      }

      private void deleteMappedEntities(String sourceEntityId) {
        var retrievedEntitiesIds = new LinkedList<String>();
        retrieveMappedEntities(sourceEntityId, retrievedEntitiesIds);
        while (!retrievedEntitiesIds.isEmpty()) {
          var id = retrievedEntitiesIds.pop();
          var entity = entityRepository.findById(id);
          entity.ifPresent(entityRepository::delete);
        }
      }
    }

    log.info("Deleting mapped entities for source entity with ID: {}", sourceEntityId);
    getTransactionTemplate()
        .executeWithoutResult(
            _ -> {
              var sourceEntity =
                  entityRepository
                      .findById(sourceEntityId)
                      .orElseThrow(
                          () -> new NotFoundException(ENTITY + sourceEntityId + DOES_NOT_EXIST));
              var sourceEntityType = sourceEntity.getEntityType();
              if (isMappingTargetEntityType(
                  mappingEntityTypeRelationshipRepository, sourceEntityType))
                throw new ServiceError(
                    "Source entity type "
                        + sourceEntityType.getName()
                        + " is a target entity type");
              new DeleteMappedEntities().deleteMappedEntities(sourceEntityId);
            });
    log.info("Deleted mapped entities for source entity with ID: {}", sourceEntityId);
  }
}
