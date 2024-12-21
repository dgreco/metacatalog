package it.witboost.dataplatformshaper.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static it.witboost.dataplatformshaper.common.JsonUtils.mergeSchemas;
import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

@Service
public class EntityTypeService {

    private final EntityTypeRepository entityTypeRepository;

    @SuppressFBWarnings
    public EntityTypeService(EntityTypeRepository entityTypeRepository) {
        this.entityTypeRepository = entityTypeRepository;
    }

    @Transactional(rollbackFor = {SchemaValidationError.class})
    public EntityType create(String name, final String schema, Optional<String> fatherName)
            throws JsonProcessingException, SchemaValidationError {
        if (entityTypeRepository.existsByName(name)) {
            throw new RuntimeException("EntityTtype " + name + " already exists");
        }
        var eitherSchema = stringToJsonSchema(schema);
        if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        var entityType = new EntityType();
        entityType.setName(name);
        entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
        var father = fatherName.flatMap(entityTypeRepository::findByName);
        if (father.isPresent()) {
            entityType.setFather(father.get());
            entityType.setDerivedSchema(generateDerivedSchema(entityType));
        }
        return entityTypeRepository.save(entityType);
    }

    @Transactional
    public Optional<EntityType> read(String name) {
        return entityTypeRepository.findByName(name);
    }

    @Transactional
    public void delete(String name) throws ServiceError {
        var entityType = entityTypeRepository
                .findByName(name)
                .orElseThrow(() -> new ServiceError("EntityType " + name + " not found"));
        entityTypeRepository.delete(entityType);
    }

    @Transactional
    public boolean exists(String name) {
        return entityTypeRepository.existsByName(name);
    }

    private List<JsonNode> loadSchemaInheritanceChain(EntityType entityType) {
        ArrayList<JsonNode> schemaInheritanceChain = new ArrayList<>();
        schemaInheritanceChain.add(entityType.getBaseSchema());
        var maybeFather = entityType.getFather();
        if (maybeFather.isPresent()) {
            schemaInheritanceChain.addAll(loadSchemaInheritanceChain(maybeFather.get()));
            return schemaInheritanceChain;
        } else {
            return schemaInheritanceChain;
        }
    }

    private JsonNode generateDerivedSchema(EntityType entityType) throws SchemaValidationError {
        var schemaInheritanceChain = loadSchemaInheritanceChain(entityType);
        Collections.reverse(schemaInheritanceChain);
        var derivedSchemaJson = mergeSchemas(schemaInheritanceChain);
        if (derivedSchemaJson.isLeft()) throw new SchemaValidationError(derivedSchemaJson.getLeft());
        else return derivedSchemaJson.get();
    }
}
