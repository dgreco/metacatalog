package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.mergeSchemas;
import static it.davidgreco.metacatalog.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.TraitRelationship;
import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Getter
@Setter
@RequiredArgsConstructor
@EnableCaching
public class TraitService implements CommonTypeService<Trait, String> {

  private final TraitRepository traitRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Trait create(String name, Optional<String> schema, Optional<String> fatherName)
      throws ServiceError {
    try {
      var eitherSchema =
          stringToJsonSchema(
              schema.orElse(
                  """
                    {
                      "type": "object",
                      "properties": {
                      }
                    }
                    """));
      if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
      var entityType = new Trait();
      entityType.setName(name);
      entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
      List<JsonNode> schemasToMerge = new java.util.ArrayList<>();
      if (fatherName.isPresent()) {
        var father =
            traitRepository
                .findByName(fatherName.get())
                .orElseThrow(
                    () -> new ServiceError("Trait " + fatherName.get() + " does not exist"));
        entityType.setFather(father);
        schemasToMerge.addAll(List.of(father.getSchema(), eitherSchema.get().getSchemaNode()));
        var mergedSchema = mergeSchemas(schemasToMerge);
        if (mergedSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        entityType.setDerivedSchema(mergedSchema.get());
      } else {
        schemasToMerge.add(eitherSchema.get().getSchemaNode());
        var mergedSchema = mergeSchemas(schemasToMerge);
        if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
        entityType.setDerivedSchema(mergedSchema.get());
      }
      return traitRepository.save(entityType);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Trait read(String name) throws ServiceError {
    return traitRepository
        .findByName(name)
        .orElseThrow(() -> new ServiceError("Trait " + name + " not found"));
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void delete(String name) throws ServiceError {
    try {
      var entityType =
          traitRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError("Trait " + name + " not found"));
      traitRepository.delete(entityType);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public boolean exists(String name) {
    return traitRepository.existsByName(name);
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void link(String traitName1, RelationType relType, String traitName2) throws ServiceError {
    try {
      if (checkLoops(traitName1, new HashSet<>(), traitName2, relType)) {
        throw new ServiceError("Loops are not allowed");
      }
      var rel1 = new TraitRelationship();
      var trait1 =
          traitRepository
              .findByName(traitName1)
              .orElseThrow(() -> new ServiceError("Trait " + traitName1 + " not found"));
      var trait2 =
          traitRepository
              .findByName(traitName2)
              .orElseThrow(() -> new ServiceError("Trait " + traitName2 + " not found"));
      rel1.setSource(trait1);
      rel1.setTarget(trait2);
      rel1.setRelationType(relType);
      traitRelationshipRepository.save(rel1);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void unlink(String traitName1, RelationType relType, String traitName2)
      throws ServiceError {
    var trait1 =
        traitRepository
            .findByName(traitName1)
            .orElseThrow(() -> new ServiceError("Trait " + traitName1 + " not found"));
    var trait2 =
        traitRepository
            .findByName(traitName2)
            .orElseThrow(() -> new ServiceError("Trait " + traitName2 + " not found"));
    var rel =
        traitRelationshipRepository
            .findBySourceAndRelationTypeAndTarget(trait1, relType, trait2)
            .orElseThrow(
                () ->
                    new ServiceError(
                        "Trait "
                            + traitName1
                            + " does not have a relationship "
                            + relType
                            + " with "
                            + traitName2));
    traitRelationshipRepository.delete(rel);
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public List<Trait> linked(String traitName1, RelationType relType) throws ServiceError {
    var trait1 =
        traitRepository
            .findByName(traitName1)
            .orElseThrow(() -> new ServiceError("Trait " + traitName1 + " not found"));
    return traitRelationshipRepository.findBySourceAndRelationType(trait1, relType).stream()
        .map(TraitRelationship::getTarget)
        .toList();
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public long countTraitChildren(String name) {
    return traitRepository.findByName(name).map(traitRepository::countTraitByFather).orElse(0L);
  }

  private boolean checkLoops(
      String sourceTraitName,
      Set<String> traitsNamesVisited,
      String targetTraitName,
      RelationType relType)
      throws ServiceError {
    var sourceTrait =
        traitRepository
            .findByName(sourceTraitName)
            .orElseThrow(() -> new ServiceError("Trait " + sourceTraitName + " not found"));
    var relationships =
        traitRelationshipRepository.findBySourceAndRelationType(sourceTrait, relType);

    var targetTraits = relationships.stream().map(TraitRelationship::getTarget).toList();
    for (var targetTrait : targetTraits) {
      if (traitsNamesVisited.contains(targetTraitName)) return true;
      else {
        traitsNamesVisited.add(targetTrait.getName());
        return checkLoops(targetTrait.getName(), traitsNamesVisited, targetTraitName, relType);
      }
    }
    return traitsNamesVisited.contains(targetTraitName);
  }
}
