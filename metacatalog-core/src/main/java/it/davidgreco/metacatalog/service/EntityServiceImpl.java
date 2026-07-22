package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.ServiceUtils.ENTITY_WITH_ID;
import static it.davidgreco.metacatalog.service.ServiceUtils.NOT_FOUND;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityLifeCycleEvent;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.repository.*;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link EntityService}. */
@Slf4j
@RequiredArgsConstructor
public class EntityServiceImpl implements EntityService {

  private static final String NO_PROCESSING = EntityLifeCycleEvent.STATUS_NO_PROCESSING;

  private final EntityTypeRepository entityTypeRepository;

  private final EntityTypeVersionRepository entityTypeVersionRepository;

  private final EntityRepository entityRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  private final JsonUtils jsonUtils;

  /**
   * Creates a new entity based on the given type name and JSON values.
   *
   * <p>The entity is pinned to the {@link EntityTypeVersion} snapshot matching the current live
   * version of its type, so subsequent {@code createVersion} calls on the type do not silently
   * migrate the entity to the new schema. Validation always uses that snapshot's schema.
   *
   * @param typeName the name of the entity type for the entity to create
   * @param values a JSON string containing the values for the entity
   * @return the created Entity
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Entity create(String typeName, String values) {
    log.info("Creating entity of type {}", typeName);
    try {
      var entityType =
          entityTypeRepository
              .findByName(typeName)
              .orElseThrow(() -> new NotFoundException("Entity type " + typeName + NOT_FOUND));

      if (ServiceUtils.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entityType))
        throw new ServiceError(
            "Creating an entity for a mapping target entity type is not allowed");

      var currentVersion =
          entityTypeVersionRepository.findByVersionGroupIdAndVersion(
              entityType.getVersionGroupId(), entityType.getVersion());
      var validationSchema =
          currentVersion.map(EntityTypeVersion::getSchema).orElse(entityType.getSchema());

      var valuesJsonNode = jsonUtils.jsonMapper().readTree(values);
      SchemaValidationError.validateOrThrow(
          jsonUtils.jsonSchemaFactory().getSchema(validationSchema), valuesJsonNode);
      var typedEntity = new Entity();
      typedEntity.setEntityType(entityType);
      currentVersion.ifPresent(typedEntity::setEntityTypeVersion);
      typedEntity.setValues(valuesJsonNode);
      var en = entityRepository.save(typedEntity);
      if (ServiceUtils.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, entityType))
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                en.getId(),
                en.getEntityType().getName(),
                EntityLifeCycleEvent.ENTITY_SOURCE_CREATED,
                EntityLifeCycleEvent.STATUS_PENDING));
      else
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                en.getId(),
                en.getEntityType().getName(),
                EntityLifeCycleEvent.ENTITY_CREATED,
                NO_PROCESSING));
      log.info("Created entity of type {}", typeName);
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
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Entity read(String entityId) {
    log.info("Reading entity with id {}", entityId);
    var entity =
        entityRepository
            .findById(entityId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + entityId + NOT_FOUND));
    log.info("Read entity with id {}", entityId);
    return entity;
  }

  /**
   * Updates an existing entity with the given ID.
   *
   * <p>Validation uses the schema of the {@link EntityTypeVersion} the entity is pinned to (set at
   * creation time). If the entity has no pin (legacy row created before pinning was introduced),
   * validation falls back to the live {@link EntityType#getSchema()}.
   *
   * @param entityId the ID of the entity to update
   * @param values a JSON string containing the new values for the entity
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void update(String entityId, String values) {
    log.info("Updating entity with id {}", entityId);
    try {
      var entity =
          entityRepository
              .findById(entityId)
              .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + entityId + NOT_FOUND));

      if (!ServiceUtils.implementsTrait(
              entity.getEntityType(), BuiltInTraits.PROVISIONABLE_RESOURCE)
          && ServiceUtils.isMappingTargetEntityType(
              mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a mapping target entity type");

      var valuesJsonNode = jsonUtils.jsonMapper().readTree(values);
      var pinnedVersion = entity.getEntityTypeVersion();
      var schema =
          pinnedVersion != null ? pinnedVersion.getSchema() : entity.getEntityType().getSchema();
      SchemaValidationError.validateOrThrow(
          jsonUtils.jsonSchemaFactory().getSchema(schema), valuesJsonNode);
      entity.setValues(valuesJsonNode);
      entityRepository.save(entity);
      if (ServiceUtils.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                entity.getId(),
                entity.getEntityType().getName(),
                EntityLifeCycleEvent.ENTITY_SOURCE_UPDATED,
                EntityLifeCycleEvent.STATUS_PENDING));
      else
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                entity.getId(),
                entity.getEntityType().getName(),
                EntityLifeCycleEvent.ENTITY_UPDATED,
                NO_PROCESSING));
      log.info("Updated entity with id {}", entityId);
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
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String entityId) {
    log.info("Deleting entity with id {}", entityId);
    try {
      var entity =
          entityRepository
              .findById(entityId)
              .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + entityId + NOT_FOUND));

      if (ServiceUtils.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a target entity type");

      if (ServiceUtils.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a source entity type");

      entityRepository.delete(entity);
      entityLifeCycleEventRepository.save(
          new EntityLifeCycleEvent(
              entity.getId(),
              entity.getEntityType().getName(),
              EntityLifeCycleEvent.ENTITY_DELETED,
              NO_PROCESSING));
      log.info("Deleted entity with id {}", entityId);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
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
    log.info("Checking if entity with id {} exists", entityId);
    var exists = entityRepository.existsById(entityId);
    log.info("Checked if entity with id {} exists", entityId);
    return exists;
  }

  /**
   * Lists entities of the specified type, optionally filtered by a JSON path query.
   *
   * @param typeName the name of the entity type to list entities for
   * @param queryPath a JSON path query to filter entities; empty string returns all entities of the
   *     type
   * @return a list of entities matching the criteria
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<Entity> list(String typeName, String queryPath) {
    log.info("Listing entities with query path {}", queryPath);
    try {
      var entityType =
          entityTypeRepository
              .findByName(typeName)
              .orElseThrow(() -> new NotFoundException("Entity type " + typeName + NOT_FOUND));
      var qp = queryPath.trim();
      if (qp.isEmpty()) {
        var result = entityRepository.findByEntityType(entityType);
        log.info("Listed entities with query path {}", queryPath);
        return result;
      } else {
        var result = entityRepository.findByEntityTypeIdAndJsonPath(entityType.getId(), queryPath);
        log.info("Listed entities with query path {}", queryPath);
        return result;
      }
    } catch (com.jayway.jsonpath.InvalidPathException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<Entity> listAll() {
    log.info("Listing all entities");
    return entityRepository.findAll();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public List<EntityRelationship> listAllRelationships() {
    log.info("Listing all entity relationships");
    return entityRelationshipRepository.findAll();
  }

  /**
   * Links two entities with a given relation type.
   *
   * @param sourceId the ID of the source entity
   * @param relType the relation type to use for the link
   * @param targetId the ID of the target entity
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void link(String sourceId, RelationType relType, String targetId) {
    log.info("Linking entity with id {} with entity with id {}", sourceId, targetId);
    // Check loops
    if (ServiceUtils.checkLoops(
        entityRepository,
        entityRelationshipRepository,
        targetId,
        new HashSet<>(),
        sourceId,
        relType)) throw new ServiceError("Loops are not allowed");

    // Check if the relationship is legit
    if (!ServiceUtils.checkRelIsLegit(
        entityRepository, traitRelationshipRepository, sourceId, relType, targetId))
      throw new ServiceError("Relationship is not legit");

    var source =
        entityRepository
            .findById(sourceId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + sourceId + NOT_FOUND));
    var target =
        entityRepository
            .findById(targetId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + targetId + NOT_FOUND));

    checkRelationshipExistenceAndSave(sourceId, relType, targetId, source, target);
    if (relType.hasInverse()) {
      checkRelationshipExistenceAndSave(targetId, relType.inverse(), sourceId, target, source);
    }
    log.info("Linked entity with id {} with entity with id {}", sourceId, targetId);
  }

  private void checkRelationshipExistenceAndSave(
      String sourceId, RelationType relType, String targetId, Entity source, Entity target) {
    if (entityRelationshipRepository
        .findBySourceAndRelationTypeAndTarget(source, relType, target)
        .isPresent())
      throw new ServiceError(
          "Entity with id "
              + sourceId
              + " is already linked with entity with id "
              + targetId
              + " with relation type "
              + relType);

    var directRel = new EntityRelationship();
    directRel.setSource(source);
    directRel.setTarget(target);
    directRel.setRelationType(relType);
    entityRelationshipRepository.save(directRel);
  }

  /**
   * Removes a link between two entities.
   *
   * @param sourceId the ID of the source entity
   * @param relType the relation type of the link
   * @param targetId the ID of the target entity
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void unlink(String sourceId, RelationType relType, String targetId) {
    log.info("Unlinking entity with id {} from entity with id {}", sourceId, targetId);
    var source =
        entityRepository
            .findById(sourceId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + sourceId + NOT_FOUND));
    var target =
        entityRepository
            .findById(targetId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + targetId + NOT_FOUND));
    var directRel =
        entityRelationshipRepository
            .findBySourceAndRelationTypeAndTarget(source, relType, target)
            .orElseThrow(
                () ->
                    new ServiceError(
                        ENTITY_WITH_ID
                            + source.getId()
                            + " does not have a relationship "
                            + relType
                            + " with entity with id "
                            + target.getId()));
    entityRelationshipRepository.delete(directRel);
    if (relType.hasInverse()) {
      var inverseRelType = relType.inverse();
      var inverseRel =
          entityRelationshipRepository
              .findBySourceAndRelationTypeAndTarget(target, inverseRelType, source)
              .orElseThrow(
                  () ->
                      new ServiceError(
                          ENTITY_WITH_ID
                              + target.getId()
                              + " does not have a relationship "
                              + inverseRelType
                              + " with entity with id "
                              + source.getId()));
      entityRelationshipRepository.delete(inverseRel);
    }

    log.info("Unlinked entity with id {} from entity with id {}", sourceId, targetId);
  }

  /**
   * Retrieves a list of entities linked to the given source entity with a specific relation type.
   *
   * @param sourceId the ID of the source entity
   * @param relType the relation type to filter the links
   * @return a list of entities that are targets of the specified relation type from the source
   *     entity
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<Entity> linked(String sourceId, RelationType relType) {
    log.info(
        "Listing entities linked to entity with id {} with relation type {}", sourceId, relType);
    var source =
        entityRepository
            .findById(sourceId)
            .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + sourceId + NOT_FOUND));
    var linkedEntities =
        entityRelationshipRepository.findBySourceAndRelationType(source, relType).stream()
            .map(EntityRelationship::getTarget)
            .toList();
    log.info(
        "Listed entities linked to entity with id {} with relation type {}", sourceId, relType);
    return linkedEntities;
  }
}
