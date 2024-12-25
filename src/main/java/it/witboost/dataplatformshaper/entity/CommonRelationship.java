package it.witboost.dataplatformshaper.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@MappedSuperclass
public class CommonRelationship<T> implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    protected String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_id")
    @ToString.Include
    protected T source;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "target_id")
    @ToString.Include
    protected T target;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    protected RelationType relationType;
}
