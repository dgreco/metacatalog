package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.mergeSchemas;
import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.TraitRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityTypeService implements CommonTypeService<EntityType> {

    private final EntityTypeRepository entityTypeRepository;
    private final TraitRepository traitRepository;

    @SuppressFBWarnings
    public EntityTypeService(EntityTypeRepository entityTypeRepository, TraitRepository traitRepository) {
        this.entityTypeRepository = entityTypeRepository;
        this.traitRepository = traitRepository;
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public EntityType create(String name, List<String> traits, Optional<String> fatherName, String schema)
            throws ServiceError {
        if (entityTypeRepository.existsByName(name)) {
            throw new ServiceError("EntityTtype " + name + " already exists");
        }
        Set<String> traitNamesSet = new HashSet<>();
        try {
            traits.forEach(trait -> {
                if (traitNamesSet.contains(trait)) throw new RuntimeException("Trait " + trait + " already defined");
                else traitNamesSet.add(trait);
            });
        } catch (RuntimeException e) {
            throw new ServiceError(e.getMessage());
        }
        List<Trait> traitsList = null;
        try {
            traitsList = traits.stream()
                    .map(trait -> traitRepository
                            .findByName(trait)
                            .orElseThrow(() -> new RuntimeException("Trait " + trait + " does not exist")))
                    .toList();
        } catch (RuntimeException e) {
            throw new ServiceError(e.getMessage());
        }
        var eitherSchema = stringToJsonSchema(schema);
        if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        var entityType = new EntityType();
        entityType.setTraits(traitsList);
        entityType.setName(name);
        entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
        var traitSchemas = traitsList.stream().map(Trait::getSchema).toList();
        var schemasToMerge = new java.util.ArrayList<>(traitSchemas);
        if (fatherName.isPresent()) {
            var father = entityTypeRepository
                    .findByName(fatherName.get())
                    .orElseThrow(() -> new ServiceError("EntityType " + fatherName + " does not exist"));
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
    }

    @Transactional
    public Optional<EntityType> read(String name) {
        return entityTypeRepository.findByName(name);
    }

    @Transactional
    public void delete(String name) throws ServiceError {
        var entityType = entityTypeRepository
                .findByName(name)
                .orElseThrow(() -> new ServiceError("EntityType " + name + " not found"));
        entityTypeRepository.delete(entityType);
    }

    @Transactional
    public boolean exists(String name) {
        return entityTypeRepository.existsByName(name);
    }

    @Transactional
    public long countEntityTypeChildren(String name) {
        return entityTypeRepository
                .findByName(name)
                .map(entityTypeRepository::countEntityTypeByFather)
                .orElse(0L);
    }
}
