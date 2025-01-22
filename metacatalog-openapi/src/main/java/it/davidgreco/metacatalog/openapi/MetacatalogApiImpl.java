package it.davidgreco.metacatalog.openapi;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.*;
import it.davidgreco.metacatalog.service.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Microservice implementation class.
 */
@Service
@RequiredArgsConstructor
public class MetacatalogApiImpl implements MetacatalogApiDelegate {

    private static final String INVALID_RELATIONSHIP_TYPE = "Invalid relationship type";

    private final TraitService traitService;

    private final EntityTypeService entityTypeService;

    private final EntityService entityService;

    private final MappingService mappingService;

    private final BulkLoaderService bulkLoaderService;

    private final NativeWebRequest request;

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }

    @Override
    public ResponseEntity createTrait(Trait trait) {
        try {
            if (trait.getSchema().isPresent())
                traitService.create(
                        trait.getName(),
                        Optional.of(trait.getSchema().orElseThrow(RuntimeException::new)),
                        trait.getInheritsFrom());
            else traitService.create(trait.getName(), Optional.empty(), trait.getInheritsFrom());
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity getTrait(String name) {
        try {
            var trait = traitService.read(name);
            Trait dtoTrait = new Trait();
            dtoTrait.setName(trait.getName());
            dtoTrait.setSchema(Optional.of(trait.getSchema().toPrettyString()));
            dtoTrait.setInheritsFrom(
                    Optional.ofNullable(trait.getFather()).map(it.davidgreco.metacatalog.entity.Trait::getName));
            return ResponseEntity.status(200).body(dtoTrait);
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity deleteTrait(String name) {
        try {
            traitService.delete(name);
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity existsTrait(String name) {
        try {
            if (traitService.exists(name)) return ResponseEntity.status(204).build();
            else return ResponseEntity.status(404).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity linkTrait(LinkTraitRequest linkTrait) {
        try {
            RelationType relType;
            relType = RelationType.valueOf(linkTrait.getRelationshipTypeName());
            traitService.link(linkTrait.getSourceTrait(), relType, linkTrait.getTargetTrait());
            return ResponseEntity.status(204).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity linkedTrait(String nameTrait1, String relationshipTypeName) throws Exception {
        try {
            var relType = RelationType.valueOf(relationshipTypeName);
            var traits = traitService.linked(nameTrait1, relType).stream().map(t -> {
                Trait trait = new Trait();
                trait.setName(t.getName());
                trait.setSchema(Optional.of(t.getSchema().toPrettyString()));
                trait.setInheritsFrom(
                        Optional.ofNullable(t.getFather()).map(it.davidgreco.metacatalog.entity.Trait::getName));
                return trait;
            });
            return ResponseEntity.status(200).body(traits);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity unlinkTrait(String sourceTrait, String relationshipTypeName, String targetTrait) {
        try {
            RelationType relType;
            relType = RelationType.valueOf(relationshipTypeName);
            traitService.unlink(sourceTrait, relType, targetTrait);
            return ResponseEntity.status(204).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity existsLinkTrait(String sourceTrait, String relationshipTypeName, String targetTrait) {
        try {
            RelationType relType;
            relType = RelationType.valueOf(relationshipTypeName);
            var linkedTraitsNames = traitService.linked(sourceTrait, relType).stream()
                    .map(it.davidgreco.metacatalog.entity.Trait::getName)
                    .collect(Collectors.toSet());
            if (linkedTraitsNames.contains(targetTrait))
                return ResponseEntity.status(200).build();
            else return ResponseEntity.status(404).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(INVALID_RELATIONSHIP_TYPE)));
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity createEntityType(EntityType entityType) {
        try {
            entityTypeService.create(
                    entityType.getName(), entityType.getTraits(), entityType.getInheritsFrom(), entityType.getSchema());
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity deleteEntityType(String name) {
        try {
            entityTypeService.delete(name);
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity existsEntityType(String name) {
        try {
            if (entityTypeService.exists(name))
                return ResponseEntity.status(204).build();
            else return ResponseEntity.status(404).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity getEntityType(String name) {
        try {
            var type = entityTypeService.read(name);
            EntityType dtoType = new EntityType();
            dtoType.setName(type.getName());
            dtoType.setSchema(type.getSchema().toPrettyString());
            dtoType.setTraits(type.getTraits().stream()
                    .map(it.davidgreco.metacatalog.entity.Trait::getName)
                    .toList());
            dtoType.setInheritsFrom(
                    Optional.ofNullable(type.getFather()).map(it.davidgreco.metacatalog.entity.EntityType::getName));
            return ResponseEntity.status(200).body(dtoType);
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity createEntity(Entity entity) {
        try {
            var ent = entityService.create(entity.getEntityType(), entity.getValues());
            return ResponseEntity.status(200).body(ent.getId());
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity deleteEntity(String id) {
        try {
            entityService.delete(id);
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity existsEntity(String id) {
        try {
            if (entityService.exists(id)) return ResponseEntity.status(204).build();
            else return ResponseEntity.status(404).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity getEntity(String id) {
        try {
            var entity = entityService.read(id);
            Entity dtoEntity = new Entity();
            dtoEntity.setId(entity.getId());
            dtoEntity.setEntityType(entity.getEntityType().getName());
            dtoEntity.setValues(entity.getValues().toPrettyString());
            return ResponseEntity.status(200).body(dtoEntity);
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }

    @Override
    public ResponseEntity bulkCreation(Resource body) throws Exception {
        try {
            bulkLoaderService.bulkCreation(body.getInputStream());
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.getErrors()));
        } catch (ServiceError | DataIntegrityViolationException e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new SystemError(e.getMessage()));
        }
    }
}
