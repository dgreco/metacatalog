package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.service.ServiceUtils.hasTrait;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommonService#hasTrait}. Verifies that trait resolution walks the full
 * entity-type inheritance chain, so a trait declared on a father type is visible on entities of a
 * child type that does not directly declare the trait.
 */
class HasTraitTest {

  private static Trait trait(String name) {
    return trait(name, null);
  }

  private static Trait trait(String name, Trait father) {
    var t = new Trait();
    t.setName(name);
    t.setFather(father);
    return t;
  }

  private static EntityType entityType(String name, EntityType father, Trait... traits) {
    var e = new EntityType();
    e.setName(name);
    e.setFather(father);
    e.setTraits(List.of(traits));
    return e;
  }

  private static Entity entity(EntityType type) {
    return new Entity(type, null);
  }

  @Test
  void directTraitIsVisible() {
    var provisionable = trait("Provisionable");
    var type = entityType("MyType", null, provisionable);
    assertTrue(hasTrait(entity(type), "Provisionable"));
  }

  @Test
  void inheritedTraitFromFatherIsVisible() {
    var provisionable = trait("Provisionable");
    var father = entityType("BaseType", null, provisionable);
    var child = entityType("ChildType", father);
    assertTrue(hasTrait(entity(child), "Provisionable"));
  }

  @Test
  void inheritedTraitFromGrandfatherIsVisible() {
    var provisionable = trait("Provisionable");
    var grandfather = entityType("Root", null, provisionable);
    var father = entityType("Mid", grandfather);
    var child = entityType("Leaf", father);
    assertTrue(hasTrait(entity(child), "Provisionable"));
  }

  @Test
  void traitInheritedFromFatherTraitChainIsVisible() {
    var provisionable = trait("Provisionable");
    var resourceTrait = trait("ProvisionableResource", provisionable);
    var type = entityType("MyType", null, resourceTrait);
    assertTrue(hasTrait(entity(type), "Provisionable"));
  }

  @Test
  void absentTraitReturnsFalse() {
    var other = trait("Other");
    var type = entityType("MyType", null, other);
    assertFalse(hasTrait(entity(type), "Provisionable"));
  }

  @Test
  void noTraitsReturnsFalse() {
    var type = entityType("EmptyType", null);
    assertFalse(hasTrait(entity(type), "Provisionable"));
  }
}
