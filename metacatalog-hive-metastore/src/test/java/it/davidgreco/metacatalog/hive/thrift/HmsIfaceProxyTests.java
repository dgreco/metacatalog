package it.davidgreco.metacatalog.hive.thrift;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The safety net for dispatching by name.
 *
 * <p>Satisfying {@code ThriftHiveMetastore.Iface} with a proxy avoids several hundred stub methods,
 * but it gives up the compile error that a Hive upgrade renaming or re-signing an operation would
 * otherwise produce: the handler would simply stop being reachable, and the failure would surface
 * as a client error at run time instead. These tests buy that back. If one fails after a Hive
 * version bump, the handler needs updating — that is the test doing its job, not being in the way.
 */
class HmsIfaceProxyTests {

  /** A handler standing in for the real one; the real handler needs a database behind it. */
  static class Handler {
    boolean called;

    public List<String> get_all_databases() {
      called = true;
      return List.of("one");
    }

    public String getMetaConf(String key) {
      return "";
    }
  }

  @Test
  void testEveryOperationTheHandlerClaimsExistsOnTheLiveInterface() {
    var supported = HmsIfaceProxy.supportedMethods(new MetacatalogHmsHandler(null));
    var ifaceMethods =
        Arrays.stream(ThriftHiveMetastore.Iface.class.getMethods()).map(Method::getName).toList();

    var missing = new ArrayList<String>();
    for (var name : supported.keySet()) {
      // supportedOperations is this class's own accessor, not a protocol operation.
      if ("supportedOperations".equals(name)) continue;
      if (!ifaceMethods.contains(name)) missing.add(name);
    }

    Assertions.assertTrue(
        missing.isEmpty(),
        "the handler answers to names the Thrift interface no longer has, so they are now "
            + "unreachable: "
            + missing);
  }

  @Test
  void testTheSignaturesStillMatchTheInterface() {
    var supported = HmsIfaceProxy.supportedMethods(new MetacatalogHmsHandler(null));
    var mismatched = new ArrayList<String>();

    for (var entry : supported.entrySet()) {
      if ("supportedOperations".equals(entry.getKey())) continue;
      var handlerMethod = entry.getValue();
      var matches =
          Arrays.stream(ThriftHiveMetastore.Iface.class.getMethods())
              .filter(m -> m.getName().equals(entry.getKey()))
              .anyMatch(
                  m -> Arrays.equals(m.getParameterTypes(), handlerMethod.getParameterTypes()));
      if (!matches) mismatched.add(entry.getKey());
    }

    Assertions.assertTrue(
        mismatched.isEmpty(),
        "the handler's parameters no longer match the interface, so dispatch would fail at "
            + "invocation time: "
            + mismatched);
  }

  /**
   * The return type matters as much as the parameters, and fails far less visibly. A handler method
   * returning {@code void} where the protocol operation returns a result struct makes the proxy
   * hand back {@code null}; Thrift then writes a reply with no success field, and the client sees
   * {@code TApplicationException: <op> failed: unknown result} — a message that names the operation
   * but says nothing about why. That is exactly what {@code alter_table_req} did: it is {@code
   * void} in most of Hive's flat operations but returns {@code AlterTableResponse} in the
   * request-object form the Hive 4 client actually calls, so every {@code alterTable} through a
   * real client failed while the parameter check above stayed green.
   */
  @Test
  void testTheReturnTypesStillSatisfyTheInterface() {
    var supported = HmsIfaceProxy.supportedMethods(new MetacatalogHmsHandler(null));
    var mismatched = new ArrayList<String>();

    for (var entry : supported.entrySet()) {
      if ("supportedOperations".equals(entry.getKey())) continue;
      var handlerMethod = entry.getValue();
      var counterpart =
          Arrays.stream(ThriftHiveMetastore.Iface.class.getMethods())
              .filter(m -> m.getName().equals(entry.getKey()))
              .filter(m -> Arrays.equals(m.getParameterTypes(), handlerMethod.getParameterTypes()))
              .findFirst();
      if (counterpart.isEmpty()) continue; // already reported by the signature test
      var expected = counterpart.get().getReturnType();
      // A void operation discards whatever the handler returns, so only a result-carrying one
      // constrains it.
      if (expected == void.class) continue;
      if (!expected.isAssignableFrom(handlerMethod.getReturnType())) {
        mismatched.add(
            entry.getKey()
                + " returns "
                + handlerMethod.getReturnType().getSimpleName()
                + " but the interface expects "
                + expected.getSimpleName());
      }
    }

    Assertions.assertTrue(
        mismatched.isEmpty(),
        "the handler returns nothing the protocol can put in the reply, so clients would get "
            + "'unknown result': "
            + mismatched);
  }

  @Test
  void testASupportedCallReachesTheHandler() throws Exception {
    var handler = new Handler();
    var iface = HmsIfaceProxy.wrap(handler);

    Assertions.assertEquals(List.of("one"), iface.get_all_databases());
    Assertions.assertTrue(handler.called);
  }

  @Test
  void testAnUnsupportedCallIsRefusedByName() {
    var iface = HmsIfaceProxy.wrap(new Handler());

    var refused = Assertions.assertThrows(MetaException.class, () -> iface.get_all_functions());
    Assertions.assertTrue(refused.getMessage().contains("get_all_functions"), refused.getMessage());
    Assertions.assertTrue(refused.getMessage().contains("not supported"), refused.getMessage());
  }

  @Test
  void testObjectMethodsAreAnsweredByTheProxyRatherThanDispatched() {
    var iface = HmsIfaceProxy.wrap(new Handler());

    Assertions.assertEquals(iface, iface);
    Assertions.assertNotEquals(iface, HmsIfaceProxy.wrap(new Handler()));
    Assertions.assertTrue(iface.toString().contains("supported operations"), iface.toString());
    Assertions.assertDoesNotThrow(iface::hashCode);
  }

  @Test
  void testDuplicateHandlerNamesAreRejectedRatherThanSilentlyShadowed() {
    class Ambiguous {
      public void get_database(String a) {}

      public void get_database(String a, String b) {}
    }

    var failure =
        Assertions.assertThrows(
            IllegalStateException.class, () -> HmsIfaceProxy.wrap(new Ambiguous()));
    Assertions.assertTrue(failure.getMessage().contains("get_database"), failure.getMessage());
  }
}
