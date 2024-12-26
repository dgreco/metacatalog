package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.*;
import static it.witboost.dataplatformshaper.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.ValidationMessage;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.MappingEntityTypeRelationship;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.MappingEntityTypeRelationshipRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MappingService implements CommonService<MappingEntityTypeRelationship, String> {

    public final EntityTypeRepository entityTypeRepository;

    public final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

    public boolean checkLoops(
            String sourceEntityTypeName, Set<String> entityTypeNamesVisited, String targetEntityTypeName) {
        var sourceEntityType =
                entityTypeRepository.findByName(sourceEntityTypeName).get();
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

    public MappingService(
            EntityTypeRepository entityTypeRepository,
            MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository) {
        this.entityTypeRepository = entityTypeRepository;
        this.mappingEntityTypeRelationshipRepository = mappingEntityTypeRelationshipRepository;
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
