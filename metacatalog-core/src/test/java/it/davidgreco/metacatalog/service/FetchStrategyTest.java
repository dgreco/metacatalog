package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.QueryCounter;
import it.davidgreco.metacatalog.repository.EntityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Guards the EAGER→LAZY fetch tuning against N+1 regressions. Because open-session-in-view is
 * enabled, an accidental per-row lazy load would not surface as a {@code
 * LazyInitializationException} in the web layer nor fail the ordinary tests — it would only show up
 * as extra SQL. These tests therefore assert the JDBC statement count stays flat as the row count
 * grows, and (running without an ambient session) also prove the targeted fetch graphs initialise
 * the lazy associations so post-transaction access does not throw.
 */
@SpringBootTest
class FetchStrategyTest extends CommonServiceTestingSupport {

  private static final int ENTITY_COUNT = 6;

  public FetchStrategyTest(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  @Test
  void findingEntitiesBatchesTheEntityTypeVersionFetch() throws ServiceError {
    var entityTypeService = getApplicationContext().getBean(EntityTypeService.class);
    var entityService = getApplicationContext().getBean(EntityService.class);
    var entityRepository = getApplicationContext().getBean(EntityRepository.class);

    entityTypeService.create(
        "FetchVerType",
        List.of(),
        Optional.empty(),
        "{ \"type\": \"object\", \"properties\": {} }");
    var type = entityTypeService.read("FetchVerType");
    for (int i = 0; i < ENTITY_COUNT; i++) {
      entityService.create("FetchVerType", "{}");
    }

    // findByEntityType uses @EntityGraph(entityTypeVersion); forcing initialisation of the (now
    // lazy) version on each detached row — via a non-id property so the proxy actually loads — must
    // therefore not issue a query per row.
    long statements =
        QueryCounter.countStatements(
            getApplicationContext(),
            () ->
                entityRepository
                    .findByEntityType(type)
                    .forEach(
                        e -> {
                          var version = e.getEntityTypeVersion();
                          if (version != null) {
                            version.getVersion();
                          }
                        }));

    assertTrue(
        statements < ENTITY_COUNT,
        "entityTypeVersion fetch is N+1: "
            + statements
            + " statements for "
            + ENTITY_COUNT
            + " entities");
  }
}
