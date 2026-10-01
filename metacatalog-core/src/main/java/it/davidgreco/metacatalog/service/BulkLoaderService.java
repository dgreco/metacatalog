package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Service class for bulk loading entities, traits, and relationships from a YAML file. */
@Slf4j
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

  private final MappingService mappingService;

  private final EntityService entityService;

  private final ObjectMapper yamlMapper;

  private JsonNode getNamedNode(JsonNode node, String name) {
    if (node.has(name)) return node.get(name);
    throw new NotFoundException("Node " + name + " not found");
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
  @Transactional(propagation = Propagation.REQUIRED)
  public void bulkModelCreation(InputStream is) {
    log.info("Bulk model creation started");
    try {
      List<ObjectNode> docs;
      try (var yamlParser = yamlMapper.createParser(is)) {
        docs = yamlMapper.readValues(yamlParser, new TypeReference<ObjectNode>() {}).readAll();
      }
      createTraits(docs);
      createTraitRelationships(docs);
      createEntityTypes(docs);
      createMappings(docs);
      log.info("Bulk model creation completed");
    } catch (IOException _) {
      throw new ServiceError("Error parsing YAML file");
    } catch (NullPointerException | ClassCastException e) {
      throw new ServiceError("Malformed YAML input: " + e.getMessage());
    }
  }

  private void createTraits(List<ObjectNode> docs) {
    docs.stream()
        .filter(doc -> doc.has("Traits"))
        .findFirst()
        .ifPresent(
            jsonTraits -> {
              var node = jsonTraits.get("Traits");
              if (node == null) return;
              if (!(node instanceof ArrayNode jsonTraitArray))
                throw new ServiceError("Section 'Traits' must be a YAML array");
              jsonTraitArray.forEach(
                  jsonTrait -> {
                    traitService.create(
                        getNamedNode(jsonTrait, "name").asText(),
                        Optional.of(
                            jsonTrait.has(SCHEMA)
                                ? jsonTrait.get(SCHEMA).toPrettyString()
                                : EMPTY_SCHEMA),
                        jsonTrait.has(INHERITS_FROM)
                            ? Optional.of(jsonTrait.get(INHERITS_FROM).asText())
                            : Optional.empty());
                  });
            });
  }

  private void createTraitRelationships(List<ObjectNode> docs) {
    docs.stream()
        .filter(doc -> doc.has("Relationships"))
        .findFirst()
        .ifPresent(
            jsonRelationships -> {
              var node = jsonRelationships.get("Relationships");
              if (node == null) return;
              if (!(node instanceof ArrayNode jsonRelationshipsArray))
                throw new ServiceError("Section 'Relationships' must be a YAML array");
              jsonRelationshipsArray.forEach(
                  jsonRelationship -> {
                    try {
                      traitService.link(
                          getNamedNode(jsonRelationship, "sourceTrait").asText(),
                          RelationType.parse(
                              getNamedNode(jsonRelationship, "relationshipType").asText()),
                          getNamedNode(jsonRelationship, "targetTrait").asText());
                    } catch (IllegalArgumentException _) {
                      throw new ServiceError("Invalid relationship type");
                    }
                  });
            });
  }

  private void createEntityTypes(List<ObjectNode> docs) {
    docs.stream()
        .filter(doc -> doc.has("EntityTypes"))
        .findFirst()
        .ifPresent(
            jsonTypes -> {
              var node = jsonTypes.get("EntityTypes");
              if (node == null) return;
              if (!(node instanceof ArrayNode jsonTypesArray))
                throw new ServiceError("Section 'EntityTypes' must be a YAML array");
              jsonTypesArray.forEach(
                  jsonType -> {
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
                  });
            });
  }

  private void createMappings(List<ObjectNode> docs) {
    docs.stream()
        .filter(doc -> doc.has("Mappings"))
        .findFirst()
        .ifPresent(
            jsonEntities -> {
              var node = jsonEntities.get("Mappings");
              if (node == null) return;
              if (!(node instanceof ArrayNode jsonEntitiesArray))
                throw new ServiceError("Section 'Mappings' must be a YAML array");
              jsonEntitiesArray.forEach(
                  jsonEntity -> {
                    var sourceEntityType = getNamedNode(jsonEntity, "sourceEntityType").asText();
                    var targetEntityType = getNamedNode(jsonEntity, "targetEntityType").asText();
                    var mappingValue = getNamedNode(jsonEntity, "mappingValues").toPrettyString();
                    var referencesNode =
                        (ArrayNode) getNamedNode(jsonEntity, "entityPathReferences");
                    var references =
                        StreamSupport.stream(referencesNode.spliterator(), false)
                            .map(
                                refNode -> {
                                  var alias = refNode.get("alias").asText();
                                  var path = refNode.get("referencePath").asText();
                                  return new MappingEntityTypeRelationship.EntityPathReference(
                                      alias, path);
                                })
                            .toList();
                    mappingService.create(
                        sourceEntityType, targetEntityType, mappingValue, references);
                  });
            });
  }

  /**
   * Creates multiple aggregates from a YAML file in the given {@link InputStream}.
   *
   * <p>The file is expected to contain a list of aggregate definitions, each with the following
   * structure:
   *
   * <ul>
   *   <li>{@code ref}: an optional reference identifier for the aggregate
   *   <li>{@code entityType}: the name of the entity type
   *   <li>{@code values}: the JSON values for the entity
   *   <li>{@code dependsOn}: an optional list of reference identifiers that this aggregate depends
   *       on
   *   <li>{@code parts}: an optional list of child aggregates
   * </ul>
   *
   * @param is the {@link InputStream} containing the YAML file
   * @return a list of IDs of the created root aggregates
   * @throws ServiceError if any error occurs during the creation or YAML parsing fails
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public List<String> bulkAggregateCreation(InputStream is) {
    log.info("Bulk aggregate creation started");
    try {
      var refs = new HashMap<String, Entity>();
      // One entry per dependsOn name, not per entity: a node may depend on several others.
      var depends = new ArrayList<Map.Entry<String, String>>();
      List<ObjectNode> docs;
      try (var yamlParser = yamlMapper.createParser(is)) {
        docs = yamlMapper.readValues(yamlParser, new TypeReference<ObjectNode>() {}).readAll();
      }
      class CreateEntity implements Function<ObjectNode, Entity> {
        @Override
        public Entity apply(ObjectNode doc) {
          var ref = Optional.ofNullable(doc.get("ref"));
          var dependsOn = Optional.ofNullable(doc.get("dependsOn"));
          var entityTypeName = doc.get("entityType").asText();
          var entityValues = doc.get("values").toPrettyString();
          var aggregate = entityService.create(entityTypeName, entityValues);
          if (ref.isPresent()) {
            var refId = ref.get().asText();
            refs.put(refId, aggregate);
          }
          if (dependsOn.isPresent()) {
            var dependsOnIds = (ArrayNode) dependsOn.get();
            for (JsonNode dependsOnId : dependsOnIds)
              depends.add(Map.entry(aggregate.getId(), dependsOnId.asText()));
          }
          var parts = (ArrayNode) doc.get("parts");
          if (parts == null) return aggregate;
          else {
            parts.forEach(
                part -> {
                  var partEntity = apply((ObjectNode) part);
                  entityService.link(aggregate.getId(), HAS_PART, partEntity.getId());
                });
            return aggregate;
          }
        }
      }
      var ids = docs.stream().map(new CreateEntity()).map(Entity::getId).toList();
      // Linking refs
      depends.forEach(
          dependsOnEntry -> {
            var entity = dependsOnEntry.getKey();
            var dependsOnId = dependsOnEntry.getValue();
            var dependency = refs.get(dependsOnId);
            if (dependency == null) {
              throw new ServiceError(
                  "Aggregate with ref '"
                      + dependsOnId
                      + "' is referenced by aggregate '"
                      + entity
                      + "' but was never declared in the bulk file");
            }
            entityService.link(entity, DEPENDS_ON, dependency.getId());
          });
      log.info("Bulk aggregate creation completed");
      return ids;
    } catch (IOException | ClassCastException _) {
      throw new ServiceError("Error parsing YAML file");
    } catch (NullPointerException e) {
      throw new ServiceError(
          "Malformed aggregate YAML: missing required field 'entityType' or 'values'");
    }
  }
}
