package it.davidgreco.metacatalog.hive.thrift;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.TException;

/**
 * Builds the {@link ThriftHiveMetastore.Iface} the Thrift processor dispatches against, from a
 * handler that implements only the operations this metastore supports.
 *
 * <p>{@code Iface} has several hundred methods, and extends fb303's service interface on top of
 * that. Implementing it literally would mean a stub file larger than the rest of this module put
 * together, almost all of it identical throw statements. So the interface is satisfied by a dynamic
 * proxy, and the handler is an ordinary class whose public method names match the protocol ones.
 *
 * <p>The cost of that is real and worth stating: a Hive version bump that renames or re-signs a
 * method would no longer be a compile error, it would be a client failure at run time. {@code
 * HmsIfaceProxyTests} buys that back by resolving every name the handler claims against the live
 * {@code Iface} and failing if one no longer exists. Do not remove it.
 */
public final class HmsIfaceProxy {

  private HmsIfaceProxy() {}

  /**
   * Wraps a handler as a full {@code Iface}.
   *
   * @param handler an object whose public methods are named exactly as the protocol operations it
   *     supports
   * @return an {@code Iface} answering those and refusing everything else
   * @throws IllegalStateException if the handler declares two public methods of the same name,
   *     which would make dispatch ambiguous
   */
  public static ThriftHiveMetastore.Iface wrap(Object handler) {
    var supported = index(handler);
    return (ThriftHiveMetastore.Iface)
        Proxy.newProxyInstance(
            HmsIfaceProxy.class.getClassLoader(),
            new Class<?>[] {ThriftHiveMetastore.Iface.class},
            (proxy, method, args) -> invoke(handler, supported, proxy, method, args));
  }

  /** The names a handler answers to, for the guard test to check against the live interface. */
  public static Map<String, Method> supportedMethods(Object handler) {
    return Map.copyOf(index(handler));
  }

  private static Map<String, Method> index(Object handler) {
    var supported = new HashMap<String, Method>();
    for (Method method : handler.getClass().getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) continue;
      if (supported.put(method.getName(), method) != null) {
        throw new IllegalStateException(
            "Handler declares more than one public method named "
                + method.getName()
                + "; dispatch is by name and would be ambiguous");
      }
    }
    return supported;
  }

  private static Object invoke(
      Object handler, Map<String, Method> supported, Object proxy, Method method, Object[] args)
      throws Throwable {
    // Object's own methods reach the proxy too, and answering them from the handler would be
    // both wrong and confusing in a stack trace.
    switch (method.getName()) {
      case "hashCode" -> {
        if (method.getParameterCount() == 0) return System.identityHashCode(proxy);
      }
      case "equals" -> {
        if (method.getParameterCount() == 1) return proxy == args[0];
      }
      case "toString" -> {
        if (method.getParameterCount() == 0) {
          return "MetacatalogHiveMetastore(" + supported.size() + " supported operations)";
        }
      }
      default -> {
        // fall through to dispatch
      }
    }

    var target = supported.get(method.getName());
    if (target == null) {
      throw refusal(method);
    }
    try {
      return target.invoke(handler, args);
    } catch (InvocationTargetException e) {
      throw translate(method, e.getCause());
    }
  }

  private static MetaException refusal(Method method) {
    return new MetaException(
        method.getName() + " is not supported by the metacatalog Hive metastore");
  }

  /**
   * A cause that the interface method does not declare would leave the proxy throwing {@code
   * UndeclaredThrowableException}, which reaches the client as an opaque internal error. Anything
   * undeclared is therefore folded into {@code MetaException} when the method allows one, which
   * almost every metastore operation does.
   */
  private static Throwable translate(Method method, Throwable cause) {
    for (Class<?> declared : method.getExceptionTypes()) {
      if (declared.isInstance(cause)) return cause;
    }
    if (cause instanceof Error error) return error;
    for (Class<?> declared : method.getExceptionTypes()) {
      if (declared.isAssignableFrom(MetaException.class)) {
        var meta = new MetaException(messageOf(cause));
        meta.initCause(cause);
        return meta;
      }
    }
    // Nothing declared fits. TException is the protocol's own escape hatch; if even that is not
    // declared the runtime exception travels as-is.
    if (cause instanceof TException) return cause;
    return cause;
  }

  private static String messageOf(Throwable cause) {
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
