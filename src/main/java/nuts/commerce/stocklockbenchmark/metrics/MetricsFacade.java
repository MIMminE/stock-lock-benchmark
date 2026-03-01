package nuts.commerce.stocklockbenchmark.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MetricsFacade {

    private final MeterRegistry registry;

    public MetricsFacade(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer timer(String lockType) {
        return Timer.builder("stockbench_request_latency_seconds")
                .description("Latency per reserve attempt")
                .publishPercentileHistogram(true)
                .tag("lockType", lockType)
                .register(registry);
    }

    public Counter requests(String lockType) {
        return Counter.builder("stockbench_requests_total")
                .description("Total reserve attempts")
                .tag("lockType", lockType)
                .register(registry);
    }

    public Counter success(String lockType) {
        return Counter.builder("stockbench_success_total")
                .description("Successful reserves")
                .tag("lockType", lockType)
                .register(registry);
    }

    public Counter fail(String lockType, String reason) {
        return Counter.builder("stockbench_fail_total")
                .description("Failed reserves")
                .tag("lockType", lockType)
                .tag("reason", reason)
                .register(registry);
    }

    public Counter retries(String lockType) {
        return Counter.builder("stockbench_retries_total")
                .description("Retries due to conflicts/timeouts")
                .tag("lockType", lockType)
                .register(registry);
    }

    public DistributionSummary attemptsPerSuccess(String lockType) {
        return DistributionSummary.builder("stockbench_attempts_per_success")
                .description("How many attempts were needed per successful reserve")
                .tag("lockType", lockType)
                .register(registry);
    }

    public void recordSleep(String lockType, Duration d) {
        Timer.builder("stockbench_backoff_sleep_seconds")
                .description("Backoff sleep time")
                .tag("lockType", lockType)
                .register(registry)
                .record(d);
    }
}