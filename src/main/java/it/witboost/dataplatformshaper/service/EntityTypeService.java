package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;
import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        father.ifPresent(f -> {
            entityType.setFather(f);
            entityType.setDerivedSchema(generatedDerivedSchema(entityType));
        });
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

    private JsonNode generatedDerivedSchema(EntityType entityType) {
        var derivedSchemaJson = jsonFactory.createObjectNode();
        derivedSchemaJson.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        var schemaInheritanceChain = loadSchemaInheritanceChain(entityType);
        Collections.reverse(schemaInheritanceChain);
        derivedSchemaJson.put(
                "$id",
                "derived_"
                        + schemaInheritanceChain
                                .get(schemaInheritanceChain.size() - 1)
                                .get("$id")
                                .asText());
        var allOf = jsonFactory.createArrayNode().addAll(schemaInheritanceChain);
        derivedSchemaJson.set("allOf", allOf);
        var propertiesNode = jsonFactory.createObjectNode();
        schemaInheritanceChain.stream()
                .flatMap(n -> {
                    Iterable<String> iterable = () -> n.get("properties").fieldNames();
                    return StreamSupport.stream(iterable.spliterator(), false).toList().stream();
                })
                .toList()
                .forEach(prop -> propertiesNode.put(prop, true));
        derivedSchemaJson.set("properties", propertiesNode);
        derivedSchemaJson.put("additionalProperties", false);
        return derivedSchemaJson;
    }
}
