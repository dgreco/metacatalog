package it.davidgreco.metacatalog;

import com.github.benmanes.caffeine.cache.Caffeine;
import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.repository.*;
import it.davidgreco.metacatalog.service.*;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring configuration class for the metacatalog core module.
 *
 * <p>This configuration sets up all core services, repositories, and infrastructure components
 * including caching, task execution, and service beans.
 */
@Configuration
@EnableCaching
@Getter
@RequiredArgsConstructor
public class CoreConfig {

  /** Application configuration properties. */
  private final CoreConfigProperties applicationConfigurationProperties;

  /** Repository for entity type persistence. */
  private final EntityTypeRepository entityTypeRepository;

  /** Repository for entity persistence. */
  private final EntityRepository entityRepository;

  /** Repository for trait persistence. */
  private final TraitRepository traitRepository;

  /** Repository for trait relationship persistence. */
  private final TraitRelationshipRepository traitRelationshipRepository;

  /** Repository for entity relationship persistence. */
  private final EntityRelationshipRepository entityRelationshipRepository;

  /** Repository for mapping type relationship persistence. */
  private final MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository;

  /** Repository for mapping entity relationship persistence. */
  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  /** Repository for entity lifecycle event persistence. */
  private final EntityLifeCycleEventRepository entityLifeCycleEventRepository;

  /** Platform transaction manager for database transactions. */
  private final PlatformTransactionManager transactionManager;

  /** Manager for PostgreSQL advisory locks. */
  private final AdvisoryLockManager advisoryLockManager;

  /**
   * Creates the Caffeine cache configuration with 60-minute expiration.
   *
   * @return the Caffeine cache builder
   */
  @Bean
  public Caffeine<Object, Object> caffeineConfig() {
    return Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.MINUTES);
  }

  /**
   * Creates the cache manager using Caffeine as the cache provider.
   *
   * @param caffeine the Caffeine configuration
   * @return the configured cache manager
   */
  @Bean
  public CaffeineCacheManager cacheManager(Caffeine<Object, Object> caffeine) {
    CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
    caffeineCacheManager.setCaffeine(caffeine);
    return caffeineCacheManager;
  }

  /**
   * Creates the trait service bean.
   *
   * @param traitRepository the trait repository
   * @param traitRelationshipRepository the trait relationship repository
   * @return the trait service
   */
  @Bean
  public TraitService traitService(
      TraitRepository traitRepository, TraitRelationshipRepository traitRelationshipRepository) {
    return new TraitService(traitRepository, traitRelationshipRepository);
  }

  /**
   * Creates the entity type service bean.
   *
   * @param entityTypeRepository the entity type repository
   * @param traitRepository the trait repository
   * @return the entity type service
   */
  @Bean
  public EntityTypeService entityTypeService(
      EntityTypeRepository entityTypeRepository, TraitRepository traitRepository) {
    return new EntityTypeService(entityTypeRepository, traitRepository);
  }

  /**
   * Creates the entity service bean.
   *
   * @param entityTypeRepository the entity type repository
   * @param entityRepository the entity repository
   * @param entityRelationshipRepository the entity relationship repository
   * @param traitRelationshipRepository the trait relationship repository
   * @param mappingEntityTypeRelationshipRepository the mapping type relationship repository
   * @param entityLifeCycleEventRepository the lifecycle event repository
   * @return the entity service
   */
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

  /**
   * Creates the mapping service bean.
   *
   * @param traitRelationshipRepository the trait relationship repository
   * @param entityRepository the entity repository
   * @param entityTypeRepository the entity type repository
   * @param mappingEntityTypeRelationshipRepository the mapping type relationship repository
   * @param mappingEntityRelationshipRepository the mapping entity relationship repository
   * @param entityRelationshipRepository the entity relationship repository
   * @param entityLifeCycleEventRepository the lifecycle event repository
   * @param transactionManager the transaction manager
   * @param applicationConfigurationProperties the application configuration
   * @return the mapping service
   */
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

  /**
   * Creates the aggregate service bean.
   *
   * @param entityService the entity service
   * @param entityRelationshipRepository the entity relationship repository
   * @param mappingEntityRelationshipRepository the mapping entity relationship repository
   * @return the aggregate service
   */
  @Bean
  public AggregateService aggregateService(
      EntityService entityService,
      EntityRelationshipRepository entityRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository) {
    return new AggregateService(
        entityService, entityRelationshipRepository, mappingEntityRelationshipRepository);
  }

  /**
   * Creates the mapping updater service bean.
   *
   * @param mappingService the mapping service
   * @return the mapping updater service
   */
  @Bean
  MappingUpdaterService mappingUpdaterService(MappingService mappingService) {
    var mus =
        new MappingUpdaterService(
            advisoryLockManager, entityLifeCycleEventRepository, mappingService);
    mus.setAutomaticEntitiesMapping(applicationConfigurationProperties.automaticEntitiesMapping());
    return mus;
  }

  /**
   * Creates the bulk loader service bean.
   *
   * @param traitService the trait service
   * @param entityTypeService the entity type service
   * @param mappingService the mapping service
   * @param entityService the entity service
   * @return the bulk loader service
   */
  @Bean
  public BulkLoaderService bulkLoaderService(
      TraitService traitService,
      EntityTypeService entityTypeService,
      MappingService mappingService,
      EntityService entityService) {
    return new BulkLoaderService(traitService, entityTypeService, mappingService, entityService);
  }

  /**
   * Creates the task executor for asynchronous task scheduling.
   *
   * @return the configured thread pool task executor
   */
  @Bean(name = "taskSchedulerExecutor")
  @Primary
  public AsyncTaskExecutor threadPoolTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(25);
    executor.setThreadNamePrefix("TaskSchedulerExecutor-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(60);
    executor.initialize();
    return executor;
  }

  /**
   * Creates the task manager for managing asynchronous tasks.
   *
   * @param asyncTaskExecutor the task executor
   * @return the task manager
   */
  @Bean()
  public TaskManager taskManager(AsyncTaskExecutor asyncTaskExecutor) {
    return new TaskManager(asyncTaskExecutor);
  }
}
