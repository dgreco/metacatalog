package it.davidgreco.metacatalog.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the Scala-style linearization of a {@link Type} hierarchy.
 *
 * <p>Metacatalog types support single inheritance through a {@link Type#getFather() father} (the
 * equivalent of a Scala superclass) and multiple {@link Type#getTraits() traits} (the equivalent of
 * Scala mixins). To decide, for a property defined by several ancestors, which declaration wins,
 * the ancestors are ordered with the same algorithm the Scala language uses for class
 * linearization.
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
   * <p>This method is specific to {@link EntityType} because only entity types can mix in traits.
   * Trait types do not participate in the trait mixin graph and therefore cannot be linearized
   * using this algorithm.
   *
   * @param type the entity type to linearize
   * @return the de-duplicated, most-specific-first list of the type and all its ancestors
   */
  public static List<Type<?>> linearize(EntityType type) {
    return linearizeInternal(type);
  }

  /**
   * Recursive helper that traverses both {@link EntityType} and {@link Trait} ancestors. The public
   * {@link #linearize(EntityType)} entry point enforces the EntityType precondition; this internal
   * variant is used for recursive descent through trait and father chains.
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
   */
  private static boolean sameType(Type<?> a, Type<?> b) {
    return a.getClass().equals(b.getClass()) && a.getName().equals(b.getName());
  }
}
