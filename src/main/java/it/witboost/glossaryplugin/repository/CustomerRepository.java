package it.witboost.glossaryplugin.repository;

import it.witboost.glossaryplugin.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {}
