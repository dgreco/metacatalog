package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;

public abstract class AbstractEntityProcedure implements EntityProcedure {

  @Override
  public void accept(Entity entity) {
    try {
      checkInputType(entity);
      execute(entity);
    } catch (ServiceError e) {
      throw new ServiceRuntimeError(e);
    }
  }

  protected abstract void checkInputType(Entity entity) throws ServiceError;

  protected abstract void execute(Entity entity) throws ServiceError;
}
