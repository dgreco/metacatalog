package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.*;
import static it.witboost.dataplatformshaper.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.networknt.schema.ValidationMessage;
import it.witboost.dataplatformshaper.entity.*;
import it.witboost.dataplatformshaper.repository.EntityRelationshipRepository;
import it.witboost.dataplatformshaper.repository.EntityRepository;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.MappingEntityTypeRelationshipRepository;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MappingService implements CommonService<MappingEntityTypeRelationship, String> {

    public final EntityRepository entityRepository;

    public final EntityTypeRepository entityTypeRepository;

    public final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;
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
        var jsonPathConfiguration =
                Configuration.builder().jsonProvider(new JacksonJsonProvider()).build();

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

            var relationSources =
                    entityRelationshipRepository.findByTargetAndRelationType(currentEntity, relType).stream()
                            .map(EntityRelationship::getSource)
                            .toList();

            if (relationSources.isEmpty()) return Optional.empty();

            if (!pathExpression.equalsIgnoreCase("$")) {
                var found = false;
                for (var relationSource : relationSources) {
                    var json = relationSource.getValues().toPrettyString();
                    var dc = JsonPath.using(jsonPathConfiguration).parse(json);
                    var res = (LinkedList) dc.read(pathExpression);
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

    public MappingService(
            EntityRepository entityRepository,
            EntityTypeRepository entityTypeRepository,
            MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
            EntityRelationshipRepository entityRelationshipRepository) {
        this.entityRepository = entityRepository;
        this.entityTypeRepository = entityTypeRepository;
        this.mappingEntityTypeRelationshipRepository = mappingEntityTypeRelationshipRepository;
        this.entityRelationshipRepository = entityRelationshipRepository;
    }

    public MappingEntityTypeRelationship create(
            String sourceEntityTypeName,
            String targetEntityTypeName,
            String mappingValues,
            List<MappingEntityTypeRelationship.SourceReference> sourceReferences)
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
            var validationErrors = validatingSchemaEither.get().validate(mappingValuesNode);
            if (!validationErrors.isEmpty()) {
                var errorMessages = validationErrors.stream()
                        .map(ValidationMessage::getMessage)
                        .toList();
                throw new SchemaValidationError(errorMessages);
            }
            mapping.setMappingValues(jsonFactory.readTree(mappingValues));
            mapping.setSourceReferences(sourceReferences);
            return mappingEntityTypeRelationshipRepository.save(mapping);
        } catch (JsonProcessingException e) {
            throw new ServiceError(e.getMessage());
        }
    }

    public void delete(String mappingId) throws ServiceError {
        mappingEntityTypeRelationshipRepository.deleteById(mappingId);
    }

    public MappingEntityTypeRelationship read(String mappingId) throws ServiceError {
        return mappingEntityTypeRelationshipRepository
                .findById(mappingId)
                .orElseThrow(() -> new ServiceError("Mapping " + mappingId + " does not exist"));
    }

    public boolean exists(String mappingId) throws ServiceError {
        return mappingEntityTypeRelationshipRepository.existsById(mappingId);
    }
}
