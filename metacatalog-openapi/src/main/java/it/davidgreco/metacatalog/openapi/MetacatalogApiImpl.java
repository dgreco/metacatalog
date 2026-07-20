package it.davidgreco.metacatalog.openapi;

import static it.davidgreco.metacatalog.common.JsonUtils.*;
import static org.springframework.http.ResponseEntity.*;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.*;
import it.davidgreco.metacatalog.service.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
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
 * <p>All operations return appropriate HTTP status codes and error responses for validation errors
 * (400) and system errors (500).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetacatalogApiImpl implements MetacatalogApiDelegate {

  private static final String INVALID_RELATIONSHIP_TYPE = "Invalid relationship type";

  private final TraitService traitService;

  private final EntityTypeService entityTypeService;

  private final EntityService entityService;

  private final BulkLoaderService bulkLoaderService;

  private final MappingService mappingService;

  private final NativeWebRequest request;
  private final AggregateService aggregateService;

  Function<it.davidgreco.metacatalog.entity.Entity, it.davidgreco.metacatalog.openapi.model.Entity>
      entityToDtoEntity =
          entity -> {
            var dtoEntity = new it.davidgreco.metacatalog.openapi.model.Entity();
            dtoEntity.setId(entity.getId());
            dtoEntity.setEntityType(entity.getEntityType().getName());
            if (entity.getEntityTypeVersion() != null)
              dtoEntity.setEntityTypeVersionId(
                  java.util.Optional.of(entity.getEntityTypeVersion().getId()));
            dtoEntity.setValues(entity.getValues().toPrettyString());
            return dtoEntity;
          };

  Function<
          it.davidgreco.metacatalog.service.AggregateService.AggregatePart,
          it.davidgreco.metacatalog.openapi.model.Aggregate>
      aggregatePartToDtoAggregatePart =
          new Function<>() {
            @Override
            public Aggregate apply(AggregateService.AggregatePart aggregatePart) {
              if (aggregatePart
                  instanceof
                  AggregateService.AggregateElement(
                      it.davidgreco.metacatalog.entity.Entity entity,
                      List<it.davidgreco.metacatalog.entity.Entity> dependencies)) {
                return new Aggregate()
                    .id(entity.getId())
                    .entityType(entity.getEntityType().getName())
                    .values(entity.getValues().toPrettyString())
                    .dependencies(
                        dependencies.stream()
                            .map(it.davidgreco.metacatalog.entity.Entity::getId)
                            .toList());
              } else {
                var aggregate = (AggregateService.Aggregate) aggregatePart;
                var aggregateDto =
                    new Aggregate()
                        .id(aggregate.entity().getId())
                        .entityType(aggregate.entity().getEntityType().getName())
                        .values(aggregate.entity().getValues().toPrettyString())
                        .dependencies(
                            aggregate.dependencies().stream()
                                .map(it.davidgreco.metacatalog.entity.Entity::getId)
                                .toList());
                aggregateDto.setParts(
                    aggregate.elements().stream().map(this).collect(Collectors.toList()));
                return aggregateDto;
              }
            }
          };

  @FunctionalInterface
  private interface ApiCall {
    ResponseEntity<?> call() throws Exception;
  }

  private ResponseEntity<?> handle(ApiCall action) {
    try {
      return action.call();
    } catch (SchemaValidationError e) {
      return status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (IllegalArgumentException e) {
      return status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (Exception e) {
      log.error("Unexpected error handling API request", e);
      return status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public Optional<NativeWebRequest> getRequest() {
    return Optional.ofNullable(request);
  }

  @Override
  public ResponseEntity createTrait(Trait trait) {
    return handle(
        () -> {
          if (trait.getSchema().isPresent())
            traitService.create(
                trait.getName(),
                Optional.of(trait.getSchema().orElseThrow(RuntimeException::new)),
                trait.getInheritsFrom());
          else traitService.create(trait.getName(), Optional.empty(), trait.getInheritsFrom());
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity getTrait(String name) {
    return handle(
        () -> {
          var trait = traitService.read(name);
          Trait dtoTrait = new Trait();
          dtoTrait.setName(trait.getName());
          dtoTrait.setSchema(Optional.of(trait.getSchema().toPrettyString()));
          dtoTrait.setInheritsFrom(
              Optional.ofNullable(trait.getFather())
                  .map(it.davidgreco.metacatalog.entity.Trait::getName));
          dtoTrait.setVersion(Optional.of(trait.getVersion()));
          dtoTrait.setVersionGroupId(Optional.of(trait.getVersionGroupId()));
          return status(200).contentType(MediaType.APPLICATION_JSON).body(dtoTrait);
        });
  }

  @Override
  public ResponseEntity deleteTrait(String name) {
    return handle(
        () -> {
          traitService.delete(name);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity existsTrait(String name) {
    return handle(
        () -> {
          if (traitService.exists(name)) return status(204).build();
          else return status(404).build();
        });
  }

  @Override
  public ResponseEntity listTraits() {
    return handle(
        () -> {
          var traits =
              traitService.list().stream()
                  .map(
                      t -> {
                        Trait trait = new Trait();
                        trait.setId(t.getId());
                        trait.setName(t.getName());
                        trait.setSchema(Optional.of(t.getSchema().toPrettyString()));
                        trait.setInheritsFrom(
                            Optional.ofNullable(t.getFather())
                                .map(it.davidgreco.metacatalog.entity.Trait::getName));
                        trait.setVersion(Optional.of(t.getVersion()));
                        trait.setVersionGroupId(Optional.of(t.getVersionGroupId()));
                        return trait;
                      })
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(traits);
        });
  }

  @Override
  public ResponseEntity listEntityTypes() {
    return handle(
        () -> {
          var entityTypes =
              entityTypeService.list().stream()
                  .map(
                      et -> {
                        EntityType dtoType = new EntityType();
                        dtoType.setId(et.getId());
                        dtoType.setName(et.getName());
                        dtoType.setSchema(et.getSchema().toPrettyString());
                        dtoType.setTraits(
                            et.getTraits().stream()
                                .map(it.davidgreco.metacatalog.entity.Trait::getName)
                                .toList());
                        dtoType.setInheritsFrom(
                            Optional.ofNullable(et.getFather())
                                .map(it.davidgreco.metacatalog.entity.EntityType::getName));
                        dtoType.setVersion(Optional.of(et.getVersion()));
                        dtoType.setVersionGroupId(Optional.of(et.getVersionGroupId()));
                        return dtoType;
                      })
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(entityTypes);
        });
  }

  @Override
  public ResponseEntity listMappings() {
    return handle(
        () -> {
          var mappings =
              mappingService.list().stream()
                  .map(
                      m -> {
                        Mapping mapping = new Mapping();
                        mapping.setId(m.getId());
                        mapping.setSourceEntityType(m.getSource().getName());
                        mapping.setTargetEntityType(m.getTarget().getName());
                        mapping.setMappingValues(m.getMappingValues().toPrettyString());
                        mapping.setEntityPathReferences(
                            jsonFactory.valueToTree(m.getEntityPathReferences()).toPrettyString());
                        return mapping;
                      })
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(mappings);
        });
  }

  @Override
  public ResponseEntity createMapping(Mapping mapping) {
    return handle(
        () -> {
          List<it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship.EntityPathReference>
              entityPathReferences =
                  (mapping.getEntityPathReferences() == null
                          || mapping.getEntityPathReferences().isBlank())
                      ? List.of()
                      : jsonFactory.readValue(
                          mapping.getEntityPathReferences(),
                          new com.fasterxml.jackson.core.type.TypeReference<>() {});
          mappingService.create(
              mapping.getSourceEntityType(),
              mapping.getTargetEntityType(),
              mapping.getMappingValues(),
              entityPathReferences);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity deleteMapping(String id) {
    return handle(
        () -> {
          mappingService.delete(id);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity linkTrait(LinkTraitRequest linkTrait) {
    return handle(
        () -> {
          RelationType relType;
          relType = RelationType.valueOf(linkTrait.getRelationshipTypeName());
          traitService.link(linkTrait.getSourceTrait(), relType, linkTrait.getTargetTrait());
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity linkEntity(LinkEntityRequest linkEntityRequest) {
    return handle(
        () -> {
          RelationType relType;
          relType = RelationType.valueOf(linkEntityRequest.getRelationshipTypeName());
          entityService.link(
              linkEntityRequest.getSourceEntityId(),
              relType,
              linkEntityRequest.getTargetEntityId());
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity linkedTrait(String nameTrait1, String relationshipTypeName) {
    return handle(
        () -> {
          var relType = RelationType.valueOf(relationshipTypeName);
          var traits =
              traitService.linked(nameTrait1, relType).stream()
                  .map(
                      t -> {
                        Trait trait = new Trait();
                        trait.setName(t.getName());
                        trait.setSchema(Optional.of(t.getSchema().toPrettyString()));
                        trait.setInheritsFrom(
                            Optional.ofNullable(t.getFather())
                                .map(it.davidgreco.metacatalog.entity.Trait::getName));
                        return trait;
                      })
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(traits);
        });
  }

  @Override
  public ResponseEntity unlinkTrait(
      String sourceTrait, String relationshipTypeName, String targetTrait) {
    return handle(
        () -> {
          RelationType relType;
          relType = RelationType.valueOf(relationshipTypeName);
          traitService.unlink(sourceTrait, relType, targetTrait);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity existsLinkTrait(
      String sourceTrait, String relationshipTypeName, String targetTrait) {
    return handle(
        () -> {
          RelationType relType;
          relType = RelationType.valueOf(relationshipTypeName);
          var linkedTraitsNames =
              traitService.linked(sourceTrait, relType).stream()
                  .map(it.davidgreco.metacatalog.entity.Trait::getName)
                  .collect(Collectors.toSet());
          if (linkedTraitsNames.contains(targetTrait)) return status(200).build();
          else return status(404).build();
        });
  }

  @Override
  public ResponseEntity createEntityType(EntityType entityType) {
    return handle(
        () -> {
          entityTypeService.create(
              entityType.getName(),
              entityType.getTraits(),
              entityType.getInheritsFrom(),
              entityType.getSchema());
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity deleteEntityType(String name) {
    return handle(
        () -> {
          entityTypeService.delete(name);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity existsEntityType(String name) {
    return handle(
        () -> {
          if (entityTypeService.exists(name)) return status(204).build();
          else return status(404).build();
        });
  }

  @Override
  public ResponseEntity getEntityType(String name) {
    return handle(
        () -> {
          var type = entityTypeService.read(name);
          EntityType dtoType = new EntityType();
          dtoType.setName(type.getName());
          dtoType.setSchema(type.getSchema().toPrettyString());
          dtoType.setTraits(
              type.getTraits().stream()
                  .map(it.davidgreco.metacatalog.entity.Trait::getName)
                  .toList());
          dtoType.setInheritsFrom(
              Optional.ofNullable(type.getFather())
                  .map(it.davidgreco.metacatalog.entity.EntityType::getName));
          dtoType.setVersion(Optional.of(type.getVersion()));
          dtoType.setVersionGroupId(Optional.of(type.getVersionGroupId()));
          return status(200).contentType(MediaType.APPLICATION_JSON).body(dtoType);
        });
  }

  @Override
  public ResponseEntity createEntity(Entity entity) {
    return handle(
        () -> {
          var ent = entityService.create(entity.getEntityType(), entity.getValues());
          return status(200).body(ent.getId());
        });
  }

  @Override
  public ResponseEntity deleteEntity(String id) {
    return handle(
        () -> {
          entityService.delete(id);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity existsEntity(String id) {
    return handle(
        () -> {
          if (entityService.exists(id)) return status(204).build();
          else return status(404).build();
        });
  }

  @Override
  public ResponseEntity getEntity(String id) {
    return handle(
        () -> {
          var entity = entityService.read(id);
          return status(200)
              .contentType(MediaType.APPLICATION_JSON)
              .body(entityToDtoEntity.apply(entity));
        });
  }

  @Override
  public ResponseEntity bulkCreation(Resource body) {
    return handle(
        () -> {
          bulkLoaderService.bulkModelCreation(body.getInputStream());
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity getAggregate(String aggregateId, Boolean retrieveMappedInstances) {
    return handle(
        () -> {
          var aggregate = aggregateService.read(aggregateId, retrieveMappedInstances);
          var aggregateDto = aggregatePartToDtoAggregatePart.apply(aggregate);
          return status(200).contentType(MediaType.APPLICATION_JSON).body(aggregateDto);
        });
  }

  @Override
  public ResponseEntity unlinkEntity(
      String sourceEntityId, String relationshipTypeName, String targetEntityId) {
    return handle(
        () -> {
          RelationType relType;
          relType = RelationType.valueOf(relationshipTypeName);
          entityService.unlink(sourceEntityId, relType, targetEntityId);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity linkedEntity(String sourceEntityId, String relationshipTypeName) {
    return handle(
        () -> {
          var relType = RelationType.valueOf(relationshipTypeName);
          var entities =
              entityService.linked(sourceEntityId, relType).stream()
                  .map(t -> entityToDtoEntity.apply(t))
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(entities);
        });
  }

  @Override
  public ResponseEntity getEntities(String entityTypeName, String queryPath) {
    return handle(
        () -> {
          List<it.davidgreco.metacatalog.entity.Entity> entities =
              entityService.list(entityTypeName, queryPath);
          return status(200)
              .contentType(MediaType.APPLICATION_JSON)
              .body(entities.stream().map(entityToDtoEntity).toList());
        });
  }

  @Override
  public ResponseEntity getAggregateAsYaml(String aggregateId, Boolean retrieveMappedInstances) {
    return handle(
        () -> {
          var aggregate = aggregateService.read(aggregateId, retrieveMappedInstances);
          var aggregateDto = aggregatePartToDtoAggregatePart.apply(aggregate);
          var bos = new ByteArrayOutputStream();
          yamlFactory.writeValue(
              bos,
              convertAggregateValuesIntoJsonNodes(
                  jsonFactory.valueToTree(aggregateDto).deepCopy()));
          var resource = new org.springframework.core.io.ByteArrayResource(bos.toByteArray());
          return status(200).contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
        });
  }

  @Override
  public ResponseEntity createAggregateAsYaml(Resource body) {
    return handle(
        () -> {
          bulkLoaderService.bulkAggregateCreation(body.getInputStream());
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity createEntityTypeVersion(String name, EntityType entityType) {
    return handle(
        () -> {
          var created =
              entityTypeService.createVersion(
                  name,
                  entityType.getTraits(),
                  entityType.getInheritsFrom(),
                  entityType.getSchema());
          return status(200).contentType(MediaType.APPLICATION_JSON).body(entityTypeToDto(created));
        });
  }

  @Override
  public ResponseEntity createTraitVersion(String name, Trait trait) {
    return handle(
        () -> {
          var created =
              traitService.createVersion(name, trait.getSchema(), trait.getInheritsFrom());
          return status(200).contentType(MediaType.APPLICATION_JSON).body(traitToDto(created));
        });
  }

  @Override
  public ResponseEntity getEntityTypeVersion(String name, Integer version) {
    return handle(
        () -> {
          var result = entityTypeService.readVersion(name, version);
          EntityType dto =
              result instanceof it.davidgreco.metacatalog.entity.EntityType live
                  ? entityTypeToDto(live)
                  : entityTypeVersionToDto(
                      (it.davidgreco.metacatalog.entity.EntityTypeVersion) result);
          return status(200).contentType(MediaType.APPLICATION_JSON).body(dto);
        });
  }

  @Override
  public ResponseEntity getTraitVersion(String name, Integer version) {
    return handle(
        () -> {
          var result = traitService.readVersion(name, version);
          Trait dto =
              result instanceof it.davidgreco.metacatalog.entity.Trait live
                  ? traitToDto(live)
                  : traitVersionToDto((it.davidgreco.metacatalog.entity.TraitVersion) result);
          return status(200).contentType(MediaType.APPLICATION_JSON).body(dto);
        });
  }

  @Override
  public ResponseEntity listEntityTypeVersions(String name) {
    return handle(
        () -> {
          var dtos =
              entityTypeService.listVersions(name).stream()
                  .map(
                      obj ->
                          obj instanceof it.davidgreco.metacatalog.entity.EntityType live
                              ? entityTypeToDto(live)
                              : entityTypeVersionToDto(
                                  (it.davidgreco.metacatalog.entity.EntityTypeVersion) obj))
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(dtos);
        });
  }

  @Override
  public ResponseEntity listTraitVersions(String name) {
    return handle(
        () -> {
          var dtos =
              traitService.listVersions(name).stream()
                  .map(
                      obj ->
                          obj instanceof it.davidgreco.metacatalog.entity.Trait live
                              ? traitToDto(live)
                              : traitVersionToDto(
                                  (it.davidgreco.metacatalog.entity.TraitVersion) obj))
                  .toList();
          return status(200).contentType(MediaType.APPLICATION_JSON).body(dtos);
        });
  }

  @Override
  public ResponseEntity deleteEntityTypeVersion(String name, Integer version) {
    return handle(
        () -> {
          entityTypeService.deleteVersion(name, version);
          return status(204).build();
        });
  }

  @Override
  public ResponseEntity deleteTraitVersion(String name, Integer version) {
    return handle(
        () -> {
          traitService.deleteVersion(name, version);
          return status(204).build();
        });
  }

  private EntityType entityTypeToDto(it.davidgreco.metacatalog.entity.EntityType et) {
    EntityType dto = new EntityType();
    dto.setId(et.getId());
    dto.setName(et.getName());
    dto.setSchema(et.getSchema().toPrettyString());
    dto.setTraits(
        et.getTraits().stream().map(it.davidgreco.metacatalog.entity.Trait::getName).toList());
    dto.setInheritsFrom(
        Optional.ofNullable(et.getFather())
            .map(it.davidgreco.metacatalog.entity.EntityType::getName));
    dto.setVersion(Optional.of(et.getVersion()));
    dto.setVersionGroupId(Optional.of(et.getVersionGroupId()));
    return dto;
  }

  private EntityType entityTypeVersionToDto(it.davidgreco.metacatalog.entity.EntityTypeVersion v) {
    EntityType dto = new EntityType();
    dto.setName(v.getName());
    dto.setSchema(v.getSchema().toPrettyString());
    var traitNames =
        java.util.stream.StreamSupport.stream(v.getTraits().spliterator(), false)
            .map(com.fasterxml.jackson.databind.JsonNode::asText)
            .toList();
    dto.setTraits(traitNames);
    dto.setInheritsFrom(Optional.ofNullable(v.getFatherName()));
    dto.setVersion(Optional.of(v.getVersion()));
    dto.setVersionGroupId(Optional.of(v.getVersionGroupId()));
    return dto;
  }

  private Trait traitToDto(it.davidgreco.metacatalog.entity.Trait t) {
    Trait dto = new Trait();
    dto.setId(t.getId());
    dto.setName(t.getName());
    dto.setSchema(Optional.of(t.getSchema().toPrettyString()));
    dto.setInheritsFrom(
        Optional.ofNullable(t.getFather()).map(it.davidgreco.metacatalog.entity.Trait::getName));
    dto.setVersion(Optional.of(t.getVersion()));
    dto.setVersionGroupId(Optional.of(t.getVersionGroupId()));
    return dto;
  }

  private Trait traitVersionToDto(it.davidgreco.metacatalog.entity.TraitVersion v) {
    Trait dto = new Trait();
    dto.setName(v.getName());
    dto.setSchema(Optional.of(v.getSchema().toPrettyString()));
    dto.setInheritsFrom(Optional.ofNullable(v.getFatherName()));
    dto.setVersion(Optional.of(v.getVersion()));
    dto.setVersionGroupId(Optional.of(v.getVersionGroupId()));
    return dto;
  }
}
