package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import it.witboost.dataplatformshaper.entity.RelationType;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.entity.TraitRelationship;
import it.witboost.dataplatformshaper.repository.TraitRelationshipRepository;
import it.witboost.dataplatformshaper.repository.TraitRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraitService implements CommonTypeService<Trait> {

    private final TraitRepository traitRepository;
    private final TraitRelationshipRepository traitRelationshipRepository;

    @SuppressFBWarnings
    public TraitService(TraitRepository traitRepository, TraitRelationshipRepository traitRelationshipRepository) {
        this.traitRepository = traitRepository;
        this.traitRelationshipRepository = traitRelationshipRepository;
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public Trait create(String name, String schema, Optional<String> fatherName) throws ServiceError {
        if (traitRepository.existsByName(name)) {
            throw new ServiceError("EntityTtype " + name + " already exists");
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

    @Transactional(rollbackFor = {ServiceError.class})
    public Trait create(String name, Optional<String> fatherName) throws ServiceError {
        return create(
                name,
                """
                {
                   "type": "object",
                   "properties": {
                    }
                }""",
                fatherName);
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

    @Transactional(rollbackFor = {ServiceError.class})
    public void link(String traitName1, RelationType relType, String traitName2) throws ServiceError {
        if (!traitRepository.existsByName(traitName1))
            throw new ServiceError("EntityTtype " + traitName1 + " does not exist");
        if (!traitRepository.existsByName(traitName1))
            throw new ServiceError("EntityTtype " + traitName2 + " does not exist");
        var rel1 = new TraitRelationship();
        var trait1 = traitRepository.findByName(traitName1).get();
        var trait2 = traitRepository.findByName(traitName2).get();
        rel1.setSource(trait1);
        rel1.setTarget(trait2);
        rel1.setRelationType(relType);
        traitRelationshipRepository.save(rel1);
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public void unlink(String traitName, RelationType relType) throws ServiceError {
        var rel = traitRelationshipRepository
                .findBySourceNameAndRelationType(traitName, relType)
                .orElseThrow(() -> new ServiceError("Trait " + traitName + " does not have a relationship " + relType));
        traitRelationshipRepository.delete(rel);
    }

    @Transactional(rollbackFor = {ServiceError.class})
    public long countTraitChildren(String name) {
        return traitRepository
                .findByName(name)
                .map(traitRepository::countTraitByFather)
                .orElse(0L);
    }
}
