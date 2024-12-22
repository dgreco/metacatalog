package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.mergeSchemas;
import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import it.witboost.dataplatformshaper.repository.TraitRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @Transactional(rollbackFor = {SchemaValidationError.class})
    public EntityType create(String name, List<String> traits, Optional<String> fatherName, String schema)
            throws JsonProcessingException, SchemaValidationError {
        if (entityTypeRepository.existsByName(name)) {
            throw new RuntimeException("EntityTtype " + name + " already exists");
        }
        Set<String> traitNamesSet = new HashSet<>();
        traits.forEach(trait -> {
            if (traitNamesSet.contains(trait)) throw new RuntimeException("Trait " + trait + " already defined");
            else traitNamesSet.add(trait);
        });
        List<Trait> traitsList = traits.stream()
                .map(trait -> traitRepository
                        .findByName(trait)
                        .orElseThrow(() -> new RuntimeException("Trait " + trait + " does not exist")))
                .toList();
        var eitherSchema = stringToJsonSchema(schema);
        if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        var entityType = new EntityType();
        entityType.setTraits(traitsList);
        entityType.setName(name);
        entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
        var traitSchemas = traitsList.stream().map(Trait::getSchema).toList();
        var schemasToMerge = new java.util.ArrayList<>(traitSchemas);
        var father = fatherName.flatMap(entityTypeRepository::findByName);
        if (father.isPresent()) {
            entityType.setFather(father.get());
            schemasToMerge.addAll(
                    List.of(father.get().getSchema(), eitherSchema.get().getSchemaNode()));
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
