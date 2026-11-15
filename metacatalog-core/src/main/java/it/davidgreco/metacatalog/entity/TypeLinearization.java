package it.davidgreco.metacatalog.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the Scala-style linearization of a {@link Type} hierarchy.
 *
 * <p>Metacatalog types — entity types and traits alike — support single inheritance through a
 * {@link Type#getFather() father} (the equivalent of a Scala superclass) and multiple {@link
 * Type#getTraits() traits} (the equivalent of Scala mixins). To decide, for a property defined by
 * several ancestors, which declaration wins, the ancestors are ordered with the same algorithm the
 * Scala language uses for class linearization.
 *
 * <p>Given a type {@code C} whose direct parents are, in Scala {@code extends} order, {@code C1
 * with ... with Cn} (here {@code C1} is the father and {@code C2 ... Cn} are the traits), the
 * linearization is:
 *
 * <pre>
 *   L(C) = C, L(Cn) +: ... +: L(C1)
 * </pre>
 *
 * where {@code +:} concatenates its operands keeping, for any element occurring in both, only the
 * occurrence coming from the right operand (i.e. the lower-precedence, more general one is dropped
 * so that each ancestor appears exactly once at its most general relevant position). The resulting
 * list is ordered most-specific first, with {@code C} itself at the head.
 *
 * <p>This de-duplication is what makes the algorithm correct in the presence of diamonds — for
 * example a trait mixed into a type and also inherited through its father, or two traits sharing a
 * common ancestor trait: such an ancestor contributes its schema exactly once, at a well-defined
 * precedence.
 */
public final class TypeLinearization {

  private TypeLinearization() {}

  /**
   * Returns the linearization of the given entity type's hierarchy, ordered from the most specific
   * type (the type itself, at the head) to the most general ancestor (at the tail). Every ancestor
   * reachable through the father chain and the trait mixins appears exactly once.
   *
   * @param type the entity type to linearize
   * @return the de-duplicated, most-specific-first list of the type and all its ancestors
   */
  public static List<Type<?>> linearize(EntityType type) {
    return linearizeInternal(type);
  }

  /**
   * Returns the linearization of the given trait's hierarchy: the trait itself at the head, then
   * every trait reachable through its father chain and its mixed-in traits, each exactly once.
   *
   * <p>This is what makes a diamond harmless. {@code Asset} inherits {@code Provisionable} and
   * mixes in {@code Authorizable}, and both of those descend from {@code Aggregate}; {@code
   * Aggregate} appears here once, after both, rather than once per path.
   *
   * @param trait the trait to linearize
   * @return the de-duplicated, most-specific-first list of the trait and all its ancestors
   */
  public static List<Trait> linearize(Trait trait) {
    // A trait's ancestors are all traits: only an entity type has an entity type for a father.
    return linearizeInternal(trait).stream().map(Trait.class::cast).toList();
  }

  /**
   * Recursive helper that traverses both {@link EntityType} and {@link Trait} ancestors, descending
   * through fathers and mixed-in traits alike.
   */
  private static List<Type<?>> linearizeInternal(Type<?> type) {
    // Scala: L(C) = C, L(Cn) +: ... +: L(C1), with C1 the father (superclass, lowest precedence)
    // and C2..Cn the traits (mixins) in declaration order. Folding the operands left-to-right
    // therefore starts from the highest-precedence mixin (the last declared trait) and ends with
    // the father, which is why the traits are traversed in reverse.
    List<Type<?>> combined = new ArrayList<>();
    var traits = type.getTraits();
    for (int i = traits.size() - 1; i >= 0; i--) {
      combined = writeConcat(combined, linearizeInternal(traits.get(i)));
    }
    if (type.getFather() != null) {
      combined = writeConcat(combined, linearizeInternal(type.getFather()));
    }

    List<Type<?>> result = new ArrayList<>();
    result.add(type);
    result.addAll(combined);
    return result;
  }

  /**
   * Concatenation with right-bias, the {@code +:} operator of the Scala linearization: the
   * surviving elements of {@code left} (those not present in {@code right}) come first, followed by
   * the whole of {@code right}. Duplicates are thus resolved in favour of their occurrence in
   * {@code right}.
   */
  private static List<Type<?>> writeConcat(List<Type<?>> left, List<Type<?>> right) {
    List<Type<?>> result = new ArrayList<>();
    for (Type<?> element : left) {
      if (right.stream().noneMatch(other -> sameType(element, other))) {
        result.add(element);
      }
    }
    result.addAll(right);
    return result;
  }

  /**
   * Two types are the same ancestor when they are of the same kind ({@link EntityType} vs {@link
   * Trait}) and share the same unique name. Names are unique within each kind, so this identifies a
   * type independently of the particular object instance loaded from the database.
   *
   * <p>The kind is tested with {@code instanceof}, not by comparing classes: a lazily fetched
   * father is a Hibernate proxy, whose class is a generated subclass. Comparing classes would call
   * the proxy and the loaded instance of one trait two different ancestors — precisely in a
   * diamond, where the same trait is reached once through a father and once through a mix-in.
   */
  private static boolean sameType(Type<?> a, Type<?> b) {
    return (a instanceof Trait) == (b instanceof Trait) && a.getName().equals(b.getName());
  }
}
