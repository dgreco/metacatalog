package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.common.WrappedJsonNode;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.support.DataBindingMethodResolver;

/**
 * Security policy that restricts which methods can be invoked during SpEL expression evaluation in
 * mapping values.
 *
 * <p>Only methods declared on {@link JsonNode} (or subclasses) or {@link WrappedJsonNode} are
 * resolved. Methods on any other receiver type — notably {@link Class} (so {@code
 * #x.getClass().forName(...)} is blocked) and {@link Object} — are not resolved, so SpEL throws an
 * evaluation error rather than invoking an unexpected method.
 *
 * <p>Delegation to {@link DataBindingMethodResolver#forInstanceMethodInvocation()} preserves the
 * full type-conversion and overload-selection machinery of Spring's default instance-method
 * resolver; the only extra check is the receiver-type whitelist. A name-based denylist rejects
 * mutator methods ({@code put}, {@code set}, {@code remove}, {@code replace}, {@code removeAll},
 * {@code retainAll}, {@code clear}, etc.) on the receiver before delegating, preventing an
 * admin-authored mapping expression from mutating the live {@link JsonNode} (which is the in-memory
 * state of a managed JPA entity and could be flushed by Hibernate dirty-checking).
 */
final class SpelSecurityPolicy implements MethodResolver {

  private final MethodResolver delegate = DataBindingMethodResolver.forInstanceMethodInvocation();

  private static final Set<String> MUTATOR_NAMES =
      Set.of(
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
      EvaluationContext context,
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
