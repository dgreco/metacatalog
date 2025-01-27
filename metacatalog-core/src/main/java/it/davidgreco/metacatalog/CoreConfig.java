package it.davidgreco.metacatalog;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.repository.*;
import it.davidgreco.metacatalog.service.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Configuration class for the core module. */
@Configuration
@Getter
@RequiredArgsConstructor
public class CoreConfig {

  private final CoreConfigProperties applicationConfigurationProperties;

  private final EntityTypeRepository entityTypeRepository;

  private final EntityRepository entityRepository;

  private final TraitRepository traitRepository;

  private final TraitRelationshipRepository traitRelationshipRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  private final PlatformTransactionManager transactionManager;

  private final AdvisoryLockManager advisoryLockManager;

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
      TraitRelationshipRepository traitRelationshipRepository,
      EntityRepository entityRepository,
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      EntityLifeCycleEventRepository entityLifeCycleEventRepository,
      PlatformTransactionManager transactionManager,
      CoreConfigProperties applicationConfigurationProperties) {
    return new MappingService(
        traitRelationshipRepository,
        entityRepository,
        entityTypeRepository,
        mappingEntityTypeRelationshipRepository,
        mappingEntityRelationshipRepository,
        entityRelationshipRepository,
        entityLifeCycleEventRepository,
        transactionManager,
        applicationConfigurationProperties);
  }

  @Bean
  public AggregateService aggregateService(
      EntityService entityService, EntityRelationshipRepository entityRelationshipRepository) {
    return new AggregateService(entityService, entityRelationshipRepository);
  }

  @Bean
  MappingUpdaterService mappingUpdaterService(MappingService mappingService) {
    var mus =
        new MappingUpdaterService(
            advisoryLockManager, entityLifeCycleEventRepository, mappingService);
    mus.setAutomaticEntitiesMapping(applicationConfigurationProperties.automaticEntitiesMapping());
    return mus;
  }

  @Bean
  public BulkLoaderService bulkLoaderService(
      TraitService traitService, EntityTypeService entityTypeService, EntityService entityService) {
    return new BulkLoaderService(traitService, entityTypeService, entityService);
  }
}
