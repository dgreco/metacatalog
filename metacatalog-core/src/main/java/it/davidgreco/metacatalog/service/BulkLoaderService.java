package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.yamlFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.RelationType;
import java.io.IOException;
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

  private static final String SCHEMA = "schema";

  private static final String INHERITS_FROM = "inheritsFrom";

  private static final String EMPTY_SCHEMA =
      """
            {
              "type": "object",
              "properties": {
              }
            }
            """;

  private final TraitService traitService;

  private final EntityTypeService entityTypeService;

  private final EntityService entityService;

  private JsonNode getNamedNode(JsonNode node, String name) throws ServiceError {
    if (node.has(name)) return node.get(name);
    throw new ServiceError("Node " + name + " not found");
  }

  /**
   * Create a bulk creation from a YAML file in the given {@link InputStream}. The file is expected
   * to contain a list of objects with the following properties:
   *
   * <ul>
   *   <li>{@code name}: the name of the trait/entity type to be created
   *   <li>{@code inheritsFrom}: the name of the trait/entity type that this trait/entity type
   *       inherits from
   *   <li>{@code traits}: the list of traits of the entity type
   *   <li>{@code schema}: the JSON schema of the trait/entity type
   *   <li>{@code Relationships}: a list of relationships between traits, each with the following
   *       properties:
   *       <ul>
   *         <li>{@code sourceTrait}: the name of the source trait
   *         <li>{@code relationshipType}: the type of the relationship (one of {@link
   *             RelationType#valueOf(String)})
   *         <li>{@code targetTrait}: the name of the target trait
   *       </ul>
   * </ul>
   *
   * @param is the {@link InputStream} containing the YAML file
   * @throws ServiceError if any error occurs during the creation
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void bulkCreation(InputStream is) throws ServiceError {
    try {
      List<ObjectNode> docs;
      try (var yamlParser = yamlFactory.createParser(is)) {

        docs = yamlFactory.readValues(yamlParser, new TypeReference<ObjectNode>() {}).readAll();
      }

      // Traits creation
      docs.stream()
          .filter(doc -> doc.has("Traits"))
          .findFirst()
          .ifPresent(
              jsonTraits -> {
                if (!(jsonTraits.get("Traits") instanceof ArrayNode jsonTraitArray)) return;
                jsonTraitArray.forEach(
                    jsonTrait -> {
                      try {
                        traitService.create(
                            getNamedNode(jsonTrait, "name").asText(),
                            Optional.of(
                                jsonTrait.has(SCHEMA)
                                    ? jsonTrait.get(SCHEMA).toPrettyString()
                                    : EMPTY_SCHEMA),
                            jsonTrait.has(INHERITS_FROM)
                                ? Optional.of(jsonTrait.get(INHERITS_FROM).asText())
                                : Optional.empty());
                      } catch (ServiceError e) {
                        throw new ServiceRuntimeError(e);
                      }
                    });
              });

      // TraitRelationships creation
      docs.stream()
          .filter(doc -> doc.has("Relationships"))
          .findFirst()
          .ifPresent(
              jsonRelationships -> {
                if (!(jsonRelationships.get("Relationships")
                    instanceof ArrayNode jsonRelationshipsArray)) return;
                jsonRelationshipsArray.forEach(
                    jsonRelationship -> {
                      try {
                        traitService.link(
                            getNamedNode(jsonRelationship, "sourceTrait").asText(),
                            RelationType.valueOf(
                                getNamedNode(jsonRelationship, "relationshipType").asText()),
                            getNamedNode(jsonRelationship, "targetTrait").asText());
                      } catch (ServiceError e) {
                        throw new ServiceRuntimeError(e);
                      } catch (IllegalArgumentException e) {
                        throw new ServiceRuntimeError(
                            new ServiceError("Invalid relationship type"));
                      }
                    });
              });

      // EntityTypes creation
      docs.stream()
          .filter(doc -> doc.has("EntityTypes"))
          .findFirst()
          .ifPresent(
              jsonTypes -> {
                if (!(jsonTypes.get("EntityTypes") instanceof ArrayNode jsonTypesArray)) return;
                jsonTypesArray.forEach(
                    jsonType -> {
                      try {
                        entityTypeService.create(
                            getNamedNode(jsonType, "name").asText(),
                            jsonType.has("traits")
                                ? StreamSupport.stream(jsonType.get("traits").spliterator(), false)
                                    .map(JsonNode::asText)
                                    .toList()
                                : List.of(),
                            jsonType.has(INHERITS_FROM)
                                ? Optional.of(jsonType.get(INHERITS_FROM).asText())
                                : Optional.empty(),
                            getNamedNode(jsonType, SCHEMA).toPrettyString());
                      } catch (ServiceError e) {
                        throw new ServiceRuntimeError(e);
                      }
                    });
              });
    } catch (ServiceRuntimeError e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    } catch (IOException e) {
      throw new ServiceError("Error parsing YAML file");
    }
  }
}
