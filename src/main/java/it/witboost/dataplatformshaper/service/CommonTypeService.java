package it.witboost.dataplatformshaper.service;

import it.witboost.dataplatformshaper.entity.Type;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public interface CommonTypeService<T extends Type> {

    private static <T extends Type> List<T> loadRevertedInheritanceChain(T type) {
        LinkedList<T> inheritanceChain = new LinkedList<>();
        inheritanceChain.add(type);
        var maybeFather = type.getFather();
        if (maybeFather.isPresent()) {
            inheritanceChain.addAll(loadRevertedInheritanceChain((T) maybeFather.get()));
            return inheritanceChain;
        } else {
            return inheritanceChain;
        }
    }

    static <T extends Type> List<T> loadInheritanceChain(T type) {
        var list = loadRevertedInheritanceChain(type);
        Collections.reverse(list);
        return list;
    }
}
