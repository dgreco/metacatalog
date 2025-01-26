package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;
import static it.davidgreco.metacatalog.common.JsonUtils.jsonSchemaFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.ValidationMessage;
import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.util.HashSet;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Getter
@Setter
@RequiredArgsConstructor
public class EntityService implements CommonService<Entity, String> {

  private static final String ENTITY_WITH_ID = " Entity with id ";

  private static final String NOT_FOUND = " not found";

  private static final String NO_PROCESSING = "NO_PROCESSING";

  private final EntityTypeRepository entityTypeRepository;

  private final EntityRepository entityRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  /**
   * Creates a new entity based on the given type name and JSON values.
   *
   * @param typeName the name of the entity type for the entity to create
   * @param values a JSON string containing the values for the entity
   * @return the created Entity
   * @throws ServiceError if the entity type is not found, creation is not allowed, validation
   *     fails, or a JSON processing error occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Entity create(String typeName, String values) throws ServiceError {
    try {
      var entityType =
          entityTypeRepository
              .findByName(typeName)
              .orElseThrow(() -> new ServiceError("Entity type " + typeName + NOT_FOUND));

      if (CommonService.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entityType))
        throw new ServiceError("Creating an entity for a target entity type is not allowed");

      var valuesJsonNode = jsonFactory.readTree(values);
      var validationMessages =
          jsonSchemaFactory.getSchema(entityType.getSchema()).validate(valuesJsonNode);
      if (!validationMessages.isEmpty()) {
        var errorMessages = validationMessages.stream().map(ValidationMessage::getMessage).toList();
        throw new SchemaValidationError(errorMessages);
      }
      var typedEntity = new Entity();
      typedEntity.setEntityType(entityType);
      typedEntity.setValues(valuesJsonNode);
      var en = entityRepository.save(typedEntity);
      if (CommonService.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, entityType))
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                en.getId(), en.getEntityType().getName(), "SOURCE_CREATED", "PENDING"));
      else
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                en.getId(), en.getEntityType().getName(), "CREATED", NO_PROCESSING));
      return en;
    } catch (JsonProcessingException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Reads an entity by its ID.
   *
   * @param entityId the ID of the entity to read
   * @return the read entity
   * @throws ServiceError if the entity with the given ID does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Entity read(String entityId) throws ServiceError {
    return entityRepository
        .findById(entityId)
        .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + entityId + NOT_FOUND));
  }

  /**
   * Updates an existing entity with the given ID.
   *
   * @param entityId the ID of the entity to update
   * @param values a JSON string containing the new values for the entity
   * @throws ServiceError if the entity with the given ID does not exist, is an instance of a target
   *     entity type, validation fails, or a JSON processing error occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void update(String entityId, String values) throws ServiceError {
    try {
      var entity =
          entityRepository
              .findById(entityId)
              .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + entityId + NOT_FOUND));

      if (CommonService.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a target entity type");

      var valuesJsonNode = jsonFactory.readTree(values);
      var validationMessages =
          jsonSchemaFactory.getSchema(entity.getEntityType().getSchema()).validate(valuesJsonNode);
      if (!validationMessages.isEmpty()) {
        var errorMessages = validationMessages.stream().map(ValidationMessage::getMessage).toList();
        throw new SchemaValidationError(errorMessages);
      }
      entity.setValues(valuesJsonNode);
      entityRepository.save(entity);
      if (CommonService.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                entity.getId(), entity.getEntityType().getName(), "SOURCE_UPDATED", "PENDING"));
      else
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                entity.getId(), entity.getEntityType().getName(), "UPDATED", NO_PROCESSING));
    } catch (JsonProcessingException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Deletes an entity by its ID.
   *
   * <p>This method only deletes entities that are instances of neither source nor target entity
   * types.
   *
   * @param entityId the ID of the entity to delete
   * @throws ServiceError if the entity with the given ID does not exist, is an instance of a source
   *     or target entity type, or a data integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void delete(String entityId) throws ServiceError {
    try {
      var entity =
          entityRepository
              .findById(entityId)
              .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + entityId + NOT_FOUND));

      if (CommonService.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a target entity type");

      if (CommonService.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a source entity type");

      entityRepository.delete(entity);
      entityLifeCycleEventRepository.save(
          new EntityLifeCycleEvent(
              entity.getId(), entity.getEntityType().getName(), "DELETED", NO_PROCESSING));
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Checks if an entity with the given ID exists.
   *
   * @param entityId the ID to check
   * @return true if an entity with the given ID exists, false otherwise
   */
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

  /**
   * Links two entities with a given relation type.
   *
   * @param sourceId the ID of the source entity
   * @param relType the relation type to use for the link
   * @param targetId the ID of the target entity
   * @throws ServiceError if the link is not allowed (e.g. due to a loop or an invalid relationship)
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void link(String sourceId, RelationType relType, String targetId) throws ServiceError {
    // Check loops
    if (CommonService.checkLoops(
        entityRepository,
        entityRelationshipRepository,
        targetId,
        new HashSet<>(),
        sourceId,
        relType)) throw new ServiceError("Loops are not allowed");

    // Check if the relationship is legit
    if (!CommonService.checkRelIsLegit(
        entityRepository, traitRelationshipRepository, sourceId, relType, targetId))
      throw new ServiceError("Relationship is not legit");

    var rel1 = new EntityRelationship();
    var source =
        entityRepository
            .findById(sourceId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + sourceId + NOT_FOUND));
    var target =
        entityRepository
            .findById(targetId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + targetId + NOT_FOUND));

    if (entityRelationshipRepository
        .findBySourceAndRelationTypeAndTarget(source, relType, target)
        .isPresent())
      throw new ServiceError(
          "Entity with id "
              + sourceId
              + " is already linked with entity with id "
              + targetId
              + "with relation type "
              + relType);

    rel1.setSource(source);
    rel1.setTarget(target);
    rel1.setRelationType(relType);
    entityRelationshipRepository.save(rel1);
  }

  /**
   * Removes a link between two entities.
   *
   * @param sourceId the ID of the source entity
   * @param relType the relation type of the link
   * @param targetId the ID of the target entity
   * @throws ServiceError if the link does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void unlink(String sourceId, RelationType relType, String targetId) throws ServiceError {
    var source =
        entityRepository
            .findById(sourceId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + sourceId + NOT_FOUND));
    var target =
        entityRepository
            .findById(targetId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + targetId + NOT_FOUND));
    var rel =
        entityRelationshipRepository
            .findBySourceAndRelationTypeAndTarget(source, relType, target)
            .orElseThrow(
                () ->
                    new ServiceError(
                        ENTITY_WITH_ID
                            + source
                            + " does not have a relationship "
                            + relType
                            + ENTITY_WITH_ID
                            + target));
    entityRelationshipRepository.delete(rel);
  }

  /**
   * Retrieves a list of entities linked to the given source entity with a specific relation type.
   *
   * @param sourceId the ID of the source entity
   * @param relType the relation type to filter the links
   * @return a list of entities that are targets of the specified relation type from the source
   *     entity
   * @throws ServiceError if the source entity with the given ID does not exist
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Entity> linked(String sourceId, RelationType relType) throws ServiceError {
    var source =
        entityRepository
            .findById(sourceId)
            .orElseThrow(() -> new ServiceError(ENTITY_WITH_ID + sourceId + NOT_FOUND));
    return entityRelationshipRepository.findBySourceAndRelationType(source, relType).stream()
        .map(EntityRelationship::getTarget)
        .toList();
  }
}
