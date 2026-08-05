package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.*;
import static it.davidgreco.metacatalog.service.ServiceUtils.implementsTrait;

import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  public sealed interface AggregatePart permits AggregateElement, Aggregate {
    Entity entity();
  }

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

  private final MappedEntityService mappedEntityService;

  /**
   * Creates an aggregate entity (i.e. a collection of entities, each potentially being an aggregate
   * itself) in the database.
   *
   * @param aggregate the aggregate to create
   * @return the created aggregate with the relevant IDs set
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public AggregatePart create(AggregatePart aggregate) {
    class CreateAggregate {
      AggregatePart create(AggregatePart aggregate) {
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
              entityService.link(
                  aggregateElement.getId(), HAS_PART, childAggregate.entity().getId());
            }
          }
        }
        return aggregate;
      }
    }
    log.info("Creating aggregate: {}", aggregate);
    var result = new CreateAggregate().create(aggregate);
    log.info("Aggregate created: {}", aggregate);
    return result;
  }

  /**
   * Reads an aggregate entity (i.e. a collection of entities, each potentially being an aggregate
   * itself) from the database.
   *
   * @param aggregateId the ID of the aggregate entity to read
   * @return the read aggregate
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Aggregate read(String aggregateId, boolean retrieveMappedInstances) {
    class ReadAggregate {
      private AggregatePart readAggregatePart(Entity entity) {
        if (implementsTrait(entity.getEntityType(), BuiltInTraits.AGGREGATE_ELEMENT)
            && !implementsTrait(entity.getEntityType(), BuiltInTraits.AGGREGATE)) {
          var dependencies =
              entityRelationshipRepository.findBySourceAndRelationType(entity, DEPENDS_ON).stream()
                  .map(EntityRelationship::getTarget)
                  .toList();
          return new AggregateElement(entity, dependencies);
        } else if (implementsTrait(entity.getEntityType(), BuiltInTraits.AGGREGATE)) {
          var linkedEntities = entityService.linked(entity.getId(), HAS_PART);
          var elements = linkedEntities.stream().map(e -> readAggregatePart(e));
          // Retrieve the mapped instances of the
          if (retrieveMappedInstances) {
            var mappedElements =
                mappingEntityRelationshipRepository
                    .findBySourceAndRelationType(entity, MAPPED_TO)
                    .stream()
                    .map(MappingEntityRelationship::getTarget)
                    .map(e -> readAggregatePart(e));
            elements = Stream.concat(elements, mappedElements);
          }
          var dependencies =
              entityRelationshipRepository.findBySourceAndRelationType(entity, DEPENDS_ON).stream()
                  .map(EntityRelationship::getTarget)
                  .toList();
          return new Aggregate(entity, dependencies, elements.toList());
        } else {
          throw new ServiceError("Invalid entity type for aggregate part");
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

  /**
   * Deletes an aggregate entity as a whole: the root, every entity reachable from it through {@code
   * HAS_PART}, and the entities derived from those through {@code MAPPED_TO}.
   *
   * <p>Everything happens in one transaction, so either the whole aggregate goes or nothing does.
   * The delete is refused up front when a member is linked to an entity outside the aggregate —
   * deleting it would leave that outsider pointing at a row that no longer exists — or when a
   * member is itself derived from a mapping source, in which case it belongs to the mapping engine
   * rather than to this aggregate.
   *
   * @param aggregateId the ID of the aggregate root entity to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String aggregateId) {
    log.info("Deleting aggregate: {}", aggregateId);
    var members = collectMembers(aggregateId);
    checkMembersAreSelfContained(members);
    // Children before parents: the reverse of the breadth-first order they were discovered in.
    var ordered = new ArrayList<>(members.values());
    Collections.reverse(ordered);
    for (var member : ordered) {
      if (!mappingEntityRelationshipRepository
          .findBySourceAndRelationType(member, MAPPED_TO)
          .isEmpty()) mappedEntityService.deleteMappedEntities(member.getId());
      entityRelationshipRepository.deleteAll(entityRelationshipRepository.findBySource(member));
      entityRelationshipRepository.deleteAll(entityRelationshipRepository.findByTarget(member));
      entityService.deleteInternal(member.getId());
    }
    log.info("Aggregate deleted: {}", aggregateId);
  }

  /**
   * Collects the aggregate root and everything below it, keyed by ID so an entity shared by two
   * parents is visited once, in breadth-first order.
   */
  private Map<String, Entity> collectMembers(String aggregateId) {
    var rootEntity = entityService.read(aggregateId);
    if (!implementsTrait(rootEntity.getEntityType(), BuiltInTraits.AGGREGATE)
        || !entityRelationshipRepository
            .findByTargetAndRelationType(rootEntity, HAS_PART)
            .isEmpty())
      throw new ServiceError("Entity with id: " + aggregateId + " is not an aggregate");

    var members = new LinkedHashMap<String, Entity>();
    members.put(rootEntity.getId(), rootEntity);
    var pending = new ArrayDeque<>(List.of(rootEntity));
    while (!pending.isEmpty()) {
      var current = pending.poll();
      for (var relationship :
          entityRelationshipRepository.findBySourceAndRelationType(current, HAS_PART)) {
        var part = relationship.getTarget();
        if (members.putIfAbsent(part.getId(), part) == null) pending.add(part);
      }
    }
    return members;
  }

  /** Rejects the delete unless the aggregate can be removed without leaving anything dangling. */
  private void checkMembersAreSelfContained(Map<String, Entity> members) {
    for (var member : members.values()) {
      Stream.concat(
              entityRelationshipRepository.findBySource(member).stream()
                  .map(EntityRelationship::getTarget),
              entityRelationshipRepository.findByTarget(member).stream()
                  .map(EntityRelationship::getSource))
          .filter(linked -> !members.containsKey(linked.getId()))
          .findFirst()
          .ifPresent(
              outsider -> {
                throw new ServiceError(
                    "Entity with id: "
                        + member.getId()
                        + " is linked to entity with id: "
                        + outsider.getId()
                        + " outside the aggregate; remove that link first");
              });

      if (!mappingEntityRelationshipRepository
          .findBySourceAndRelationType(member, IS_MAPPED_BY)
          .isEmpty())
        throw new ServiceError(
            "Entity with id: "
                + member.getId()
                + " is mapped from another entity and cannot be deleted as part of an aggregate");
    }
  }
}
