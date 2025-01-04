package it.davidgreco.metacatalog.openapi;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.openapi.model.Trait;
import it.davidgreco.metacatalog.openapi.model.ValidationError;
import it.davidgreco.metacatalog.service.SchemaValidationError;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Microservice implementation class.
 */
@Service
public final class MetacatalogApiImpl implements MetacatalogApiDelegate {

    @Autowired
    private TraitService traitService;

    /**
     * Native request.
     */
    private final NativeWebRequest request;

    /**
     * Constructor.
     * @param req
     */
    @Autowired
    public MetacatalogApiImpl(final NativeWebRequest req) {
        this.request = req;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }

    @Override
    public ResponseEntity createTrait(Trait trait) {
        try {
            if (trait.getSchema().isPresent())
                traitService.create(trait.getName(), trait.getSchema().get(), trait.getInheritsFrom());
            else traitService.create(trait.getName(), trait.getInheritsFrom());
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.errors));
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    @Override
    public ResponseEntity getTrait(String name) throws Exception {
        try {
            var trait = traitService.read(name);
            Trait dtoTrait = new Trait();
            dtoTrait.setName(trait.getName());
            dtoTrait.setSchema(Optional.of(trait.getSchema().toPrettyString()));
            dtoTrait.setInheritsFrom(
                    Optional.ofNullable(trait.getFather()).map(it.davidgreco.metacatalog.entity.Trait::getName));
            return ResponseEntity.status(200).body(dtoTrait);
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.errors));
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    @Override
    public ResponseEntity deleteTrait(String name) throws Exception {
        try {
            traitService.delete(name);
            return ResponseEntity.status(204).build();
        } catch (SchemaValidationError e) {
            return ResponseEntity.status(400).body(new ValidationError(e.errors));
        } catch (ServiceError e) {
            return ResponseEntity.status(400).body(new ValidationError(List.of(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }

    @Override
    public ResponseEntity traitExists(String name) throws Exception {
        try {
            if (traitService.exists(name)) return ResponseEntity.status(204).build();
            else return ResponseEntity.status(404).build();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }
}
