package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.repository.TraitRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraitService implements CommonTypeService<Trait> {

    private final TraitRepository traitRepository;

    @SuppressFBWarnings
    public TraitService(TraitRepository traitRepository) {
        this.traitRepository = traitRepository;
    }

    @Transactional(rollbackFor = {SchemaValidationError.class})
    public Trait create(String name, final String schema, Optional<String> fatherName)
            throws JsonProcessingException, SchemaValidationError {
        if (traitRepository.existsByName(name)) {
            throw new RuntimeException("EntityTtype " + name + " already exists");
        }
        var eitherSchema = stringToJsonSchema(schema);
        if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        var entityType = new Trait();
        entityType.setName(name);
        entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
        var father = fatherName.flatMap(traitRepository::findByName);
        if (father.isPresent()) {
            entityType.setFather(father.get());
            entityType.setDerivedSchema(generateDerivedSchema(entityType));
        }
        return traitRepository.save(entityType);
    }

    @Transactional
    public Optional<Trait> read(String name) {
        return traitRepository.findByName(name);
    }

    @Transactional
    public void delete(String name) throws ServiceError {
        var entityType = traitRepository
                .findByName(name)
                .orElseThrow(() -> new ServiceError("EntityType " + name + " not found"));
        traitRepository.delete(entityType);
    }

    @Transactional
    public boolean exists(String name) {
        return traitRepository.existsByName(name);
    }

    @Transactional
    public long countTraitChildren(String name) {
        return traitRepository
                .findByName(name)
                .map(traitRepository::countTraitByFather)
                .orElse(0L);
    }
}
