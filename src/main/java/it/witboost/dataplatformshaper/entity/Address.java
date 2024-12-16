package it.witboost.dataplatformshaper.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(
        name = "address",
        indexes = {@Index(name = "idx_address_id_unq", columnList = "id", unique = true)})
public class Address {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private String id;

    @Column(name = "addressLine1", nullable = false)
    @ToString.Include
    private String addressLine1;

    @Column(name = "addressLine2")
    @ToString.Include
    private String addressLine2;

    @Column(name = "city", nullable = false)
    @ToString.Include
    private String city;

    @Column(name = "state", nullable = false)
    @ToString.Include
    private String state;

    @Column(name = "country", nullable = false)
    @ToString.Include
    private String country;

    @Column(name = "zipcode", nullable = false)
    @ToString.Include
    private String zipcode;

    @ManyToOne
    @JoinColumn(name = "customerId")
    @ToString.Include
    private Customer customer;
}
