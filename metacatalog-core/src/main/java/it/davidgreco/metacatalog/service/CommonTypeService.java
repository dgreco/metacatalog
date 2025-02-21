package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/** Common service interface for EntityType and Trait services. */
public interface CommonTypeService<T extends Type<T>, K> extends CommonService<T, K> {

  CommonTypeService<EntityType, String> genericTypeService =
      new CommonTypeService<>() {

        @Override
        public void delete(String key) {
          throw new UnsupportedOperationException();
        }

        @Override
        public EntityType read(String key) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String key) {
          throw new UnsupportedOperationException();
        }
      };

  CommonTypeService<Trait, String> genericTraitService =
      new CommonTypeService<>() {

        @Override
        public void delete(String key) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Trait read(String key) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String key) {
          throw new UnsupportedOperationException();
        }
      };

  /**
   * Loads the inheritance chain for a given type in reverse order, starting from the type itself
   * and moving upwards to its ancestors. The returned list begins with the given type and ends with
   * the root ancestor.
   *
   * @param type the initial type for which the reversed inheritance chain is generated
   * @return a list of types representing the reversed inheritance chain, starting from the given
   *     type and ending with the root ancestor
   */
  default List<T> loadRevertedInheritanceChain(T type) {
    LinkedList<T> inheritanceChain = new LinkedList<>();
    inheritanceChain.add(type);
    var maybeFather = Optional.ofNullable(type.getFather());
    if (maybeFather.isPresent()) {
      inheritanceChain.addAll(loadRevertedInheritanceChain(maybeFather.get()));
      return inheritanceChain;
    } else {
      return inheritanceChain;
    }
  }

  /**
   * Loads the inheritance chain for a given type in the normal order, starting from the given type
   * and moving downwards to its descendants. The returned list begins with the given type and ends
   * with its most derived descendant.
   *
   * @param type the initial type for which the inheritance chain is generated
   * @return a list of types representing the inheritance chain, starting from the given type and
   *     ending with its most derived descendant
   */
  default List<T> loadInheritanceChain(T type) {
    var list = loadRevertedInheritanceChain(type);
    Collections.reverse(list);
    return list;
  }
}
