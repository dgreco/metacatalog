package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.common.JsonUtils.mergeSchemas;

import com.fasterxml.jackson.databind.JsonNode;
import it.witboost.dataplatformshaper.entity.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public interface CommonTypeService<T extends Type> {

    default List<JsonNode> loadSchemaInheritanceChain(T trait) {
        ArrayList<JsonNode> schemaInheritanceChain = new ArrayList<>();
        schemaInheritanceChain.add(trait.getBaseSchema());
        var maybeFather = trait.getFather();
        if (maybeFather.isPresent()) {
            schemaInheritanceChain.addAll(loadSchemaInheritanceChain((T) maybeFather.get()));
            return schemaInheritanceChain;
        } else {
            return schemaInheritanceChain;
        }
    }

    default JsonNode generateDerivedSchema(T trait) throws SchemaValidationError {
        var schemaInheritanceChain = loadSchemaInheritanceChain(trait);
        Collections.reverse(schemaInheritanceChain);
        var derivedSchemaJson = mergeSchemas(schemaInheritanceChain);
        if (derivedSchemaJson.isLeft()) throw new SchemaValidationError(derivedSchemaJson.getLeft());
        else return derivedSchemaJson.get();
    }
}
