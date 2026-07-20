package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import it.davidgreco.metacatalog.common.WrappedJsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Evaluates the Spring Expression Language (SpEL) expressions embedded in a mapping definition and
 * validates the produced values against the target schema.
 *
 * <p>Extracted from {@link MappingService} so the pure "turn a mapping definition + source values
 * into validated target values" concern lives on its own, separate from persistence and lifecycle
 * management.
 */
final class MappingValueEvaluator {

  private static final ExpressionParser PARSER = new SpelExpressionParser();

  private MappingValueEvaluator() {}

  /**
   * Generates mapped values by evaluating the SpEL expressions in {@code mappingValues}.
   *
   * <p>The mapping values can reference source entity values using {@code #source} and additional
   * entity values using their aliases (e.g. {@code #alias}). Expressions are evaluated and the
   * resulting values are validated against the target schema.
   *
   * @param sourceValues the JSON values of the source entity
   * @param externalValues a map of alias to JSON values for additional entities referenced in the
   *     mapping
   * @param mappingValues the JSON mapping definition containing SpEL expressions
   * @param targetSchema the JSON schema to validate the generated values against
   * @return the generated JSON values
   * @throws ServiceError if expression evaluation fails or schema validation fails
   */
  static JsonNode generateMappedValues(
      JsonNode sourceValues,
      Map<String, JsonNode> externalValues,
      JsonNode mappingValues,
      JsonSchema targetSchema)
      throws ServiceError {

    StandardEvaluationContext context = new StandardEvaluationContext();
    context.setVariable("source", new WrappedJsonNode(sourceValues));
    externalValues.forEach((k, v) -> context.setVariable(k, new WrappedJsonNode(v)));

    var mappedValues = mappingValues.deepCopy();
    try {
      evaluateMappingValues(mappedValues, context);
    } catch (ServiceRuntimeError e) {
      throw new ServiceError("Error while evaluating mapping values: " + e.getMessage());
    }

    var res =
        targetSchema.validate(
            mappedValues.toPrettyString(),
            InputFormat.JSON,
            executionContext ->
                executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
    if (res.isEmpty()) {
      return mappedValues;
    }
    List<String> errors = new ArrayList<>(res.stream().map(ValidationMessage::toString).toList());
    throw new SchemaValidationError(errors);
  }

  private static void evaluateMappingValues(
      JsonNode mappingValues, StandardEvaluationContext context) {
    if (mappingValues.isObject()) {
      ObjectNode objectNode = (ObjectNode) mappingValues;
      objectNode
          .properties()
          .forEach(
              entry -> {
                JsonNode childNode = entry.getValue();
                if (childNode.isContainerNode()) {
                  evaluateMappingValues(childNode, context);
                } else {
                  objectNode.set(entry.getKey(), evaluateLeaf(childNode, context));
                }
              });
    } else if (mappingValues.isArray()) {
      ArrayNode arrayNode = (ArrayNode) mappingValues;
      for (int i = 0; i < arrayNode.size(); i++) {
        JsonNode childNode = arrayNode.get(i);
        if (childNode.isContainerNode()) {
          evaluateMappingValues(childNode, context);
        } else {
          arrayNode.set(i, evaluateLeaf(childNode, context));
        }
      }
    }
  }

  private static JsonNode evaluateLeaf(JsonNode leaf, StandardEvaluationContext context) {
    Expression exp = PARSER.parseExpression(leaf.asText());
    var result = exp.getValue(context);
    return switch (result) {
      case Boolean b -> BooleanNode.valueOf(b);
      case Integer i -> IntNode.valueOf(i);
      case Long l -> LongNode.valueOf(l);
      case String s -> TextNode.valueOf(s);
      case Float f -> FloatNode.valueOf(f);
      case Double d -> DoubleNode.valueOf(d);
      case null, default ->
          throw new ServiceRuntimeError("Error while evaluating expression: " + leaf.asText());
    };
  }
}
