package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.yamlFactory;
import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Service class for bulk loading entities, traits, and relationships from a YAML file. */
@Slf4j
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

  private final MappingService mappingService;

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
  public void bulkModelCreation(InputStream is) throws ServiceError {
    log.info("Bulk model creation started");
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
                      } catch (IllegalArgumentException _) {
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

      // Mappings creation
      docs.stream()
          .filter(doc -> doc.has("Mappings"))
          .findFirst()
          .ifPresent(
              jsonEntities -> {
                if (!(jsonEntities.get("Mappings") instanceof ArrayNode jsonEntitiesArray)) return;
                jsonEntitiesArray.forEach(
                    jsonEntity -> {
                      try {
                        var sourceEntityType =
                            getNamedNode(jsonEntity, "sourceEntityType").asText();
                        var targetEntityType =
                            getNamedNode(jsonEntity, "targetEntityType").asText();
                        var mappingValue =
                            getNamedNode(jsonEntity, "mappingValues").toPrettyString();
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
                      } catch (ServiceError e) {
                        throw new ServiceRuntimeError(e);
                      }
                    });
              });
      log.info("Bulk model creation completed");
    } catch (ServiceRuntimeError e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    } catch (IOException _) {
      throw new ServiceError("Error parsing YAML file");
    }
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
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<String> bulkAggregateCreation(InputStream is) throws ServiceError {
    log.info("Bulk aggregate creation started");
    try {
      var refs = new HashMap<String, Entity>();
      var depends = new HashMap<String, String>();
      List<ObjectNode> docs;
      try (var yamlParser = yamlFactory.createParser(is)) {
        docs = yamlFactory.readValues(yamlParser, new TypeReference<ObjectNode>() {}).readAll();
      }
      class CreateEntity implements Function<ObjectNode, Entity> {
        @Override
        public Entity apply(ObjectNode doc) {
          var ref = Optional.ofNullable(doc.get("ref"));
          var dependsOn = Optional.ofNullable(doc.get("dependsOn"));
          var entityTypeName = doc.get("entityType").asText();
          var entityValues = doc.get("values").toPrettyString();
          try {
            var aggregate = entityService.create(entityTypeName, entityValues);
            if (ref.isPresent()) {
              var refId = ref.get().asText();
              refs.put(refId, aggregate);
            }
            if (dependsOn.isPresent()) {
              var dependsOnIds = (ArrayNode) dependsOn.get();
              for (JsonNode dependsOnId : dependsOnIds)
                depends.put(aggregate.getId(), dependsOnId.asText());
            }
            var parts = (ArrayNode) doc.get("parts");
            if (parts == null) return aggregate;
            else {
              parts.forEach(
                  part -> {
                    var partEntity = apply((ObjectNode) part);
                    try {
                      entityService.link(aggregate.getId(), HAS_PART, partEntity.getId());
                    } catch (ServiceError e) {
                      throw new ServiceRuntimeError(e);
                    }
                  });
              return aggregate;
            }
          } catch (ServiceError e) {
            throw new ServiceRuntimeError(e);
          }
        }
      }
      var ids = docs.stream().map(new CreateEntity()).map(Entity::getId).toList();
      // Linking refs
      depends.forEach(
          (entity, dependsOnId) -> {
            try {
              entityService.link(entity, DEPENDS_ON, refs.get(dependsOnId).getId());
            } catch (ServiceError e) {
              throw new ServiceRuntimeError(e);
            }
          });
      log.info("Bulk aggregate creation completed");
      return ids;
    } catch (ServiceRuntimeError e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    } catch (IOException | ClassCastException _) {
      throw new ServiceError("Error parsing YAML file");
    }
  }
}
