package it.davidgreco.metacatalog.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.davidgreco.metacatalog.entity.AccessControl;
import it.davidgreco.metacatalog.entity.BuiltInCapability;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import it.davidgreco.metacatalog.entity.TraitVersion;
import it.davidgreco.metacatalog.openapi.model.AccessGrant;
import it.davidgreco.metacatalog.openapi.model.AccessPermission;
import it.davidgreco.metacatalog.openapi.model.AccessPrincipal;
import it.davidgreco.metacatalog.openapi.model.Aggregate;
import it.davidgreco.metacatalog.openapi.model.AggregateAccess;
import it.davidgreco.metacatalog.openapi.model.EffectiveGrant;
import it.davidgreco.metacatalog.openapi.model.Mapping;
import it.davidgreco.metacatalog.openapi.model.ResourceAccess;
import it.davidgreco.metacatalog.service.AccessPolicy;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.VersionResult;
import java.util.LinkedHashMap;
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
    dto.setPreviousVersionId(Optional.ofNullable(v.getPreviousVersionId()));
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
    dto.setPreviousVersionId(Optional.ofNullable(v.getPreviousVersionId()));
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
    dtoEntity.setEntityTypeVersionId(entity.getEntityTypeVersion().getId());
    dtoEntity.setValues(entity.getValues().toPrettyString());
    return dtoEntity;
  }

  public it.davidgreco.metacatalog.openapi.model.EntityRelationship entityRelationshipToDto(
      it.davidgreco.metacatalog.entity.EntityRelationship rel) {
    var dto = new it.davidgreco.metacatalog.openapi.model.EntityRelationship();
    dto.setId(Optional.ofNullable(rel.getId()));
    dto.setSourceEntityId(Optional.of(rel.getSource().getId()));
    dto.setTargetEntityId(Optional.of(rel.getTarget().getId()));
    dto.setRelationType(Optional.of(rel.getRelationType().name()));
    return dto;
  }

  /**
   * Maps an instance-level mapping relationship, carrying along the mapping rule that produced it.
   * The rule is optional: a relationship outlives the rule that created it if the rule is later
   * deleted, in which case the expression fields are left empty rather than failing the mapping.
   */
  public it.davidgreco.metacatalog.openapi.model.MappingEntityRelationship
      mappingEntityRelationshipToDto(
          it.davidgreco.metacatalog.entity.MappingEntityRelationship rel) {
    var dto = new it.davidgreco.metacatalog.openapi.model.MappingEntityRelationship();
    dto.setId(Optional.ofNullable(rel.getId()));
    dto.setSourceEntityId(Optional.of(rel.getSource().getId()));
    dto.setTargetEntityId(Optional.of(rel.getTarget().getId()));
    dto.setRelationType(Optional.of(rel.getRelationType().name()));
    var rule = rel.getMappingEntityTypeRelationship();
    dto.setMappingValues(Optional.ofNullable(rule).map(r -> r.getMappingValues().toPrettyString()));
    dto.setEntityPathReferences(
        Optional.ofNullable(rule)
            .map(r -> jsonMapper.valueToTree(r.getEntityPathReferences()).toPrettyString()));
    return dto;
  }

  public it.davidgreco.metacatalog.openapi.model.TraitRelationship traitRelationshipToDto(
      TraitRelationship rel) {
    var dto = new it.davidgreco.metacatalog.openapi.model.TraitRelationship();
    dto.setId(Optional.ofNullable(rel.getId()));
    dto.setSourceTrait(Optional.of(rel.getSource().getName()));
    dto.setTargetTrait(Optional.of(rel.getTarget().getName()));
    dto.setRelationType(Optional.of(rel.getRelationType().name()));
    return dto;
  }

  public Mapping mappingToDto(it.davidgreco.metacatalog.entity.MappingEntityTypeRelationship m) {
    Mapping mapping = new Mapping();
    mapping.setId(Optional.ofNullable(m.getId()));
    mapping.setSourceEntityType(m.getSource().getName());
    mapping.setTargetEntityType(m.getTarget().getName());
    mapping.setSourceEntityTypeVersion(Optional.ofNullable(m.getSourceEntityTypeVersion()));
    mapping.setTargetEntityTypeVersion(Optional.ofNullable(m.getTargetEntityTypeVersion()));
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

  /**
   * Assembles an aggregate's access view: the policy its root declares, and the grants each of its
   * resources is actually holding.
   *
   * <p>The two halves are read from different places on purpose. The policy comes from the root's
   * declaration — intent, editable at any moment. The per-resource grants come from each resource's
   * recorded {@code effectiveGrants} — fact, written by a run. They are not recomputed here: doing
   * so would report what the <em>next</em> run would do while presenting it as what is in force,
   * and would make a read endpoint fail on a policy that is mid-edit. A resource whose grants no
   * longer match the policy is one that has not been authorized since the policy changed, and being
   * able to see that is the point.
   *
   * @param root the aggregate root
   * @param policy the policy parsed from the root
   * @param resources every {@code AuthorizableResource} the aggregate contains, in tree order
   * @return the DTO
   */
  public AggregateAccess aggregateAccessToDto(
      Entity root, AccessPolicy policy, List<Entity> resources) {
    return new AggregateAccess()
        .aggregateId(root.getId())
        .entityTypeName(root.getEntityType().getName())
        .authorizationStatus(text(root.getValues(), BuiltInCapability.AUTHORIZATION.statusField()))
        .principals(policy.principals().stream().map(DtoMapper::principalToDto).toList())
        .permissions(
            policy.permissions().stream()
                .map(
                    permission ->
                        new AccessPermission()
                            .name(permission.name())
                            .description(permission.description().orElse(null)))
                .toList())
        .grants(policy.grants().stream().map(DtoMapper::grantToDto).toList())
        .resources(resources.stream().map(this::resourceAccessToDto).toList());
  }

  private static AccessPrincipal principalToDto(AccessPolicy.Principal principal) {
    return new AccessPrincipal()
        .id(principal.id())
        .type(AccessPrincipal.TypeEnum.fromValue(principal.type().name()))
        .description(principal.description().orElse(null));
  }

  private static AccessGrant grantToDto(AccessPolicy.Grant grant) {
    var dto = new AccessGrant().principals(grant.principals()).permissions(grant.permissions());
    if (!grant.resources().entityTypes().isEmpty())
      dto.entityTypes(grant.resources().entityTypes());
    if (!grant.resources().values().isEmpty()) {
      var values = new LinkedHashMap<String, Object>();
      grant.resources().values().forEach((key, value) -> values.put(key, unwrap(value)));
      dto.values(values);
    }
    return dto;
  }

  private ResourceAccess resourceAccessToDto(Entity resource) {
    var recorded =
        resource.getValues() == null
            ? null
            : resource.getValues().get(AccessControl.EFFECTIVE_GRANTS);
    // The recorded array is exactly the spec's EffectiveGrant shape — AccessPolicy.toJson writes
    // it and BuiltInCapabilityTests pins it — so it binds directly instead of being walked by hand.
    List<EffectiveGrant> grants =
        recorded != null && recorded.isArray()
            ? jsonMapper.convertValue(recorded, new TypeReference<>() {})
            : List.of();
    return new ResourceAccess()
        .entityId(resource.getId())
        .entityTypeName(resource.getEntityType().getName())
        .authorizationStatus(
            text(resource.getValues(), BuiltInCapability.AUTHORIZATION.statusField()))
        .grants(grants);
  }

  /** The JSON scalar as the plain Java value the generated {@code values} map holds. */
  private static Object unwrap(JsonNode node) {
    if (node.isNumber()) return node.numberValue();
    if (node.isBoolean()) return node.booleanValue();
    return node.asText();
  }

  private static String text(JsonNode node, String field) {
    if (node == null) return null;
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
