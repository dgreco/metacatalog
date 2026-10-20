package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.entity.RelationType.IS_MAPPED_BY;
import static it.davidgreco.metacatalog.entity.RelationType.IS_PART_OF;
import static it.davidgreco.metacatalog.entity.RelationType.MAPPED_TO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.EntityVersion;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityVersionRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records the version history of entities whose type carries the built-in {@link
 * BuiltInTraits#VERSIONABLE} trait, directly or through inheritance.
 *
 * <p>Every path that changes an entity calls this: {@link EntityServiceImpl} for user and internal
 * writes and for links, {@link MappedEntityService} for derived entities. Keeping the rules here
 * means no write path can version differently from another.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>The live type decides, not the type version the entity is pinned to. A type that gains the
 *       trait starts versioning its existing instances, and one that loses it stops.
 *   <li>Every version has a snapshot, the current one included, so the latest snapshot always
 *       equals the live entity.
 *   <li>A change that leaves the recorded state as it was records nothing.
 *   <li>An entity outside the aggregate model is versioned on each write. The first versioned write
 *       to one with no history yet, because its type gained the trait after it was created, first
 *       records the state before the write.
 *   <li>An aggregate root is versioned as a whole — itself, its {@code HAS_PART} tree, the {@code
 *       DEPENDS_ON} links and the entities derived from any of them through {@code MAPPED_TO} —
 *       once per transaction, when the transaction commits. Building or changing an aggregate takes
 *       many operations, and a version per operation would record each half-built state in between.
 *       Its parts are never versionable themselves ({@link
 *       ServiceUtils#checkVersionableOnlyOnAggregateRoots}): their changes, and their derived
 *       entities' changes, are versions of the root. A derived entity is regenerated in a
 *       transaction of its own after its source changes, so that is a version of its own too.
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class EntityVersionRecorder {

  /**
   * Compares JSON leaves by value, so that {@code 1.0} (a floating-point node) equals {@code 1} (an
   * integer node), as it does in {@code jsonb}. Without it, re-writing a number in a different
   * notation would record a version although the stored value is unchanged.
   */
  private static final Comparator<JsonNode> BY_VALUE =
      (a, b) -> {
        if (a.equals(b)) return 0;
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue());
        return 1;
      };

  private final EntityRepository entityRepository;

  private final EntityVersionRepository entityVersionRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final EntityManager entityManager;

  /**
   * Records version 1 of a newly saved entity, if its type is versionable. A versionable aggregate
   * root is recorded when the transaction commits instead, once its parts are in place.
   *
   * @param entity the entity, already passed to {@code save}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCreated(Entity entity) {
    if (isAggregateRoot(entity)) {
      if (isVersionable(entity)) recordAtCommit(entity.getId());
      return;
    }
    if (!isVersionable(entity)) return;
    append(entity, null, 0, entity.getValues(), entity.getEntityTypeVersion(), null);
    log.info("Recorded version 1 of entity with id {}", entity.getId());
  }

  /**
   * Records the state an entity has just been written to, if it is versionable — or, if it belongs
   * to a versionable aggregate, records the aggregate when the transaction commits.
   *
   * <p>For an entity outside the aggregate model this takes a row lock on it first, so concurrent
   * writers number their snapshots one after the other. The latest snapshot is then read from the
   * database rather than from the managed entity, which may have been loaded before another writer
   * committed. The previous values are used only to backfill an entity that has no history yet.
   *
   * @param entity the entity, holding its new values and pin
   * @param previousValues the values before the write
   * @param previousPin the type version the entity was pinned to before the write
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordUpdated(Entity entity, JsonNode previousValues, EntityTypeVersion previousPin) {
    aggregateChanged(entity);
    if (isAggregateRoot(entity) || !isVersionable(entity)) return;
    entityRepository.findByIdForUpdate(entity.getId());
    var latest = entityVersionRepository.findFirstByEntityIdOrderByVersionDesc(entity.getId());
    if (latest.isPresent()) {
      var last = latest.get();
      if (sameState(last.getValues(), last.getEntityTypeVersion(), entity)) return;
      append(
          entity,
          last.getId(),
          last.getVersion(),
          entity.getValues(),
          entity.getEntityTypeVersion(),
          null);
    } else {
      if (sameState(previousValues, previousPin, entity)) return;
      var before = append(entity, null, 0, previousValues, previousPin, null);
      append(entity, before.getId(), 1, entity.getValues(), entity.getEntityTypeVersion(), null);
    }
    log.info("Recorded version {} of entity with id {}", entity.getVersion(), entity.getId());
  }

  /**
   * Notes that the given entities changed in a way that changes the aggregates they belong to — a
   * write, or a link added or about to be removed — so every versionable aggregate root at or above
   * them is recorded when the transaction commits.
   *
   * <p>Call it for a removal <em>before</em> the relationship rows go: the walk up to the root
   * follows {@code IS_PART_OF}, and a part already detached would no longer reach it.
   *
   * @param members the entities that changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void aggregateChanged(Entity... members) {
    for (var member : members)
      for (var root : versionableRootsAtOrAbove(member)) recordAtCommit(root.getId());
  }

  private List<Entity> versionableRootsAtOrAbove(Entity member) {
    var roots = new ArrayList<Entity>();
    var seen = new HashSet<String>();
    var pending = new ArrayDeque<>(List.of(member));
    while (!pending.isEmpty()) {
      var current = pending.poll();
      if (!seen.add(current.getId())) continue;
      var traits = ServiceUtils.traitNamesOf(current.getEntityType());
      if (traits.contains(BuiltInTraits.AGGREGATE)
          && !traits.contains(BuiltInTraits.AGGREGATE_ELEMENT)) {
        if (traits.contains(BuiltInTraits.VERSIONABLE)) roots.add(current);
        continue;
      }
      // A derived entity belongs to the aggregate of the entity it was derived from.
      for (var rel :
          mappingEntityRelationshipRepository.findBySourceAndRelationType(current, IS_MAPPED_BY))
        pending.add(rel.getTarget());
      // Only an AggregateElement carrier can be a part, so anything else has no parent to climb to.
      if (!traits.contains(BuiltInTraits.AGGREGATE_ELEMENT)) continue;
      for (var rel : entityRelationshipRepository.findBySourceAndRelationType(current, IS_PART_OF))
        pending.add(rel.getTarget());
    }
    return roots;
  }

  /**
   * Adds a root to this transaction's set of aggregates to record at commit.
   *
   * <p>The set lives in a {@link TransactionSynchronization} rather than a bound resource: Spring
   * suspends synchronizations with their transaction, so a {@code REQUIRES_NEW} transaction opened
   * inside this one (the mapping updater does that) gets a set of its own and records at its own
   * commit, instead of adding to an outer set that may never commit.
   */
  private void recordAtCommit(String rootId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      recordAggregate(rootId);
      return;
    }
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
      if (synchronization instanceof AggregatesToRecord pending && pending.owner() == this) {
        pending.rootIds().add(rootId);
        return;
      }
    var pending = new AggregatesToRecord(this, new LinkedHashSet<>(List.of(rootId)));
    TransactionSynchronizationManager.registerSynchronization(pending);
  }

  /** The aggregates one transaction changed, recorded just before it commits. */
  private record AggregatesToRecord(EntityVersionRecorder owner, Set<String> rootIds)
      implements TransactionSynchronization {
    @Override
    public void beforeCommit(boolean readOnly) {
      for (var rootId : List.copyOf(rootIds)) owner.recordAggregate(rootId);
    }
  }

  /**
   * Records the next version of an aggregate root, unless the aggregate is as last recorded.
   *
   * <p>Runs inside the committing transaction, so the version commits or rolls back with the
   * changes it records. The pending changes are flushed first and the root is then re-read under a
   * row lock, which orders concurrent recordings of the same aggregate and gives a fresh root: the
   * managed copy may predate another writer's commit, and saving it with the new version number
   * would write its stale values back over that commit.
   */
  private void recordAggregate(String rootId) {
    entityManager.flush();
    var root = entityManager.find(Entity.class, rootId);
    if (root == null) return; // deleted in this same transaction, history and all
    entityManager.refresh(root, LockModeType.PESSIMISTIC_WRITE);
    if (!isAggregateRoot(root) || !isVersionable(root)) return;
    var tree = treeOf(root, new HashSet<>(), true);
    var latest = entityVersionRepository.findFirstByEntityIdOrderByVersionDesc(rootId);
    if (latest.isPresent()
        && latest.get().getAggregate() != null
        && latest.get().getAggregate().equals(BY_VALUE, tree)) return;
    append(
        root,
        latest.map(EntityVersion::getId).orElse(null),
        latest.map(EntityVersion::getVersion).orElse(0),
        root.getValues(),
        root.getEntityTypeVersion(),
        tree);
    log.info("Recorded version {} of aggregate with root id {}", root.getVersion(), rootId);
  }

  /**
   * The aggregate below {@code member}: its values and pinned type version, the ids it depends on,
   * its parts, and the entities derived from it under {@code mapped} — kept apart from the parts
   * because they are not authored, and a reader of a past version needs to tell the two apart.
   * Everything is sorted by id so that an unchanged aggregate always produces the same tree and
   * compares equal to its last version.
   *
   * <p>Each part is refreshed before it is read, for the same reason the root is: an entity loaded
   * earlier in the transaction may predate another writer's commit.
   */
  private ObjectNode treeOf(Entity member, Set<String> path, boolean fresh) {
    if (!fresh) entityManager.refresh(member);
    var node = JsonNodeFactory.instance.objectNode();
    node.put("id", member.getId());
    node.put("entityType", member.getEntityType().getName());
    node.put("entityTypeVersionId", member.getEntityTypeVersion().getId());
    node.set("values", member.getValues().deepCopy());
    var dependencies = node.arrayNode();
    targetsOf(member, DEPENDS_ON).forEach(target -> dependencies.add(target.getId()));
    if (!dependencies.isEmpty()) node.set("dependencies", dependencies);
    path.add(member.getId());
    var parts = node.arrayNode();
    for (var part : targetsOf(member, HAS_PART))
      // A part already on the path would be a containment cycle; link() refuses those, and the
      // guard keeps a corrupt one from recursing forever.
      if (!path.contains(part.getId())) parts.add(treeOf(part, path, false));
    var mapped = node.arrayNode();
    for (var derived : derivedFrom(member))
      if (!path.contains(derived.getId())) mapped.add(treeOf(derived, path, false));
    path.remove(member.getId());
    if (!parts.isEmpty()) node.set("parts", parts);
    if (!mapped.isEmpty()) node.set("mapped", mapped);
    return node;
  }

  private List<Entity> derivedFrom(Entity source) {
    return mappingEntityRelationshipRepository
        .findBySourceAndRelationType(source, MAPPED_TO)
        .stream()
        .map(MappingEntityRelationship::getTarget)
        .sorted(Comparator.comparing(Entity::getId))
        .toList();
  }

  private List<Entity> targetsOf(Entity source, RelationType type) {
    return entityRelationshipRepository.findBySourceAndRelationType(source, type).stream()
        .map(EntityRelationship::getTarget)
        .sorted(Comparator.comparing(Entity::getId))
        .toList();
  }

  private boolean isVersionable(Entity entity) {
    return ServiceUtils.implementsTrait(entity.getEntityType(), BuiltInTraits.VERSIONABLE);
  }

  private static boolean isAggregateRoot(Entity entity) {
    var traits = ServiceUtils.traitNamesOf(entity.getEntityType());
    return traits.contains(BuiltInTraits.AGGREGATE)
        && !traits.contains(BuiltInTraits.AGGREGATE_ELEMENT);
  }

  private static boolean sameState(JsonNode values, EntityTypeVersion pin, Entity entity) {
    return pin.getId().equals(entity.getEntityTypeVersion().getId())
        && values.equals(BY_VALUE, entity.getValues());
  }

  private EntityVersion append(
      Entity entity,
      String previousId,
      int previousNumber,
      JsonNode values,
      EntityTypeVersion pin,
      JsonNode aggregate) {
    var snapshot = new EntityVersion();
    snapshot.setEntity(entity);
    snapshot.setVersion(previousNumber + 1);
    // A copy, because JsonNode is mutable and the snapshot must not move with the live entity.
    snapshot.setValues(values.deepCopy());
    snapshot.setEntityTypeVersion(pin);
    snapshot.setPreviousVersionId(previousId);
    snapshot.setCreatedAt(Instant.now());
    snapshot.setAggregate(aggregate);
    entityVersionRepository.save(snapshot);
    entity.setVersion(snapshot.getVersion());
    return snapshot;
  }
}
