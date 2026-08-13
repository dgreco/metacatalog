package it.davidgreco.metacatalog.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.iceberg.registry.IcebergRegistryService;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class IcebergRegistryServiceTests extends CommonServiceTestingSupport {

  private final IcebergRegistryService registry;
  private final EntityService entityService;
  private final EntityTypeService entityTypeService;

  @Autowired
  IcebergRegistryServiceTests(
      ApplicationContext applicationContext,
      IcebergRegistryService registry,
      EntityService entityService,
      EntityTypeService entityTypeService) {
    super(applicationContext);
    this.registry = registry;
    this.entityService = entityService;
    this.entityTypeService = entityTypeService;
  }

  private static final String METADATA_JSON = "{\"format-version\": 2}";

  @Test
  void icebergModelIsInstalledImmutable() {
    assertTrue(entityTypeService.exists(IcebergModel.NAMESPACE_TYPE));
    assertTrue(entityTypeService.exists(IcebergModel.TABLE_TYPE));
    assertTrue(entityTypeService.read(IcebergModel.NAMESPACE_TYPE).isImmutable());
    assertTrue(entityTypeService.read(IcebergModel.TABLE_TYPE).isImmutable());
  }

  @Test
  void namespaceLifecycle() {
    var namespace = Namespace.of("db1");
    registry.createNamespace(namespace, Map.of("owner", "dgreco"));
    assertTrue(registry.namespaceExists(namespace));
    assertEquals(Map.of("owner", "dgreco"), registry.loadNamespaceProperties(namespace));
    assertThrows(AlreadyExistsException.class, () -> registry.createNamespace(namespace, Map.of()));

    registry.updateNamespaceProperties(namespace, List.of("owner"), Map.of("comment", "hello"));
    assertEquals(Map.of("comment", "hello"), registry.loadNamespaceProperties(namespace));

    assertTrue(registry.dropNamespace(namespace));
    assertFalse(registry.namespaceExists(namespace));
    assertFalse(registry.dropNamespace(namespace));
  }

  @Test
  void multiLevelNamespacesAndDotsInLevelsDoNotCollide() {
    // "x.y" as ONE level vs. ("x", "y") as two levels: same dotted rendering, distinct
    // namespaces — this is exactly what the \u001F key separator exists for.
    var oneLevel = Namespace.of("x.y");
    var twoLevels = Namespace.of("x", "y");
    registry.createNamespace(oneLevel, Map.of());
    registry.createNamespace(Namespace.of("x"), Map.of());
    registry.createNamespace(twoLevels, Map.of());
    assertTrue(registry.namespaceExists(oneLevel));
    assertTrue(registry.namespaceExists(twoLevels));

    // Top level: "x.y" and "x", not "x"'s child.
    var topLevel = registry.listNamespaces(Namespace.empty());
    assertTrue(topLevel.contains(oneLevel));
    assertTrue(topLevel.contains(Namespace.of("x")));
    assertFalse(topLevel.contains(twoLevels));

    // Children of "x": only ("x", "y") — pins the jsonpath \u001F prefix-escaping.
    assertEquals(List.of(twoLevels), registry.listNamespaces(Namespace.of("x")));

    assertThrows(NamespaceNotEmptyException.class, () -> registry.dropNamespace(Namespace.of("x")));
    assertThrows(
        NoSuchNamespaceException.class, () -> registry.listNamespaces(Namespace.of("nope")));
  }

  @Test
  void tableLifecycleAndMetacatalogSideEffects() {
    var namespace = Namespace.of("db2");
    var identifier = TableIdentifier.of(namespace, "orders");
    registry.createNamespace(namespace, Map.of());

    // Create is a CAS from "absent".
    assertThrows(
        NoSuchNamespaceException.class,
        () ->
            registry.casCommit(
                TableIdentifier.of(Namespace.of("missing"), "t"), null, "loc-0", METADATA_JSON));
    registry.casCommit(identifier, null, "loc-1", METADATA_JSON);
    assertTrue(registry.tableExists(identifier));
    assertEquals("loc-1", registry.findMetadataLocation(identifier));
    assertEquals(List.of(identifier), registry.listTables(namespace));
    assertThrows(
        AlreadyExistsException.class,
        () -> registry.casCommit(identifier, null, "loc-x", METADATA_JSON));

    // The metacatalog side: a HAS_PART link from the namespace entity to the table entity.
    var namespaceEntity =
        entityService.list(IcebergModel.NAMESPACE_TYPE, "$ ? (@.key == \"db2\")").getFirst();
    var linkedTables = entityService.linked(namespaceEntity.getId(), RelationType.HAS_PART);
    assertEquals(1, linkedTables.size());
    assertEquals(IcebergModel.TABLE_TYPE, linkedTables.getFirst().getEntityType().getName());

    // Update commits: matching expected location wins, stale one is refused.
    registry.casCommit(identifier, "loc-1", "loc-2", METADATA_JSON);
    assertEquals("loc-2", registry.findMetadataLocation(identifier));
    assertThrows(
        CommitFailedException.class,
        () -> registry.casCommit(identifier, "loc-1", "loc-3", METADATA_JSON));
    assertThrows(
        NoSuchTableException.class,
        () ->
            registry.casCommit(
                TableIdentifier.of(namespace, "ghost"), "loc-1", "loc-3", METADATA_JSON));

    // Rename within the namespace, then across namespaces (the link must follow).
    var renamed = TableIdentifier.of(namespace, "orders_v2");
    registry.renameTable(identifier, renamed);
    assertFalse(registry.tableExists(identifier));
    assertTrue(registry.tableExists(renamed));

    var otherNamespace = Namespace.of("db2", "archive");
    registry.createNamespace(otherNamespace, Map.of());
    var moved = TableIdentifier.of(otherNamespace, "orders_v2");
    registry.renameTable(renamed, moved);
    assertEquals(List.of(), registry.listTables(namespace));
    assertEquals(List.of(moved), registry.listTables(otherNamespace));
    assertTrue(entityService.linked(namespaceEntity.getId(), RelationType.HAS_PART).isEmpty());
    assertThrows(
        NoSuchTableException.class,
        () -> registry.renameTable(renamed, TableIdentifier.of(namespace, "z")));

    // A namespace with tables cannot be dropped; after the table is gone it can.
    assertThrows(NamespaceNotEmptyException.class, () -> registry.dropNamespace(otherNamespace));
    assertEquals("loc-2", registry.dropTable(moved));
    assertFalse(registry.tableExists(moved));
    assertNull(registry.dropTable(moved));
    assertTrue(registry.dropNamespace(otherNamespace));
    assertTrue(registry.dropNamespace(namespace));
  }

  @Test
  void concurrentCommitsOnlyOneWins() throws InterruptedException {
    var namespace = Namespace.of("db3");
    var identifier = TableIdentifier.of(namespace, "contended");
    registry.createNamespace(namespace, Map.of());
    registry.casCommit(identifier, null, "base", METADATA_JSON);

    int competitors = 4;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(competitors);
    var failures = new CopyOnWriteArrayList<Exception>();
    try (var executor = Executors.newFixedThreadPool(competitors)) {
      for (int i = 0; i < competitors; i++) {
        var newLocation = "winner-" + i;
        executor.submit(
            () -> {
              try {
                start.await();
                registry.casCommit(identifier, "base", newLocation, METADATA_JSON);
              } catch (Exception e) {
                failures.add(e);
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(done.await(60, TimeUnit.SECONDS));
    }

    // Exactly one commit moved the pointer; every loser failed with CommitFailedException.
    assertEquals(competitors - 1, failures.size());
    failures.forEach(failure -> assertTrue(failure instanceof CommitFailedException));
    var finalLocation = registry.findMetadataLocation(identifier);
    assertTrue(finalLocation.startsWith("winner-"));
  }
}
