package it.witboost.glossaryplugin.repository;

import it.witboost.glossaryplugin.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, String> {}
