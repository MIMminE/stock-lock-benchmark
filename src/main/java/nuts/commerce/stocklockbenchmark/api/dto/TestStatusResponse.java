package nuts.commerce.stocklockbenchmark.api.dto;

import nuts.commerce.stocklockbenchmark.service.runner.TestRunStatus;

public class TestStatusResponse {
    public String testId;
    public TestRunStatus status;
    public String currentPhase;
    public String message;

    public TestStatusResponse(String testId, TestRunStatus status, String currentPhase, String message) {
        this.testId = testId;
        this.status = status;
        this.currentPhase = currentPhase;
        this.message = message;
    }
}