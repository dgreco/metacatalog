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
import java.util.ArrayList;
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

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  private final EntityPathResolver entityPathResolver;

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
      var cachedType =
          entityTypeRepository
              .findByName(typeName)
              .orElseThrow(() -> new NotFoundException("Entity type " + typeName + NOT_FOUND));
      // findByName is @Cacheable, so it can return an instance detached from the session that
      // first loaded it (e.g. a read request right after a restart warms the cache, and this
      // write request gets its entity). Re-resolve by id into the current session before
      // attaching the type to the new entity: bulk creation links entities in the same
      // transaction, and walking a detached type's lazy trait/father graph there dies with a
      // LazyInitializationException.
      var entityType =
          entityTypeRepository
              .findById(cachedType.getId())
              .orElseThrow(() -> new NotFoundException("Entity type " + typeName + NOT_FOUND));

      if (ServiceUtils.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entityType))
        throw new ServiceError(
            "Creating an entity for a mapping target entity type is not allowed");

      var currentVersion =
          entityTypeVersionRepository
              .findByVersionGroupIdAndVersion(
                  entityType.getVersionGroupId(), entityType.getVersion())
              .orElseThrow(
                  () ->
                      new ServiceError(
                          "Entity type "
                              + typeName
                              + " has no snapshot for its live version "
                              + entityType.getVersion()
                              + "; cannot pin a new entity to it"));

      var valuesJsonNode = jsonUtils.jsonMapper().readTree(values);
      SchemaValidationError.validateOrThrow(
          jsonUtils.jsonSchemaFactory().getSchema(currentVersion.getSchema()), valuesJsonNode);
      var typedEntity = new Entity();
      typedEntity.setEntityType(entityType);
      typedEntity.setEntityTypeVersion(currentVersion);
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
   * <p>Entities whose type is the target of a mapping relationship cannot be updated through this
   * method. Internal system updates (e.g. provisioning) should use {@link #updateValues}.
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

      if (ServiceUtils.isMappingTargetEntityType(
          mappingEntityTypeRelationshipRepository, entity.getEntityType()))
        throw new ServiceError(
            ENTITY_WITH_ID + entityId + " is an instance of a mapping target entity type");

      var valuesJsonNode = jsonUtils.jsonMapper().readTree(values);
      var schema = entity.getEntityTypeVersion().getSchema();
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
      else {
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                entity.getId(),
                entity.getEntityType().getName(),
                EntityLifeCycleEvent.ENTITY_UPDATED,
                NO_PROCESSING));
        emitSourceUpdatedForDependentMappingSources(entity);
      }
      log.info("Updated entity with id {}", entityId);
    } catch (JsonProcessingException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Updates an existing entity's values without enforcing mapping-target restrictions or emitting
   * lifecycle events.
   *
   * <p>Intended for internal system use (e.g. the provisioning task writing back provisioning
   * status to a mapped entity). Validation still uses the entity's pinned schema.
   *
   * @param entityId the ID of the entity to update
   * @param values a JSON string containing the new values for the entity
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void updateValues(String entityId, String values) {
    log.info("Updating values for entity with id {}", entityId);
    try {
      var entity =
          entityRepository
              .findById(entityId)
              .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + entityId + NOT_FOUND));
      var valuesJsonNode = jsonUtils.jsonMapper().readTree(values);
      var schema = entity.getEntityTypeVersion().getSchema();
      SchemaValidationError.validateOrThrow(
          jsonUtils.jsonSchemaFactory().getSchema(schema), valuesJsonNode);
      entity.setValues(valuesJsonNode);
      entityRepository.save(entity);
      log.info("Updated values for entity with id {}", entityId);
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

      if (!entityRelationshipRepository.findBySource(entity).isEmpty()
          || !entityRelationshipRepository.findByTarget(entity).isEmpty())
        throw new ServiceError(
            ENTITY_WITH_ID
                + entityId
                + " has relationships and cannot be deleted; remove all links first");

      deleteAndRecord(entity);
      log.info("Deleted entity with id {}", entityId);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  /**
   * Deletes an entity without the guards {@link #delete} applies, for callers that have already
   * torn down the entity's relationships themselves.
   *
   * @param entityId the ID of the entity to delete
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteInternal(String entityId) {
    log.info("Deleting entity with id {} without checks", entityId);
    try {
      var entity =
          entityRepository
              .findById(entityId)
              .orElseThrow(() -> new NotFoundException(ENTITY_WITH_ID + entityId + NOT_FOUND));
      deleteAndRecord(entity);
      log.info("Deleted entity with id {} without checks", entityId);
    } catch (DataIntegrityViolationException e) {
      throw ServiceError.forDataIntegrity(e);
    }
  }

  private void deleteAndRecord(Entity entity) {
    entityRepository.delete(entity);
    entityLifeCycleEventRepository.save(
        new EntityLifeCycleEvent(
            entity.getId(),
            entity.getEntityType().getName(),
            EntityLifeCycleEvent.ENTITY_DELETED,
            NO_PROCESSING));
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

  /**
   * Rejects the removal of a containment link whose containing side is an aggregate, in whichever
   * direction it is expressed — the two rows are stored as a pair, and removing either takes both.
   */
  private void checkIsNotAggregateContainment(Entity source, RelationType relType, Entity target) {
    if (relType != RelationType.HAS_PART && relType != RelationType.IS_PART_OF) return;
    var whole = relType == RelationType.HAS_PART ? source : target;
    var part = relType == RelationType.HAS_PART ? target : source;
    if (ServiceUtils.implementsTrait(whole.getEntityType(), BuiltInTraits.AGGREGATE))
      throw new ServiceError(
          ENTITY_WITH_ID
              + part.getId()
              + " is a part of the aggregate entity with id "
              + whole.getId()
              + "; unlinking it would leave it unreachable. Delete the aggregate instead.");
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
   * <p>Containment links inside an aggregate cannot be removed: they are what makes a part
   * reachable from its root. Detaching one strands the part — {@link AggregateService#delete} only
   * reaches what still hangs off the root, and {@link #delete} refuses anything that still has a
   * link — so the aggregate can end up undeletable with the detached part left over. Delete the
   * aggregate as a whole instead. (A stranded part can be recovered by linking it back with {@code
   * HAS_PART}, but that is a repair, not a workflow.) Containment between entities that are not
   * aggregates, and dependency links within an aggregate, are unaffected — neither determines
   * reachability.
   *
   * <p>A link a mapping resolves a path reference through cannot be removed either: the mapped
   * entities derived through that path would fail to update from then on — asynchronously, as
   * FAILED lifecycle events, with nothing pointing back at the unlink that caused it. The check is
   * exact, not type-level: each existing mapping instance whose rule names the link's relation type
   * in a path reference has that path re-resolved, and only a traversal that actually walks this
   * link blocks the removal. Delete or re-create the mapping first.
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
    checkIsNotAggregateContainment(source, relType, target);
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
    EntityRelationship inverseRel = null;
    if (relType.hasInverse()) {
      var inverseRelType = relType.inverse();
      inverseRel =
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
    }
    checkIsNotReferredByMapping(directRel, inverseRel);
    entityRelationshipRepository.delete(directRel);
    if (inverseRel != null) entityRelationshipRepository.delete(inverseRel);

    log.info("Unlinked entity with id {} from entity with id {}", sourceId, targetId);
  }

  /**
   * Rejects the removal of a link that some existing mapping resolves a path reference through.
   *
   * <p>For every mapped instance ({@code MAPPED_TO} row) whose rule carries a path reference naming
   * this link's relation type (or its inverse — the traversal may walk either row of the pair), the
   * path is re-resolved from the mapping's source entity with the traversed rows recorded. Rows
   * walked before a resolution dead-ends still count: a path that currently leans on this link is a
   * path this unlink breaks.
   */
  private void checkIsNotReferredByMapping(
      EntityRelationship directRel, EntityRelationship inverseRel) {
    var relTypeName = directRel.getRelationType().name();
    var inverseName = directRel.getRelationType().inverse().name();
    for (var mapping : mappingEntityRelationshipRepository.findAll()) {
      if (mapping.getRelationType() != RelationType.MAPPED_TO) continue;
      var rule = mapping.getMappingEntityTypeRelationship();
      for (var reference : rule.getEntityPathReferences()) {
        var path = reference.referencePath();
        if (!path.contains(relTypeName) && !path.contains(inverseName)) continue;
        var traversed = new ArrayList<EntityRelationship>();
        try {
          entityPathResolver.retrieveEntityByPath(mapping.getSource().getId(), path, traversed);
        } catch (RuntimeException e) {
          // an ambiguous or invalid path stops the traversal; whatever it walked first still counts
        }
        for (var row : traversed) {
          if (!row.getId().equals(directRel.getId())
              && (inverseRel == null || !row.getId().equals(inverseRel.getId()))) continue;
          throw new ServiceError(
              "Cannot remove the "
                  + directRel.getRelationType()
                  + " link between entity with id "
                  + directRel.getSource().getId()
                  + " and entity with id "
                  + directRel.getTarget().getId()
                  + ": the mapping "
                  + rule.getSource().getName()
                  + " MAPPED_TO "
                  + rule.getTarget().getName()
                  + " resolves its path reference '"
                  + reference.alias()
                  + "' ("
                  + path
                  + ") through it, so the mapped entity with id "
                  + mapping.getTarget().getId()
                  + " could no longer be derived. Delete or re-create the mapping first.");
        }
      }
    }
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

  /**
   * Emits {@code ENTITY_SOURCE_UPDATED} events for every mapping source entity that is directly
   * related to the given non-source entity.
   *
   * <p>When an entity that is not itself a mapping source (e.g. a {@code DataProductType} entity)
   * is updated, the mapping values that reference it indirectly — via an {@code
   * entityPathReferences} path in a mapping relationship — must be re-evaluated. This method finds
   * all entities that have a direct entity relationship with the updated entity and, for each one
   * whose type is a mapping source type, emits a {@code SOURCE_UPDATED} lifecycle event so the
   * {@link MappingUpdaterService} will re-run the mapping for that source entity.
   *
   * @param entity the non-source entity that was updated
   */
  private void emitSourceUpdatedForDependentMappingSources(Entity entity) {
    var relatedEntities = new java.util.LinkedHashSet<Entity>();
    entityRelationshipRepository.findBySource(entity).stream()
        .map(EntityRelationship::getTarget)
        .forEach(relatedEntities::add);
    entityRelationshipRepository.findByTarget(entity).stream()
        .map(EntityRelationship::getSource)
        .forEach(relatedEntities::add);
    for (Entity related : relatedEntities) {
      if (related.getId().equals(entity.getId())) continue;
      if (ServiceUtils.isMappingSourceEntityType(
          mappingEntityTypeRelationshipRepository, related.getEntityType())) {
        log.info(
            "Emitting SOURCE_UPDATED for dependent mapping source {} (triggered by update of {})",
            related.getId(),
            entity.getId());
        entityLifeCycleEventRepository.save(
            new EntityLifeCycleEvent(
                related.getId(),
                related.getEntityType().getName(),
                EntityLifeCycleEvent.ENTITY_SOURCE_UPDATED,
                EntityLifeCycleEvent.STATUS_PENDING));
      }
    }
  }
}
