package it.davidgreco.metacatalog.openapi;

import static org.springframework.http.ResponseEntity.*;

import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.openapi.common.GlobalExceptionHandler;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.*;
import it.davidgreco.metacatalog.service.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Implementation of the Metacatalog REST API.
 *
 * <p>This service implements the {@link MetacatalogApiDelegate} interface generated from the
 * OpenAPI specification. It provides REST endpoints for managing:
 *
 * <ul>
 *   <li>Traits - reusable characteristics that can be applied to entity types
 *   <li>Entity Types - type definitions for entities with schemas
 *   <li>Entities - instances of entity types with JSON values
 *   <li>Relationships - links between traits and entities
 *   <li>Aggregates - hierarchical collections of entities
 *   <li>Bulk operations - loading models and aggregates from YAML files
 * </ul>
 *
 * <p>Exceptions thrown by the service layer (e.g. {@link ServiceError}, {@link NotFoundException},
 * {@link SchemaValidationError}) and by the framework are allowed to propagate to the Spring {@code
 * DispatcherServlet}, where {@link GlobalExceptionHandler} translates them into the OpenAPI
 * contract's {@code ValidationError} (4xx) and {@code SystemError} (5xx) response bodies. This
 * class therefore contains no try/catch blocks and focuses purely on request-to-service translation
 * and DTO assembly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetacatalogApiImpl implements MetacatalogApiDelegate {

  private final TraitService traitService;

  private final EntityTypeService entityTypeService;

  private final EntityService entityService;

  private final BulkLoaderService bulkLoaderService;

  private final MappingService mappingService;

  private final NativeWebRequest request;
  private final AggregateService aggregateService;

  private final AggregateSchemaService aggregateSchemaService;

  private final DtoMapper dtoMapper;

  private final JsonUtils jsonUtils;

  @Override
  public Optional<NativeWebRequest> getRequest() {
    return Optional.ofNullable(request);
  }

  @Override
  public ResponseEntity createTrait(Trait trait) {
    traitService.create(trait.getName(), trait.getSchema(), trait.getInheritsFrom());
    return status(204).build();
  }

  @Override
  public ResponseEntity getTrait(String name) {
    var trait = traitService.read(name);
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtoMapper.traitToDto(trait));
  }

  @Override
  public ResponseEntity deleteTrait(String name) {
    traitService.delete(name);
    return status(204).build();
  }

  @Override
  public ResponseEntity existsTrait(String name) {
    if (traitService.exists(name)) return status(200).build();
    else return status(404).build();
  }

  @Override
  public ResponseEntity listTraits() {
    var traits = traitService.list().stream().map(dtoMapper::traitToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(traits);
  }

  @Override
  public ResponseEntity listEntityTypes() {
    var entityTypes = entityTypeService.list().stream().map(dtoMapper::entityTypeToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(entityTypes);
  }

  @Override
  public ResponseEntity listMappings() {
    var mappings = mappingService.list().stream().map(dtoMapper::mappingToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(mappings);
  }

  @Override
  public ResponseEntity listTraitRelationships() {
    var rels =
        traitService.listAllRelationships().stream()
            .map(dtoMapper::traitRelationshipToDto)
            .toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(rels);
  }

  @Override
  public ResponseEntity createMapping(Mapping mapping) {
    mappingService.create(
        mapping.getSourceEntityType(),
        mapping.getTargetEntityType(),
        mapping.getMappingValues(),
        mapping.getEntityPathReferences());
    return status(204).build();
  }

  @Override
  public ResponseEntity deleteMapping(String id) {
    mappingService.delete(id);
    return status(204).build();
  }

  @Override
  public ResponseEntity linkTrait(LinkTraitRequest linkTrait) {
    RelationType relType;
    relType = RelationType.parse(linkTrait.getRelationshipTypeName());
    traitService.link(linkTrait.getSourceTrait(), relType, linkTrait.getTargetTrait());
    return status(204).build();
  }

  @Override
  public ResponseEntity linkEntity(LinkEntityRequest linkEntityRequest) {
    RelationType relType;
    relType = RelationType.parse(linkEntityRequest.getRelationshipTypeName());
    entityService.link(
        linkEntityRequest.getSourceEntityId(), relType, linkEntityRequest.getTargetEntityId());
    return status(204).build();
  }

  @Override
  public ResponseEntity linkedTrait(String nameTrait1, String relationshipTypeName) {
    var relType = RelationType.parse(relationshipTypeName);
    var traits =
        traitService.linked(nameTrait1, relType).stream().map(dtoMapper::traitToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(traits);
  }

  @Override
  public ResponseEntity unlinkTrait(
      String sourceTrait, String relationshipTypeName, String targetTrait) {
    RelationType relType;
    relType = RelationType.parse(relationshipTypeName);
    traitService.unlink(sourceTrait, relType, targetTrait);
    return status(204).build();
  }

  @Override
  public ResponseEntity existsLinkTrait(
      String sourceTrait, String relationshipTypeName, String targetTrait) {
    RelationType relType;
    relType = RelationType.parse(relationshipTypeName);
    var linkedTraitsNames =
        traitService.linked(sourceTrait, relType).stream()
            .map(it.davidgreco.metacatalog.entity.Trait::getName)
            .collect(Collectors.toSet());
    if (linkedTraitsNames.contains(targetTrait)) return status(200).build();
    else return status(404).build();
  }

  @Override
  public ResponseEntity createEntityType(EntityType entityType) {
    entityTypeService.create(
        entityType.getName(),
        entityType.getTraits(),
        entityType.getInheritsFrom(),
        entityType.getSchema());
    return status(204).build();
  }

  @Override
  public ResponseEntity deleteEntityType(String name) {
    entityTypeService.delete(name);
    return status(204).build();
  }

  @Override
  public ResponseEntity existsEntityType(String name) {
    if (entityTypeService.exists(name)) return status(200).build();
    else return status(404).build();
  }

  @Override
  public ResponseEntity getEntityType(String name) {
    var type = entityTypeService.read(name);
    return status(200)
        .contentType(MediaType.APPLICATION_JSON)
        .body(dtoMapper.entityTypeToDto(type));
  }

  @Override
  public ResponseEntity createEntity(Entity entity) {
    var ent = entityService.create(entity.getEntityType(), entity.getValues());
    return status(200).contentType(MediaType.TEXT_PLAIN).body(ent.getId());
  }

  @Override
  public ResponseEntity updateEntity(String id, Entity entity) {
    entityService.update(id, entity.getValues());
    return status(204).build();
  }

  @Override
  public ResponseEntity deleteEntity(String id) {
    entityService.delete(id);
    return status(204).build();
  }

  @Override
  public ResponseEntity existsEntity(String id) {
    if (entityService.exists(id)) return status(200).build();
    else return status(404).build();
  }

  @Override
  public ResponseEntity getEntity(String id) {
    var entity = entityService.read(id);
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtoMapper.entityToDto(entity));
  }

  @Override
  public ResponseEntity bulkCreation(Resource body) {
    try {
      bulkLoaderService.bulkModelCreation(body.getInputStream());
    } catch (java.io.IOException e) {
      throw new ServiceError(e.getMessage(), e);
    }
    return status(204).build();
  }

  @Override
  public ResponseEntity getAggregate(String aggregateId, Boolean retrieveMappedInstances) {
    var aggregateDto = readAggregateAsDto(aggregateId, retrieveMappedInstances);
    return status(200).contentType(MediaType.APPLICATION_JSON).body(aggregateDto);
  }

  @Override
  public ResponseEntity deleteAggregate(String aggregateId) {
    aggregateService.delete(aggregateId);
    return status(204).build();
  }

  @Override
  public ResponseEntity unlinkEntity(
      String sourceEntityId, String relationshipTypeName, String targetEntityId) {
    RelationType relType;
    relType = RelationType.parse(relationshipTypeName);
    entityService.unlink(sourceEntityId, relType, targetEntityId);
    return status(204).build();
  }

  @Override
  public ResponseEntity linkedEntity(String sourceEntityId, String relationshipTypeName) {
    var relType = RelationType.parse(relationshipTypeName);
    var entities =
        entityService.linked(sourceEntityId, relType).stream().map(dtoMapper::entityToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(entities);
  }

  @Override
  public ResponseEntity getEntities(Optional<String> entityTypeName, Optional<String> queryPath) {
    var path = queryPath.orElse("");
    // An absent type name lists every type's entities; the JSON-path filter still applies.
    List<it.davidgreco.metacatalog.entity.Entity> entities =
        entityTypeName
            .filter(name -> !name.isBlank())
            .map(name -> entityService.list(name, path))
            .orElseGet(
                () ->
                    entityTypeService.list().stream()
                        .flatMap(type -> entityService.list(type.getName(), path).stream())
                        .toList());
    return status(200)
        .contentType(MediaType.APPLICATION_JSON)
        .body(entities.stream().map(dtoMapper::entityToDto).toList());
  }

  @Override
  public ResponseEntity<List<it.davidgreco.metacatalog.openapi.model.EntityRelationship>>
      listEntityRelationships() {
    var rels =
        entityService.listAllRelationships().stream()
            .map(dtoMapper::entityRelationshipToDto)
            .toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(rels);
  }

  @Override
  public ResponseEntity<List<it.davidgreco.metacatalog.openapi.model.MappingEntityRelationship>>
      listMappingEntityRelationships() {
    var rels =
        mappingService.listAllEntityRelationships().stream()
            .map(dtoMapper::mappingEntityRelationshipToDto)
            .toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(rels);
  }

  @Override
  public ResponseEntity<List<Trait>> listAllTraitVersions() {
    var dtos = traitService.listAllVersions().stream().map(dtoMapper::traitVersionToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtos);
  }

  @Override
  public ResponseEntity<List<EntityType>> listAllEntityTypeVersions() {
    var dtos =
        entityTypeService.listAllVersions().stream()
            .map(dtoMapper::entityTypeVersionToDto)
            .toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtos);
  }

  @Override
  public ResponseEntity getAggregateAsYaml(String aggregateId, Boolean retrieveMappedInstances) {
    try {
      var aggregateDto = readAggregateAsDto(aggregateId, retrieveMappedInstances);
      var bos = new ByteArrayOutputStream();
      jsonUtils
          .yamlMapper()
          .writeValue(
              bos,
              jsonUtils.convertAggregateValuesIntoJsonNodes(
                  jsonUtils.jsonMapper().valueToTree(aggregateDto).deepCopy()));
      var resource = new org.springframework.core.io.ByteArrayResource(bos.toByteArray());
      return status(200).contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ServiceError(e.getMessage(), e);
    } catch (java.io.IOException e) {
      throw new ServiceError(e.getMessage(), e);
    }
  }

  @Override
  public ResponseEntity listAggregateRootTypes() {
    var rootTypes =
        aggregateSchemaService.aggregateRootTypes().stream()
            .map(dtoMapper::entityTypeToDto)
            .toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(rootTypes);
  }

  @Override
  public ResponseEntity<java.util.Map<String, Object>> getAggregateSchema(String name) {
    // The contract declares a free-form JSON object, so the schema tree is converted to a Map:
    // handing Jackson the JsonNode itself against a declared Map type would serialize the node's
    // bean properties (isArray, isObject, ...) instead of its contents.
    var schema = aggregateSchemaService.aggregateSchema(name);
    java.util.Map<String, Object> body =
        jsonUtils
            .jsonMapper()
            .convertValue(schema, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    return status(200).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  @Override
  public ResponseEntity createAggregateAsYaml(Resource body) {
    try {
      bulkLoaderService.bulkAggregateCreation(body.getInputStream());
    } catch (java.io.IOException e) {
      throw new ServiceError(e.getMessage(), e);
    }
    return status(204).build();
  }

  @Override
  public ResponseEntity createEntityTypeVersion(String name, EntityType entityType) {
    var created =
        entityTypeService.createVersion(
            name, entityType.getTraits(), entityType.getInheritsFrom(), entityType.getSchema());
    return status(200)
        .contentType(MediaType.APPLICATION_JSON)
        .body(dtoMapper.entityTypeToDto(created));
  }

  @Override
  public ResponseEntity createTraitVersion(String name, Trait trait) {
    var created = traitService.createVersion(name, trait.getSchema(), trait.getInheritsFrom());
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtoMapper.traitToDto(created));
  }

  @Override
  public ResponseEntity getEntityTypeVersion(String name, Integer version) {
    var dto = dtoMapper.entityTypeVersionResultToDto(entityTypeService.readVersion(name, version));
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dto);
  }

  @Override
  public ResponseEntity getTraitVersion(String name, Integer version) {
    var dto = dtoMapper.traitVersionResultToDto(traitService.readVersion(name, version));
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dto);
  }

  @Override
  public ResponseEntity listEntityTypeVersions(String name) {
    var dtos =
        entityTypeService.listVersions(name).stream()
            .map(dtoMapper::entityTypeVersionResultToDto)
            .toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtos);
  }

  @Override
  public ResponseEntity listTraitVersions(String name) {
    var dtos =
        traitService.listVersions(name).stream().map(dtoMapper::traitVersionResultToDto).toList();
    return status(200).contentType(MediaType.APPLICATION_JSON).body(dtos);
  }

  @Override
  public ResponseEntity deleteEntityTypeVersion(String name, Integer version) {
    entityTypeService.deleteVersion(name, version);
    return status(204).build();
  }

  @Override
  public ResponseEntity deleteTraitVersion(String name, Integer version) {
    traitService.deleteVersion(name, version);
    return status(204).build();
  }

  private Aggregate readAggregateAsDto(String aggregateId, Boolean retrieveMappedInstances) {
    var aggregate = aggregateService.read(aggregateId, retrieveMappedInstances);
    return dtoMapper.aggregatePartToDto.apply(aggregate);
  }
}
