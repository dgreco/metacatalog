package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.CommonTypeService.genericTraitService;
import static it.davidgreco.metacatalog.service.CommonTypeService.genericTypeService;

import it.davidgreco.metacatalog.entity.*;
import it.davidgreco.metacatalog.repository.*;
import java.util.Set;
import java.util.stream.Collectors;

public interface CommonService<T, K> {

  T read(K key) throws ServiceError;

  void delete(K key) throws ServiceError;

  boolean exists(K key) throws ServiceError;

  static boolean checkRelIsLegit(
      EntityRepository entityRepository,
      TraitRelationshipRepository traitRelationshipRepository,
      String sourceEntityId,
      RelationType relType,
      String targetEntityId)
      throws ServiceError {

    var sourceEntity =
        entityRepository
            .findById(sourceEntityId)
            .orElseThrow(() -> new ServiceError("Entity with id " + sourceEntityId + " not found"));
    var targetEntity =
        entityRepository
            .findById(targetEntityId)
            .orElseThrow(() -> new ServiceError("Entity with id " + targetEntityId + " not found"));
    var sourceEntityType = sourceEntity.getEntityType();
    var targetEntityType = targetEntity.getEntityType();

    var allTheTraitsForTheSourceType =
        genericTypeService.loadInheritanceChain(sourceEntityType).stream()
            .flatMap(
                entityType ->
                    entityType.getTraits().stream()
                        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream()))
            .toList();

    var allTheTraitsNamesForTheTargetType =
        genericTypeService.loadInheritanceChain(targetEntityType).stream()
            .flatMap(
                entityType ->
                    entityType.getTraits().stream()
                        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream()))
            .map(Trait::getName)
            .collect(Collectors.toSet());

    var sourceRelationships =
        allTheTraitsForTheSourceType.stream()
            .flatMap(
                sourceTrait ->
                    traitRelationshipRepository
                        .findBySourceAndRelationType(sourceTrait, relType)
                        .stream())
            .toList();

    for (var sourceRelationship : sourceRelationships) {
      var targetTrait = sourceRelationship.getTarget();
      if (allTheTraitsNamesForTheTargetType.contains(targetTrait.getName())) return true;
    }
    return false;
  }

  static boolean isSourceEntityType(
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipBySource(entityType)
        .isEmpty();
  }

  static boolean isTargetEntityType(
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      EntityType entityType) {
    return !mappingEntityTypeRelationshipRepository
        .findMappingEntityTypeRelationshipByTarget(entityType)
        .isEmpty();
  }

  static boolean checkLoops(
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      String sourceEntityId,
      Set<String> entityIdsVisited,
      String targetEntityId,
      RelationType relationType)
      throws ServiceError {
    var sourceEntity =
        entityRepository
            .findById(sourceEntityId)
            .orElseThrow(() -> new ServiceError("Entity with id " + sourceEntityId + " not found"));
    var mappings =
        entityRelationshipRepository.findBySourceAndRelationType(sourceEntity, relationType);

    var targetIds =
        mappings.stream().map(EntityRelationship::getTarget).map(Entity::getId).toList();
    for (var targetId : targetIds) {
      if (entityIdsVisited.contains(targetEntityId)) return true;
      else {
        entityIdsVisited.add(targetId);
        return checkLoops(
            entityRepository,
            entityRelationshipRepository,
            targetId,
            entityIdsVisited,
            targetEntityId,
            relationType);
      }
    }
    return entityIdsVisited.contains(targetEntityId);
  }

  static boolean checkMappingLoops(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      String sourceEntityTypeName,
      Set<String> entityTypeNamesVisited,
      String targetEntityTypeName)
      throws ServiceError {
    var sourceEntityType =
        entityTypeRepository
            .findByName(sourceEntityTypeName)
            .orElseThrow(
                () -> new ServiceError("Entity type " + targetEntityTypeName + " not found"));
    var mappings =
        mappingEntityTypeRelationshipRepository.findMappingEntityTypeRelationshipBySource(
            sourceEntityType);

    var targetTypes = mappings.stream().map(MappingEntityTypeRelationship::getTarget).toList();
    for (var targetType : targetTypes) {
      if (entityTypeNamesVisited.contains(targetEntityTypeName)) return true;
      else {
        entityTypeNamesVisited.add(targetType.getName());
        return checkMappingLoops(
            entityTypeRepository,
            mappingEntityTypeRelationshipRepository,
            targetType.getName(),
            entityTypeNamesVisited,
            targetEntityTypeName);
      }
    }
    return entityTypeNamesVisited.contains(targetEntityTypeName);
  }
}
