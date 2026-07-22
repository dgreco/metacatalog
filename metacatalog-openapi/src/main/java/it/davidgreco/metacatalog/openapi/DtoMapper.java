package it.davidgreco.metacatalog.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.openapi.model.Aggregate;
import it.davidgreco.metacatalog.openapi.model.Mapping;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.VersionResult;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps core domain entities to OpenAPI DTOs.
 *
 * <p>Extracted from {@link MetacatalogApiImpl} so the delegate stays focused on request-to-service
 * translation and response assembly.
 */
@Component
@RequiredArgsConstructor
public class DtoMapper {

  private final ObjectMapper jsonMapper;

  public it.davidgreco.metacatalog.openapi.model.EntityType entityTypeToDto(
      it.davidgreco.metacatalog.entity.EntityType et) {
    it.davidgreco.metacatalog.openapi.model.EntityType dto =
        new it.davidgreco.metacatalog.openapi.model.EntityType();
    dto.setId(Optional.ofNullable(et.getId()));
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

  public it.davidgreco.metacatalog.openapi.model.EntityType entityTypeVersionToDto(
      EntityTypeVersion v) {
    it.davidgreco.metacatalog.openapi.model.EntityType dto =
        new it.davidgreco.metacatalog.openapi.model.EntityType();
    dto.setId(Optional.ofNullable(v.getId()));
    dto.setName(v.getName());
    dto.setSchema(v.getSchema().toPrettyString());
    var traitNames =
        StreamSupport.stream(v.getTraits().spliterator(), false)
            .map(com.fasterxml.jackson.databind.JsonNode::asText)
            .toList();
    dto.setTraits(traitNames);
    dto.setInheritsFrom(Optional.ofNullable(v.getFatherName()));
    dto.setVersion(Optional.of(v.getVersion()));
    dto.setVersionGroupId(Optional.of(v.getVersionGroupId()));
    return dto;
  }

  public it.davidgreco.metacatalog.openapi.model.Trait traitToDto(
      it.davidgreco.metacatalog.entity.Trait t) {
    it.davidgreco.metacatalog.openapi.model.Trait dto =
        new it.davidgreco.metacatalog.openapi.model.Trait();
    dto.setId(Optional.ofNullable(t.getId()));
    dto.setName(t.getName());
    dto.setSchema(Optional.of(t.getSchema().toPrettyString()));
    dto.setInheritsFrom(
        Optional.ofNullable(t.getFather()).map(it.davidgreco.metacatalog.entity.Trait::getName));
    dto.setVersion(Optional.of(t.getVersion()));
    dto.setVersionGroupId(Optional.of(t.getVersionGroupId()));
    return dto;
  }

  public it.davidgreco.metacatalog.openapi.model.Trait traitVersionToDto(TraitVersion v) {
    it.davidgreco.metacatalog.openapi.model.Trait dto =
        new it.davidgreco.metacatalog.openapi.model.Trait();
    dto.setId(Optional.ofNullable(v.getId()));
    dto.setName(v.getName());
    dto.setSchema(Optional.of(v.getSchema().toPrettyString()));
    dto.setInheritsFrom(Optional.ofNullable(v.getFatherName()));
    dto.setVersion(Optional.of(v.getVersion()));
    dto.setVersionGroupId(Optional.of(v.getVersionGroupId()));
    return dto;
  }

  public it.davidgreco.metacatalog.openapi.model.EntityType entityTypeVersionResultToDto(
      VersionResult<it.davidgreco.metacatalog.entity.EntityType, EntityTypeVersion> result) {
    return switch (result) {
      case VersionResult.Live(var live) -> entityTypeToDto(live);
      case VersionResult.Snapshot(var snap) -> entityTypeVersionToDto(snap);
    };
  }

  public it.davidgreco.metacatalog.openapi.model.Trait traitVersionResultToDto(
      VersionResult<it.davidgreco.metacatalog.entity.Trait, TraitVersion> result) {
    return switch (result) {
      case VersionResult.Live(var live) -> traitToDto(live);
      case VersionResult.Snapshot(var snap) -> traitVersionToDto(snap);
    };
  }

  public it.davidgreco.metacatalog.openapi.model.Entity entityToDto(Entity entity) {
    var dtoEntity = new it.davidgreco.metacatalog.openapi.model.Entity();
    dtoEntity.setId(Optional.ofNullable(entity.getId()));
    dtoEntity.setEntityType(entity.getEntityType().getName());
    if (entity.getEntityTypeVersion() != null)
      dtoEntity.setEntityTypeVersionId(
          java.util.Optional.of(entity.getEntityTypeVersion().getId()));
    dtoEntity.setValues(entity.getValues().toPrettyString());
    return dtoEntity;
  }

  public Mapping mappingToDto(it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship m) {
    Mapping mapping = new Mapping();
    mapping.setId(Optional.ofNullable(m.getId()));
    mapping.setSourceEntityType(m.getSource().getName());
    mapping.setTargetEntityType(m.getTarget().getName());
    mapping.setMappingValues(m.getMappingValues().toPrettyString());
    mapping.setEntityPathReferences(
        jsonMapper.valueToTree(m.getEntityPathReferences()).toPrettyString());
    return mapping;
  }

  public final Function<AggregateService.AggregatePart, Aggregate> aggregatePartToDto =
      new Function<>() {
        @Override
        public Aggregate apply(AggregateService.AggregatePart aggregatePart) {
          return switch (aggregatePart) {
            case AggregateService.AggregateElement(Entity entity, List<Entity> dependencies) ->
                new Aggregate()
                    .id(entity.getId())
                    .entityType(entity.getEntityType().getName())
                    .values(entity.getValues().toPrettyString())
                    .dependencies(dependencies.stream().map(Entity::getId).toList());
            case AggregateService.Aggregate aggregate -> {
              var aggregateDto =
                  new Aggregate()
                      .id(aggregate.entity().getId())
                      .entityType(aggregate.entity().getEntityType().getName())
                      .values(aggregate.entity().getValues().toPrettyString())
                      .dependencies(aggregate.dependencies().stream().map(Entity::getId).toList());
              aggregateDto.setParts(
                  aggregate.elements().stream().map(this).collect(Collectors.toList()));
              yield aggregateDto;
            }
          };
        }
      };
}
