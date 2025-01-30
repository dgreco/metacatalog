package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;
import static it.davidgreco.metacatalog.service.CommonService.implementsTrait;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service class for managing aggregate entities. An aggregate entity is a collection of entities,
 * each potentially being an aggregate itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregateService {

  public interface AggregatePart {}

  public record AggregateElement(Entity entity) implements AggregatePart {}

  public record Aggregate(Entity entity, List<AggregatePart> elements) implements AggregatePart {}

  private final EntityService entityService;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

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
    log.info("Creating aggregate: {}", aggregate);
    try {
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
    } finally {
      log.info("Created aggregate: {}", aggregate);
    }
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
  public Aggregate read(String aggregateId, boolean retrieveMappedInstances) throws ServiceError {
    class ReadAggregate {
      private AggregatePart readAggregatePart(Entity entity) throws ServiceError {
        try {
          if (implementsTrait(entity.getEntityType(), "AggregateElement")
              && !implementsTrait(entity.getEntityType(), "Aggregate")) {
            return new AggregateElement(entity);
          } else if (implementsTrait(entity.getEntityType(), "Aggregate")) {
            var linkedEntities = entityService.linked(entity.getId(), HAS_PART);
            var elements =
                linkedEntities.stream()
                    .map(
                        e -> {
                          try {
                            return readAggregatePart(e);
                          } catch (ServiceError ex) {
                            throw new ServiceRuntimeError(ex);
                          }
                        });
            // Retrieve the mapped instances of the
            if (retrieveMappedInstances) {
              var mappedElements =
                  mappingEntityRelationshipRepository
                      .findBySourceAndRelationType(entity, MAPPED_TO)
                      .stream()
                      .map(MappingEntityRelationship::getTarget)
                      .map(
                          e -> {
                            try {
                              return readAggregatePart(e);
                            } catch (ServiceError ex) {
                              throw new ServiceRuntimeError(ex);
                            }
                          });
              elements = Stream.concat(elements, mappedElements);
            }
            return new Aggregate(entity, elements.toList());
          } else {
            throw new ServiceError("Invalid entity type for aggregate part");
          }
        } catch (ServiceRuntimeError ex) {
          throw (ServiceError) ex.getCause();
        }
      }
    }

    var aggregateEntity = entityService.read(aggregateId);
    if (!entityRelationshipRepository
        .findByTargetAndRelationType(aggregateEntity, HAS_PART)
        .isEmpty()) {
      throw new ServiceError("Entity with id: " + aggregateId + " is not an aggregate");
    }

    var result =
        Optional.ofNullable(
            new ReadAggregate().readAggregatePart(aggregateEntity) instanceof Aggregate aggregate
                ? aggregate
                : null);

    return result.orElseThrow(
        () -> new ServiceError("Entity with id: " + aggregateId + " is not an aggregate"));
  }
}
