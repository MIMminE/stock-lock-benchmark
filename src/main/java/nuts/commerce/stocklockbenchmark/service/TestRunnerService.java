package nuts.commerce.stocklockbenchmark.service;

import io.micrometer.core.instrument.Timer;
import nuts.commerce.stocklockbenchmark.metrics.MetricsFacade;
import nuts.commerce.stocklockbenchmark.service.runner.RunContext;
import nuts.commerce.stocklockbenchmark.service.runner.TestRun;
import nuts.commerce.stocklockbenchmark.service.runner.TestRunStatus;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TestRunnerService {

    private final StockResetService stockResetService;
    private final OptimisticStockUpdateService optimistic;
    private final PessimisticStockUpdateService pessimistic;
    private final MetricsFacade metrics;

    private final Map<String, TestRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService orchestrator = Executors.newSingleThreadExecutor();

    public TestRunnerService(
            StockResetService stockResetService,
            OptimisticStockUpdateService optimistic,
            PessimisticStockUpdateService pessimistic,
            MetricsFacade metrics
    ) {
        this.stockResetService = stockResetService;
        this.optimistic = optimistic;
        this.pessimistic = pessimistic;
        this.metrics = metrics;
    }

    public String start(RunContext ctx) {
        String testId = UUID.randomUUID().toString();
        TestRun run = new TestRun(testId);
        runs.put(testId, run);

        orchestrator.submit(() -> {
            try {
                run.phase = "optimistic";
                stockResetService.reset(ctx.initialStock());
                executePhase(ctx, optimistic);

                run.phase = "reset_for_pessimistic";
                stockResetService.reset(ctx.initialStock());

                run.phase = "pessimistic";
                executePhase(ctx, pessimistic);

                run.phase = "done";
                run.status = TestRunStatus.COMPLETED;
            } catch (Exception e) {
                run.status = TestRunStatus.FAILED;
                run.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        });

        return testId;
    }

    public TestRun get(String testId) {
        return runs.get(testId);
    }

    private void executePhase(RunContext ctx, StockUpdateService service) throws InterruptedException {
        String lockType = service.lockType();

        ExecutorService pool = Executors.newFixedThreadPool(ctx.concurrency());
        CountDownLatch latch = new CountDownLatch(ctx.concurrency());

        AtomicLong successCount = new AtomicLong(0);

        for (int i = 0; i < ctx.concurrency(); i++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        long done = successCount.get();
                        if (done >= ctx.targetSuccessCount()) break;

                        boolean success = attemptReserveWithRetry(ctx, service, lockType);
                        if (!success) {
                            // 재고가 0이면 종료(또는 targetSuccessCount에 도달했으면 종료)
                            break;
                        }
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    private boolean attemptReserveWithRetry(RunContext ctx, StockUpdateService service, String lockType) {
        Timer timer = metrics.timer(lockType);
        metrics.requests(lockType).increment();

        AtomicInteger attempts = new AtomicInteger(0);

        return timer.record(() -> {
            while (true) {
                int attempt = attempts.incrementAndGet();

                try {
                    boolean ok = service.tryReserveOne(1L);
                    if (ok) {
                        metrics.success(lockType).increment();
                        metrics.attemptsPerSuccess(lockType).record(attempt);
                    }
                    return ok;
                } catch (ObjectOptimisticLockingFailureException e) {
                    metrics.retries(lockType).increment();
                    if (attempt > ctx.maxRetriesPerSuccess()) {
                        metrics.fail(lockType, "optimistic_lock_exhausted").increment();
                        return false;
                    }
                    backoff(lockType, ctx.backoffMillis());
                } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                    metrics.retries(lockType).increment();
                    if (attempt > ctx.maxRetriesPerSuccess()) {
                        metrics.fail(lockType, "lock_timeout_or_deadlock").increment();
                        return false;
                    }
                    backoff(lockType, ctx.backoffMillis());
                } catch (IllegalStateException outOfStock) {
                    metrics.fail(lockType, "out_of_stock").increment();
                    return false;
                } catch (Exception e) {
                    metrics.fail(lockType, "unexpected").increment();
                    throw e;
                }
            }
        });
    }

    private void backoff(String lockType, int backoffMillis) {
        if (backoffMillis <= 0) return;
        Duration d = Duration.ofMillis(backoffMillis);
        metrics.recordSleep(lockType, d);
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}