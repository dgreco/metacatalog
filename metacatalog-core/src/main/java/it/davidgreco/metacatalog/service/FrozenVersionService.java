package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.entity.BuiltInCapability;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.EntityVersion;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityTypeRepository;
import it.davidgreco.metacatalog.repository.EntityTypeVersionRepository;
import it.davidgreco.metacatalog.repository.EntityVersionRepository;
import it.davidgreco.metacatalog.repository.MappingEntityTypeRelationshipRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns the past versions of a {@link BuiltInTraits#VERSIONABLE} aggregate root into frozen copies:
 * real aggregates — root, parts and the entities derived from them — that functions such as
 * provisioning can still run on, and that nothing else can change.
 *
 * <p>A copy is cloned from the tree its version recorded, not from the live entities, and every
 * entity in it carries the live root's id and the version in {@link Entity#getFrozenVersionOf()}
 * and {@link Entity#getFrozenVersion()}. Run results — every {@link BuiltInCapability} status,
 * result and grants field — are left behind, so a copy's status only ever reflects runs made on the
 * copy. Containment and dependency links are replicated inside the copy only; a dependency on an
 * entity outside the aggregate is dropped, so a copy never links to a live entity. The derived
 * entities are not cloned but derived afresh from the cloned parts with the current mapping rules,
 * which makes every copy complete and runnable.
 *
 * <p>Consecutive versions that differ only in run results would give identical copies, so they
 * share one: a provisioning run records versions as statuses change but makes no new copy. A copy
 * is made only when a version changes the parts, their values or their links.
 *
 * <p>The current version is never copied: it is the live aggregate itself.
 */
@Slf4j
public class FrozenVersionService {

  private static final Set<String> RUN_FIELDS = BuiltInCapability.runFields();

  private final EntityRepository entityRepository;

  private final EntityTypeRepository entityTypeRepository;

  private final EntityTypeVersionRepository entityTypeVersionRepository;

  private final EntityVersionRepository entityVersionRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final MappedEntityService mappedEntityService;

  private final TransactionTemplate ownTransaction;

  /**
   * Creates the service.
   *
   * @param entityRepository the entity repository
   * @param entityTypeRepository the entity type repository, to resolve a recorded type name
   * @param entityTypeVersionRepository the type version repository, to resolve a recorded pin
   * @param entityVersionRepository the version history repository
   * @param entityRelationshipRepository the relationship repository, to replicate links
   * @param mappingEntityTypeRelationshipRepository the mapping rule repository, to tell which
   *     cloned entities derive others
   * @param mappedEntityService the derivation, run on each cloned mapping source
   * @param transactionManager the transaction manager; every unit of work runs in a transaction of
   *     its own
   */
  public FrozenVersionService(
      EntityRepository entityRepository,
      EntityTypeRepository entityTypeRepository,
      EntityTypeVersionRepository entityTypeVersionRepository,
      EntityVersionRepository entityVersionRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      MappedEntityService mappedEntityService,
      PlatformTransactionManager transactionManager) {
    this.entityRepository = entityRepository;
    this.entityTypeRepository = entityTypeRepository;
    this.entityTypeVersionRepository = entityTypeVersionRepository;
    this.entityVersionRepository = entityVersionRepository;
    this.entityRelationshipRepository = entityRelationshipRepository;
    this.mappingEntityTypeRelationshipRepository = mappingEntityTypeRelationshipRepository;
    this.mappedEntityService = mappedEntityService;
    // Freezing runs after the transaction that recorded a version has committed, while that
    // transaction's resources are still bound to the thread: joining it would write into a
    // transaction that never commits again.
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Gives every past version of an aggregate root that has no frozen copy yet one of its own, or
   * the copy of the version before it when the two differ only in run results.
   *
   * <p>Called once a transaction that recorded a version of the root has committed. The copies are
   * cloned in one transaction holding the root's row lock, so two recordings of the same root
   * cannot both copy a version; the derived entities of each cloned node are then derived in a
   * transaction of their own, so one that cannot be derived does not undo the others or the copy.
   *
   * <p>Never throws. The change that recorded the version is already committed, and a past
   * version's copy failing must not turn it into an error for the caller; the next recording of the
   * root retries a version left without a copy.
   *
   * @param rootId the id of the live aggregate root
   */
  public void freezePastVersions(String rootId) {
    try {
      var created = ownTransaction.execute(_ -> cloneMissingCopies(rootId));
      if (created != null) for (var copyRootId : created) deriveMappedInstances(copyRootId);
    } catch (RuntimeException e) {
      log.warn("Could not freeze the past versions of aggregate {}", rootId, e);
    }
  }

  private List<String> cloneMissingCopies(String rootId) {
    var root = entityRepository.findByIdForUpdate(rootId).orElse(null);
    if (root == null || root.isFrozen()) return List.of();
    var versions = entityVersionRepository.findByEntityIdOrderByVersionAsc(rootId);
    var created = new ArrayList<String>();
    JsonNode previousShape = null;
    String previousCopy = null;
    // The last version is the current one, which the live aggregate already is.
    for (var version : versions.subList(0, Math.max(0, versions.size() - 1))) {
      if (version.getAggregate() == null) {
        previousShape = null;
        previousCopy = null;
        continue;
      }
      var shape = shapeOf(version.getAggregate());
      if (version.getFrozenRootId() == null) {
        if (previousCopy != null && previousShape.equals(EntityVersionRecorder.BY_VALUE, shape))
          version.setFrozenRootId(previousCopy);
        else {
          var copy = cloneVersion(rootId, version);
          if (copy.isEmpty()) {
            previousShape = null;
            previousCopy = null;
            continue;
          }
          version.setFrozenRootId(copy.get().getId());
          created.add(copy.get().getId());
        }
        entityVersionRepository.save(version);
        log.info(
            "Froze version {} of aggregate {} as copy {}",
            version.getVersion(),
            rootId,
            version.getFrozenRootId());
      }
      previousShape = shape;
      previousCopy = version.getFrozenRootId();
    }
    return created;
  }

  /**
   * A recorded tree as its copy would reproduce it: without run results, which a copy never
   * inherits, and without the derived entities, which a copy derives afresh. Two versions with the
   * same shape would get identical copies.
   */
  private static ObjectNode shapeOf(JsonNode tree) {
    ObjectNode shape = tree.deepCopy();
    shape.remove("mapped");
    if (shape.get("values") instanceof ObjectNode values) values.remove(RUN_FIELDS);
    if (shape.get("parts") instanceof ArrayNode parts)
      for (var i = 0; i < parts.size(); i++) parts.set(i, shapeOf(parts.get(i)));
    return shape;
  }

  private Optional<Entity> cloneVersion(String rootId, EntityVersion version) {
    var tree = version.getAggregate();
    var unresolvable = firstUnresolvable(tree);
    if (unresolvable.isPresent()) {
      log.warn(
          "Version {} of aggregate {} cannot be frozen: {}",
          version.getVersion(),
          rootId,
          unresolvable.get());
      return Optional.empty();
    }
    var copies = new HashMap<String, Entity>();
    var dependencies = new ArrayList<Map.Entry<Entity, String>>();
    var copyRoot = cloneNode(tree, rootId, version.getVersion(), copies, dependencies);
    for (var dependency : dependencies) {
      var target = copies.get(dependency.getValue());
      if (target != null) linkPair(dependency.getKey(), DEPENDS_ON, target);
    }
    return Optional.of(copyRoot);
  }

  /**
   * Checks the whole tree before anything is written, so that a version whose type or pinned type
   * version is gone is skipped as a whole instead of being half-copied.
   */
  private Optional<String> firstUnresolvable(JsonNode node) {
    var type = node.path("entityType").asText();
    if (entityTypeRepository.findByName(type).isEmpty())
      return Optional.of("entity type " + type + " no longer exists");
    var pin = node.path("entityTypeVersionId").asText();
    if (!entityTypeVersionRepository.existsById(pin))
      return Optional.of("type version " + pin + " of " + type + " no longer exists");
    for (var part : node.path("parts")) {
      var missing = firstUnresolvable(part);
      if (missing.isPresent()) return missing;
    }
    return Optional.empty();
  }

  private Entity cloneNode(
      JsonNode node,
      String rootId,
      int version,
      Map<String, Entity> copies,
      List<Map.Entry<Entity, String>> dependencies) {
    var copy = new Entity();
    copy.setEntityType(
        entityTypeRepository.findByName(node.path("entityType").asText()).orElseThrow());
    copy.setEntityTypeVersion(
        entityTypeVersionRepository
            .findById(node.path("entityTypeVersionId").asText())
            .orElseThrow());
    JsonNode values = node.path("values").deepCopy();
    if (values instanceof ObjectNode object) object.remove(RUN_FIELDS);
    copy.setValues(values);
    copy.setFrozenVersionOf(rootId);
    copy.setFrozenVersion(version);
    entityRepository.save(copy);
    copies.put(node.path("id").asText(), copy);
    for (var dependency : node.path("dependencies"))
      dependencies.add(Map.entry(copy, dependency.asText()));
    for (var part : node.path("parts"))
      linkPair(copy, HAS_PART, cloneNode(part, rootId, version, copies, dependencies));
    return copy;
  }

  private void linkPair(Entity source, RelationType type, Entity target) {
    entityRelationshipRepository.save(relationship(source, type, target));
    if (type.hasInverse())
      entityRelationshipRepository.save(relationship(target, type.inverse(), source));
  }

  private static EntityRelationship relationship(Entity source, RelationType type, Entity target) {
    var relationship = new EntityRelationship();
    relationship.setSource(source);
    relationship.setRelationType(type);
    relationship.setTarget(target);
    return relationship;
  }

  private void deriveMappedInstances(String copyRootId) {
    var order = ownTransaction.execute(_ -> derivationOrder(copyRootId));
    if (order == null) return;
    for (var sourceId : order) {
      try {
        ownTransaction.executeWithoutResult(
            _ -> mappedEntityService.createMappedEntities(sourceId));
      } catch (RuntimeException e) {
        log.warn(
            "Could not derive the mapped entities of entity {} in frozen copy {}",
            sourceId,
            copyRootId,
            e);
      }
    }
  }

  /**
   * The copy's mapping sources in the order to derive them: an entity after the entities it depends
   * on, since a mapping may reach a dependency's derived entity through its path references;
   * otherwise top-down, in the order the copy's containment tree is walked.
   */
  private List<String> derivationOrder(String copyRootId) {
    var members = new LinkedHashMap<String, Entity>();
    var pending = new ArrayDeque<Entity>();
    entityRepository.findById(copyRootId).ifPresent(pending::add);
    while (!pending.isEmpty()) {
      var current = pending.poll();
      if (members.putIfAbsent(current.getId(), current) != null) continue;
      for (var part : entityRelationshipRepository.findBySourceAndRelationType(current, HAS_PART))
        pending.add(part.getTarget());
    }
    var position = new HashMap<String, Integer>();
    members.keySet().forEach(id -> position.put(id, position.size()));
    var dependencies = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
    members.keySet().forEach(dependencies::addVertex);
    for (var member : members.values())
      for (var link : entityRelationshipRepository.findBySourceAndRelationType(member, DEPENDS_ON))
        if (members.containsKey(link.getTarget().getId()))
          dependencies.addEdge(link.getTarget().getId(), member.getId());
    var ordered = new ArrayList<String>();
    try {
      new TopologicalOrderIterator<>(dependencies, Comparator.comparing(position::get))
          .forEachRemaining(ordered::add);
    } catch (IllegalArgumentException cycle) {
      // link() refuses dependency cycles, so this is a corrupt copy; tree order still derives it.
      ordered = new ArrayList<>(members.keySet());
    }
    return ordered.stream()
        .filter(
            id ->
                ServiceUtils.isMappingSourceEntityType(
                    mappingEntityTypeRelationshipRepository, members.get(id).getEntityType()))
        .toList();
  }
}
