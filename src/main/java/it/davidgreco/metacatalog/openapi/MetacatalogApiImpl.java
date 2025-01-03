package it.davidgreco.metacatalog.openapi;

import static it.davidgreco.metacatalog.common.JsonUtils.yamlFactory;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.TraitService;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
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
    public ResponseEntity<Void> createTrait(Resource body) throws Exception {

        var payloadNode = yamlFactory.readTree(body.getInputStream());
        var name = Optional.ofNullable(payloadNode.get("name"))
                .map(JsonNode::asText)
                .orElseThrow(() -> new ServiceError("Missing 'name' field"));
        var inheritsFrom = Optional.ofNullable(payloadNode.get("name")).map(JsonNode::asText);
        var schema = Optional.ofNullable(payloadNode.get("schema"))
                .orElseThrow(() -> new ServiceError("Missing 'schema' field"));

        traitService.create(name, schema.toPrettyString(), inheritsFrom);

        System.out.println(payloadNode.toPrettyString());
        return ResponseEntity.ok().build(); // MetacatalogApiDelegate.super.createTrait(body);
    }
}
