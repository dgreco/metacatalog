package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.EMPTY_SCHEMA;
import static it.davidgreco.metacatalog.entity.RelationType.HAS_PART;

import it.davidgreco.metacatalog.entity.EntityType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class AggregateSchemaServiceTests extends CommonServiceTestingSupport {

  public AggregateSchemaServiceTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  private static final String NAMED_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "name": {
            "type": "string"
          }
        },
        "required": ["name"]
      }
      """;

  /**
   * Builds a three-level model classified by the built-in traits: {@code ProductType} is a root
   * (carries {@code Aggregate} only), {@code ComponentType} an intermediate (carries both), {@code
   * ResourceType} a leaf (carries {@code AggregateElement} only). Only {@code Product} should be
   * reported as a root, and its schema should cover all three types.
   */
  @Test
  void rootDiscoveryAndCombinedSchema() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var schemaService = getApplicationContext().getBean(AggregateSchemaService.class);

    traitService.create("Named", Optional.of(NAMED_SCHEMA), Optional.empty());
    traitService.create("ProductTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.create(
        "ComponentTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));
    traitService.create(
        "ResourceTrait", Optional.of(EMPTY_SCHEMA), Optional.of("AggregateElement"));

    traitService.link("ProductTrait", HAS_PART, "ComponentTrait");
    traitService.link("ComponentTrait", HAS_PART, "ResourceTrait");

    entityTypeService.create(
        "ProductType", List.of("ProductTrait", "Named"), Optional.empty(), EMPTY_SCHEMA);
    // The intermediate mixes Aggregate in as well: it both contains parts and is one.
    entityTypeService.create(
        "ComponentType",
        List.of("ComponentTrait", "Aggregate", "Named"),
        Optional.empty(),
        EMPTY_SCHEMA);
    entityTypeService.create(
        "ResourceType",
        List.of("ResourceTrait"),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": {
            "bucket": { "type": "string" }
          }
        }
        """);

    // The root carries Aggregate only; the intermediate (both traits) and the leaf (element
    // only) must not be roots. Other classes' root types may coexist in the shared database.
    var roots = schemaService.aggregateRootTypes().stream().map(EntityType::getName).toList();
    Assertions.assertTrue(roots.contains("ProductType"), roots.toString());
    Assertions.assertFalse(roots.contains("ComponentType"), roots.toString());
    Assertions.assertFalse(roots.contains("ResourceType"), roots.toString());

    var schema = schemaService.aggregateSchema("ProductType");

    // The root node is inlined at the top level and every reachable type is under $defs.
    Assertions.assertEquals("object", schema.get("type").asText());
    var defs = schema.get("$defs");
    Assertions.assertTrue(defs.has("ProductType"));
    Assertions.assertTrue(defs.has("ComponentType"));
    Assertions.assertTrue(defs.has("ResourceType"));

    // Carrying Aggregate, the root may contain any AggregateElement carrier (the built-in
    // Aggregate HAS_PART AggregateElement sanction), so both other types are offered as parts.
    var rootPartRefs = schema.get("properties").get("parts").get("items").get("anyOf");
    Assertions.assertEquals(2, rootPartRefs.size());
    Assertions.assertEquals("#/$defs/ComponentType", rootPartRefs.get(0).get("$ref").asText());
    Assertions.assertEquals("#/$defs/ResourceType", rootPartRefs.get(1).get("$ref").asText());
    // The intermediate also carries Aggregate, so it composes the same part set.
    var componentPartRefs =
        defs.get("ComponentType").get("properties").get("parts").get("items").get("anyOf");
    Assertions.assertEquals(2, componentPartRefs.size());
    Assertions.assertEquals("#/$defs/ComponentType", componentPartRefs.get(0).get("$ref").asText());
    Assertions.assertEquals("#/$defs/ResourceType", componentPartRefs.get(1).get("$ref").asText());

    // A leaf type composes nothing, so it carries no 'parts'.
    Assertions.assertFalse(defs.get("ResourceType").get("properties").has("parts"));

    // Each node exposes its own entity type's effective schema under 'values'.
    Assertions.assertTrue(
        defs.get("ProductType").get("properties").get("values").get("properties").has("name"));
    Assertions.assertTrue(
        defs.get("ResourceType").get("properties").get("values").get("properties").has("bucket"));

    // 'entityType' and 'values' are what the aggregate loader needs to create the entity.
    var required = defs.get("ProductType").get("required");
    Assertions.assertEquals(2, required.size());
    Assertions.assertEquals("entityType", required.get(0).asText());
    Assertions.assertEquals("values", required.get(1).asText());
  }

  /**
   * A self-referential composition ({@code Folder} has parts {@code Folder}) must still yield a
   * finite schema, and the type must still be reported as a root: it carries {@code Aggregate} and
   * not {@code AggregateElement}, and nesting instances of its own kind does not change that.
   */
  @Test
  void selfReferentialCompositionStaysFiniteAndRoots() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var schemaService = getApplicationContext().getBean(AggregateSchemaService.class);

    traitService.create("FolderTrait", Optional.of(EMPTY_SCHEMA), Optional.of("Aggregate"));
    traitService.link("FolderTrait", HAS_PART, "FolderTrait");
    entityTypeService.create("FolderType", List.of("FolderTrait"), Optional.empty(), EMPTY_SCHEMA);

    Assertions.assertTrue(
        schemaService.aggregateRootTypes().stream()
            .map(EntityType::getName)
            .anyMatch("FolderType"::equals));

    var schema = schemaService.aggregateSchema("FolderType");
    // The database is shared across the class's tests, so other AggregateElement-carrying types
    // may be offered as parts too (the built-in Aggregate HAS_PART AggregateElement sanction);
    // what matters here is that the self-reference is expressed as a $ref and stays finite.
    Assertions.assertTrue(partRefs(schema).contains("#/$defs/FolderType"));
    Assertions.assertTrue(schema.get("$defs").has("FolderType"));
  }

  /** The {@code $ref}s a node's {@code parts} may contain, whether one or an {@code anyOf}. */
  private static List<String> partRefs(com.fasterxml.jackson.databind.JsonNode nodeSchema) {
    var items = nodeSchema.get("properties").get("parts").get("items");
    if (items.has("$ref")) {
      return List.of(items.get("$ref").asText());
    }
    var refs = new java.util.ArrayList<String>();
    items.get("anyOf").forEach(ref -> refs.add(ref.get("$ref").asText()));
    return refs;
  }

  /** A type that composes nothing cannot root an aggregate. */
  @Test
  void typeWithoutPartsIsNotAnAggregateRoot() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var schemaService = getApplicationContext().getBean(AggregateSchemaService.class);

    traitService.create("LonelyTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    entityTypeService.create("LonelyType", List.of("LonelyTrait"), Optional.empty(), EMPTY_SCHEMA);

    Assertions.assertFalse(
        schemaService.aggregateRootTypes().stream()
            .map(EntityType::getName)
            .anyMatch("LonelyType"::equals));

    Assertions.assertThrows(ServiceError.class, () -> schemaService.aggregateSchema("LonelyType"));
  }

  /** An unknown type name is a not-found condition, not a validation error. */
  @Test
  void unknownRootTypeIsNotFound() {
    var schemaService = getApplicationContext().getBean(AggregateSchemaService.class);
    Assertions.assertThrows(
        NotFoundException.class, () -> schemaService.aggregateSchema("NoSuchType"));
  }

  /**
   * Every node's {@code values} must come from its type's derived schema, so properties contributed
   * by a father type or a mixed-in trait are present even when the type's own base schema is empty.
   * Authoring against the base schema would present a form missing the very fields entity creation
   * requires.
   */
  @Test
  void valuesUseTheDerivedSchemaNotTheBaseSchema() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var schemaService = getApplicationContext().getBean(AggregateSchemaService.class);

    // A part trait contributing a property, mixed into a type whose own base schema is empty.
    traitService.create("CrateTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create(
        "LabelledTrait",
        Optional.of(
            """
            {
              "type": "object",
              "properties": { "label": { "type": "string" } },
              "required": ["label"]
            }
            """),
        Optional.empty());
    traitService.link("CrateTrait", HAS_PART, "LabelledTrait");

    entityTypeService.create("CrateType", List.of("CrateTrait"), Optional.empty(), EMPTY_SCHEMA);

    // A father contributing a property, inherited by a child whose own base schema is empty.
    entityTypeService.create(
        "BaseGoodType",
        List.of("LabelledTrait"),
        Optional.empty(),
        """
        {
          "type": "object",
          "properties": { "sku": { "type": "string" } }
        }
        """);
    entityTypeService.create(
        "FragileGoodType", List.of(), Optional.of("BaseGoodType"), EMPTY_SCHEMA);

    var schema = schemaService.aggregateSchema("CrateType");
    var defs = schema.get("$defs");

    // The trait-contributed property reaches the part node even though its base schema is empty.
    var baseGoodValues = defs.get("BaseGoodType").get("properties").get("values");
    Assertions.assertTrue(baseGoodValues.get("properties").has("label"));
    Assertions.assertTrue(baseGoodValues.get("properties").has("sku"));

    // The child inherits both the father's and the trait's properties through the derived schema.
    var fragileValues = defs.get("FragileGoodType").get("properties").get("values");
    Assertions.assertTrue(fragileValues.get("properties").has("sku"));
    Assertions.assertTrue(fragileValues.get("properties").has("label"));

    // The derived schema is exactly what entity creation validates against.
    var fragileType = entityTypeService.read("FragileGoodType");
    Assertions.assertEquals(
        fragileType.getDerivedSchema().get("properties"), fragileValues.get("properties"));
  }

  /**
   * When several types carry the part trait, the {@code parts} items list them all through {@code
   * anyOf} so any of them may appear.
   */
  @Test
  void multiplePartTypesAreOfferedAsAlternatives() {
    var traitService = getApplicationContext().getBean(TraitService.class);
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var schemaService = getApplicationContext().getBean(AggregateSchemaService.class);

    traitService.create("BoxTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.create("ItemTrait", Optional.of(EMPTY_SCHEMA), Optional.empty());
    traitService.link("BoxTrait", HAS_PART, "ItemTrait");

    entityTypeService.create("BoxType", List.of("BoxTrait"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create("BookType", List.of("ItemTrait"), Optional.empty(), EMPTY_SCHEMA);
    entityTypeService.create("PenType", List.of("ItemTrait"), Optional.empty(), EMPTY_SCHEMA);

    var schema = schemaService.aggregateSchema("BoxType");
    var anyOf = schema.get("properties").get("parts").get("items").get("anyOf");
    Assertions.assertEquals(2, anyOf.size());
    Assertions.assertEquals("#/$defs/BookType", anyOf.get(0).get("$ref").asText());
    Assertions.assertEquals("#/$defs/PenType", anyOf.get(1).get("$ref").asText());
  }
}
