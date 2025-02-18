package it.davidgreco.metacatalog.functions.provisioning;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.service.Task;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Getter
@Component
public abstract class ProvisioningTask extends Task<Entity> {

  protected ProvisioningTask(Entity entity) {
    super(entity);
  }

  @Override
  public abstract Void apply();
}
