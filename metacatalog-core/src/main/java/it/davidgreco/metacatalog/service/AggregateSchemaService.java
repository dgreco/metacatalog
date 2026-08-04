package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;
import static it.davidgreco.metacatalog.service.CommonTypeService.loadInheritanceChain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import it.davidgreco.metacatalog.repository.EntityTypeRepository;
import it.davidgreco.metacatalog.repository.MappingEntityTypeRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives, from the type model, the single JSON Schema that describes a whole aggregate tree.
 *
 * <p>The metacatalog does not store {@code HAS_PART} between entity types directly: composition is
 * declared once between <em>traits</em>, and an entity type takes part in it by mixing those traits
 * in. This is exactly the rule {@link ServiceUtils#checkRelIsLegit} enforces when two entity
 * instances are linked. This service projects those trait relationships onto the entity types
 * carrying them — giving a type-level {@code HAS_PART} graph — and then walks it.
 *
 * <p>Two operations are exposed:
 *
 * <ul>
 *   <li>{@link #aggregateRootTypes()} — the types that can start an aggregate: a type with at least
 *       one outgoing {@code HAS_PART} edge and no incoming one, i.e. it can contain parts but is
 *       never itself a part.
 *   <li>{@link #aggregateSchema(String)} — one self-contained JSON Schema for the entire tree
 *       reachable from a root type.
 * </ul>
 *
 * <p>The generated schema mirrors the aggregate YAML shape {@link
 * BulkLoaderService#bulkAggregateCreation} already accepts ({@code entityType}, {@code values},
 * {@code ref}, {@code dependsOn}, {@code parts}), so a document written against it can be handed
 * straight to that loader. Every reachable type becomes an entry under {@code $defs} and
 * containment is expressed with {@code $ref}. Referencing rather than inlining is what keeps the
 * output finite when the composition graph contains a cycle (a type transitively containing its own
 * kind), which the trait model permits.
 *
 * <p>Each node's {@code values} are described by its entity type's <em>derived</em> schema — the
 * merge of the whole linearized ancestry, father chain and mixed-in traits included. That is the
 * same schema entity creation validates against, so a document written against the aggregate schema
 * is accepted by {@code EntityService.create}. See {@link #valuesSchema(EntityType)}.
 */
@Slf4j
@RequiredArgsConstructor
public class AggregateSchemaService {

  private static final String DEFS_POINTER = "#/$defs/";
  private static final String PROPERTIES = "properties";
  private static final String OBJECT = "object";
  private static final String STRING = "string";
  private static final String ARRAY = "array";
  private static final String TYPE = "type";
  private static final String ITEMS = "items";
  private static final String DESCRIPTION = "description";
  private static final String ENTITY_TYPE = "entityType";
  private static final String VALUES = "values";
  private static final String PARTS = "parts";
  private static final String REF_FIELD = "ref";
  private static final String DEPENDS_ON_FIELD = "dependsOn";

  private final EntityTypeRepository entityTypeRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final JsonUtils jsonUtils;

  /**
   * Returns the entity types that are aggregate roots, ordered by name.
   *
   * <p>A root can contain parts ({@code HAS_PART} towards at least one type) but is never a part
   * itself (no type declares {@code HAS_PART} towards it). A type whose only incoming edge comes
   * from itself is still a root: it may nest instances of its own kind without being contained by
   * anything else.
   *
   * @return the aggregate root entity types, ordered by name
   */
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public List<EntityType> aggregateRootTypes() {
    log.info("Computing aggregate root types");
    var types = authorableTypes();
    var graph = hasPartGraph(types);
    var roots =
        types.stream()
            .filter(type -> !graph.getOrDefault(type.getName(), List.of()).isEmpty())
            .filter(type -> !isContainedByAnotherType(type, graph, types))
            .sorted(Comparator.comparing(EntityType::getName))
            .toList();
    log.info("Computed {} aggregate root type(s)", roots.size());
    return roots;
  }

  /**
   * Builds the combined JSON Schema for the aggregate tree rooted at the given entity type.
   *
   * @param rootTypeName the name of the aggregate root entity type
   * @return a self-contained JSON Schema describing the whole aggregate
   * @throws NotFoundException if no entity type with that name exists
   * @throws ServiceError if the type has no {@code HAS_PART} relationship and so roots no aggregate
   */
  @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
  public JsonNode aggregateSchema(String rootTypeName) {
    log.info("Building aggregate schema for root type {}", rootTypeName);
    var types = authorableTypes();
    var root =
        types.stream()
            .filter(type -> type.getName().equals(rootTypeName))
            .findFirst()
            .orElseThrow(
                () -> new NotFoundException("EntityType " + rootTypeName + ServiceUtils.NOT_FOUND));

    var graph = hasPartGraph(types);
    if (graph.getOrDefault(rootTypeName, List.of()).isEmpty()) {
      throw new ServiceError(
          "EntityType "
              + rootTypeName
              + " is not an aggregate root: it has no HAS_PART relationship with any other type");
    }

    var reachable = reachableFrom(root, graph);
    var defs = jsonUtils.jsonMapper().createObjectNode();
    for (var type : reachable) {
      defs.set(type.getName(), nodeSchema(type, graph, types));
    }

    var schema = jsonUtils.jsonMapper().createObjectNode();
    schema.setAll((ObjectNode) defs.get(rootTypeName).deepCopy());
    schema.put(
        DESCRIPTION,
        "Aggregate rooted at entity type '"
            + rootTypeName
            + "'. Each node carries the values of its own entity type; 'parts' nests the types it"
            + " is composed of.");
    schema.set("$defs", defs);
    log.info(
        "Built aggregate schema for root type {} covering {} type(s)",
        rootTypeName,
        reachable.size());
    return schema;
  }

  /**
   * Builds the schema of a single aggregate node: the entity type's derived schema under {@code
   * values}, the bookkeeping fields the aggregate loader understands, and — when the type composes
   * others — a {@code parts} array referencing them.
   */
  private ObjectNode nodeSchema(
      EntityType type, Map<String, List<EntityType>> graph, List<EntityType> types) {
    var mapper = jsonUtils.jsonMapper();
    var node = mapper.createObjectNode();
    node.put(TYPE, OBJECT);
    node.put(DESCRIPTION, "An entity of type '" + type.getName() + "' within the aggregate.");

    var properties = mapper.createObjectNode();

    var entityTypeProperty = mapper.createObjectNode();
    entityTypeProperty.put(TYPE, STRING);
    entityTypeProperty.put(DESCRIPTION, "The entity type of this aggregate node.");
    var allowed = entityTypeProperty.putArray("enum");
    assignableTypeNames(type, types).forEach(allowed::add);
    properties.set(ENTITY_TYPE, entityTypeProperty);

    properties.set(VALUES, valuesSchema(type));

    var refProperty = mapper.createObjectNode();
    refProperty.put(TYPE, STRING);
    refProperty.put(
        DESCRIPTION, "Optional local identifier so other nodes can name this one in 'dependsOn'.");
    properties.set(REF_FIELD, refProperty);

    var dependsOnProperty = mapper.createObjectNode();
    dependsOnProperty.put(TYPE, ARRAY);
    dependsOnProperty.put(DESCRIPTION, "The 'ref' values of the nodes this one depends on.");
    dependsOnProperty.set(ITEMS, mapper.createObjectNode().put(TYPE, STRING));
    properties.set(DEPENDS_ON_FIELD, dependsOnProperty);

    var parts = graph.getOrDefault(type.getName(), List.of());
    if (!parts.isEmpty()) {
      properties.set(PARTS, partsProperty(parts));
    }

    node.set(PROPERTIES, properties);
    var required = node.putArray("required");
    required.add(ENTITY_TYPE);
    required.add(VALUES);
    return node;
  }

  /**
   * The schema an aggregate node's {@code values} are validated against: the entity type's <em>
   * derived</em> schema.
   *
   * <p>The derived schema is the one {@link EntityTypeServiceImpl} computes by merging the
   * linearized ancestors — father chain and mixed-in traits — so it is the only schema that
   * describes an entity completely. A type's {@code baseSchema} holds just its own declarations,
   * which for a type composed mostly of traits is often empty: authoring against it would present a
   * form with none of the fields the entity actually requires, and the document would then fail
   * validation on create.
   *
   * <p>Falling back to the base schema when the derived one is missing would reintroduce exactly
   * that gap silently, so a type without a derived schema contributes an empty object instead —
   * every persisted type gets one at creation time, making this a defensive branch rather than an
   * expected path.
   */
  private ObjectNode valuesSchema(EntityType type) {
    var mapper = jsonUtils.jsonMapper();
    var derived = type.getDerivedSchema();
    var valuesProperty =
        derived == null
            ? mapper.createObjectNode().put(TYPE, OBJECT)
            : (ObjectNode) derived.deepCopy();
    valuesProperty.put(
        DESCRIPTION,
        "The values of this entity, validated against the derived schema of '"
            + type.getName()
            + "'.");
    return valuesProperty;
  }

  /** The {@code parts} array property, referencing every type this one may contain. */
  private ObjectNode partsProperty(List<EntityType> parts) {
    var mapper = jsonUtils.jsonMapper();
    var partsProperty = mapper.createObjectNode();
    partsProperty.put(TYPE, ARRAY);
    partsProperty.put(
        DESCRIPTION,
        "The parts composing this entity. Allowed part types: "
            + parts.stream().map(EntityType::getName).collect(Collectors.joining(", "))
            + ".");
    var items = mapper.createObjectNode();
    if (parts.size() == 1) {
      items.put("$ref", DEFS_POINTER + parts.getFirst().getName());
    } else {
      var anyOf = items.putArray("anyOf");
      for (var part : parts) {
        anyOf.addObject().put("$ref", DEFS_POINTER + part.getName());
      }
    }
    partsProperty.set(ITEMS, items);
    return partsProperty;
  }

  /**
   * The entity types an aggregate can be authored from: everything except the targets of a mapping.
   * Mapping targets are legitimate parts of the model, but the mapping engine derives their
   * instances — {@link EntityServiceImpl#create} refuses to create them directly — so offering them
   * for authoring would produce a document that cannot be loaded.
   */
  private List<EntityType> authorableTypes() {
    return entityTypeRepository.findAll().stream()
        .filter(
            type ->
                !ServiceUtils.isMappingTargetEntityType(
                    mappingEntityTypeRelationshipRepository, type))
        .toList();
  }

  /**
   * Projects the trait-level {@code HAS_PART} declarations onto entity types, yielding — per type
   * name — the entity types it may contain, ordered by name.
   *
   * <p>A type may contain another when some trait in the source type's linearized trait set
   * declares {@code HAS_PART} towards a trait the target type carries: the same test that decides
   * whether two instances may actually be linked.
   */
  private Map<String, List<EntityType>> hasPartGraph(List<EntityType> types) {
    var traitsByType = new LinkedHashMap<String, Set<String>>();
    var allTraits = new LinkedHashMap<String, Trait>();
    for (var type : types) {
      var names = new LinkedHashSet<String>();
      for (var trait : linearizedTraits(type)) {
        names.add(trait.getName());
        allTraits.putIfAbsent(trait.getName(), trait);
      }
      traitsByType.put(type.getName(), names);
    }

    var partTraitsOfTrait = new LinkedHashMap<String, Set<String>>();
    for (var trait : allTraits.values()) {
      partTraitsOfTrait.put(
          trait.getName(),
          traitRelationshipRepository.findBySourceAndRelationType(trait, HAS_PART).stream()
              .map(rel -> rel.getTarget().getName())
              .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    var graph = new LinkedHashMap<String, List<EntityType>>();
    for (var source : types) {
      var partTraits =
          traitsByType.get(source.getName()).stream()
              .flatMap(traitName -> partTraitsOfTrait.getOrDefault(traitName, Set.of()).stream())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      if (partTraits.isEmpty()) {
        graph.put(source.getName(), List.of());
        continue;
      }
      graph.put(
          source.getName(),
          types.stream()
              .filter(
                  target ->
                      traitsByType.get(target.getName()).stream().anyMatch(partTraits::contains))
              .sorted(Comparator.comparing(EntityType::getName))
              .toList());
    }
    return graph;
  }

  /** Every trait carried by a type, following both the type's and the traits' father chains. */
  private static List<Trait> linearizedTraits(EntityType type) {
    return loadInheritanceChain(type).stream()
        .flatMap(
            et -> et.getTraits().stream().flatMap(trait -> loadInheritanceChain(trait).stream()))
        .toList();
  }

  /** The types reachable from {@code root} through containment, breadth-first, root first. */
  private static List<EntityType> reachableFrom(
      EntityType root, Map<String, List<EntityType>> graph) {
    var visited = new LinkedHashSet<String>();
    var ordered = new ArrayList<EntityType>();
    var toVisit = new ArrayDeque<EntityType>();
    toVisit.add(root);
    while (!toVisit.isEmpty()) {
      var current = toVisit.poll();
      if (!visited.add(current.getName())) continue;
      ordered.add(current);
      toVisit.addAll(graph.getOrDefault(current.getName(), List.of()));
    }
    return ordered;
  }

  /**
   * The names a node of this type may declare in {@code entityType}: the type itself plus every
   * type inheriting from it, since a subtype satisfies every constraint its father declares.
   */
  private static List<String> assignableTypeNames(EntityType type, List<EntityType> types) {
    return types.stream()
        .filter(
            candidate ->
                loadInheritanceChain(candidate).stream()
                    .anyMatch(ancestor -> ancestor.getName().equals(type.getName())))
        .map(EntityType::getName)
        .sorted()
        .toList();
  }

  /** True when some type other than {@code type} itself declares {@code HAS_PART} towards it. */
  private static boolean isContainedByAnotherType(
      EntityType type, Map<String, List<EntityType>> graph, List<EntityType> types) {
    return types.stream()
        .filter(source -> !source.getName().equals(type.getName()))
        .anyMatch(
            source ->
                graph.getOrDefault(source.getName(), List.of()).stream()
                    .anyMatch(part -> part.getName().equals(type.getName())));
  }
}
