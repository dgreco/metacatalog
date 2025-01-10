package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.repository.*;
import it.davidgreco.metacatalog.service.*;
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

    @Bean
    public BulkLoaderService bulkLoaderService(
            TraitService traitService,
            EntityTypeService entityTypeService,
            EntityService entityService,
            PlatformTransactionManager transactionManager) {
        return new BulkLoaderService(traitService, entityTypeService, entityService, transactionManager);
    }
}
