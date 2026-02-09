package it.davidgreco.metacatalog.openapi;

import static it.davidgreco.metacatalog.common.JsonUtils.*;

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
public class MetacatalogApiImpl implements MetacatalogApiDelegate {

  private static final String INVALID_RELATIONSHIP_TYPE = "Invalid relationship type";

  private final TraitService traitService;

  private final EntityTypeService entityTypeService;

  private final EntityService entityService;

  private final MappingService mappingService;

  private final BulkLoaderService bulkLoaderService;

  private final NativeWebRequest request;
  private final AggregateService aggregateService;

  Function<it.davidgreco.metacatalog.entity.Entity, it.davidgreco.metacatalog.openapi.model.Entity>
      entityToDtoEntity =
          entity -> {
            var dtoEntity = new it.davidgreco.metacatalog.openapi.model.Entity();
            dtoEntity.setId(entity.getId());
            dtoEntity.setEntityType(entity.getEntityType().getName());
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
                    aggregate.elements().stream().map(this::apply).collect(Collectors.toList()));
                return aggregateDto;
              }
            }
          };

  @Override
  public Optional<NativeWebRequest> getRequest() {
    return Optional.ofNullable(request);
  }

  @Override
  public ResponseEntity createTrait(Trait trait) {
    try {
      if (trait.getSchema().isPresent())
        traitService.create(
            trait.getName(),
            Optional.of(trait.getSchema().orElseThrow(RuntimeException::new)),
            trait.getInheritsFrom());
      else traitService.create(trait.getName(), Optional.empty(), trait.getInheritsFrom());
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity getTrait(String name) {
    try {
      var trait = traitService.read(name);
      Trait dtoTrait = new Trait();
      dtoTrait.setName(trait.getName());
      dtoTrait.setSchema(Optional.of(trait.getSchema().toPrettyString()));
      dtoTrait.setInheritsFrom(
          Optional.ofNullable(trait.getFather())
              .map(it.davidgreco.metacatalog.entity.Trait::getName));
      return ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(dtoTrait);
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity deleteTrait(String name) {
    try {
      traitService.delete(name);
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity existsTrait(String name) {
    try {
      if (traitService.exists(name)) return ResponseEntity.status(204).build();
      else return ResponseEntity.status(404).build();
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity listTraits() throws Exception {
    try {
      var traits =
          traitService.list().stream()
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
      return ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(traits);
    } catch (Exception e) {
      return ResponseEntity.status(500)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity linkTrait(LinkTraitRequest linkTrait) {
    try {
      RelationType relType;
      relType = RelationType.valueOf(linkTrait.getRelationshipTypeName());
      traitService.link(linkTrait.getSourceTrait(), relType, linkTrait.getTargetTrait());
      return ResponseEntity.status(204).build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity linkEntity(LinkEntityRequest linkEntityRequest) {
    try {
      RelationType relType;
      relType = RelationType.valueOf(linkEntityRequest.getRelationshipTypeName());
      entityService.link(
          linkEntityRequest.getSourceEntityId(), relType, linkEntityRequest.getTargetEntityId());
      return ResponseEntity.status(204).build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity linkedTrait(String nameTrait1, String relationshipTypeName)
      throws Exception {
    try {
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
                  });
      return ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(traits);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity unlinkTrait(
      String sourceTrait, String relationshipTypeName, String targetTrait) {
    try {
      RelationType relType;
      relType = RelationType.valueOf(relationshipTypeName);
      traitService.unlink(sourceTrait, relType, targetTrait);
      return ResponseEntity.status(204).build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity existsLinkTrait(
      String sourceTrait, String relationshipTypeName, String targetTrait) {
    try {
      RelationType relType;
      relType = RelationType.valueOf(relationshipTypeName);
      var linkedTraitsNames =
          traitService.linked(sourceTrait, relType).stream()
              .map(it.davidgreco.metacatalog.entity.Trait::getName)
              .collect(Collectors.toSet());
      if (linkedTraitsNames.contains(targetTrait)) return ResponseEntity.status(200).build();
      else return ResponseEntity.status(404).build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity createEntityType(EntityType entityType) {
    try {
      entityTypeService.create(
          entityType.getName(),
          entityType.getTraits(),
          entityType.getInheritsFrom(),
          entityType.getSchema());
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity deleteEntityType(String name) {
    try {
      entityTypeService.delete(name);
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity existsEntityType(String name) {
    try {
      if (entityTypeService.exists(name)) return ResponseEntity.status(204).build();
      else return ResponseEntity.status(404).build();
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity getEntityType(String name) {
    try {
      var type = entityTypeService.read(name);
      EntityType dtoType = new EntityType();
      dtoType.setName(type.getName());
      dtoType.setSchema(type.getSchema().toPrettyString());
      dtoType.setTraits(
          type.getTraits().stream().map(it.davidgreco.metacatalog.entity.Trait::getName).toList());
      dtoType.setInheritsFrom(
          Optional.ofNullable(type.getFather())
              .map(it.davidgreco.metacatalog.entity.EntityType::getName));
      return ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(dtoType);
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity createEntity(Entity entity) {
    try {
      var ent = entityService.create(entity.getEntityType(), entity.getValues());
      return ResponseEntity.status(200).body(ent.getId());
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity deleteEntity(String id) {
    try {
      entityService.delete(id);
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity existsEntity(String id) {
    try {
      if (entityService.exists(id)) return ResponseEntity.status(204).build();
      else return ResponseEntity.status(404).build();
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity getEntity(String id) {
    try {
      var entity = entityService.read(id);
      return ResponseEntity.status(200)
          .contentType(MediaType.APPLICATION_JSON)
          .body(entityToDtoEntity.apply(entity));
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity bulkCreation(Resource body) {
    try {
      bulkLoaderService.bulkModelCreation(body.getInputStream());
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity getAggregate(String aggregateId, Boolean retrieveMappedInstances) {
    try {
      var aggregate = aggregateService.read(aggregateId, retrieveMappedInstances);
      var aggregateDto = aggregatePartToDtoAggregatePart.apply(aggregate);
      return ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(aggregateDto);
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity unlinkEntity(
      String sourceEntityId, String relationshipTypeName, String targetEntityId) {
    try {
      RelationType relType;
      relType = RelationType.valueOf(relationshipTypeName);
      entityService.unlink(sourceEntityId, relType, targetEntityId);
      return ResponseEntity.status(204).build();
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity linkedEntity(String sourceEntityId, String relationshipTypeName) {
    try {
      var relType = RelationType.valueOf(relationshipTypeName);
      var entitities =
          entityService.linked(sourceEntityId, relType).stream()
              .map(t -> entityToDtoEntity.apply(t));
      return ResponseEntity.status(200).contentType(MediaType.APPLICATION_JSON).body(entitities);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity getEntities(String entityTypeName, String queryPath) throws Exception {
    try {
      List<it.davidgreco.metacatalog.entity.Entity> entities =
          entityService.list(entityTypeName, queryPath);
      return ResponseEntity.status(200)
          .contentType(MediaType.APPLICATION_JSON)
          .body(entities.stream().map(entityToDtoEntity).toList());
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity getAggregateAsYaml(String aggregateId, Boolean retrieveMappedInstances) {
    try {
      var aggregate = aggregateService.read(aggregateId, retrieveMappedInstances);
      var aggregateDto = aggregatePartToDtoAggregatePart.apply(aggregate);
      var bos = new ByteArrayOutputStream();
      yamlFactory.writeValue(
          bos,
          convertAggregateValuesIntoJsonNodes(jsonFactory.valueToTree(aggregateDto).deepCopy()));
      var resource = new org.springframework.core.io.ByteArrayResource(bos.toByteArray());
      return ResponseEntity.status(200)
          .contentType(MediaType.APPLICATION_OCTET_STREAM)
          .body(resource);
    } catch (ServiceError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }

  @Override
  public ResponseEntity createAggregateAsYaml(Resource body) throws Exception {
    try {
      bulkLoaderService.bulkAggregateCreation(body.getInputStream());
      return ResponseEntity.status(204).build();
    } catch (SchemaValidationError e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(e.getErrors()));
    } catch (ServiceError | DataIntegrityViolationException e) {
      return ResponseEntity.status(400)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new ValidationError(List.of(e.getMessage())));
    } catch (Exception e) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SystemError(e.getMessage()));
    }
  }
}
