package it.davidgreco.metacatalog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@MappedSuperclass
public class CommonRelationship<T> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_id", nullable = false)
    @ToString.Include
    private T source;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_id", nullable = false)
    @ToString.Include
    private T target;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private RelationType relationType;
}
