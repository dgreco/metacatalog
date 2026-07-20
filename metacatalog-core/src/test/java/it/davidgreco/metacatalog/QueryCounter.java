package it.davidgreco.metacatalog;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.context.ApplicationContext;

/**
 * Test helper that counts the JDBC statements Hibernate issues while running an action, via
 * Hibernate {@code Statistics}. Used to prove that a fetch stays batched (no N+1) after an
 * association is switched from EAGER to LAZY — under open-session-in-view the ordinary tests would
 * otherwise stay green even with a hidden per-row lazy load.
 */
public final class QueryCounter {

  private QueryCounter() {}

  /**
   * Runs {@code action} and returns the number of JDBC prepared statements Hibernate issued during
   * it.
   *
   * @param ctx the Spring application context
   * @param action the action to measure
   * @return the number of prepared statements executed
   */
  public static long countStatements(ApplicationContext ctx, Runnable action) {
    var statistics =
        ctx.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    action.run();
    return statistics.getPrepareStatementCount();
  }
}
