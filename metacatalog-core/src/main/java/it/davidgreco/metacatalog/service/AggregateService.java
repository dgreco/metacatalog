package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.*;
import static it.davidgreco.metacatalog.service.CommonService.implementsTrait;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
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

  /** Marker interface for aggregate parts (either elements or nested aggregates). */
  public interface AggregatePart {}

  /**
   * Represents a leaf element in an aggregate hierarchy.
   *
   * @param entity the entity for this element
   * @param dependencies the list of entities this element depends on
   */
  public record AggregateElement(Entity entity, List<Entity> dependencies)
      implements AggregatePart {
    public AggregateElement(Entity entity) {
      this(entity, List.of());
    }
  }

  /**
   * Represents a composite aggregate containing other aggregate parts.
   *
   * @param entity the root entity for this aggregate
   * @param dependencies the list of entities this aggregate depends on
   * @param elements the child aggregate parts contained in this aggregate
   */
  public record Aggregate(Entity entity, List<Entity> dependencies, List<AggregatePart> elements)
      implements AggregatePart {
    public Aggregate(Entity entity, List<AggregatePart> elements) {
      this(entity, List.of(), elements);
    }
  }

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
  public AggregatePart create(AggregatePart aggregate) throws ServiceError {
    class CreateAggregate {
      AggregatePart create(AggregatePart aggregate) throws ServiceError {
        switch (aggregate) {
          case AggregateElement(Entity entity, List<Entity> dependencies) -> {
            var aggregateElement =
                entityService.create(
                    entity.getEntityType().getName(), entity.getValues().toPrettyString());
            entity.setId(aggregateElement.getId());
            for (var dependency : dependencies) {
              entityService.link(aggregateElement.getId(), DEPENDS_ON, dependency.getId());
            }
          }
          case Aggregate(
                  Entity entity,
                  List<Entity> dependencies,
                  List<AggregatePart> elements) -> {
            var aggregateElement =
                entityService.create(
                    entity.getEntityType().getName(), entity.getValues().toPrettyString());
            entity.setId(aggregateElement.getId());
            for (var dependency : dependencies) {
              entityService.link(aggregateElement.getId(), DEPENDS_ON, dependency.getId());
            }
            for (var childElement : elements) {
              var childAggregate = create(childElement);
              switch (childAggregate) {
                case AggregateElement childAggregateAggregateElement ->
                    entityService.link(
                        aggregateElement.getId(),
                        HAS_PART,
                        childAggregateAggregateElement.entity().getId());
                case Aggregate childAggregateAggregagte ->
                    entityService.link(
                        aggregateElement.getId(),
                        HAS_PART,
                        childAggregateAggregagte.entity().getId());
                default -> throw new ServiceError("Invalid aggregate part type");
              }
            }
          }
          default -> throw new ServiceError("Invalid aggregate part type");
        }
        return aggregate;
      }
    }
    log.info("Creating aggregate: " + aggregate);
    try {
      return new CreateAggregate().create(aggregate);
    } finally {
      log.info("Aggregate created: " + aggregate);
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
            var dependencies =
                entityRelationshipRepository
                    .findBySourceAndRelationType(entity, DEPENDS_ON)
                    .stream()
                    .map(EntityRelationship::getTarget)
                    .toList();
            return new AggregateElement(entity, dependencies);
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
            var dependencies =
                entityRelationshipRepository
                    .findBySourceAndRelationType(entity, DEPENDS_ON)
                    .stream()
                    .map(EntityRelationship::getTarget)
                    .toList();
            return new Aggregate(entity, dependencies, elements.toList());
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
