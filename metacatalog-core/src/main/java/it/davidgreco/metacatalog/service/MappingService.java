package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.*;
import static it.davidgreco.metacatalog.entity.RelationType.IS_MAPPED_BY;
import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.jayway.jsonpath.JsonPath;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import it.davidgreco.metacatalog.CoreConfigProperties;
import it.davidgreco.metacatalog.common.WrappedJsonNode;
import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service class for managing {@link MappingEntityTypeRelationship} entities and automatic
 * management of mapped entities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MappingService implements CommonService<MappingEntityTypeRelationship, String> {

  private static final String DOES_NOT_EXIST = " does not exist";

  private static final String INVALID_PATH_SEGMENT = "Invalid path segment: ";

  private static final String ENTITY = "Entity ";

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final EntityRepository entityRepository;

  private final EntityTypeRepository entityTypeRepository;

  private final EntityTypeVersionRepository entityTypeVersionRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  private final PlatformTransactionManager transactionManager;

  private final CoreConfigProperties coreConfigProperties;

  @Getter(lazy = true)
  private final TransactionTemplate transactionTemplate =
      new TransactionTemplate(transactionManager);

  /**
   * Creates a new MappingEntityTypeRelationship between the specified source and target entity
   * types.
   *
   * @param sourceEntityTypeName the name of the source entity type
   * @param targetEntityTypeName the name of the target entity type
   * @param mappingValues a JSON string representing the mapping values
   * @param entityPathReferences a list of entity path references used in the mapping
   * @return the created MappingEntityTypeRelationship
   * @throws ServiceError if loops are detected, entity types are not found, validation fails, or a
   *     JSON processing error occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public MappingEntityTypeRelationship create(
      String sourceEntityTypeName,
      String targetEntityTypeName,
      String mappingValues,
      List<MappingEntityTypeRelationship.EntityPathReference> entityPathReferences)
      throws ServiceError {
    log.info(
        "Creating MappingEntityTypeRelationship from {} to {}",
        sourceEntityTypeName,
        targetEntityTypeName);
    try {
      if (CommonService.checkMappingLoops(
          entityTypeRepository,
          mappingEntityTypeRelationshipRepository,
          targetEntityTypeName,
          new HashSet<>(),
          sourceEntityTypeName)) throw new ServiceError("Loops are not allowed");

      var mapping = new MappingEntityTypeRelationship();

      var sourceEntityType =
          entityTypeRepository
              .findByName(sourceEntityTypeName)
              .orElseThrow(
                  () -> new ServiceError("EntityType " + sourceEntityTypeName + DOES_NOT_EXIST));

      var targetEntityType =
          entityTypeRepository
              .findByName(targetEntityTypeName)
              .orElseThrow(
                  () -> new ServiceError("EntityType " + targetEntityTypeName + DOES_NOT_EXIST));

      mapping.setSource(sourceEntityType);
      mapping.setRelationType(MAPPED_TO);
      mapping.setTarget(targetEntityType);
      mapping.setMappingValues(jsonFactory.readTree(mappingValues));
      var validatingSchemaEither =
          convertToMappingSchema(jsonSchemaFactory.getSchema(targetEntityType.getSchema()));
      if (validatingSchemaEither.isLeft())
        throw new SchemaValidationError(validatingSchemaEither.getLeft());
      var mappingValuesNode = jsonFactory.readTree(mappingValues);
      var validatingSchema = validatingSchemaEither.get();
      var validationErrors = validatingSchema.validate(mappingValuesNode);
      if (!validationErrors.isEmpty()) {
        var errorMessages = validationErrors.stream().map(ValidationMessage::getMessage).toList();
        throw new SchemaValidationError(errorMessages);
      }
      mapping.setMappingValues(jsonFactory.readTree(mappingValues));
      mapping.setEntityPathReferences(entityPathReferences);
      return mappingEntityTypeRelationshipRepository.save(mapping);
    } catch (JsonProcessingException e) {
      throw new ServiceError(e.getMessage());
    } finally {
      log.info(
          "Created MappingEntityTypeRelationship from {} to {}",
          sourceEntityTypeName,
          targetEntityTypeName);
    }
  }

  /**
   * Deletes a mapping entity type relationship by its ID.
   *
   * @param mappingId the ID of the mapping entity type relationship to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String mappingId) {
    log.info("Deleting MappingEntityTypeRelationship with id: {}", mappingId);
    try {
      mappingEntityTypeRelationshipRepository.deleteById(mappingId);
    } finally {
      log.info("Deleted MappingEntityTypeRelationship with id: {}", mappingId);
    }
  }

  /**
   * Reads a mapping entity type relationship by its ID.
   *
   * @param mappingId the ID of the mapping entity type relationship to read
   * @return the MappingEntityTypeRelationship with the given ID
   * @throws ServiceError if the mapping entity type relationship is not found
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public MappingEntityTypeRelationship read(String mappingId) throws ServiceError {
    log.info("Reading MappingEntityTypeRelationship with id: {}", mappingId);
    try {
      return mappingEntityTypeRelationshipRepository
          .findById(mappingId)
          .orElseThrow(() -> new ServiceError("Mapping " + mappingId + DOES_NOT_EXIST));
    } finally {
      log.info("Read MappingEntityTypeRelationship with id: {}", mappingId);
    }
  }

  /**
   * Checks if a mapping entity type relationship with the given ID exists.
   *
   * @param mappingId the ID of the mapping entity type relationship to check
   * @return true if the mapping entity type relationship with the given ID exists, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean exists(String mappingId) {
    log.info("Checking if MappingEntityTypeRelationship with id: {}", mappingId);
    try {
      return mappingEntityTypeRelationshipRepository.existsById(mappingId);
    } finally {
      log.info("Checked if MappingEntityTypeRelationship with id: {}", mappingId);
    }
  }

  /**
   * Retrieves all mapping entity type relationships from the repository.
   *
   * @return a list of all mapping entity type relationships
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<MappingEntityTypeRelationship> list() {
    log.info("Listing all MappingEntityTypeRelationships");
    try {
      return mappingEntityTypeRelationshipRepository.findAll();
    } finally {
      log.info("Listed all MappingEntityTypeRelationships");
    }
  }

  /**
   * Creates mapped entities for the given event, and then marks the event as processed.
   *
   * @param event the event to process
   * @throws ServiceError if an error occurs while processing the event
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      rollbackFor = {ServiceError.class})
  public void createMappedEntities(EntityLifeCycleEvent event) throws ServiceError {
    log.info("Creating mapped entities for event with ID: {}", event.getId());
    try {
      createMappedEntities(event.getEntityId());
      event.setEventStatus("PROCESSED");
      event.setProcessTime(new Timestamp(System.currentTimeMillis()));
      entityLifeCycleEventRepository.save(event);
    } finally {
      log.info("Created mapped entities for event with ID: {}", event.getId());
    }
  }

  /**
   * Creates mapped entities for the given source entity ID.
   *
   * @param sourceEntityId the ID of the source entity to process
   * @throws ServiceError if an error occurs while processing the event
   */
  public void createMappedEntities(String sourceEntityId) throws ServiceError {

    class CreateMappedEntities {
      private final Set<String> visited = new HashSet<>();

      private void createMappedEntities(String sourceEntityId) throws ServiceError {
        if (!visited.add(sourceEntityId)) return;
        RetryTemplate createRetryTemplate =
            RetryTemplate.builder()
                .maxAttempts(coreConfigProperties.entityPathResolutionMaxAttempts())
                .fixedBackoff(500)
                .retryOn(ServiceRuntimeError.class)
                .build();

        var sourceEntity =
            entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new ServiceError(ENTITY + sourceEntityId + DOES_NOT_EXIST));
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
                          retrieveEntityByPath(sourceEntityId, sourceReference.referencePath())
                              .orElseThrow(() -> new ServiceRuntimeError("Entity not found")));
              additionalEntitiesValues.put(as, jn.getValues());
            }
            var mappedValues =
                generateMappedValues(
                    sourceEntity.getValues(),
                    additionalEntitiesValues,
                    mappingRelationship.getMappingValues(),
                    jsonSchemaFactory.getSchema(targetEntityType.getSchema()));
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
                    "CREATED",
                    "NO_PROCESSING"));
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
    try {
      getTransactionTemplate()
          .executeWithoutResult(
              _ -> {
                try {
                  var sourceEntity =
                      entityRepository
                          .findById(sourceEntityId)
                          .orElseThrow(
                              () -> new ServiceError(ENTITY + sourceEntityId + DOES_NOT_EXIST));
                  var sourceEntityType = sourceEntity.getEntityType();
                  if (isTargetEntityType(sourceEntityType))
                    throw new ServiceError(
                        "Source entity type "
                            + sourceEntityType.getName()
                            + " is a target entity type");
                  new CreateMappedEntities().createMappedEntities(sourceEntityId);
                } catch (ServiceError e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    } finally {
      log.info("Created mapped entities for source entity with ID: {}", sourceEntityId);
    }
  }

  /**
   * Updates mapped entities for the given event.
   *
   * @param event the event to process
   * @throws ServiceError if an error occurs while processing the event
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      rollbackFor = {ServiceError.class})
  public void updateMappedEntities(EntityLifeCycleEvent event) throws ServiceError {
    log.info("Updating mapped entities for event with ID: {}", event.getId());
    try {
      updateMappedEntities(event.getEntityId());
      event.setEventStatus("PROCESSED");
      event.setProcessTime(new Timestamp(System.currentTimeMillis()));
      entityLifeCycleEventRepository.save(event);
    } finally {
      log.info("Updated mapped entities for event with ID: {}", event.getId());
    }
  }

  /**
   * Updates mapped entities for the given source entity ID.
   *
   * @param sourceEntityId the ID of the source entity to process
   * @throws ServiceError if an error occurs while processing the event
   */
  public void updateMappedEntities(String sourceEntityId) throws ServiceError {

    class UpdateMappedEntities {
      private final Set<String> visited = new HashSet<>();

      private void updateMappedEntities(String sourceEntityId) throws ServiceError {
        if (!visited.add(sourceEntityId)) return;
        RetryTemplate updateRetryTemplate =
            RetryTemplate.builder()
                .maxAttempts(coreConfigProperties.entityPathResolutionMaxAttempts())
                .fixedBackoff(500)
                .retryOn(ServiceRuntimeError.class)
                .build();

        var sourceEntity =
            entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new ServiceError(ENTITY + sourceEntityId + DOES_NOT_EXIST));
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
                        retrieveEntityByPath(sourceEntityId, sourceReference.referencePath())
                            .orElseThrow(() -> new ServiceRuntimeError("Entity not found")));
            additionalEntitiesValues.put(as, jn.getValues());
          }
          var mappedEntity = entityMappingRelationship.getTarget();
          var pinnedTargetVersion = mappedEntity.getEntityTypeVersion();
          var targetSchema =
              pinnedTargetVersion != null
                  ? pinnedTargetVersion.getSchema()
                  : mappingTypeRelationship.getTarget().getSchema();
          var mappedValues =
              generateMappedValues(
                  sourceEntity.getValues(),
                  additionalEntitiesValues,
                  mappingTypeRelationship.getMappingValues(),
                  jsonSchemaFactory.getSchema(targetSchema));
          mappedEntity.setValues(mappedValues);
          entityRepository.save(mappedEntity);
          entityLifeCycleEventRepository.save(
              new EntityLifeCycleEvent(
                  mappedEntity.getId(),
                  mappedEntity.getEntityType().getName(),
                  "UPDATED",
                  "NO_PROCESSING"));
          updateMappedEntities(mappedEntity.getId());
        }
      }
    }

    log.info("Updating mapped entities for source entity with ID: {}", sourceEntityId);
    try {
      getTransactionTemplate()
          .executeWithoutResult(
              _ -> {
                try {
                  var sourceEntity =
                      entityRepository
                          .findById(sourceEntityId)
                          .orElseThrow(
                              () -> new ServiceError(ENTITY + sourceEntityId + DOES_NOT_EXIST));
                  var sourceEntityType = sourceEntity.getEntityType();
                  if (isTargetEntityType(sourceEntityType))
                    throw new ServiceError(
                        "Source entity type "
                            + sourceEntityType.getName()
                            + " is a target entity type");
                  new UpdateMappedEntities().updateMappedEntities(sourceEntityId);
                } catch (ServiceError e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    } finally {
      log.info("Updated mapped entities for source entity with ID: {}", sourceEntityId);
    }
  }

  /**
   * Deletes all entities mapped from the given source entity.
   *
   * <p>This method only deletes entities that are instances of neither source nor target entity
   * types.
   *
   * @param sourceEntityId the ID of the source entity
   * @throws ServiceError if the entity with the given ID does not exist, is an instance of a source
   *     or target entity type, or a data integrity violation occurs
   */
  public void deleteMappedEntities(String sourceEntityId) throws ServiceError {

    class DeleteMappedEntities {
      private void retrieveMappedEntities(String sourceEntityId, Deque<String> retrievedEntitiesIds)
          throws ServiceError {
        var sourceEntity =
            entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new ServiceError(ENTITY + sourceEntityId + DOES_NOT_EXIST));
        var entityMappingRelationships =
            mappingEntityRelationshipRepository.findBySourceAndRelationType(
                sourceEntity, MAPPED_TO);
        for (MappingEntityRelationship entityMappingRelationship : entityMappingRelationships) {
          var mappedEntity = entityMappingRelationship.getTarget();
          retrievedEntitiesIds.push(mappedEntity.getId());
          mappingEntityRelationshipRepository.delete(entityMappingRelationship);
          mappingEntityRelationshipRepository
              .findBySourceAndRelationTypeAndTarget(
                  entityMappingRelationship.getSource(),
                  MAPPED_TO,
                  entityMappingRelationship.getTarget())
              .ifPresent(mappingEntityRelationshipRepository::delete);
          mappingEntityRelationshipRepository
              .findBySourceAndRelationTypeAndTarget(
                  entityMappingRelationship.getTarget(),
                  MAPPED_TO.inverse(),
                  entityMappingRelationship.getSource())
              .ifPresent(mappingEntityRelationshipRepository::delete);
          retrieveMappedEntities(mappedEntity.getId(), retrievedEntitiesIds);
        }
      }

      private void deleteMappedEntities(String sourceEntityId) throws ServiceError {
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
    try {
      getTransactionTemplate()
          .executeWithoutResult(
              _ -> {
                try {
                  var sourceEntity =
                      entityRepository
                          .findById(sourceEntityId)
                          .orElseThrow(
                              () -> new ServiceError(ENTITY + sourceEntityId + DOES_NOT_EXIST));
                  var sourceEntityType = sourceEntity.getEntityType();
                  if (isTargetEntityType(sourceEntityType))
                    throw new ServiceError(
                        "Source entity type "
                            + sourceEntityType.getName()
                            + " is a target entity type");
                  new DeleteMappedEntities().deleteMappedEntities(sourceEntityId);
                } catch (ServiceError e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    } finally {
      log.info("Deleted mapped entities for source entity with ID: {}", sourceEntityId);
    }
  }

  /**
   * Checks if the given entity type is a source entity type in any mapping relationship.
   *
   * @param entityType the entity type to check
   * @return true if the entity type is a source in at least one mapping, false otherwise
   */
  boolean isSourceEntityType(EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipBySource(entityType)
        .isEmpty();
  }

  /**
   * Checks if the given entity type is a target entity type in any mapping relationship.
   *
   * @param entityType the entity type to check
   * @return true if the entity type is a target in at least one mapping, false otherwise
   */
  boolean isTargetEntityType(EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipByTarget(entityType)
        .isEmpty();
  }

  /**
   * Retrieves an entity by traversing a path from a starting entity.
   *
   * <p>The path is specified as a string of segments separated by slashes. Each segment consists of
   * a relation type followed by a JSON path expression in curly braces, e.g.,
   * "HAS_PART{$.name=='foo'}/DEPENDS_ON{$}".
   *
   * @param startEntityId the ID of the starting entity
   * @param pathString the path string to traverse
   * @return an Optional containing the entity at the end of the path, or empty if not found
   * @throws ServiceError if the starting entity is not found or the path is invalid
   */
  public Optional<Entity> retrieveEntityByPath(String startEntityId, String pathString)
      throws ServiceError {

    var pathSegments = pathString.split("/");

    var pathExpressionPattern = Pattern.compile("(?<=\\{)([^}]+)(?=})");

    var relTypePattern = Pattern.compile("^\\w*");

    Entity currentEntity =
        entityRepository
            .findById(startEntityId)
            .orElseThrow(() -> new ServiceError("Entity with id " + startEntityId + " not found"));
    for (var segment : pathSegments) {
      var pathExpressions =
          pathExpressionPattern.matcher(segment).results().map(MatchResult::group).toList();
      var relTypes = relTypePattern.matcher(segment).results().map(MatchResult::group).toList();

      if (pathExpressions.size() != 1) throw new ServiceError(INVALID_PATH_SEGMENT + segment);

      if (relTypes.size() != 1) throw new ServiceError(INVALID_PATH_SEGMENT + segment);

      var relTypeStr = relTypes.getFirst();

      var enumSet =
          Arrays.stream(RelationType.values()).map(Enum::name).collect(Collectors.toSet());
      if (!enumSet.contains(relTypeStr)) throw new ServiceError(INVALID_PATH_SEGMENT + segment);

      var relType = RelationType.valueOf(relTypeStr);
      var pathExpression = pathExpressions.getFirst().trim();

      List<Entity> relationSources =
          switch (relType) {
            case IS_MAPPED_BY, MAPPED_TO ->
                mappingEntityRelationshipRepository
                    .findByTargetAndRelationType(currentEntity, relType.inverse())
                    .stream()
                    .map(MappingEntityRelationship::getSource)
                    .toList();
            default ->
                entityRelationshipRepository
                    .findByTargetAndRelationType(currentEntity, relType)
                    .stream()
                    .map(EntityRelationship::getSource)
                    .toList();
          };

      if (relationSources.isEmpty()) return Optional.empty();

      if (!pathExpression.equalsIgnoreCase("$")) {
        var found = false;
        for (var relationSource : relationSources) {
          var json = relationSource.getValues().toPrettyString();
          var dc = JsonPath.using(jsonPathConfiguration).parse(json);
          Object res = dc.read(pathExpression);
          int matchCount;
          if (res instanceof ArrayNode arr) {
            matchCount = arr.size();
          } else if (res == null
              || (res instanceof JsonNode node && (node.isMissingNode() || node.isNull()))) {
            matchCount = 0;
          } else {
            matchCount = 1;
          }
          if (matchCount > 1) throw new ServiceError("Ambiguous path expression: " + segment);
          if (matchCount == 1) {
            currentEntity = relationSource;
            found = true;
            break;
          }
        }
        if (!found) return Optional.empty();
      } else {
        if (relationSources.size() > 1) throw new ServiceError("Ambiguous path: " + segment);
        currentEntity = relationSources.getFirst();
      }
    }

    if (currentEntity.getId().equals(startEntityId)) return Optional.empty();
    else return Optional.of(currentEntity);
  }

  /**
   * Generates mapped values by evaluating Spring Expression Language (SpEL) expressions in the
   * mapping values.
   *
   * <p>The mapping values can reference source entity values using "#source" and additional entity
   * values using their aliases (e.g., "#alias"). SpEL expressions are evaluated and the resulting
   * values are validated against the target schema.
   *
   * @param sourceValues the JSON values of the source entity
   * @param externalValues a map of alias to JSON values for additional entities referenced in the
   *     mapping
   * @param mappingValues the JSON mapping definition containing SpEL expressions
   * @param targetSchema the JSON schema to validate the generated values against
   * @return the generated JSON values
   * @throws ServiceError if expression evaluation fails or schema validation fails
   */
  public static JsonNode generateMappedValues(
      JsonNode sourceValues,
      Map<String, JsonNode> externalValues,
      JsonNode mappingValues,
      JsonSchema targetSchema)
      throws ServiceError {

    StandardEvaluationContext context = new StandardEvaluationContext();
    context.setVariable("source", new WrappedJsonNode(sourceValues));
    externalValues.forEach((k, v) -> context.setVariable(k, new WrappedJsonNode(v)));

    class GenerateValues {

      private GenerateValues() {}

      private static final ExpressionParser parser = new SpelExpressionParser();

      private static JsonNode getMappedValues(
          JsonNode mappingValues, StandardEvaluationContext context) throws ServiceError {
        var mappedValues = mappingValues.deepCopy();
        try {
          evaluateMappingValues(mappedValues, context);
          return mappedValues;
        } catch (ServiceRuntimeError e) {
          throw new ServiceError("Error while evaluating mapping values: " + e.getMessage());
        }
      }

      private static void evaluateMappingValues(
          JsonNode mappingValues, StandardEvaluationContext context) {
        if (mappingValues.isObject()) {
          ObjectNode objectNode = (ObjectNode) mappingValues;
          objectNode
              .properties()
              .forEach(
                  entry -> {
                    JsonNode childNode = entry.getValue();
                    if (childNode.isContainerNode()) {
                      evaluateMappingValues(childNode, context);
                    } else {
                      objectNode.set(entry.getKey(), evaluateLeaf(childNode, context));
                    }
                  });
        } else if (mappingValues.isArray()) {
          ArrayNode arrayNode = (ArrayNode) mappingValues;
          for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode childNode = arrayNode.get(i);
            if (childNode.isContainerNode()) {
              evaluateMappingValues(childNode, context);
            } else {
              arrayNode.set(i, evaluateLeaf(childNode, context));
            }
          }
        }
      }

      private static JsonNode evaluateLeaf(JsonNode leaf, StandardEvaluationContext context) {
        Expression exp = parser.parseExpression(leaf.asText());
        var result = exp.getValue(context);
        return switch (result) {
          case Boolean b -> BooleanNode.valueOf(b);
          case Integer i -> IntNode.valueOf(i);
          case Long l -> LongNode.valueOf(l);
          case String s -> TextNode.valueOf(s);
          case Float f -> FloatNode.valueOf(f);
          case Double d -> DoubleNode.valueOf(d);
          case null, default ->
              throw new ServiceRuntimeError("Error while evaluating expression: " + leaf.asText());
        };
      }
    }

    var mappedValues = GenerateValues.getMappedValues(mappingValues, context);

    var res =
        targetSchema.validate(
            mappedValues.toPrettyString(),
            InputFormat.JSON,
            executionContext ->
                executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
    if (res.isEmpty()) return mappedValues;
    else {
      List<String> errors = new ArrayList<>(res.stream().map(ValidationMessage::toString).toList());
      throw new SchemaValidationError(errors);
    }
  }
}
