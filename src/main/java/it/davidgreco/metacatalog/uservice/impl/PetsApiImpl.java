package it.davidgreco.metacatalog.uservice.impl;

import com.example.petstore.controller.PetsApiDelegate;
import com.example.petstore.model.Pet;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Microservice implementation class.
 */
@Service
public final class PetsApiImpl implements PetsApiDelegate {
    /**
     * Native request.
     */
    private final NativeWebRequest request;

    /**
     * Constructor.
     * @param req
     */
    @Autowired
    public PetsApiImpl(final NativeWebRequest req) {
        this.request = req;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.ofNullable(request);
    }

    @Override
    public ResponseEntity<Void> createPets(final Pet pet) {
        return new ResponseEntity<Void>(HttpStatus.CREATED);
    }
}
