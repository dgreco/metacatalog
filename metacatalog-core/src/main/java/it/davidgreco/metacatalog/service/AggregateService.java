package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.*;
import static it.davidgreco.metacatalog.service.ServiceUtils.implementsTrait;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.BuiltInCapability;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /**
     * Returns the entity this part wraps.
     *
     * @return the entity
     */
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
    /**
     * Creates an element with no dependencies.
     *
     * @param entity the entity for this element
     */
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
    /**
     * Creates an aggregate with no dependencies.
     *
     * @param entity the root entity for this aggregate
     * @param elements the child aggregate parts contained in this aggregate
     */
    public Aggregate(Entity entity, List<AggregatePart> elements) {
      this(entity, List.of(), elements);
    }
  }

  private final EntityService entityService;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final MappedEntityService mappedEntityService;

  /** Told about the parts an update removes, so the change becomes a version of their root. */
  private final EntityVersionRecorder entityVersionRecorder;

  /** Asked which types are asset types, an answer that needs every type's trait ancestry. */
  private final AggregateSchemaService aggregateSchemaService;

  /**
   * An asset as the catalog lists it: the root entity of an aggregate whose type carries the
   * built-in {@link BuiltInTraits#ASSET} trait, and the other assets it depends on.
   *
   * @param entity the asset's root entity
   * @param dependencies the IDs of the assets its root depends on, in no particular order
   */
  public record Asset(Entity entity, List<String> dependencies) {}

  /**
   * Lists every asset — every aggregate rooted at an entity of an asset type — with the assets it
   * depends on.
   *
   * <p>An asset is an aggregate and nothing more, which is why this lives here: reading, updating,
   * deleting and running one all go through the methods of this service and the procedures, with
   * the asset's id. What a catalog needs on top is the view <em>across</em> assets, which none of
   * those gives without one read per aggregate.
   *
   * <p>A dependency between assets is a {@code DEPENDS_ON} link between their <em>roots</em>. A
   * link from a root to something that is not an asset, or between parts of two assets, is not a
   * dependency between the assets and is left out.
   *
   * @return the assets, grouped by type in type-name order
   */
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public List<Asset> assets() {
    log.info("Listing assets");
    var roots = new LinkedHashMap<String, Entity>();
    for (var type : aggregateSchemaService.assetTypes()) {
      entityService.list(type.getName(), "").forEach(root -> roots.put(root.getId(), root));
    }
    // One read of the links rather than one per asset: a catalog page lists every asset at once.
    var dependencies = new LinkedHashMap<String, List<String>>();
    for (var link : entityService.listAllRelationships()) {
      if (link.getRelationType() != DEPENDS_ON) continue;
      var source = link.getSource().getId();
      var target = link.getTarget().getId();
      if (roots.containsKey(source) && roots.containsKey(target))
        dependencies.computeIfAbsent(source, id -> new ArrayList<>()).add(target);
    }
    var assets =
        roots.values().stream()
            .map(root -> new Asset(root, dependencies.getOrDefault(root.getId(), List.of())))
            .toList();
    log.info("Listed {} asset(s)", assets.size());
    return assets;
  }

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
    checkIsAggregateRoot(aggregateEntity, aggregateId);

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
   * The delete is refused up front when an entity outside the aggregate depends on a member or
   * contains one — deleting it would leave that outsider needing a row that no longer exists — or
   * when a member is itself derived from a mapping source, in which case it belongs to the mapping
   * engine rather than to this aggregate. The aggregate's own dependencies on outside entities are
   * not an obstacle: they are removed with it.
   *
   * <p>The frozen copies of the root's past versions go with it, unless one of them is still
   * provisioned or authorized; a frozen copy itself cannot be deleted this way.
   *
   * @param aggregateId the ID of the aggregate root entity to delete
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void delete(String aggregateId) {
    log.info("Deleting aggregate: {}", aggregateId);
    // A past version goes with its version (EntityService.deleteVersion) or its live root.
    ServiceUtils.checkNotFrozen(entityService.read(aggregateId));
    var members = collectMembers(aggregateId);
    checkMembersAreSelfContained(members, Set.of());
    // Children before parents: the reverse of the breadth-first order they were discovered in.
    var ordered = new ArrayList<>(members.values());
    Collections.reverse(ordered);
    // Every member's derived entities first, and only then any link: a member's own mapping
    // usually resolves a path through the links removed below, and would otherwise be reported as
    // relying on them.
    for (var member : ordered) {
      if (!mappingEntityRelationshipRepository
          .findBySourceAndRelationType(member, MAPPED_TO)
          .isEmpty()) mappedEntityService.deleteMappedEntities(member.getId());
    }
    // The aggregate's own dependencies on entities outside it go with it. Through the entity
    // service rather than with the rows below, so that a dependency some outside entity's mapping
    // resolves a path reference through is refused, as it is on DELETE /entity/link.
    for (var member : ordered) {
      for (var dependency :
          entityRelationshipRepository.findBySourceAndRelationType(member, DEPENDS_ON)) {
        if (!members.containsKey(dependency.getTarget().getId()))
          entityService.unlink(member.getId(), DEPENDS_ON, dependency.getTarget().getId());
      }
    }
    for (var member : ordered) {
      entityRelationshipRepository.deleteAll(entityRelationshipRepository.findBySource(member));
      entityRelationshipRepository.deleteAll(entityRelationshipRepository.findByTarget(member));
      entityService.deleteInternal(member.getId());
    }
    log.info("Aggregate deleted: {}", aggregateId);
  }

  /**
   * Updates an aggregate in place so that it matches a document: the values of the members it still
   * lists, the parts it adds, the parts it no longer lists, and the dependencies it declares.
   *
   * <p>The document has the shape {@code GET /aggregate/{id}/yaml} serves, and also understands
   * what {@code POST /aggregate/yaml} consumes, so one that was downloaded can be edited and sent
   * back, and a new part can be written the way a new aggregate is:
   *
   * <ul>
   *   <li>A node with an {@code id} is that existing member. Its type cannot change and it cannot
   *       move to another parent; its values are updated when they differ.
   *   <li>A node without an {@code id} is a new part, created under the node that lists it.
   *   <li>A member the document does not list is removed, with its own parts and everything derived
   *       from them through {@code MAPPED_TO}.
   *   <li>A node declaring {@code dependencies} (ids) or {@code dependsOn} (the {@code ref} or
   *       {@code id} of another node, or the id of an existing entity) depends on exactly those
   *       afterwards. A node declaring neither keeps the dependencies it has: saying nothing is not
   *       the same as saying "none", and a document built by something that does not know about a
   *       dependency — on another aggregate, say — must not silently drop it.
   * </ul>
   *
   * <p>Everything happens in one transaction, so a refusal anywhere leaves the aggregate as it was,
   * and — because an aggregate is versioned once per transaction — a versionable root gains a
   * single new version however many members changed.
   *
   * <p>The fields a provisioning or authorization run writes are never taken from the document. A
   * document is edited from a download, and a run that completed in between would otherwise be
   * undone by the stale statuses it carries.
   *
   * @param aggregateId the ID of the aggregate root entity to update
   * @param document the aggregate as it should be
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void update(String aggregateId, ObjectNode document) {
    log.info("Updating aggregate: {}", aggregateId);
    ServiceUtils.checkNotFrozen(entityService.read(aggregateId));
    new AggregateUpdate(aggregateId, collectMembers(aggregateId)).apply(document);
    log.info("Aggregate updated: {}", aggregateId);
  }

  /** One run of {@link #update}: what the aggregate holds, and what the document says about it. */
  private final class AggregateUpdate {

    private final String aggregateId;
    private final Map<String, Entity> members;

    /** For each part, the members containing it — normally one, but containment is a graph. */
    private final Map<String, Set<String>> containersOf = new HashMap<>();

    /**
     * The entities derived from the members through mappings. They are never authored, so a node
     * naming one — a document downloaded with the mapped instances included — is skipped rather
     * than refused: it says nothing the mapping engine does not already decide.
     */
    private final Set<String> derived = new HashSet<>();

    private final Set<String> listed = new LinkedHashSet<>();
    private final Map<String, String> entityIdByRef = new HashMap<>();
    private final Map<String, List<String>> declaredDependencies = new LinkedHashMap<>();

    private AggregateUpdate(String aggregateId, Map<String, Entity> members) {
      this.aggregateId = aggregateId;
      this.members = members;
      for (var member : members.values()) {
        for (var containment :
            entityRelationshipRepository.findBySourceAndRelationType(member, HAS_PART)) {
          containersOf
              .computeIfAbsent(containment.getTarget().getId(), id -> new HashSet<>())
              .add(member.getId());
        }
        collectDerived(member);
      }
    }

    private void collectDerived(Entity source) {
      for (var mapping :
          mappingEntityRelationshipRepository.findBySourceAndRelationType(source, MAPPED_TO)) {
        // A mapping chain: what is derived from a derived entity is derived too.
        if (derived.add(mapping.getTarget().getId())) collectDerived(mapping.getTarget());
      }
    }

    private void apply(ObjectNode document) {
      visit(document, null);
      // After the whole document has been read: a dependency may name a node that comes later.
      reconcileDependencies();
      // After the dependencies: a link the document dropped must not keep its target alive.
      removeUnlisted();
    }

    private void visit(JsonNode json, String containerId) {
      if (!(json instanceof ObjectNode node))
        throw new ServiceError("Malformed aggregate document: every node must be an object");
      var id = text(node, "id");
      if (containerId == null) {
        if (id != null && !id.equals(aggregateId))
          throw new ServiceError(
              "The document describes the aggregate with id: "
                  + id
                  + ", not the aggregate with id: "
                  + aggregateId);
        id = aggregateId;
      }
      if (id != null && derived.contains(id)) return;

      var typeName = text(node, "entityType");
      var values = node.get("values");
      if (typeName == null || values == null)
        throw new ServiceError(
            "Malformed aggregate document: missing required field 'entityType' or 'values'");

      String entityId;
      if (id == null) {
        entityId = entityService.create(typeName, values.toString()).getId();
        entityService.link(containerId, HAS_PART, entityId);
      } else {
        var existing = existingMember(id, typeName, containerId);
        // A part shared by two containers may be listed under each; its values are applied once.
        if (listed.add(id)) updateValues(existing, values);
        entityId = id;
      }

      entityIdByRef.put(entityId, entityId);
      var ref = text(node, "ref");
      if (ref != null) entityIdByRef.put(ref, entityId);
      dependencyNames(node).ifPresent(names -> declaredDependencies.put(entityId, names));

      var parts = node.get("parts");
      if (parts == null || parts.isNull()) return;
      if (!parts.isArray())
        throw new ServiceError("Malformed aggregate document: 'parts' must be a list");
      for (var part : parts) visit(part, entityId);
    }

    private Entity existingMember(String id, String typeName, String containerId) {
      var existing = members.get(id);
      if (existing == null)
        throw new ServiceError(
            "Entity with id: "
                + id
                + " is not part of the aggregate with id: "
                + aggregateId
                + "; to add a new part, leave its id out");
      if (!existing.getEntityType().getName().equals(typeName))
        throw new ServiceError(
            "Entity with id: "
                + id
                + " is of type "
                + existing.getEntityType().getName()
                + "; an update cannot change it to "
                + typeName);
      if (containerId != null && !containersOf.getOrDefault(id, Set.of()).contains(containerId))
        throw new ServiceError(
            "Entity with id: "
                + id
                + " is not a part of the entity with id: "
                + containerId
                + "; an update cannot move a part");
      return existing;
    }

    private void updateValues(Entity existing, JsonNode values) {
      if (!(values instanceof ObjectNode declared)) {
        // Not an object: handed over as it is, for validation to refuse with its own message.
        entityService.update(existing.getId(), values.toString());
        return;
      }
      var merged = declared.deepCopy();
      for (var field : BuiltInCapability.runFields()) {
        var current = existing.getValues().get(field);
        if (current == null) merged.remove(field);
        else merged.set(field, current);
      }
      if (!merged.equals(existing.getValues()))
        entityService.update(existing.getId(), merged.toString());
    }

    /** The dependencies a node declares, or empty when it declares none of the two fields. */
    private Optional<List<String>> dependencyNames(ObjectNode node) {
      var dependencies = node.get("dependencies");
      var dependsOn = node.get("dependsOn");
      if (dependencies == null && dependsOn == null) return Optional.empty();
      var names = new ArrayList<String>();
      for (var field : new JsonNode[] {dependencies, dependsOn}) {
        if (field == null || field.isNull()) continue;
        if (!field.isArray())
          throw new ServiceError(
              "Malformed aggregate document: 'dependencies' and 'dependsOn' must be lists");
        field.forEach(name -> names.add(name.asText()));
      }
      return Optional.of(names);
    }

    private void reconcileDependencies() {
      for (var declared : declaredDependencies.entrySet()) {
        var sourceId = declared.getKey();
        var wanted = new LinkedHashSet<String>();
        for (var name : declared.getValue()) wanted.add(resolveDependency(sourceId, name));
        var current =
            entityRelationshipRepository
                .findBySourceAndRelationType(entityService.read(sourceId), DEPENDS_ON)
                .stream()
                .map(dependency -> dependency.getTarget().getId())
                .toList();
        // Through the entity service, not the repository: a dependency a mapping resolves a path
        // reference through must be refused here exactly as it is on DELETE /entity/link.
        for (var dropped : current) {
          if (!wanted.contains(dropped)) entityService.unlink(sourceId, DEPENDS_ON, dropped);
        }
        for (var added : wanted) {
          if (!current.contains(added)) entityService.link(sourceId, DEPENDS_ON, added);
        }
      }
    }

    private String resolveDependency(String sourceId, String name) {
      var inDocument = entityIdByRef.get(name);
      if (inDocument != null) return inDocument;
      if (entityService.exists(name)) return name;
      throw new ServiceError(
          "Entity with id: "
              + sourceId
              + " declares a dependency on '"
              + name
              + "', which is neither the ref or id of a node of the document nor the id of an"
              + " existing entity");
    }

    private void removeUnlisted() {
      var removed = new LinkedHashMap<String, Entity>();
      members.forEach(
          (id, member) -> {
            if (!listed.contains(id)) removed.put(id, member);
          });
      if (removed.isEmpty()) return;
      checkMembersAreSelfContained(removed, listed);

      // Children before parents: the reverse of the breadth-first order they were discovered in.
      var ordered = new ArrayList<>(removed.values());
      Collections.reverse(ordered);
      // Before the rows go: the walk up to the root, which gains a version, follows them.
      entityVersionRecorder.aggregateChanged(ordered.toArray(Entity[]::new));
      // The derived entities of every removed part first, and only then the links. A removed part's
      // own mapping usually resolves a path through the very links being removed, and would
      // otherwise be reported as relying on them.
      for (var member : ordered) {
        if (!mappingEntityRelationshipRepository
            .findBySourceAndRelationType(member, MAPPED_TO)
            .isEmpty()) mappedEntityService.deleteMappedEntities(member.getId());
      }
      for (var member : ordered) {
        // Each link is stored as a pair of rows, one starting at either end, and unlinking removes
        // both — so the rows starting here cover every link the part still has.
        for (var link : entityRelationshipRepository.findBySource(member)) {
          entityService.unlinkInternal(
              member.getId(), link.getRelationType(), link.getTarget().getId());
        }
      }
      for (var member : ordered) entityService.deleteInternal(member.getId());
    }

    private static String text(ObjectNode node, String field) {
      var value = node.get(field);
      return value == null || value.isNull() ? null : value.asText();
    }
  }

  /**
   * Collects the aggregate root and everything below it, keyed by ID so an entity shared by two
   * parents is visited once, in breadth-first order.
   */
  private Map<String, Entity> collectMembers(String aggregateId) {
    var rootEntity = entityService.read(aggregateId);
    checkIsAggregateRoot(rootEntity, aggregateId);

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

  /**
   * Rejects anything but an aggregate <em>root</em>, classified by the built-in traits its type
   * carries: a root carries {@code Aggregate} but not {@code AggregateElement} — an intermediate
   * node carries both, a leaf only {@code AggregateElement}. Whether the entity is itself contained
   * by another one is deliberately not part of the test: a root may hang off an entity outside the
   * aggregate model (one carrying neither trait) without ceasing to be a root.
   */
  /**
   * Whether {@code holder}, by holding a link of the given type <em>towards</em> an entity being
   * removed, needs that entity to stay.
   */
  private static boolean needs(RelationType type, Entity holder, Set<String> remaining) {
    return switch (type) {
      case DEPENDS_ON -> true;
      case HAS_PART -> !remaining.contains(holder.getId());
      default -> false;
    };
  }

  private static void checkIsAggregateRoot(Entity rootEntity, String aggregateId) {
    if (!implementsTrait(rootEntity.getEntityType(), BuiltInTraits.AGGREGATE)
        || implementsTrait(rootEntity.getEntityType(), BuiltInTraits.AGGREGATE_ELEMENT))
      throw new ServiceError("Entity with id: " + aggregateId + " is not an aggregate");
  }

  /**
   * Rejects a removal unless the given entities can go without leaving anything that stays
   * dangling.
   *
   * <p>What blocks a removal is something that stays and still <em>needs</em> a removed entity: an
   * entity depending on it, or an entity outside the aggregate containing it. What a removed entity
   * itself points at never does — its own dependencies, on a remaining part or on another aggregate
   * altogether, simply go with it. Refusing those too would make every asset that depends on
   * another undeletable until its dependencies were removed by hand, protecting nothing: the entity
   * depended on loses a dependent, which is exactly what deleting the dependent means.
   *
   * <p>{@code remaining} is empty when a whole aggregate is deleted. When an update removes some
   * parts it holds the members that stay, and being contained by one of them is expected: the
   * document dropping the part is the container letting go of it.
   *
   * @param members the entities about to be removed, keyed by ID
   * @param remaining the IDs of the members of the same aggregate that are not removed
   */
  private void checkMembersAreSelfContained(Map<String, Entity> members, Set<String> remaining) {
    for (var member : members.values()) {
      // Each link is a pair of rows; both are read, so that a pair a hand-edited database left
      // one-sided is still seen.
      Stream.concat(
              entityRelationshipRepository.findByTarget(member).stream()
                  .filter(link -> needs(link.getRelationType(), link.getSource(), remaining))
                  .map(EntityRelationship::getSource),
              entityRelationshipRepository.findBySource(member).stream()
                  .filter(
                      link -> needs(link.getRelationType().inverse(), link.getTarget(), remaining))
                  .map(EntityRelationship::getTarget))
          .filter(linked -> !members.containsKey(linked.getId()))
          .findFirst()
          .ifPresent(
              outsider -> {
                throw new ServiceError(
                    "Entity with id: "
                        + member.getId()
                        + " is linked to entity with id: "
                        + outsider.getId()
                        + (remaining.contains(outsider.getId())
                            ? ", which stays in the aggregate; remove that link first"
                            : " outside the aggregate; remove that link first"));
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
