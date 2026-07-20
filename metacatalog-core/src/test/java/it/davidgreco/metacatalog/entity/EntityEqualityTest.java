package it.davidgreco.metacatalog.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Entity} identity semantics (JPA equals/hashCode contract). */
class EntityEqualityTest {

  @Test
  void transientEntitiesAreNotEqual() {
    // Regression: with the previous id-only equals, two unsaved entities (both id == null) compared
    // equal and collided in hash-based collections.
    Entity a = new Entity();
    Entity b = new Entity();
    assertNotEquals(a, b);

    var set = new HashSet<Entity>();
    set.add(a);
    set.add(b);
    assertEquals(2, set.size());
  }

  @Test
  void transientEntityEqualsItself() {
    Entity a = new Entity();
    assertEquals(a, a);
  }

  @Test
  void persistedEntitiesWithSameIdAreEqual() {
    Entity a = new Entity();
    Entity b = new Entity();
    a.setId("shared-id");
    b.setId("shared-id");
    assertEquals(a, b);
  }

  @Test
  void persistedEntitiesWithDifferentIdsAreNotEqual() {
    Entity a = new Entity();
    Entity b = new Entity();
    a.setId("id-1");
    b.setId("id-2");
    assertNotEquals(a, b);
  }

  @Test
  void hashCodeIsStableAcrossPersistTransition() {
    // hashCode must not change when the generated id is assigned, otherwise the entity would be
    // lost
    // in a HashSet it was added to while transient.
    Entity a = new Entity();
    int before = a.hashCode();
    a.setId("assigned-after-save");
    assertEquals(before, a.hashCode());
  }
}
