package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.repository.EntityTypeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityTypeService implements CommonTypeService<EntityType> {

    private final EntityTypeRepository entityTypeRepository;

    @SuppressFBWarnings
    public EntityTypeService(EntityTypeRepository entityTypeRepository) {
        this.entityTypeRepository = entityTypeRepository;
    }

    @Transactional(rollbackFor = {SchemaValidationError.class})
    public EntityType create(String name, List<String> traits, Optional<String> fatherName, String schema)
            throws JsonProcessingException, SchemaValidationError {
        if (entityTypeRepository.existsByName(name)) {
            throw new RuntimeException("EntityTtype " + name + " already exists");
        }
        var eitherSchema = stringToJsonSchema(schema);
        if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        var entityType = new EntityType();
        entityType.setName(name);
        entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
        var father = fatherName.flatMap(entityTypeRepository::findByName);
        if (father.isPresent()) {
            if (!entityTypeRepository.existsByName(father.get().getName()))
                throw new RuntimeException("The inherited EntityType " + name + " does not exist");
            entityType.setFather(father.get());
            entityType.setDerivedSchema(generateDerivedSchema(entityType));
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
