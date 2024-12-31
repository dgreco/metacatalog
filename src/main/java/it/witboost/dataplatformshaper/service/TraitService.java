package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.mergeSchemas;
import static it.witboost.dataplatformshaper.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import it.witboost.dataplatformshaper.entity.RelationType;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.entity.TraitRelationship;
import it.witboost.dataplatformshaper.repository.TraitRelationshipRepository;
import it.witboost.dataplatformshaper.repository.TraitRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Getter
@Setter
@RequiredArgsConstructor
public class TraitService implements CommonTypeService<Trait, String> {

    private final TraitRepository traitRepository;

    private final TraitRelationshipRepository traitRelationshipRepository;

    private final EntityManagerFactory entityManagerFactory;

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public Trait create(String name, String schema, Optional<String> fatherName) throws ServiceError {
        if (traitRepository.existsByName(name)) {
            throw new ServiceError("Trait " + name + " already exists");
        }
        var eitherSchema = stringToJsonSchema(schema);
        if (eitherSchema.isLeft()) throw new SchemaValidationError(eitherSchema.getLeft());
        var entityType = new Trait();
        entityType.setName(name);
        entityType.setBaseSchema(eitherSchema.get().getSchemaNode());
        List<JsonNode> schemasToMerge = new java.util.ArrayList<>();
        if (fatherName.isPresent()) {
            var father = traitRepository
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
            if (mergedSchema.isLeft()) throw new SchemaValidationError(mergedSchema.getLeft());
            entityType.setDerivedSchema(mergedSchema.get());
        }
        return traitRepository.save(entityType);
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
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

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public Trait read(String name) throws ServiceError {
        return traitRepository.findByName(name).orElseThrow(() -> new ServiceError("Trait " + name + " not found"));
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void delete(String name) throws ServiceError {
        var entityType =
                traitRepository.findByName(name).orElseThrow(() -> new ServiceError("Trait " + name + " not found"));
        traitRepository.delete(entityType);
        entityManagerFactory.getCache().evict(Trait.class, entityType.getId());
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
        var rel1 = new TraitRelationship();
        var trait1 = traitRepository
                .findByName(traitName1)
                .orElseThrow(() -> new ServiceError("Trait " + traitName1 + " not found"));
        var trait2 = traitRepository
                .findByName(traitName2)
                .orElseThrow(() -> new ServiceError("Trait " + traitName2 + " not found"));
        rel1.setSource(trait1);
        rel1.setTarget(trait2);
        rel1.setRelationType(relType);
        traitRelationshipRepository.save(rel1);
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void unlink(String traitName1, RelationType relType, String traitName2) throws ServiceError {
        var trait1 = traitRepository
                .findByName(traitName1)
                .orElseThrow(() -> new ServiceError("Trait " + traitName1 + " not found"));
        var trait2 = traitRepository
                .findByName(traitName2)
                .orElseThrow(() -> new ServiceError("Trait " + traitName2 + " not found"));
        var rel = traitRelationshipRepository
                .findBySourceAndRelationTypeAndTarget(trait1, relType, trait2)
                .orElseThrow(() -> new ServiceError(
                        "Trait " + traitName1 + " does not have a relationship " + relType + " with " + traitName2));
        traitRelationshipRepository.delete(rel);
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public List<Trait> linked(String traitName1, RelationType relType) throws ServiceError {
        var trait1 = traitRepository
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
        return traitRepository
                .findByName(name)
                .map(traitRepository::countTraitByFather)
                .orElse(0L);
    }
}
