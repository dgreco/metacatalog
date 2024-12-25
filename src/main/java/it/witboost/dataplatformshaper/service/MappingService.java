package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.witboost.dataplatformshaper.entity.MappingTypeRelationship;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MappingService {

    public final EntityTypeRepository entityTypeRepository;

    public MappingService(EntityTypeRepository entityTypeRepository) {
        this.entityTypeRepository = entityTypeRepository;
    }

    public void create(
            String sourceEntityTypeName,
            String targetEntityTypeName,
            String mappingValues,
            List<MappingTypeRelationship.SourceReference> sourceReferences)
            throws JsonProcessingException {
        var mapping = new MappingTypeRelationship();

        var sourceEntityType =
                entityTypeRepository.findByName(sourceEntityTypeName).get();

        var targetEntityType =
                entityTypeRepository.findByName(targetEntityTypeName).get();

        mapping.setSource(sourceEntityType);
        mapping.setTarget(targetEntityType);
        mapping.setMappingValues(jsonFactory.readTree(mappingValues));
        mapping.setSourceReferences(sourceReferences);
    }
}
