package nuts.commerce.stocklockbenchmark.api;

import jakarta.validation.Valid;
import nuts.commerce.stocklockbenchmark.api.dto.StartTestRequest;
import nuts.commerce.stocklockbenchmark.api.dto.StartTestResponse;
import nuts.commerce.stocklockbenchmark.api.dto.TestStatusResponse;
import nuts.commerce.stocklockbenchmark.service.TestRunnerService;
import nuts.commerce.stocklockbenchmark.service.runner.RunContext;
import nuts.commerce.stocklockbenchmark.service.runner.TestRun;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tests")
public class TestController {

    private final TestRunnerService runnerService;

    public TestController(TestRunnerService runnerService) {
        this.runnerService = runnerService;
    }

    @PostMapping("/start")
    public ResponseEntity<StartTestResponse> start(@RequestBody @Valid StartTestRequest req) {
        long target = (req.targetSuccessCount != null) ? req.targetSuccessCount : req.initialStock;

        RunContext ctx = new RunContext(
                req.concurrency,
                req.initialStock,
                req.backoffMillis,
                req.maxRetriesPerSuccess,
                target
        );

        String testId = runnerService.start(ctx);
        return ResponseEntity.ok(new StartTestResponse(testId));
    }

    @GetMapping("/{testId}")
    public ResponseEntity<TestStatusResponse> status(@PathVariable String testId) {
        TestRun run = runnerService.get(testId);
        if (run == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok(new TestStatusResponse(run.testId, run.status, run.phase, run.message));
    }

    @GetMapping(value = "/{testId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> report(@PathVariable String testId) {
        return runnerService.getReportJson(testId)
                .map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
