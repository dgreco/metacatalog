package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/** Common service interface for EntityType and Trait services. */
public interface CommonTypeService<T extends Type<T>, K> extends CommonService<T, K> {

  /**
   * Loads the inheritance chain for a given type in reverse order, starting from the type itself
   * and moving upwards to its ancestors. The returned list begins with the given type and ends with
   * the root ancestor.
   *
   * <p>The traversal depends only on {@code type}'s {@code father} chain, so it is a plain static
   * utility rather than an instance operation.
   *
   * @param type the initial type for which the reversed inheritance chain is generated
   * @param <S> the concrete type being traversed
   * @return a list of types representing the reversed inheritance chain, starting from the given
   *     type and ending with the root ancestor
   */
  static <S extends Type<S>> List<S> loadRevertedInheritanceChain(S type) {
    LinkedList<S> inheritanceChain = new LinkedList<>();
    inheritanceChain.add(type);
    var maybeFather = Optional.ofNullable(type.getFather());
    maybeFather.ifPresent(father -> inheritanceChain.addAll(loadRevertedInheritanceChain(father)));
    return inheritanceChain;
  }

  /**
   * Loads the inheritance chain for a given type in the normal order, starting from the given type
   * and moving downwards to its descendants. The returned list begins with the given type and ends
   * with its most derived descendant.
   *
   * @param type the initial type for which the inheritance chain is generated
   * @param <S> the concrete type being traversed
   * @return a list of types representing the inheritance chain, starting from the given type and
   *     ending with its most derived descendant
   */
  static <S extends Type<S>> List<S> loadInheritanceChain(S type) {
    var list = loadRevertedInheritanceChain(type);
    Collections.reverse(list);
    return list;
  }
}
