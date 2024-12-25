package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;
import static it.witboost.dataplatformshaper.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.witboost.dataplatformshaper.entity.MappingEntityTypeRelationship;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.MappingEntityTypeRelationshipRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MappingService {

    public final EntityTypeRepository entityTypeRepository;

    public final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

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
            throws JsonProcessingException {
        var mapping = new MappingEntityTypeRelationship();

        var sourceEntityType =
                entityTypeRepository.findByName(sourceEntityTypeName).get();

        var targetEntityType =
                entityTypeRepository.findByName(targetEntityTypeName).get();

        mapping.setSource(sourceEntityType);
        mapping.setRelationType(MAPPED_TO);
        mapping.setTarget(targetEntityType);
        mapping.setMappingValues(jsonFactory.readTree(mappingValues));
        mapping.setSourceReferences(sourceReferences);
        return mappingEntityTypeRelationshipRepository.save(mapping);
    }
}
