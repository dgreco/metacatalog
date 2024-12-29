package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.*;
import static it.witboost.dataplatformshaper.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.jayway.jsonpath.JsonPath;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import it.witboost.dataplatformshaper.common.WrappedJsonNode;
import it.witboost.dataplatformshaper.entity.*;
import it.witboost.dataplatformshaper.repository.*;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MappingService implements CommonService<MappingEntityTypeRelationship, String> {

    public final EntityRepository entityRepository;

    public final EntityTypeRepository entityTypeRepository;

    public final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

    public final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

    private final EntityRelationshipRepository entityRelationshipRepository;

    public boolean checkLoops(
            String sourceEntityTypeName, Set<String> entityTypeNamesVisited, String targetEntityTypeName)
            throws ServiceError {
        var sourceEntityType = entityTypeRepository
                .findByName(sourceEntityTypeName)
                .orElseThrow(() -> new ServiceError("Entity type " + targetEntityTypeName + " not found"));
        var mappings =
                mappingEntityTypeRelationshipRepository.findMappingEntityTypeRelationshipBySource(sourceEntityType);

        var targetTypes =
                mappings.stream().map(MappingEntityTypeRelationship::getTarget).toList();
        for (var targetType : targetTypes) {
            if (entityTypeNamesVisited.contains(targetEntityTypeName)) return true;
            else {
                entityTypeNamesVisited.add(targetType.getName());
                return checkLoops(targetType.getName(), entityTypeNamesVisited, targetEntityTypeName);
            }
        }
        return entityTypeNamesVisited.contains(targetEntityTypeName);
    }

    public boolean isSourceEntityType(EntityType entityType) {
        return !mappingEntityTypeRelationshipRepository
                .findMappingEntityTypeRelationshipBySource(entityType)
                .isEmpty();
    }

    public boolean isTargetEntityType(EntityType entityType) {
        return !mappingEntityTypeRelationshipRepository
                .findMappingEntityTypeRelationshipByTarget(entityType)
                .isEmpty();
    }

    public Optional<Entity> retrieveEntityByPath(String startEntityId, String pathString) throws ServiceError {

        var pathSegments = pathString.split("/");

        var pathExpressionPattern = Pattern.compile("(?<=\\{)([^}]+)(?=})");

        var relTypePattern = Pattern.compile("^\\w*");

        Entity currentEntity = entityRepository
                .findById(startEntityId)
                .orElseThrow(() -> new ServiceError("Entity with id " + startEntityId + " not found"));
        for (var segment : pathSegments) {
            var pathExpressions = pathExpressionPattern
                    .matcher(segment)
                    .results()
                    .map(MatchResult::group)
                    .toList();
            var relTypes = relTypePattern
                    .matcher(segment)
                    .results()
                    .map(MatchResult::group)
                    .toList();

            if (pathExpressions.size() != 1) throw new ServiceError("Invalid path segment: " + segment);

            if (relTypes.size() != 1) throw new ServiceError("Invalid path segment: " + segment);

            var relTypeStr = relTypes.get(0);

            var enumSet = Arrays.stream(RelationType.values()).map(Enum::name).collect(Collectors.toSet());
            if (!enumSet.contains(relTypeStr)) throw new ServiceError("Invalid path segment: " + segment);

            var relType = RelationType.valueOf(relTypeStr);
            var pathExpression = pathExpressions.get(0).trim();

            List<Entity> relationSources;
            if (relType == MAPPED_TO)
                relationSources =
                        mappingEntityRelationshipRepository.findByTargetAndRelationType(currentEntity, relType).stream()
                                .map(MappingEntityRelationship::getSource)
                                .toList();
            else
                relationSources =
                        entityRelationshipRepository.findByTargetAndRelationType(currentEntity, relType).stream()
                                .map(EntityRelationship::getSource)
                                .toList();

            if (relationSources.isEmpty()) return Optional.empty();

            if (!pathExpression.equalsIgnoreCase("$")) {
                var found = false;
                for (var relationSource : relationSources) {
                    var json = relationSource.getValues().toPrettyString();
                    var dc = JsonPath.using(jsonPathConfiguration).parse(json);
                    var res = (ArrayNode) dc.read(pathExpression);
                    if (res.size() > 1) throw new ServiceError("Ambiguous path expression: " + segment);
                    if (res.size() == 1) {
                        currentEntity = relationSource;
                        found = true;
                        break;
                    }
                }
                if (!found) return Optional.empty();
            } else {
                if (relationSources.size() > 1) throw new ServiceError("Ambiguous path: " + segment);
                currentEntity = relationSources.get(0);
            }
        }

        if (currentEntity.getId().equals(startEntityId)) return Optional.empty();
        else return Optional.of(currentEntity);
    }

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
            private static ExpressionParser parser = new SpelExpressionParser();

            private static JsonNode getMappedValues(JsonNode mappingValues, StandardEvaluationContext context)
                    throws ServiceError {
                var mappedValues = mappingValues.deepCopy();
                try {
                    evaluateMappingValues(mappedValues, context);
                    return mappedValues;
                } catch (RuntimeException e) {
                    throw new ServiceError("Error while evaluating mapping values: " + e.getMessage());
                }
            }

            private static void evaluateMappingValues(JsonNode mappingValues, StandardEvaluationContext context) {
                if (mappingValues.isObject()) {
                    ObjectNode objectNode = (ObjectNode) mappingValues;
                    objectNode.fields().forEachRemaining(entry -> {
                        String fieldName = entry.getKey();
                        JsonNode childNode = entry.getValue();
                        if (childNode.isObject()) evaluateMappingValues(childNode, context);
                        else if (mappingValues.isArray()) {
                            ArrayNode arrayNode = (ArrayNode) mappingValues;
                            for (int i = 0; i < arrayNode.size(); i++) {
                                evaluateMappingValues(arrayNode.get(i), context);
                            }
                        } else {
                            var val = mappingValues.get(fieldName);
                            Expression exp = parser.parseExpression(val.asText());
                            var result = exp.getValue(context);

                            switch (result) {
                                case null -> throw new RuntimeException(
                                        "Error while evaluating expression: " + val.asText());
                                case Boolean b -> ((ObjectNode) mappingValues).set(fieldName, BooleanNode.valueOf(b));
                                case Integer i -> ((ObjectNode) mappingValues).set(fieldName, IntNode.valueOf(i));
                                case Long l -> ((ObjectNode) mappingValues).set(fieldName, LongNode.valueOf(l));
                                case String s -> ((ObjectNode) mappingValues).set(fieldName, TextNode.valueOf(s));
                                case Float f -> ((ObjectNode) mappingValues).set(fieldName, FloatNode.valueOf(f));
                                case Double d -> ((ObjectNode) mappingValues).set(fieldName, DoubleNode.valueOf(d));
                                default -> {
                                    throw new RuntimeException("Error while evaluating expression: " + val.asText());
                                }
                            }
                        }
                    });
                }
            }
        }

        var mappedValues = GenerateValues.getMappedValues(mappingValues, context);

        var res = targetSchema.validate(
                mappedValues.toPrettyString(),
                InputFormat.JSON,
                executionContext -> executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
        if (res.isEmpty()) return mappedValues;
        else {
            List<String> errors = new ArrayList<>(
                    res.stream().map(ValidationMessage::toString).toList());
            throw new SchemaValidationError(errors);
        }
    }

    public MappingService(
            EntityRepository entityRepository,
            EntityTypeRepository entityTypeRepository,
            MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
            MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
            EntityRelationshipRepository entityRelationshipRepository) {
        this.entityRepository = entityRepository;
        this.entityTypeRepository = entityTypeRepository;
        this.mappingEntityTypeRelationshipRepository = mappingEntityTypeRelationshipRepository;
        this.mappingEntityRelationshipRepository = mappingEntityRelationshipRepository;
        this.entityRelationshipRepository = entityRelationshipRepository;
    }

    public MappingEntityTypeRelationship create(
            String sourceEntityTypeName,
            String targetEntityTypeName,
            String mappingValues,
            List<MappingEntityTypeRelationship.EntityPathReference> entityPathReferences)
            throws ServiceError {
        try {
            if (checkLoops(targetEntityTypeName, new HashSet<>(), sourceEntityTypeName))
                throw new ServiceError("Loops are not allowed");

            var mapping = new MappingEntityTypeRelationship();

            var sourceEntityType = entityTypeRepository
                    .findByName(sourceEntityTypeName)
                    .orElseThrow(() -> new ServiceError("EntityType " + sourceEntityTypeName + " does not exist"));

            var targetEntityType = entityTypeRepository
                    .findByName(targetEntityTypeName)
                    .orElseThrow(() -> new ServiceError("EntityType " + targetEntityTypeName + " does not exist"));

            mapping.setSource(sourceEntityType);
            mapping.setRelationType(MAPPED_TO);
            mapping.setTarget(targetEntityType);
            mapping.setMappingValues(jsonFactory.readTree(mappingValues));
            var validatingSchemaEither =
                    convertToMappingSchema(jsonSchemaFactory.getSchema(targetEntityType.getSchema()));
            if (validatingSchemaEither.isLeft()) throw new SchemaValidationError(validatingSchemaEither.getLeft());
            var mappingValuesNode = jsonFactory.readTree(mappingValues);
            var validatingSchema = validatingSchemaEither.get();
            var validationErrors = validatingSchema.validate(mappingValuesNode);
            if (!validationErrors.isEmpty()) {
                var errorMessages = validationErrors.stream()
                        .map(ValidationMessage::getMessage)
                        .toList();
                throw new SchemaValidationError(errorMessages);
            }
            mapping.setMappingValues(jsonFactory.readTree(mappingValues));
            mapping.setEntityPathReferences(entityPathReferences);
            return mappingEntityTypeRelationshipRepository.save(mapping);
        } catch (JsonProcessingException e) {
            throw new ServiceError(e.getMessage());
        }
    }

    @Transactional
    public void delete(String mappingId) throws ServiceError {
        mappingEntityTypeRelationshipRepository.deleteById(mappingId);
    }

    @Transactional
    public MappingEntityTypeRelationship read(String mappingId) throws ServiceError {
        return mappingEntityTypeRelationshipRepository
                .findById(mappingId)
                .orElseThrow(() -> new ServiceError("Mapping " + mappingId + " does not exist"));
    }

    @Transactional
    public boolean exists(String mappingId) throws ServiceError {
        return mappingEntityTypeRelationshipRepository.existsById(mappingId);
    }

    @Transactional
    public void createMappedEntities(String sourceEntityId) throws ServiceError {
        var sourceEntity = entityRepository
                .findById(sourceEntityId)
                .orElseThrow(() -> new ServiceError("Entity " + sourceEntityId + " does not exist"));
        var sourceEntityType = sourceEntity.getEntityType();
        // TODO check if sourceEntityType is a valid entity type
        var mappingRelationships =
                mappingEntityTypeRelationshipRepository.findMappingEntityTypeRelationshipBySource(sourceEntityType);
        for (MappingEntityTypeRelationship mappingRelationship : mappingRelationships) {
            var targetEntityType = mappingRelationship.getTarget();
            var additionalEntitiesValues = new HashMap<String, JsonNode>();
            for (MappingEntityTypeRelationship.EntityPathReference sourceReference :
                    mappingRelationship.getEntityPathReferences()) {
                var as = sourceReference.alias();
                var jn = retrieveEntityByPath(sourceEntityId, sourceReference.referencePath())
                        .orElseThrow(() -> new ServiceError(
                                "Wrong reference path " + sourceReference.referencePath() + " for entity"))
                        .getValues();
                additionalEntitiesValues.put(as, jn);
                var mappedValues = generateMappedValues(
                        sourceEntity.getValues(),
                        additionalEntitiesValues,
                        mappingRelationship.getMappingValues(),
                        jsonSchemaFactory.getSchema(targetEntityType.getSchema()));
                var mappedEntity = new Entity();
                mappedEntity.setEntityType(targetEntityType);
                mappedEntity.setValues(mappedValues);
                entityRepository.save(mappedEntity);
                var mappingEntityRelationship = new MappingEntityRelationship();
                mappingEntityRelationship.setSource(sourceEntity);
                mappingEntityRelationship.setTarget(mappedEntity);
                mappingEntityRelationship.setMappingValues(mappingRelationship.getMappingValues());
                mappingEntityRelationship.setEntityPathReferences(mappingRelationship.getEntityPathReferences());
                mappingEntityRelationship.setRelationType(MAPPED_TO);
                mappingEntityRelationshipRepository.save(mappingEntityRelationship);
                createMappedEntities(mappedEntity.getId());
            }
        }
    }
}
