package nuts.commerce.stocklockbenchmark.service;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
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

        log.info("run.start testId={} ctx={}", testId, ctx);

        orchestrator.submit(() -> {
            try {
                run.phase = "optimistic";
                log.info("phase.start testId={} phase={}", testId, run.phase);
                stockResetService.reset(ctx.initialStock());
                executePhase(testId, ctx, optimistic);
                log.info("phase.end testId={} phase={}", testId, run.phase);

                run.phase = "reset_for_pessimistic";
                log.info("phase.start testId={} phase={}", testId, run.phase);
                stockResetService.reset(ctx.initialStock());
                log.info("phase.end testId={} phase={}", testId, run.phase);

                run.phase = "pessimistic";
                log.info("phase.start testId={} phase={}", testId, run.phase);
                executePhase(testId, ctx, pessimistic);
                log.info("phase.end testId={} phase={}", testId, run.phase);

                run.phase = "done";
                run.status = TestRunStatus.COMPLETED;
                log.info("run.done testId={}", testId);

            } catch (Exception e) {
                run.status = TestRunStatus.FAILED;
                run.message = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("run.failed testId={} msg={}", testId, run.message, e);
            }
        });

        return testId;
    }

    public TestRun get(String testId) {
        return runs.get(testId);
    }

    private void executePhase(String testId, RunContext ctx, StockUpdateService service) throws InterruptedException {
        String lockType = service.lockType();

        ExecutorService pool = Executors.newFixedThreadPool(ctx.concurrency());
        CountDownLatch latch = new CountDownLatch(ctx.concurrency());

        AtomicLong successCount = new AtomicLong(0);

        log.info("phase.exec.start testId={} lockType={} concurrency={} targetSuccess={} maxRetries={} backoffMs={}",
                testId, lockType, ctx.concurrency(), ctx.targetSuccessCount(), ctx.maxRetriesPerSuccess(), ctx.backoffMillis());

        for (int i = 0; i < ctx.concurrency(); i++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        long done = successCount.get();
                        if (done >= ctx.targetSuccessCount()) break;

                        boolean success = attemptReserveWithRetry(testId, ctx, service, lockType);
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

        log.info("phase.exec.end testId={} lockType={} successCount={}",
                testId, lockType, successCount.get());
    }

    private boolean attemptReserveWithRetry(String testId, RunContext ctx, StockUpdateService service, String lockType) {
        // “요청 1건”을 로그로 묶기 위한 식별자
        String reqId = UUID.randomUUID().toString().substring(0, 8);

        Timer timer = metrics.timer(lockType);
        metrics.requests(lockType).increment();

        AtomicInteger attempts = new AtomicInteger(0);

        return timer.record(() -> {
            long t0 = System.nanoTime();

            log.debug("reserve.start testId={} reqId={} lockType={}", testId, reqId, lockType);

            while (true) {
                int attempt = attempts.incrementAndGet();

                try {
                    boolean ok = service.tryReserveOne(1L);

                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                    if (ok) {
                        metrics.success(lockType).increment();
                        metrics.attemptsPerSuccess(lockType).record(attempt);

                        log.debug("reserve.success testId={} reqId={} lockType={} attempt={} elapsedMs={}",
                                testId, reqId, lockType, attempt, elapsedMs);
                    } else {
                        // tryReserveOne이 false로 “실패를 표현”하는 구현도 있을 수 있어서 로그로 남김
                        log.debug("reserve.returnFalse testId={} reqId={} lockType={} attempt={} elapsedMs={}",
                                testId, reqId, lockType, attempt, elapsedMs);
                    }

                    return ok;

                } catch (ObjectOptimisticLockingFailureException e) {
                    metrics.retries(lockType).increment();

                    log.debug("reserve.retry testId={} reqId={} lockType={} attempt={} reason=optimistic_lock",
                            testId, reqId, lockType, attempt);

                    if (attempt > ctx.maxRetriesPerSuccess()) {
                        metrics.fail(lockType, "optimistic_lock_exhausted").increment();
                        log.info("reserve.fail testId={} reqId={} lockType={} attempt={} reason=optimistic_lock_exhausted",
                                testId, reqId, lockType, attempt);
                        return false;
                    }

                    backoff(testId, lockType, ctx.backoffMillis(), reqId, attempt);

                } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                    metrics.retries(lockType).increment();

                    log.debug("reserve.retry testId={} reqId={} lockType={} attempt={} reason=lock_timeout_or_deadlock",
                            testId, reqId, lockType, attempt);

                    if (attempt > ctx.maxRetriesPerSuccess()) {
                        metrics.fail(lockType, "lock_timeout_or_deadlock").increment();
                        log.info("reserve.fail testId={} reqId={} lockType={} attempt={} reason=lock_timeout_or_deadlock",
                                testId, reqId, lockType, attempt);
                        return false;
                    }

                    backoff(testId, lockType, ctx.backoffMillis(), reqId, attempt);

                } catch (IllegalStateException outOfStock) {
                    metrics.fail(lockType, "out_of_stock").increment();

                    log.info("reserve.fail testId={} reqId={} lockType={} attempt={} reason=out_of_stock",
                            testId, reqId, lockType, attempt);

                    return false;

                } catch (Exception e) {
                    metrics.fail(lockType, "unexpected").increment();

                    log.error("reserve.fail testId={} reqId={} lockType={} attempt={} reason=unexpected",
                            testId, reqId, lockType, attempt, e);

                    throw e;
                }
            }
        });
    }

    private void backoff(String testId, String lockType, int backoffMillis, String reqId, int attempt) {
        if (backoffMillis <= 0) return;

        Duration d = Duration.ofMillis(backoffMillis);
        metrics.recordSleep(lockType, d);

        log.debug("reserve.backoff testId={} reqId={} lockType={} attempt={} backoffMs={}",
                testId, reqId, lockType, attempt, backoffMillis);

        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
