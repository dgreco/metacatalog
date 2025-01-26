package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.service.CommonService.implementsTrait;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
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

  private final EntityRelationshipRepository entityRelationshipRepository;

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
        entity.setId(childAggregate.getId());
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

  /**
   * Reads an aggregate entity (i.e. a collection of entities, each potentially being an aggregate
   * itself) from the database.
   *
   * @param aggregateId the ID of the aggregate entity to read
   * @return the read aggregate
   * @throws ServiceError if an error occurs during creation
   */
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = {ServiceError.class})
  public Aggregate read(String aggregateId) throws ServiceError {
    class ReadAggregate {
      private AggregatePart readAggregatePart(String entityId) throws ServiceError {
        try {
          var entity = entityService.read(entityId);
          if (implementsTrait(entity.getEntityType(), "AggregateElement")
              && !implementsTrait(entity.getEntityType(), "Aggregate")) {
            return new AggregateElement(entity);
          } else if (implementsTrait(entity.getEntityType(), "Aggregate")) {
            var linkedEntities = entityService.linked(entityId, HAS_PART);
            var elements =
                linkedEntities.stream()
                    .map(
                        e -> {
                          try {
                            return readAggregatePart(e.getId());
                          } catch (ServiceError ex) {
                            throw new ServiceRuntimeError(ex);
                          }
                        })
                    .toList();
            return new Aggregate(entity, elements);
          } else {
            throw new ServiceError("Invalid entity type for aggregate part");
          }
        } catch (ServiceRuntimeError ex) {
          throw (ServiceError) ex.getCause();
        }
      }
    }

    if (!entityService.exists(aggregateId)) {
      throw new ServiceError("Aggregate with id: " + aggregateId + " not found");
    }

    var aggregateEntity = entityService.read(aggregateId);
    if (!entityRelationshipRepository
        .findByTargetAndRelationType(aggregateEntity, HAS_PART)
        .isEmpty()) {
      throw new ServiceError("Entity with id: " + aggregateId + " is not an aggregate");
    }

    var result =
        new ReadAggregate().readAggregatePart(aggregateId) instanceof Aggregate aggregate
            ? aggregate
            : null;

    if (result == null) {
      throw new ServiceError("Entity with id: " + aggregateId + " is not an aggregate");
    } else return result;
  }
}
