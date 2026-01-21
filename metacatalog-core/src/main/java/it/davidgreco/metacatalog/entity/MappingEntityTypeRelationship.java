package it.davidgreco.metacatalog.entity;

import static it.davidgreco.metacatalog.common.JsonUtils.jsonFactory;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a mapping rule between two entity types.
 *
 * <p>Mapping entity type relationships define how entities of one type should be automatically
 * transformed into entities of another type. The mapping includes transformation expressions and
 * references to related entities that provide additional context for the transformation.
 *
 * @see CommonRelationship
 * @see MappingEntityRelationship
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "mapping_type_relationship",
    indexes = {
      @Index(
          name = "idx_mapping_type_relationship_source_id_relation_type",
          columnList = "source_id, relation_type"),
      @Index(
          name = "idx_mapping_type_relationship_source_id_relation_type_target_id",
          columnList = "source_id, relation_type, target_id")
    })
public class MappingEntityTypeRelationship extends CommonRelationship<EntityType> {

  /** The JSON expressions that define how to map source values to target values. */
  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "mapping_values", columnDefinition = "jsonb", nullable = false)
  @ToString.Include
  private JsonNode mappingValues;

  /** The references to related entities used in the mapping expressions. */
  @org.hibernate.annotations.Type(JsonBinaryType.class)
  @Column(name = "entity_path_references", columnDefinition = "jsonb", nullable = false)
  @ToString.Include
  private JsonNode entityPathReferences;

  /**
   * Represents a reference to an entity path used in mapping expressions.
   *
   * @param alias the alias used to reference this path in mapping expressions
   * @param referencePath the path expression to traverse entity relationships
   */
  public record EntityPathReference(String alias, String referencePath) {}

  /**
   * Sets the entity path references from a list of reference objects.
   *
   * @param eprs the list of entity path references to set
   */
  public void setEntityPathReferences(List<EntityPathReference> eprs) {
    var nodes = eprs.stream().map(jsonFactory::<JsonNode>valueToTree).toList();
    var node = jsonFactory.createArrayNode();
    node.addAll(nodes);
    entityPathReferences = node;
  }

  /**
   * Returns the entity path references as a list of reference objects.
   *
   * @return the list of entity path references
   */
  public List<MappingEntityTypeRelationship.EntityPathReference> getEntityPathReferences() {
    var list = new ArrayList<EntityPathReference>();
    var nodes = entityPathReferences.elements();
    while (nodes.hasNext()) {
      var node = nodes.next();
      var alias = node.get("alias").asText();
      var referencePath = node.get("referencePath").asText();
      list.add(new EntityPathReference(alias, referencePath));
    }
    return list;
  }
}
