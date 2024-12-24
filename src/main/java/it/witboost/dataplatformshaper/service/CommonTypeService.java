package it.witboost.dataplatformshaper.service;

import it.witboost.dataplatformshaper.entity.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public interface CommonTypeService<T extends Type> {

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
