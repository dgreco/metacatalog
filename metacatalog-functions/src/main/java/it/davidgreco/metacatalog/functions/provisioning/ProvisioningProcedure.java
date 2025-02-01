package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.CommonTypeService.genericTraitService;
import static it.davidgreco.metacatalog.service.CommonTypeService.genericTypeService;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.functions.common.AbstractEntityProcedure;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProvisioningProcedure extends AbstractEntityProcedure {

  @Override
  protected void execute(Entity entity) {
    log.error("Provisioning entity: " + entity.getId());
  }

  @Override
  protected void checkInputType(EntityType entityType) throws ServiceError {
    if (!genericTypeService.loadInheritanceChain(entityType).stream()
        .flatMap(
            et ->
                et.getTraits().stream()
                    .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream()))
        .map(Trait::getName)
        .collect(Collectors.toSet())
        .contains("Provisionable"))
      throw new ServiceError(
          "Entity type: " + entityType.getName() + " has not a trait Provisionable");
  }
}
