package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.repository.*;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.EntityTypeService;
import it.davidgreco.metacatalog.service.MappingService;
import it.davidgreco.metacatalog.service.TraitService;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ApplicationConfig {

    @Autowired
    EntityTypeRepository entityTypeRepository;

    @Autowired
    EntityRepository entityRepository;

    @Autowired
    TraitRepository traitRepository;

    @Autowired
    TraitRelationshipRepository traitRelationshipRepository;

    @Autowired
    EntityRelationshipRepository entityRelationshipRepository;

    @Autowired
    MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

    @Autowired
    MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Bean
    public TraitService traitService(
            TraitRepository traitRepository,
            TraitRelationshipRepository traitRelationshipRepository,
            EntityManagerFactory entityManagerFactory) {
        return new TraitService(traitRepository, traitRelationshipRepository, entityManagerFactory);
    }

    @Bean
    public EntityTypeService entityTypeService(
            EntityTypeRepository entityTypeRepository,
            TraitRepository traitRepository,
            EntityManagerFactory entityManagerFactory) {
        return new EntityTypeService(entityTypeRepository, traitRepository, entityManagerFactory);
    }

    @Bean
    public EntityService entityService(
            EntityTypeRepository entityTypeRepository,
            EntityRepository entityRepository,
            EntityRelationshipRepository entityRelationshipRepository,
            TraitRelationshipRepository traitRelationshipRepository) {
        return new EntityService(
                entityTypeRepository, entityRepository, entityRelationshipRepository, traitRelationshipRepository);
    }

    @Bean
    public MappingService mappingService(
            EntityRepository entityRepository,
            EntityTypeRepository entityTypeRepository,
            MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
            MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
            EntityRelationshipRepository entityRelationshipRepository,
            PlatformTransactionManager transactionManager) {
        return new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                mappingEntityRelationshipRepository,
                entityRelationshipRepository,
                transactionManager);
    }
}
