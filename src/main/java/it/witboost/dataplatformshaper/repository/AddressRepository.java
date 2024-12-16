package it.witboost.dataplatformshaper.repository;

import it.witboost.dataplatformshaper.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, String> {}
