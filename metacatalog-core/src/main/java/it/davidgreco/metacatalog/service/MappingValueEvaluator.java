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
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingMethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Evaluates the Spring Expression Language (SpEL) expressions embedded in a mapping definition and
 * validates the produced values against the target schema.
 *
 * <p>Extracted from {@link MappingService} so the pure "turn a mapping definition + source values
 * into validated target values" concern lives on its own, separate from persistence and lifecycle
 * management.
 *
 * <p><b>Security note:</b> SpEL expressions in mapping values are evaluated with a {@link
 * SimpleEvaluationContext} configured for read-only data binding. Instance-method invocation is
 * restricted to a custom {@link MethodResolver} ({@link WhitelistedMethodResolver}) that only
 * resolves methods declared on {@link JsonNode} or {@link WrappedJsonNode}. This blocks every known
 * remote-code-execution vector: type references ({@code T(java.lang.Runtime).getRuntime()}),
 * constructors ({@code new ProcessBuilder(...)}), static method calls, the {@code getClass()} chain
 * ({@code #x.getClass().forName(...).getMethod(...).invoke(...)}), and any method inherited from
 * {@link Object}.
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

    SimpleEvaluationContext context =
        SimpleEvaluationContext.forReadOnlyDataBinding()
            .withMethodResolvers(new WhitelistedMethodResolver())
            .build();
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
      JsonNode mappingValues, SimpleEvaluationContext context) {
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

  private static JsonNode evaluateLeaf(JsonNode leaf, SimpleEvaluationContext context) {
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

  /**
   * A {@link MethodResolver} that only resolves methods when the receiver (target object) is a
   * {@link JsonNode} (or subclass) or a {@link WrappedJsonNode}. Methods on any other receiver type
   * — notably {@link Class} (so {@code #x.getClass().forName(...)} is blocked) and {@link Object} —
   * are not resolved, so SpEL throws an evaluation error rather than invoking an unexpected method.
   *
   * <p>Delegation to {@link DataBindingMethodResolver#forInstanceMethodInvocation()} preserves the
   * full type-conversion and overload-selection machinery of Spring's default instance-method
   * resolver; the only extra check is the receiver-type whitelist. Note that {@code
   * forInstanceMethodInvocation()} does <strong>not</strong> filter mutators — it resolves any
   * instance method not declared on {@link Object}, {@link Class}, or {@link ClassLoader}. To
   * prevent an admin-authored mapping expression from mutating the live {@link JsonNode} (which is
   * the in-memory state of a managed JPA entity and could be flushed by Hibernate dirty-checking),
   * a name-based denylist rejects methods matching {@code put}, {@code set}, {@code remove}, {@code
   * replace}, {@code removeAll}, {@code retainAll}, and {@code clear} on the receiver before
   * delegating.
   */
  static final class WhitelistedMethodResolver implements MethodResolver {

    private final MethodResolver delegate = DataBindingMethodResolver.forInstanceMethodInvocation();

    /**
     * Method-name prefixes and exact names that mutate a {@link JsonNode} / {@link ObjectNode} /
     * {@link ArrayNode}. Resolving these is refused so a mapping expression cannot call, e.g.,
     * {@code #source.node.removeAll()} or {@code #source.node.put("evil", 1)}.
     */
    private static final java.util.Set<String> MUTATOR_NAMES =
        java.util.Set.of(
            "put",
            "putAll",
            "putIfAbsent",
            "set",
            "setAll",
            "remove",
            "removeAll",
            "retainAll",
            "replace",
            "replaceAll",
            "clear",
            "add",
            "addAll",
            "insert",
            "append",
            "assign",
            "with",
            "removeNode");

    @Override
    public MethodExecutor resolve(
        org.springframework.expression.EvaluationContext context,
        Object targetObject,
        String name,
        List<TypeDescriptor> argumentTypes)
        throws AccessException {
      if (targetObject == null) return null;
      if (!(targetObject instanceof JsonNode) && !(targetObject instanceof WrappedJsonNode))
        return null;
      if (MUTATOR_NAMES.contains(name)) return null;
      return delegate.resolve(context, targetObject, name, argumentTypes);
    }
  }
}
