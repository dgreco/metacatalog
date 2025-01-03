package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.repository.TraitRelationshipRepository;
import it.davidgreco.metacatalog.repository.TraitRepository;
import it.davidgreco.metacatalog.service.TraitService;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ApplicationConfig {

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManagerFactory emf;

    @Bean
    public TraitService traitService(
            TraitRepository traitRepository, TraitRelationshipRepository traitRelationshipRepository) {
        return new TraitService(traitRepository, traitRelationshipRepository, emf);
    }
}
