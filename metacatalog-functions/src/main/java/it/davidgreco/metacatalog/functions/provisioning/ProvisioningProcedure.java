package it.davidgreco.metacatalog.functions.provisioning;

import static it.davidgreco.metacatalog.service.CommonTypeService.genericTraitService;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.functions.common.AbstractEntityProcedure;
import it.davidgreco.metacatalog.functions.common.ProcedureExecutor;
import it.davidgreco.metacatalog.service.AggregateService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class ProvisioningProcedure extends AbstractEntityProcedure {

  static {
    ProcedureExecutor.getProcedureRegistry()
        .put("ProvisioningProcedure", ProvisioningProcedure.class);
  }

  private final AggregateService aggregateService;

  private List<Entity> getPhysicalResourceSequence(AggregateService.AggregatePart aggregate) {
    switch (aggregate) {
      case AggregateService.AggregateElement(Entity entity, List<Entity> _):
        if (hasTrait(entity, "ProvisionableResource")) {
          return List.of(entity);
        } else {
          return List.of();
        }
      case AggregateService.Aggregate(
          Entity entity,
          List<Entity> _,
          List<AggregateService.AggregatePart> elements):
        var sequence = new ArrayList<Entity>();
        if (hasTrait(entity, "ProvisionableResource")) {
          sequence.add(entity);
        }
        for (var childElement : elements) {
          sequence.addAll(getPhysicalResourceSequence(childElement));
        }
        return sequence;
      default:
        throw new IllegalArgumentException("Unknown aggregate part type: " + aggregate.getClass());
    }
  }

  private boolean hasTrait(Entity entity, String traitName) {
    return entity.getEntityType().getTraits().stream()
        .flatMap(trait -> genericTraitService.loadInheritanceChain(trait).stream())
        .map(Trait::getName)
        .collect(Collectors.toSet())
        .contains(traitName);
  }

  @Override
  protected void execute(Entity entity) throws ServiceError {
    var aggregate = aggregateService.read(entity.getId(), true);
    log.error("Provisioning entity: " + aggregate.entity().getId());
    getPhysicalResourceSequence(aggregate)
        .forEach(
            physicalResource -> log.error("Provisioning physical resource: " + physicalResource));
  }

  @Override
  protected void checkInputType(Entity entity) throws ServiceError {
    if (!hasTrait(entity, "Provisionable"))
      throw new ServiceError(
          "Entity type: " + entity.getEntityType().getName() + " has not a trait Provisionable");
  }
}
