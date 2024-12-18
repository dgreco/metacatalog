package it.witboost.dataplatformshaper.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import io.vavr.control.Either;
import java.util.ArrayList;
import java.util.List;

public class JsonUtils {

    public static ObjectMapper jsonFactory = new ObjectMapper();

    public static JsonSchemaFactory jsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909);

    public static JsonSchema jsonSchemaSchema = jsonSchemaFactory.getSchema(
            SchemaLocation.of(SchemaId.V201909),
            SchemaValidatorsConfig.builder().build());

    public static Either<List<String>, JsonSchema> stringToJsonSchema(String json) throws JsonProcessingException {
        var schemaNode = jsonFactory.readTree(json);
        var res = jsonSchemaSchema.validate(
                schemaNode.toPrettyString(),
                InputFormat.JSON,
                executionContext -> executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
        if (res.isEmpty()) return Either.right(jsonSchemaFactory.getSchema(schemaNode));
        else {
            List<String> errors = new ArrayList<>(
                    res.stream().map(ValidationMessage::toString).toList());
            return Either.left(errors);
        }
    }
}
