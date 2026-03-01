package nuts.commerce.stocklockbenchmark.service.runner;

import java.time.Instant;

public class TestRun {
    public final String testId;
    public volatile TestRunStatus status = TestRunStatus.RUNNING;
    public volatile String phase = "starting";
    public volatile String message = "";
    public final Instant startedAt = Instant.now();

    public TestRun(String testId) {
        this.testId = testId;
    }
}