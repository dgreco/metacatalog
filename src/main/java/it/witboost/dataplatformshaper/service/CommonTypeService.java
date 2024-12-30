package it.witboost.dataplatformshaper.service;

import it.witboost.dataplatformshaper.entity.EntityType;
import it.witboost.dataplatformshaper.entity.Trait;
import it.witboost.dataplatformshaper.entity.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public interface CommonTypeService<T extends Type, K> extends CommonService<T, K> {

    CommonTypeService<EntityType, String> commonTypeService = new CommonTypeService<>() {

        @Override
        public void delete(String key) throws ServiceError {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public EntityType read(String key) throws ServiceError {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public boolean exists(String key) throws ServiceError {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };

    CommonTypeService<Trait, String> commonTraitService = new CommonTypeService<>() {
        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Trait read(String key) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public boolean exists(String key) {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    };

    default List<T> loadRevertedInheritanceChain(T type) {
        LinkedList<T> inheritanceChain = new LinkedList<>();
        inheritanceChain.add(type);
        var maybeFather = Optional.ofNullable(type.getFather());
        if (maybeFather.isPresent()) {
            final var b = inheritanceChain.addAll(loadRevertedInheritanceChain((T) maybeFather.get()));
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
