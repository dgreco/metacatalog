package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.mergeSchemas;
import static it.davidgreco.metacatalog.common.JsonUtils.stringToJsonSchema;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.repository.EntityTypeRepository;
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
public class EntityTypeService implements CommonTypeService<EntityType, String> {

  private static final String ENTITYTYPE = "EntityType ";

  private final EntityTypeRepository entityTypeRepository;

  private final TraitRepository traitRepository;

  /**
   * Creates a new EntityType with the specified name, traits, optional father, and schema.
   *
   * <p>This method validates that each trait in the list exists and is unique. It also converts the
   * provided schema to a JSON schema and merges it with the schemas of the traits and the optional
   * father EntityType. The resulting EntityType is stored in the repository.
   *
   * @param name the name for the new EntityType
   * @param traits a list of trait names to associate with the EntityType
   * @param fatherName an optional name of the father EntityType, if any
   * @param schema the JSON schema string for the EntityType
   * @return the newly created and persisted EntityType
   * @throws ServiceError if a trait does not exist, a schema validation error occurs, or a data
   *     integrity violation occurs
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class, DataIntegrityViolationException.class})
  public EntityType create(
      String name, List<String> traits, Optional<String> fatherName, String schema)
      throws ServiceError {
    try {
      Set<String> traitNamesSet = new HashSet<>();
      traits.forEach(
          trait -> {
            if (traitNamesSet.contains(trait))
              throw new ServiceRuntimeError("Trait " + trait + " already defined");
            else traitNamesSet.add(trait);
          });
      List<Trait> traitsList;
      traitsList =
          traits.stream()
              .map(
                  trait ->
                      traitRepository
                          .findByName(trait)
                          .orElseThrow(
                              () -> new ServiceRuntimeError("Trait " + trait + " does not exist")))
              .toList();
      var eitherSchema = stringToJsonSchema(schema);
      if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
      var entityType = new EntityType();
      entityType.setTraits(traitsList);
      entityType.setName(name);
      entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
      var traitSchemas = traitsList.stream().map(Trait::getSchema).toList();
      var schemasToMerge = new java.util.ArrayList<>(traitSchemas);
      if (fatherName.isPresent()) {
        var father =
            entityTypeRepository
                .findByName(fatherName.get())
                .orElseThrow(() -> new ServiceError(ENTITYTYPE + fatherName + " does not exist"));
        entityType.setFather(father);
        schemasToMerge.addAll(List.of(father.getSchema(), eitherSchema.get().getSchemaNode()));
        var mergedSchema = mergeSchemas(schemasToMerge);
        if (mergedSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        entityType.setDerivedSchema(mergedSchema.get());
      } else {
        schemasToMerge.add(eitherSchema.get().getSchemaNode());
        var mergedSchema = mergeSchemas(schemasToMerge);
        if (mergedSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        entityType.setDerivedSchema(mergedSchema.get());
      }
      return entityTypeRepository.save(entityType);
    } catch (ServiceRuntimeError | DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public EntityType read(String name) throws ServiceError {
    return entityTypeRepository
        .findByName(name)
        .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
  }

  /**
   * Deletes an entity type given its name.
   *
   * @param name the name of the entity type to delete
   * @throws ServiceError if the entity type is not found, or if a {@link
   *     DataIntegrityViolationException} occurs while deleting the entity type
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public void delete(String name) throws ServiceError {
    try {
      var entityType =
          entityTypeRepository
              .findByName(name)
              .orElseThrow(() -> new ServiceError(ENTITYTYPE + name + " not found"));
      entityTypeRepository.delete(entityType);
    } catch (DataIntegrityViolationException e) {
      throw new ServiceError(e.getMessage());
    }
  }

  /**
   * Checks if an entity type with the given name exists.
   *
   * @param name the name of the entity type to check
   * @return true if an entity type with the given name exists, false otherwise
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean exists(String name) {
    return entityTypeRepository.existsByName(name);
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public long countEntityTypeChildren(String name) {
    return entityTypeRepository
        .findByName(name)
        .map(entityTypeRepository::countEntityTypeByFather)
        .orElse(0L);
  }
}
