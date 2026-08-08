package it.davidgreco.metacatalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Represents a relationship between two traits.
 *
 * <p>Trait relationships define how traits are connected to each other in the metacatalog. These
 * relationships are used to express dependencies or associations between traits, which in turn
 * affect the entities that have those traits.
 *
 * @see CommonRelationship
 * @see RelationType
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@jakarta.persistence.Entity
@Table(
    name = "trait_relationship",
    indexes = {
      @Index(
          name = "idx_trait_relationship_source_id_relation_type",
          columnList = "source_id, relation_type"),
      @Index(
          name = "idx_trait_relationship_source_id_relation_type_target_id",
          columnList = "source_id, relation_type, target_id",
          unique = true),
    })
public class TraitRelationship extends CommonRelationship<Trait> {

  /**
   * Whether this relationship is frozen: an immutable trait relationship cannot be unlinked.
   *
   * <p>The flag lives here rather than on {@link CommonRelationship} on purpose — only trait
   * relationships carry it, so putting it on the shared superclass would add a dead column to
   * {@code entity_relationship} and both mapping relationship tables.
   *
   * <p>A relation type with an inverse is stored as a pair of rows, created and removed together by
   * {@code TraitServiceImpl.link} / {@code unlink}, so both rows are always marked alike; the
   * removal guard still checks both, since a row could only ever disagree if the database were
   * edited by hand.
   */
  @Column(name = "immutable", nullable = false)
  @ToString.Include
  private boolean immutable = false;
}
