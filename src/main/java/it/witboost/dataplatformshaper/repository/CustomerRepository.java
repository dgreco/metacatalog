package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, String> {}
