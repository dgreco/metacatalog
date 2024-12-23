package it.witboost.dataplatformshaper.entity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(
        name = "trait_relationship",
        indexes = {
            @Index(
                    name = "idx_trait_relationship_source_id_relation_type_unq",
                    columnList = "source_id, relation_type",
                    unique = true)
        })
@SuppressFBWarnings
public class TraitRelationship {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @ManyToOne
    @JoinColumn(name = "source_id")
    @ToString.Include
    private Trait source;

    @ManyToOne
    @JoinColumn(name = "target_id")
    @ToString.Include
    private Trait target;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private RelationType relationType;
}
