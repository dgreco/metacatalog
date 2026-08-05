package it.davidgreco.metacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import it.davidgreco.metacatalog.common.JsonUtils;
import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import it.davidgreco.metacatalog.repository.*;
import it.davidgreco.metacatalog.service.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *
 * <p>Note: JPA repositories are intentionally NOT injected into this {@code @Configuration} class
 * constructor. Doing so creates an initialization cycle ({@code entityManagerFactoryBuilder} →
 * {@code coreConfig} → repository → {@code jpaSharedEM_entityManagerFactory} → {@code
 * entityManagerFactory}) that Spring Framework 7.x (Spring Boot 4.1+) rejects. Each {@code @Bean}
 * method instead takes the repositories it needs as parameters, and {@link #mappingUpdaterService}
 * takes its advisory-lock / lifecycle-event dependencies the same way.
 */
@Configuration
@EnableCaching
@Getter
@RequiredArgsConstructor
public class CoreConfig {

  /** Application configuration properties. */
  private final CoreConfigProperties applicationConfigurationProperties;

  /**
   * Creates the Caffeine cache configuration.
   *
   * @param properties application configuration properties
   * @return the Caffeine cache builder
   */
  @Bean
  public Caffeine<Object, Object> caffeineConfig(CoreConfigProperties properties) {
    return Caffeine.newBuilder().expireAfterWrite(properties.cacheExpireAfterWrite());
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

  @Bean
  @Primary
  public ObjectMapper jsonMapper() {
    var mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    return mapper;
  }

  @Bean
  public ObjectMapper yamlMapper() {
    var mapper = new ObjectMapper(new YAMLFactory());
    mapper.registerModule(new Jdk8Module());
    return mapper;
  }

  @Bean
  public JsonSchemaFactory jsonSchemaFactory() {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  }

  @Bean
  public com.jayway.jsonpath.Configuration jsonPathConfiguration() {
    return com.jayway.jsonpath.Configuration.builder()
        .jsonProvider(new JacksonJsonNodeJsonProvider())
        .build();
  }

  @Bean
  public JsonUtils jsonUtils(
      ObjectMapper jsonMapper,
      @Qualifier("yamlMapper") ObjectMapper yamlMapper,
      JsonSchemaFactory jsonSchemaFactory) {
    return new JsonUtils(jsonMapper, yamlMapper, jsonSchemaFactory);
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
      TraitRepository traitRepository,
      TraitRelationshipRepository traitRelationshipRepository,
      TraitVersionRepository traitVersionRepository,
      JsonUtils jsonUtils) {
    return new TraitServiceImpl(
        traitRepository, traitRelationshipRepository, traitVersionRepository, jsonUtils);
  }

  /**
   * Creates the entity type service bean.
   *
   * @param entityTypeRepository the entity type repository
   * @param traitRepository the trait repository
   * @param entityTypeVersionRepository the entity type version repository
   * @param entityRepository the entity repository
   * @return the entity type service
   */
  @Bean
  public EntityTypeService entityTypeService(
      EntityTypeRepository entityTypeRepository,
      TraitRepository traitRepository,
      EntityTypeVersionRepository entityTypeVersionRepository,
      EntityRepository entityRepository,
      JsonUtils jsonUtils) {
    return new EntityTypeServiceImpl(
        entityTypeRepository,
        traitRepository,
        entityTypeVersionRepository,
        entityRepository,
        jsonUtils);
  }

  /**
   * Creates the entity service bean.
   *
   * @param entityTypeRepository the entity type repository
   * @param entityTypeVersionRepository the entity type version repository
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
      EntityTypeVersionRepository entityTypeVersionRepository,
      EntityRepository entityRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      TraitRelationshipRepository traitRelationshipRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      EntityLifeCycleEventRepository entityLifeCycleEventRepository,
      JsonUtils jsonUtils) {
    return new EntityServiceImpl(
        entityTypeRepository,
        entityTypeVersionRepository,
        entityRepository,
        entityRelationshipRepository,
        traitRelationshipRepository,
        mappingEntityTypeRelationshipRepository,
        entityLifeCycleEventRepository,
        jsonUtils);
  }

  /**
   * Creates the mapping service bean.
   *
   * @param entityTypeRepository the entity type repository
   * @param mappingEntityTypeRelationshipRepository the mapping type relationship repository
   * @param mappingEntityRelationshipRepository the mapping entity relationship repository
   * @return the mapping service
   */
  @Bean
  public MappingService mappingService(
      EntityTypeRepository entityTypeRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
      JsonUtils jsonUtils) {
    return new MappingServiceImpl(
        entityTypeRepository,
        mappingEntityTypeRelationshipRepository,
        mappingEntityRelationshipRepository,
        jsonUtils);
  }

  /**
   * Creates the entity path resolver bean.
   *
   * @param entityRepository the entity repository
   * @param mappingEntityRelationshipRepository the mapping entity relationship repository
   * @param entityRelationshipRepository the entity relationship repository
   * @return the entity path resolver
   */
  @Bean
  public EntityPathResolver entityPathResolver(
      EntityRepository entityRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
      EntityRelationshipRepository entityRelationshipRepository,
      com.jayway.jsonpath.Configuration jsonPathConfiguration) {
    return new EntityPathResolver(
        entityRepository,
        mappingEntityRelationshipRepository,
        entityRelationshipRepository,
        jsonPathConfiguration);
  }

  /**
   * Creates the mapped entity service bean.
   *
   * @param entityRepository the entity repository
   * @param entityTypeVersionRepository the entity type version repository
   * @param mappingEntityTypeRelationshipRepository the mapping type relationship repository
   * @param mappingEntityRelationshipRepository the mapping entity relationship repository
   * @param entityLifeCycleEventRepository the lifecycle event repository
   * @param transactionManager the transaction manager
   * @param applicationConfigurationProperties the application configuration
   * @param entityPathResolver the entity path resolver
   * @return the mapped entity service
   */
  @Bean
  public MappedEntityService mappedEntityService(
      EntityRepository entityRepository,
      EntityTypeVersionRepository entityTypeVersionRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
      EntityLifeCycleEventRepository entityLifeCycleEventRepository,
      PlatformTransactionManager transactionManager,
      CoreConfigProperties applicationConfigurationProperties,
      EntityPathResolver entityPathResolver,
      JsonUtils jsonUtils) {
    return new MappedEntityService(
        entityRepository,
        entityTypeVersionRepository,
        mappingEntityTypeRelationshipRepository,
        mappingEntityRelationshipRepository,
        entityLifeCycleEventRepository,
        transactionManager,
        applicationConfigurationProperties,
        entityPathResolver,
        jsonUtils);
  }

  /**
   * Creates the aggregate service bean.
   *
   * @param entityService the entity service
   * @param entityRelationshipRepository the entity relationship repository
   * @param mappingEntityRelationshipRepository the mapping entity relationship repository
   * @param mappedEntityService the mapped entity service
   * @return the aggregate service
   */
  @Bean
  public AggregateService aggregateService(
      EntityService entityService,
      EntityRelationshipRepository entityRelationshipRepository,
      MappingEntityRelationshipRepository mappingEntityRelationshipRepository,
      MappedEntityService mappedEntityService) {
    return new AggregateService(
        entityService,
        entityRelationshipRepository,
        mappingEntityRelationshipRepository,
        mappedEntityService);
  }

  /**
   * Creates the aggregate schema service bean, which derives the combined JSON schema of an
   * aggregate from the type-level composition graph.
   *
   * @param entityTypeRepository the entity type repository
   * @param traitRelationshipRepository the trait relationship repository
   * @param mappingEntityTypeRelationshipRepository the mapping type relationship repository
   * @return the aggregate schema service
   */
  @Bean
  public AggregateSchemaService aggregateSchemaService(
      EntityTypeRepository entityTypeRepository,
      TraitRelationshipRepository traitRelationshipRepository,
      MappingEntityTypeRelationshipRepository mappingEntityTypeRelationshipRepository,
      JsonUtils jsonUtils) {
    return new AggregateSchemaService(
        entityTypeRepository,
        traitRelationshipRepository,
        mappingEntityTypeRelationshipRepository,
        jsonUtils);
  }

  /**
   * Creates the mapping updater service bean.
   *
   * @param advisoryLockManager the advisory lock manager
   * @param entityLifeCycleEventRepository the lifecycle event repository
   * @param mappedEntityService the mapped entity service
   * @param transactionManager the transaction manager (used for the {@code markFailed} {@code
   *     REQUIRES_NEW} transaction)
   * @return the mapping updater service
   */
  @Bean
  MappingUpdaterService mappingUpdaterService(
      AdvisoryLockManager advisoryLockManager,
      EntityLifeCycleEventRepository entityLifeCycleEventRepository,
      MappedEntityService mappedEntityService,
      PlatformTransactionManager transactionManager) {
    var mus =
        new MappingUpdaterService(
            advisoryLockManager,
            entityLifeCycleEventRepository,
            mappedEntityService,
            transactionManager);
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
      EntityService entityService,
      @Qualifier("yamlMapper") ObjectMapper yamlMapper) {
    return new BulkLoaderService(
        traitService, entityTypeService, mappingService, entityService, yamlMapper);
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

  @Bean
  public TaskFactoryRegistry taskFactoryRegistry() {
    return new TaskFactoryRegistry();
  }

  @Bean
  public TaskManager taskManager(
      AsyncTaskExecutor asyncTaskExecutor, CoreConfigProperties coreConfigProperties) {
    return new TaskManager(asyncTaskExecutor, coreConfigProperties);
  }
}
