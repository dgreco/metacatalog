package it.davidgreco.metacatalog.functions.common;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;

public abstract class AbstractEntityProcedure implements EntityProcedure {

  @Override
  public void accept(Entity entity) {
    try {
      checkInputType(entity.getEntityType());
      execute(entity);
    } catch (ServiceError e) {
      throw new ServiceRuntimeError(e);
    }
  }

  protected void checkInputType(EntityType entityType) throws ServiceError {
    // do nothing
  }

  protected abstract void execute(Entity entity) throws ServiceError;
}
