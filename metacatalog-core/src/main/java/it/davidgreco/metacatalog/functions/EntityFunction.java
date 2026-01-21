package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import java.util.function.Function;

/**
 * Functional interface for functions that transform entities.
 *
 * <p>Entity functions take an entity as input and return a transformed entity as output. This
 * interface extends {@link Function} with Entity as both input and output types.
 *
 * @see EntityProcedure
 * @see ProcedureExecutor
 */
public interface EntityFunction extends Function<Entity, Entity> {}
