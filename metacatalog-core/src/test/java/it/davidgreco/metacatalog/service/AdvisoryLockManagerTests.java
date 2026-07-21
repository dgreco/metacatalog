package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.davidgreco.metacatalog.entity.AdvisoryLockManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link AdvisoryLockManager} verifying that PostgreSQL transaction-scoped
 * advisory locks correctly serialize concurrent access across separate transactions (i.e. across
 * application instances).
 *
 * <p>The advisory lock is the mechanism that ensures only one application instance runs the
 * scheduled {@link MappingUpdaterService#updateMappedEntities()} at a time: {@code
 * pg_try_advisory_xact_lock} is a database-level, non-blocking try-lock held for the duration of
 * the transaction. A second instance that calls {@code acquireLock} while the first holds the lock
 * gets {@code false} and skips.
 *
 * <p>These tests simulate two instances by running two separate transactions (on separate threads
 * with separate connections from the pool) against the same PostgreSQL instance.
 */
@SpringBootTest
class AdvisoryLockManagerTests extends CommonServiceTestingSupport {

  public AdvisoryLockManagerTests(ApplicationContext applicationContext) {
    super(applicationContext);
  }

  /**
   * A lock id that does not collide with {@code MappingUpdaterService.UPDATE_MAPPING_LOCK_ID}
   * ({@code 1}), so the scheduled task (which may fire during the {@code @SpringBootTest} context)
   * cannot interfere with this test.
   */
  private static final int TEST_LOCK_ID = 4242;

  private AdvisoryLockManager lockManager() {
    return getApplicationContext().getBean(AdvisoryLockManager.class);
  }

  private TransactionTemplate newTx() {
    var tm = getApplicationContext().getBean(PlatformTransactionManager.class);
    var tx = new TransactionTemplate(tm);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx;
  }

  /**
   * While transaction T1 holds the advisory lock, a concurrent transaction T2 must fail to acquire
   * it (returns {@code false}). After T1 commits and releases the lock, a third transaction T3
   * succeeds.
   *
   * <p>This is the guarantee that serializes the scheduled mapping update across instances: the
   * second instance's {@code pg_try_advisory_xact_lock} returns {@code false} immediately
   * (non-blocking) and the method skips.
   */
  @Test
  void concurrentTransactionCannotAcquireLockHeldByAnother() throws Exception {
    var t1Ready = new CountDownLatch(1);
    var t1CanFinish = new CountDownLatch(1);
    var t1Acquired = new AtomicBoolean(false);

    var t1 = newTx();
    var t2 = newTx();
    var t3 = newTx();

    var holderThread =
        new Thread(
            () ->
                t1.executeWithoutResult(
                    status -> {
                      boolean acquired = lockManager().acquireLock(TEST_LOCK_ID);
                      t1Acquired.set(acquired);
                      t1Ready.countDown();
                      try {
                        t1CanFinish.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));

    try {
      holderThread.start();
      assertTrue(t1Ready.await(10, TimeUnit.SECONDS), "T1 should acquire the lock promptly");
      assertTrue(t1Acquired.get(), "T1 must have acquired the lock");

      // T2 runs on the test thread while T1 holds the lock on a separate connection.
      Boolean acquiredByT2 = t2.execute(status -> lockManager().acquireLock(TEST_LOCK_ID));
      assertFalse(
          acquiredByT2,
          "a concurrent transaction must NOT acquire the lock while another holds it");

      // Release T1: its transaction commits and the transaction-scoped advisory lock is freed.
      t1CanFinish.countDown();
      holderThread.join(10_000);
      assertFalse(holderThread.isAlive(), "T1 thread must finish after release");

      // After T1 commits the lock is available again.
      Boolean acquiredByT3 = t3.execute(status -> lockManager().acquireLock(TEST_LOCK_ID));
      assertTrue(
          acquiredByT3, "the lock must be available again after the holding transaction commits");
    } finally {
      t1CanFinish.countDown();
      holderThread.join(10_000);
    }
  }

  /**
   * A transaction that did not acquire a lock can still acquire a different lock id — i.e. locks do
   * not globally block each other.
   */
  @Test
  void independentLockIdsDoNotInterfere() {
    var tx = newTx();
    Boolean acquired =
        tx.execute(
            status -> {
              boolean a = lockManager().acquireLock(TEST_LOCK_ID);
              boolean b = lockManager().acquireLock(TEST_LOCK_ID + 1);
              return a && b;
            });
    assertTrue(acquired, "two different lock ids must be independently acquirable in one tx");
  }
}
