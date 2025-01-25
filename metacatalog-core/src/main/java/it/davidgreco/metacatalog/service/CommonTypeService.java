package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.entity.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public interface CommonTypeService<T extends Type, K> extends CommonService<T, K> {

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

  default List<T> loadRevertedInheritanceChain(T type) {
    LinkedList<T> inheritanceChain = new LinkedList<>();
    inheritanceChain.add(type);
    var maybeFather = Optional.ofNullable(type.getFather());
    if (maybeFather.isPresent()) {
      inheritanceChain.addAll(loadRevertedInheritanceChain((T) maybeFather.get()));
      return inheritanceChain;
    } else {
      return inheritanceChain;
    }
  }

  default List<T> loadInheritanceChain(T type) {
    var list = loadRevertedInheritanceChain(type);
    Collections.reverse(list);
    return list;
  }
}
