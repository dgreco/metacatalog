package it.davidgreco.metacatalog.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the Scala-style {@link TypeLinearization#linearize} algorithm. */
class TypeLinearizationTest {

  private static Trait trait(String name, Trait father) {
    Trait t = new Trait();
    t.setName(name);
    t.setFather(father);
    return t;
  }

  private static EntityType entityType(String name, EntityType father, Trait... traits) {
    EntityType e = new EntityType();
    e.setName(name);
    e.setFather(father);
    e.setTraits(List.of(traits));
    return e;
  }

  private static List<String> names(List<Type<?>> types) {
    return types.stream().map(Type::getName).toList();
  }

  @Test
  void singleTypeLinearizesToItself() {
    var a = entityType("A", null);
    assertEquals(List.of("A"), names(TypeLinearization.linearize(a)));
  }

  @Test
  void fatherChainIsMostSpecificFirst() {
    var g = entityType("G", null);
    var f = entityType("F", g);
    var e = entityType("E", f);
    assertEquals(List.of("E", "F", "G"), names(TypeLinearization.linearize(e)));
  }

  @Test
  void lastDeclaredTraitHasHighestPrecedence() {
    // Traits [B, C]: C is declared last, so it must appear before B (more specific).
    var b = trait("B", null);
    var c = trait("C", null);
    var d = entityType("D", null, b, c);
    assertEquals(List.of("D", "C", "B"), names(TypeLinearization.linearize(d)));
  }

  @Test
  void diamondAncestorAppearsExactlyOnceAtMostGeneralPosition() {
    // A is inherited by both B and C, and D mixes in [B, C]. A must appear once, at the tail.
    var a = trait("A", null);
    var b = trait("B", a);
    var c = trait("C", a);
    var d = entityType("D", null, b, c);
    assertEquals(List.of("D", "C", "B", "A"), names(TypeLinearization.linearize(d)));
  }

  @Test
  void traitReachableViaBothFatherAndMixinIsDeduplicated() {
    // Shared trait S reachable via a mixin and via the father chain must not be duplicated.
    var s = trait("S", null);
    var father = entityType("Father", null, s);
    var child = entityType("Child", father, s);
    var result = names(TypeLinearization.linearize(child));
    assertEquals(1, result.stream().filter("S"::equals).count(), "S must appear exactly once");
    assertEquals("Child", result.getFirst());
  }

  private static Trait trait(String name, Trait father, Trait... mixins) {
    Trait t = trait(name, father);
    t.setTraits(List.of(mixins));
    return t;
  }

  @Test
  void traitMixingTraitsInLinearizesItsOwnDiamondOnce() {
    // The shape of the built-in Asset trait: a father and a mix-in sharing one ancestor.
    var aggregate = trait("Aggregate", null);
    var provisionable = trait("Provisionable", aggregate);
    var authorizable = trait("Authorizable", aggregate);
    var asset = trait("Asset", provisionable, authorizable);

    assertEquals(
        List.of("Asset", "Authorizable", "Provisionable", "Aggregate"),
        TypeLinearization.linearize(asset).stream().map(Trait::getName).toList());
  }

  @Test
  void entityTypeSeesThroughATraitsMixins() {
    var aggregate = trait("Aggregate", null);
    var provisionable = trait("Provisionable", aggregate);
    var authorizable = trait("Authorizable", aggregate);
    var asset = trait("Asset", provisionable, authorizable);
    var named = trait("WithName", null);
    var table = entityType("Table", null, asset, named);

    assertEquals(
        List.of("Table", "WithName", "Asset", "Authorizable", "Provisionable", "Aggregate"),
        names(TypeLinearization.linearize(table)));
  }

  /**
   * A lazily fetched father is a Hibernate proxy — an instance of a generated subclass. The
   * ancestor reached through it and the same ancestor reached through a loaded mix-in must still
   * count as one, or the diamond's shared trait would be listed (and merged) twice.
   */
  @Test
  void anAncestorReachedAsASubclassInstanceIsStillTheSameAncestor() {
    var aggregate = trait("Aggregate", null);
    var aggregateProxy = new Trait() {};
    aggregateProxy.setName("Aggregate");
    var provisionable = trait("Provisionable", aggregateProxy);
    var authorizable = trait("Authorizable", aggregate);
    var asset = trait("Asset", provisionable, authorizable);

    assertEquals(
        List.of("Asset", "Authorizable", "Provisionable", "Aggregate"),
        TypeLinearization.linearize(asset).stream().map(Trait::getName).toList());
  }
}
