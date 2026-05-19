package nuts.commerce.stocklockbenchmark.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.Optional;

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
            List<PhaseReport> phaseReports = new ArrayList<>();
            try {
                run.phase = "optimistic";
                log.info("phase.start testId={} phase={}", testId, run.phase);
                stockResetService.reset(ctx.initialStock());
                PhaseReport optimisticReport = executePhase(testId, ctx, optimistic);
                phaseReports.add(optimisticReport);
                log.info("phase.end testId={} phase={}", testId, run.phase);

                run.phase = "reset_for_pessimistic";
                log.info("phase.start testId={} phase={}", testId, run.phase);
                stockResetService.reset(ctx.initialStock());
                log.info("phase.end testId={} phase={}", testId, run.phase);

                run.phase = "pessimistic";
                log.info("phase.start testId={} phase={}", testId, run.phase);
                PhaseReport pessimisticReport = executePhase(testId, ctx, pessimistic);
                phaseReports.add(pessimisticReport);
                log.info("phase.end testId={} phase={}", testId, run.phase);

                run.phase = "done";
                run.status = TestRunStatus.COMPLETED;
                log.info("run.done testId={}", testId);

                // 리포트 저장
                TestReport report = new TestReport();
                report.testId = testId;
                report.status = run.status.name();
                report.message = run.message;
                report.startedAt = run.startedAt;
                report.finishedAt = Instant.now();
                report.concurrency = ctx.concurrency();
                report.initialStock = ctx.initialStock();
                report.backoffMillis = ctx.backoffMillis();
                report.maxRetriesPerSuccess = ctx.maxRetriesPerSuccess();
                report.targetSuccessCount = ctx.targetSuccessCount();
                report.phases = phaseReports;

                saveReportJson(report);
                saveReportCsv(report);

            } catch (Exception e) {
                run.status = TestRunStatus.FAILED;
                run.message = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("run.failed testId={} msg={}", testId, run.message, e);
                // 실패 리포트 저장 시도
                TestReport report = new TestReport();
                report.testId = testId;
                report.status = run.status.name();
                report.message = run.message;
                report.startedAt = run.startedAt;
                report.finishedAt = Instant.now();
                report.concurrency = ctx.concurrency();
                report.initialStock = ctx.initialStock();
                report.backoffMillis = ctx.backoffMillis();
                report.maxRetriesPerSuccess = ctx.maxRetriesPerSuccess();
                report.targetSuccessCount = ctx.targetSuccessCount();
                report.phases = phaseReports;
                try {
                    saveReportJson(report);
                    saveReportCsv(report);
                } catch (Exception ex) {
                    log.error("failed to save report for testId={}", testId, ex);
                }
            }
        });

        return testId;
    }

    public TestRun get(String testId) {
        return runs.get(testId);
    }

    public Optional<String> getReportJson(String testId) {
        Path file = reportDir().resolve(testId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("report.read.failed testId={} path={}", testId, file.toAbsolutePath(), e);
            return Optional.empty();
        }
    }

    private PhaseReport executePhase(String testId, RunContext ctx, StockUpdateService service) throws InterruptedException {
        String lockType = service.lockType();

        ExecutorService pool = Executors.newFixedThreadPool(ctx.concurrency());
        CountDownLatch latch = new CountDownLatch(ctx.concurrency());

        AtomicLong successCount = new AtomicLong(0);

        // 집계용 스레드 안전한 누적기
        LongAdder totalRequests = new LongAdder();
        LongAdder totalRetries = new LongAdder();
        LongAdder attemptsSum = new LongAdder();
        LongAdder requestLatencySum = new LongAdder();
        LongAdder successLatencySum = new LongAdder();
        LongAdder successAttemptsSum = new LongAdder();
        AtomicInteger maxAttempts = new AtomicInteger(0);
        ConcurrentHashMap<String, LongAdder> failuresByReason = new ConcurrentHashMap<>();
        LongAdder totalBackoffMs = new LongAdder();

        // 샘플러 (reservoir sampling) - 최대 샘플 수 지정
        final int SAMPLE_CAP = 10000;
        ReservoirSampler requestSampler = new ReservoirSampler(SAMPLE_CAP);
        ReservoirSampler successSampler = new ReservoirSampler(SAMPLE_CAP);

        log.info("phase.exec.start testId={} lockType={} concurrency={} targetSuccess={} maxRetries={} backoffMs={}",
                testId, lockType, ctx.concurrency(), ctx.targetSuccessCount(), ctx.maxRetriesPerSuccess(), ctx.backoffMillis());

        long t0 = System.nanoTime();

        for (int i = 0; i < ctx.concurrency(); i++) {
            pool.submit(() -> {
                try {
                    while (true) {
                        long done = successCount.get();
                        if (done >= ctx.targetSuccessCount()) break;

                        AttemptResult r = attemptReserveWithRetry(testId, ctx, service, lockType, totalBackoffMs, requestSampler, successSampler);

                        totalRequests.increment();
                        attemptsSum.add(r.attempts);
                        requestLatencySum.add(r.elapsedMs);
                        if (r.attempts > 1) totalRetries.add(r.attempts - 1);
                        maxAttempts.getAndUpdate(prev -> Math.max(prev, r.attempts));

                        if (r.success) {
                            successCount.incrementAndGet();
                            successLatencySum.add(r.elapsedMs);
                            successAttemptsSum.add(r.attempts);
                        } else {
                            String reason = (r.failReason == null) ? "unknown" : r.failReason;
                            failuresByReason.computeIfAbsent(reason, k -> new LongAdder()).increment();
                            // 재고 없음이면 루프 종료
                            if ("out_of_stock".equals(reason)) break;
                        }

                        // 목표 도달 시 빠져나오기
                        if (successCount.get() >= ctx.targetSuccessCount()) break;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdownNow();
        boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
        if (!terminated) {
            log.warn("pool did not terminate within timeout for testId={} phase={}", testId, lockType);
        }

        long durationMs = (System.nanoTime() - t0) / 1_000_000;

        log.info("phase.exec.end testId={} lockType={} successCount={} durationMs={}",
                testId, lockType, successCount.get(), durationMs);

        PhaseReport pr = new PhaseReport();
        pr.phase = service.lockType();
        pr.successCount = successCount.get();
        pr.durationMs = durationMs;

        pr.totalRequests = totalRequests.longValue();
        pr.totalRetries = totalRetries.longValue();
        pr.avgAttemptsPerSuccess = (successAttemptsSum.longValue() == 0) ? 0.0
                : (double) successAttemptsSum.longValue() / (double) successCount.get();
        pr.avgRequestLatencyMs = (pr.totalRequests == 0) ? 0.0 : (double) requestLatencySum.longValue() / (double) pr.totalRequests;
        pr.avgSuccessLatencyMs = (successCount.get() == 0) ? 0.0 : (double) successLatencySum.longValue() / (double) successCount.get();
        pr.maxAttempts = maxAttempts.get();
        pr.totalBackoffMs = totalBackoffMs.longValue();

        // 실패 맵
        Map<String, Long> failures = new HashMap<>();
        failuresByReason.forEach((k, v) -> failures.put(k, v.longValue()));
        pr.failuresByReason = failures;

        // 추가 지표: throughput, successRate, retriesPerRequest
        pr.throughputPerSec = (durationMs == 0) ? 0.0 : ((double) pr.successCount) / ((double) durationMs / 1000.0);
        pr.successRate = (pr.totalRequests == 0) ? 0.0 : ((double) pr.successCount) / ((double) pr.totalRequests);
        pr.retriesPerRequest = (pr.totalRequests == 0) ? 0.0 : ((double) pr.totalRetries) / ((double) pr.totalRequests);

        // Percentiles from samplers
        double[] percentiles = new double[]{50.0, 90.0, 95.0, 99.0};
        pr.requestLatencyPercentiles = requestSampler.getPercentiles(percentiles);
        pr.successLatencyPercentiles = successSampler.getPercentiles(percentiles);

        return pr;
    }

    private AttemptResult attemptReserveWithRetry(String testId, RunContext ctx, StockUpdateService service, String lockType,
                                                  LongAdder totalBackoffMs, ReservoirSampler requestSampler, ReservoirSampler successSampler) {
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

                    // 샘플 추가
                    requestSampler.add(elapsedMs);

                    if (ok) {
                        metrics.success(lockType).increment();
                        metrics.attemptsPerSuccess(lockType).record(attempt);

                        // 성공 샘플
                        successSampler.add(elapsedMs);

                        log.debug("reserve.success testId={} reqId={} lockType={} attempt={} elapsedMs={}",
                                testId, reqId, lockType, attempt, elapsedMs);

                        return new AttemptResult(true, attempt, elapsedMs, null);
                    } else {
                        // tryReserveOne이 false로 “실패를 표현”하는 구현도 있을 수 있어서 로그로 남김
                        log.debug("reserve.returnFalse testId={} reqId={} lockType={} attempt={} elapsedMs={}",
                                testId, reqId, lockType, attempt, elapsedMs);
                        // 실패하지만 명확한 이유 없음
                        return new AttemptResult(false, attempt, elapsedMs, "return_false");
                    }

                } catch (ObjectOptimisticLockingFailureException e) {
                    metrics.retries(lockType).increment();

                    log.debug("reserve.retry testId={} reqId={} lockType={} attempt={} reason=optimistic_lock",
                            testId, reqId, lockType, attempt);

                    if (attempt > ctx.maxRetriesPerSuccess()) {
                        metrics.fail(lockType, "optimistic_lock_exhausted").increment();
                        log.info("reserve.fail testId={} reqId={} lockType={} attempt={} reason=optimistic_lock_exhausted",
                                testId, reqId, lockType, attempt);
                        return new AttemptResult(false, attempt, (System.nanoTime() - t0) / 1_000_000, "optimistic_lock_exhausted");
                    }

                    backoff(testId, lockType, ctx.backoffMillis(), reqId, attempt, totalBackoffMs);

                } catch (CannotAcquireLockException | DeadlockLoserDataAccessException e) {
                    metrics.retries(lockType).increment();

                    log.debug("reserve.retry testId={} reqId={} lockType={} attempt={} reason=lock_timeout_or_deadlock",
                            testId, reqId, lockType, attempt);

                    if (attempt > ctx.maxRetriesPerSuccess()) {
                        metrics.fail(lockType, "lock_timeout_or_deadlock").increment();
                        log.info("reserve.fail testId={} reqId={} lockType={} attempt={} reason=lock_timeout_or_deadlock",
                                testId, reqId, lockType, attempt);
                        return new AttemptResult(false, attempt, (System.nanoTime() - t0) / 1_000_000, "lock_timeout_or_deadlock");
                    }

                    backoff(testId, lockType, ctx.backoffMillis(), reqId, attempt, totalBackoffMs);

                } catch (IllegalStateException outOfStock) {
                    metrics.fail(lockType, "out_of_stock").increment();

                    log.info("reserve.fail testId={} reqId={} lockType={} attempt={} reason=out_of_stock",
                            testId, reqId, lockType, attempt);

                    return new AttemptResult(false, attempt, (System.nanoTime() - t0) / 1_000_000, "out_of_stock");

                } catch (Exception e) {
                    metrics.fail(lockType, "unexpected").increment();

                    log.error("reserve.fail testId={} reqId={} lockType={} attempt={} reason=unexpected",
                            testId, reqId, lockType, attempt, e);

                    throw e;
                }
            }
        });
    }

    private void backoff(String testId, String lockType, int backoffMillis, String reqId, int attempt, LongAdder totalBackoffMs) {
        if (backoffMillis <= 0) return;

        Duration d = Duration.ofMillis(backoffMillis);
        metrics.recordSleep(lockType, d);

        totalBackoffMs.add(backoffMillis);

        log.debug("reserve.backoff testId={} reqId={} lockType={} attempt={} backoffMs={}",
                testId, reqId, lockType, attempt, backoffMillis);

        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void saveReportJson(TestReport report) throws IOException {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path dir = reportDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Path file = dir.resolve(report.testId + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        log.info("report.saved testId={} path={}", report.testId, file.toAbsolutePath());
    }

    private void saveReportCsv(TestReport report) throws IOException {
        Path dir = reportDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        Path file = dir.resolve(report.testId + ".csv");

        StringBuilder sb = new StringBuilder();
        // overall header
        sb.append("testId,status,startedAt,finishedAt,concurrency,initialStock,backoffMillis,maxRetriesPerSuccess,targetSuccessCount\n");
        sb.append(String.format("%s,%s,%s,%s,%d,%d,%d,%d,%d\n",
                quote(report.testId), report.status, report.startedAt, report.finishedAt,
                report.concurrency, report.initialStock, report.backoffMillis, report.maxRetriesPerSuccess, report.targetSuccessCount));
        sb.append("\n");

        // per-phase header
        sb.append("phase,successCount,durationMs,totalRequests,totalRetries,avgAttemptsPerSuccess,avgRequestLatencyMs,avgSuccessLatencyMs,maxAttempts,throughputPerSec,successRate,retriesPerRequest,totalBackoffMs,p50_req_ms,p90_req_ms,p95_req_ms,p99_req_ms,p50_succ_ms,p90_succ_ms,p95_succ_ms,p99_succ_ms\n");

        if (report.phases != null) {
            for (PhaseReport p : report.phases) {
                String p50r = safeGetPercent(p.requestLatencyPercentiles, "p50");
                String p90r = safeGetPercent(p.requestLatencyPercentiles, "p90");
                String p95r = safeGetPercent(p.requestLatencyPercentiles, "p95");
                String p99r = safeGetPercent(p.requestLatencyPercentiles, "p99");
                String p50s = safeGetPercent(p.successLatencyPercentiles, "p50");
                String p90s = safeGetPercent(p.successLatencyPercentiles, "p90");
                String p95s = safeGetPercent(p.successLatencyPercentiles, "p95");
                String p99s = safeGetPercent(p.successLatencyPercentiles, "p99");

                sb.append(String.format("%s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%d,%s,%s,%s,%s,%s,%s,%s,%s\n",
                        quote(p.phase), p.successCount, p.durationMs, p.totalRequests, p.totalRetries,
                        p.avgAttemptsPerSuccess, p.avgRequestLatencyMs, p.avgSuccessLatencyMs, p.maxAttempts,
                        p.throughputPerSec, p.successRate, p.retriesPerRequest, p.totalBackoffMs,
                        p50r, p90r, p95r, p99r, p50s, p90s, p95s, p99s));
            }
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("report.csv.saved testId={} path={}", report.testId, file.toAbsolutePath());
    }

    private static String safeGetPercent(Map<String, Double> m, String key) {
        if (m == null) return "";
        Double v = m.get(key);
        return (v == null) ? "" : String.format("%.3f", v);
    }

    private static String quote(String s) {
        if (s == null) return "";
        return '"' + s.replace("\"", "\\\"") + '"';
    }

    private static Path reportDir() {
        return Paths.get("build", "reports", "stockbench");
    }

    // 리포트 모델들
    private static class PhaseReport {
        public String phase;
        public long successCount;
        public long durationMs;

        // 추가 메트릭
        public long totalRequests;
        public long totalRetries;
        public Map<String, Long> failuresByReason;
        public double avgAttemptsPerSuccess;
        public double avgRequestLatencyMs;
        public double avgSuccessLatencyMs;
        public int maxAttempts;

        // 추가 성능 지표
        public double throughputPerSec;
        public double successRate;
        public double retriesPerRequest;
        public long totalBackoffMs;

        // latency percentiles
        public Map<String, Double> requestLatencyPercentiles;
        public Map<String, Double> successLatencyPercentiles;
    }

    private static class TestReport {
        public String testId;
        public String status;
        public String message;
        public Instant startedAt;
        public Instant finishedAt;

        public int concurrency;
        public long initialStock;
        public int backoffMillis;
        public int maxRetriesPerSuccess;
        public long targetSuccessCount;

        public List<PhaseReport> phases;
    }

    // 내부 결과 타입
    private static class AttemptResult {
        public final boolean success;
        public final int attempts;
        public final long elapsedMs;
        public final String failReason; // null이면 성공

        public AttemptResult(boolean success, int attempts, long elapsedMs, String failReason) {
            this.success = success;
            this.attempts = attempts;
            this.elapsedMs = elapsedMs;
            this.failReason = failReason;
        }
    }

    // Reservoir sampler for bounded memory percentile estimation
    private static class ReservoirSampler {
        private final long[] samples;
        private final int capacity;
        private final AtomicLong count = new AtomicLong(0);
        private final Random rnd = new Random();

        public ReservoirSampler(int capacity) {
            this.capacity = capacity;
            this.samples = new long[capacity];
        }

        public synchronized void add(long value) {
            long c = count.incrementAndGet();
            if (c <= capacity) {
                samples[(int) c - 1] = value;
            } else {
                long r = Math.abs(rnd.nextLong()) % c;
                if (r < capacity) {
                    samples[(int) r] = value;
                }
            }
        }

        public Map<String, Double> getPercentiles(double[] percentiles) {
            long c = Math.min(count.get(), capacity);
            if (c == 0) return Map.of();
            long[] copy = new long[(int) c];
            System.arraycopy(samples, 0, copy, 0, (int) c);
            Arrays.sort(copy);
            Map<String, Double> res = new HashMap<>();
            for (double p : percentiles) {
                double rank = p / 100.0 * (c - 1);
                int idx = (int) Math.round(rank);
                res.put(String.format("p%.0f", p), (double) copy[idx]);
            }
            return res;
        }
    }
}
