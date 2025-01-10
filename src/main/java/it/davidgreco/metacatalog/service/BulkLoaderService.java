package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.yamlFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class BulkLoaderService {

    private final TraitService traitService;

    private final EntityTypeService entityTypeService;

    private final EntityService entityService;

    private final PlatformTransactionManager transactionManager;

    @Getter(lazy = true)
    private final TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    @Transactional(
            propagation = Propagation.REQUIRED,
            rollbackFor = {ServiceError.class})
    public void bulkCreation(InputStream is) throws Exception {
        try {
            var yamlParser = yamlFactory.createParser(is);

            List<ObjectNode> docs = yamlFactory
                    .readValues(yamlParser, new TypeReference<ObjectNode>() {})
                    .readAll();

            // Traits creation
            docs.stream().filter(doc -> doc.has("Traits")).findFirst().ifPresent(jsonTraits -> {
                if (!(jsonTraits.get("Traits") instanceof ArrayNode jsonTraitArray)) return;
                jsonTraitArray.forEach(jsonTrait -> {
                    try {
                        traitService.create(
                                jsonTrait.get("name").asText(),
                                jsonTrait.has("schema")
                                        ? jsonTrait.get("schema").toPrettyString()
                                        : """
                                        {
                                          "type": "object",
                                          "properties": {
                                          }
                                        }
                                        """,
                                jsonTrait.has("inheritsFrom")
                                        ? Optional.of(
                                                jsonTrait.get("inheritsFrom").asText())
                                        : Optional.empty());
                    } catch (ServiceError e) {
                        throw new RuntimeException(e);
                    }
                });
            });

            // EntityTypes creation
            docs.stream().filter(doc -> doc.has("EntityTypes")).findFirst().ifPresent(jsonTypes -> {
                if (!(jsonTypes.get("EntityTypes") instanceof ArrayNode jsonTypesArray)) return;
                jsonTypesArray.forEach(jsonType -> {
                    try {
                        traitService.create(
                                jsonType.get("name").asText(),
                                jsonType.has("schema")
                                        ? jsonType.get("schema").toPrettyString()
                                        : """
                                        {
                                          "type": "object",
                                          "properties": {
                                          }
                                        }
                                        """,
                                jsonType.has("inheritsFrom")
                                        ? Optional.of(
                                                jsonType.get("inheritsFrom").asText())
                                        : Optional.empty());
                    } catch (ServiceError e) {
                        throw new RuntimeException(e);
                    }
                });
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ServiceError) throw (ServiceError) e.getCause();
            else throw e;
        }
    }
}
