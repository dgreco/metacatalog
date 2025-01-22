package it.davidgreco.metacatalog.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.networknt.schema.*;
import io.vavr.Tuple2;
import io.vavr.control.Either;
import java.util.*;

public class JsonUtils {

    private JsonUtils() {}

    public static final ObjectMapper jsonFactory = new ObjectMapper();

    public static final ObjectMapper yamlFactory = new ObjectMapper(new YAMLFactory());

    static {
        jsonFactory.registerModule(new Jdk8Module());
        yamlFactory.registerModule(new Jdk8Module());
    }

    public static final JsonSchemaFactory jsonSchemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public static final JsonSchema jsonSchemaSchema = jsonSchemaFactory.getSchema(
            SchemaLocation.of(SchemaId.V201909),
            SchemaValidatorsConfig.builder().build());

    public static final Configuration jsonPathConfiguration = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .build();

    private static final Set<String> notAllowedKeywords = Set.of(
            "$schema",
            "$anchor",
            "$ref",
            "$id",
            "if",
            "then",
            "else",
            "dependentRequired",
            "dependentSchemas",
            "allOf",
            "anyOf",
            "oneOf",
            "not",
            "unevaluatedProperties");

    /**
     * Check if a given JSON node contains any of the keywords that are not
     * allowed in a Metacatalog schema.
     *
     * @param node the JSON node to check
     * @return true if the node contains any of the not allowed keywords,
     *         false otherwise
     */
    private static boolean checkNotAllowedKeywords(JsonNode node) {
        if (node.isObject()) {
            var entries = node.fields();
            while (entries.hasNext()) {
                var key = entries.next().getKey();
                var value = node.get(key);
                if (notAllowedKeywords.contains(key)) {
                    return true;
                } else if (value.isObject()) {
                    if (checkNotAllowedKeywords(value)) return true;
                } else if (value.isArray()) {
                    var array = value.elements();
                    while (array.hasNext()) {
                        if (checkNotAllowedKeywords(array.next())) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Converts the field types of a JSON node to "string" if they are not
     * already "string" or "object". This method recursively traverses
     * through the JSON node and its nested objects or arrays, ensuring that
     * all non-object, non-string types are set to "string".
     *
     * @param node the JSON node whose field types are to be converted
     */
    private static void convertFieldTypeToStringType(JsonNode node) {
        if (node.isObject()) {
            var entries = node.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var key = entry.getKey();
                var value = node.get(key);
                if (value.isObject()) {
                    convertFieldTypeToStringType(value);
                } else if (value.isArray()) {
                    var array = value.elements();
                    while (array.hasNext()) {
                        convertFieldTypeToStringType(array.next());
                    }
                } else {
                    if (!value.asText().equals("string") && !value.asText().equals("object")) {
                        entry.setValue(JsonNodeFactory.instance.textNode("string"));
                    }
                }
            }
        }
    }

    public static Either<List<String>, JsonSchema> convertToMappingSchema(JsonSchema jsonSchema) {
        var node = jsonSchema.getSchemaNode().deepCopy();
        convertFieldTypeToStringType(node.get("properties"));
        return Either.right(jsonSchemaFactory.getSchema(node));
    }

    /**
     * Validate a given JSON schema string and return it as a {@link JsonSchema}
     * if it is valid. Otherwise, return the validation errors as a list of
     * strings.
     *
     * @param json the JSON schema string to validate
     * @return the validated JSON schema if it is valid, otherwise a list of
     *         validation errors
     */
    public static Either<List<String>, JsonSchema> stringToJsonSchema(String json) {
        try {
            var schemaNode = jsonFactory.readTree(json);
            var res = jsonSchemaSchema.validate(
                    schemaNode.toPrettyString(),
                    InputFormat.JSON,
                    executionContext -> executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
            if (checkNotAllowedKeywords(schemaNode))
                return Either.left(List.of("The schema contains not allowed keywords"));
            if (res.isEmpty()) return Either.right(jsonSchemaFactory.getSchema(schemaNode));
            else {
                List<String> errors = new ArrayList<>(
                        res.stream().map(ValidationMessage::toString).toList());
                return Either.left(errors);
            }
        } catch (JsonProcessingException e) {
            return Either.left(List.of(e.getMessage()));
        }
    }

    /**
     * Merge a list of JSON schemas into a single JSON schema.
     *
     * @param schemas the JSON schemas to merge
     * @return the merged JSON schema if it is valid, otherwise a list of
     *         validation errors
     */
    public static Either<List<String>, JsonNode> mergeSchemas(List<JsonNode> schemas) {
        Map<String, Tuple2<JsonNode, Boolean>> propertiesToMerge = new HashMap<>();
        List<String> propertiesNamesToMerge = new LinkedList<>();
        for (JsonNode node : schemas) {
            var requiredProperties = new HashSet<String>();
            var maybeRequired = Optional.<JsonNode>empty();
            if (node.has("required")) maybeRequired = Optional.of(node.get("required"));
            maybeRequired.ifPresent(required -> {
                if (required.isArray()) {
                    required.elements()
                            .forEachRemaining(requiredElement -> requiredProperties.add(requiredElement.asText()));
                }
            });
            if (!node.has("properties")) return Either.left(List.of("The schema must have a properties field"));

            node.get("properties").fields().forEachRemaining(entry -> {
                var key = entry.getKey();
                var value = entry.getValue();
                var isRequired = requiredProperties.contains(key);
                if (!propertiesToMerge.containsKey(key)) propertiesNamesToMerge.add(key);
                propertiesToMerge.put(key, new Tuple2<>(value, isRequired));
            });
        }
        var mergedSchemaJson = jsonFactory.createObjectNode();
        var properties = jsonFactory.createObjectNode();
        var required = jsonFactory.createArrayNode();

        propertiesNamesToMerge.forEach(propertyName -> {
            var tuple = propertiesToMerge.get(propertyName);
            var value = tuple._1();
            properties.set(propertyName, value);
            if (tuple._2()) required.add(propertyName);
        });
        mergedSchemaJson.put("type", "object");
        mergedSchemaJson.set("properties", properties);
        mergedSchemaJson.set("required", required);
        mergedSchemaJson.put("additionalProperties", false);
        var res = jsonSchemaSchema.validate(
                mergedSchemaJson.toPrettyString(),
                InputFormat.JSON,
                executionContext -> executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
        if (res.isEmpty()) return Either.right(mergedSchemaJson);
        else {
            List<String> errors = new ArrayList<>(
                    res.stream().map(ValidationMessage::toString).toList());
            return Either.left(errors);
        }
    }
}
