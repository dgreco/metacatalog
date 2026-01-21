package it.davidgreco.metacatalog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Abstract base class for relationship entities in the metacatalog domain model.
 *
 * <p>This class provides a common structure for representing directed relationships between
 * entities of the same type. It serves as a mapped superclass for specific relationship
 * implementations such as entity relationships, trait relationships, and mapping relationships.
 *
 * @param <T> the type of entities that participate in this relationship
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@MappedSuperclass
public class CommonRelationship<T> {

  /** The unique identifier for this relationship, generated as a UUID. */
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  @ToString.Include
  private String id;

  /** The source entity of this relationship (the "from" side of the directed relationship). */
  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "source_id", nullable = false)
  @ToString.Include
  private T source;

  /** The target entity of this relationship (the "to" side of the directed relationship). */
  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "target_id", nullable = false)
  @ToString.Include
  private T target;

  /** The type of relationship between the source and target entities. */
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @ToString.Include
  private RelationType relationType;
}
