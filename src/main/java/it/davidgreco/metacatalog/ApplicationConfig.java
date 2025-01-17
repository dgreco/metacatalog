package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.repository.*;
import it.davidgreco.metacatalog.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ApplicationConfig {

    @Autowired
    ApplicationConfigProperties applicationConfigurationProperties;

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
    EntityLifeCycleEventRepository entityLifeCycleEventRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    AdvisoryLockManager advisoryLockManager;

    @Bean
    public TraitService traitService(
            TraitRepository traitRepository, TraitRelationshipRepository traitRelationshipRepository) {
        return new TraitService(traitRepository, traitRelationshipRepository);
    }

    @Bean
    public EntityTypeService entityTypeService(
            EntityTypeRepository entityTypeRepository, TraitRepository traitRepository) {
        return new EntityTypeService(entityTypeRepository, traitRepository);
    }

    @Bean
    public EntityService entityService(
            EntityTypeRepository entityTypeRepository,
            EntityRepository entityRepository,
            EntityRelationshipRepository entityRelationshipRepository,
            TraitRelationshipRepository traitRelationshipRepository,
            MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
            EntityLifeCycleEventRepository entityLifeCycleEventRepository) {
        return new EntityService(
                entityTypeRepository,
                entityRepository,
                entityRelationshipRepository,
                traitRelationshipRepository,
                mappingEntityTypeRelationshipRepository,
                entityLifeCycleEventRepository);
    }

    @Bean
    public MappingService mappingService(
            EntityRepository entityRepository,
            EntityTypeRepository entityTypeRepository,
            MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
            MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
            EntityRelationshipRepository entityRelationshipRepository,
            EntityLifeCycleEventRepository entityLifeCycleEventRepository,
            PlatformTransactionManager transactionManager,
            AdvisoryLockManager advisoryLockManager) {
        var ms = new MappingService(
                entityRepository,
                entityTypeRepository,
                mappingEntityTypeRelationshipRepository,
                mappingEntityRelationshipRepository,
                entityRelationshipRepository,
                entityLifeCycleEventRepository,
                transactionManager,
                advisoryLockManager);
        return ms;
    }

    @Bean
    MappingUpdaterService mappingUpdaterService(MappingService mappingService) {
        var mus = new MappingUpdaterService(advisoryLockManager, entityLifeCycleEventRepository, mappingService);
        mus.automaticEntitiesMapping = applicationConfigurationProperties.isAutomaticEntitiesMapping();
        return mus;
    }

    @Bean
    public BulkLoaderService bulkLoaderService(
            TraitService traitService, EntityTypeService entityTypeService, EntityService entityService) {
        return new BulkLoaderService(traitService, entityTypeService, entityService);
    }
}
