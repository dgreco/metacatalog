package it.witboost.glossaryplugin.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.Collection;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(
        name = "customer",
        indexes = {@Index(name = "idx_customer_id_unq", columnList = "id", unique = true)})
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @Column(name = "firstName")
    @ToString.Include
    private String firstName;

    @Column(name = "middleName")
    @ToString.Include
    private String middleName;

    @Column(name = "familyName")
    @ToString.Include
    private String familyName;

    @Column(name = "age")
    @ToString.Include
    private int age;

    @OneToMany(mappedBy = "customer", orphanRemoval = true, fetch = FetchType.EAGER)
    @ToString.Exclude
    private Collection<Address> addresses = new ArrayList<>();
}
