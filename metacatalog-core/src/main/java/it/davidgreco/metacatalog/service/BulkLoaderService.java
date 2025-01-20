package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.yamlFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.RelationType;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BulkLoaderService {

    private final TraitService traitService;

    private final EntityTypeService entityTypeService;

    private final EntityService entityService;

    private JsonNode getNamedNode(JsonNode node, String name) throws ServiceError {
        if (node.has(name)) return node.get(name);
        throw new ServiceError("Node " + name + " not found");
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void bulkCreation(InputStream is) throws Exception {
        try {
            var yamlParser = yamlFactory.createParser(is);

            List<ObjectNode> docs = yamlFactory
                    .readValues(yamlParser, new TypeReference<ObjectNode>() {})
                    .readAll();

            // Traits creation
            docs.stream().filter(doc -> doc.has("Traits")).findFirst().ifPresent(jsonTraits -> {
                if (!(jsonTraits.get("Traits") instanceof ArrayNode jsonTraitArray)) return;
                jsonTraitArray.forEach(jsonTrait -> {
                    try {
                        traitService.create(
                                getNamedNode(jsonTrait, "name").asText(),
                                jsonTrait.has("schema")
                                        ? jsonTrait.get("schema").toPrettyString()
                                        : """
                                        {
                                          "type": "object",
                                          "properties": {
                                          }
                                        }
                                        """,
                                jsonTrait.has("inheritsFrom")
                                        ? Optional.of(
                                                jsonTrait.get("inheritsFrom").asText())
                                        : Optional.empty());
                    } catch (ServiceError e) {
                        throw new RuntimeException(e);
                    }
                });
            });

            // TraitRelationships creation
            docs.stream().filter(doc -> doc.has("Relationships")).findFirst().ifPresent(jsonRelationships -> {
                if (!(jsonRelationships.get("Relationships") instanceof ArrayNode jsonRelationshipsArray)) return;
                jsonRelationshipsArray.forEach(jsonRelationship -> {
                    try {
                        traitService.link(
                                getNamedNode(jsonRelationship, "sourceTrait").asText(),
                                RelationType.valueOf(getNamedNode(jsonRelationship, "relationshipType")
                                        .asText()),
                                getNamedNode(jsonRelationship, "targetTrait").asText());
                    } catch (ServiceError e) {
                        throw new RuntimeException(e);
                    } catch (IllegalArgumentException e) {
                        throw new RuntimeException(new ServiceError("Invalid relationship type"));
                    }
                });
            });

            // EntityTypes creation
            docs.stream().filter(doc -> doc.has("EntityTypes")).findFirst().ifPresent(jsonTypes -> {
                if (!(jsonTypes.get("EntityTypes") instanceof ArrayNode jsonTypesArray)) return;
                jsonTypesArray.forEach(jsonType -> {
                    try {
                        entityTypeService.create(
                                getNamedNode(jsonType, "name").asText(),
                                jsonType.has("traits")
                                        ? StreamSupport.stream(
                                                        jsonType.get("traits").spliterator(), false)
                                                .map(JsonNode::asText)
                                                .toList()
                                        : List.of(),
                                jsonType.has("inheritsFrom")
                                        ? Optional.of(
                                                jsonType.get("inheritsFrom").asText())
                                        : Optional.empty(),
                                getNamedNode(jsonType, "schema").toPrettyString());
                    } catch (ServiceError e) {
                        throw new RuntimeException(e);
                    }
                });
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ServiceError) throw (ServiceError) e.getCause();
            else throw e;
        }
    }
}
