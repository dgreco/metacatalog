package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

    public EntityTypeService(EntityTypeRepository entityTypeRepository) {
        this.entityTypeRepository = entityTypeRepository;
    }

    @Transactional
    public EntityType create(EntityType entityType) {
        return entityTypeRepository.save(entityType);
    }

    @Transactional(rollbackFor = {JsonProcessingException.class})
    public EntityType create(String name, final String schema, Optional<String> fatherName)
            throws JsonProcessingException {
        JsonNode schemaNode = jsonFactory.readTree(schema);
        var entityType = new EntityType();
        entityType.setName(name);
        entityType.setSchema(schemaNode);
        var father = fatherName.flatMap(entityTypeRepository::findByName);
        father.ifPresent(entityType::setFather);
        return entityTypeRepository.save(entityType);
    }

    @Transactional(rollbackFor = Throwable.class)
    public Optional<EntityType> read(String name) {
        return entityTypeRepository.findByName(name);
    }

    @Transactional
    protected List<JsonNode> loadSchemaInheritanceChain(EntityType entityType) {
        ArrayList<JsonNode> schemaInheritanceChain = new ArrayList<>();
        schemaInheritanceChain.add(entityType.getSchema());
        var maybeFather = entityType.getFather();
        if (maybeFather.isPresent()) {
            schemaInheritanceChain.addAll(loadSchemaInheritanceChain(maybeFather.get()));
            return schemaInheritanceChain;
        } else {
            return schemaInheritanceChain;
        }
    }

    @Transactional
    public JsonNode generatedDerivedSchema(EntityType entityType) {
        var derivedSchemaJson = jsonFactory.createObjectNode();
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
