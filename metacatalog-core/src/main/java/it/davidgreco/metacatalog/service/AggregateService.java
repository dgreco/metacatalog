package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;

import it.davidgreco.metacatalog.entity.Entity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AggregateService {
  public interface AggregatePart {}

  public record AggregateElement(Entity entity) implements AggregatePart {}

  public record Aggregate(Entity entity, List<AggregatePart> elements) implements AggregatePart {}

  private final EntityService entityService;

  /**
   * Creates an aggregate entity (i.e. a collection of entities, each potentially being an aggregate
   * itself) in the database.
   *
   * @param aggregate the aggregate to create
   * @return the created aggregate with the relevant IDs set
   * @throws ServiceError if an error occurs during creation
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Aggregate create(Aggregate aggregate) throws ServiceError {
    var rootAggregate =
        entityService.create(
            aggregate.entity().getEntityType().getName(),
            aggregate.entity().getValues().toPrettyString());
    aggregate.entity().setId(rootAggregate.getId());
    for (var element : aggregate.elements()) {
      if (element instanceof AggregateElement(Entity entity)) {
        var aggregateElement =
            entityService.create(
                entity.getEntityType().getName(), entity.getValues().toPrettyString());
        entity.setId(aggregateElement.getId());
        entityService.link(rootAggregate.getId(), HAS_PART, aggregateElement.getId());
      } else if (element instanceof Aggregate(Entity entity, List<AggregatePart> elements)) {
        var childAggregate =
            entityService.create(
                entity.getEntityType().getName(), entity.getValues().toPrettyString());
        entityService.link(rootAggregate.getId(), HAS_PART, childAggregate.getId());
        for (var childElement : elements) {
          if (childElement instanceof AggregateElement(Entity anotherEntity)) {
            var childAggregateElement =
                entityService.create(
                    anotherEntity.getEntityType().getName(),
                    anotherEntity.getValues().toPrettyString());
            anotherEntity.setId(childAggregateElement.getId());
            entityService.link(childAggregate.getId(), HAS_PART, childAggregateElement.getId());
          }
        }
      }
    }
    return aggregate;
  }
}
