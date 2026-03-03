package nuts.commerce.stocklockbenchmark.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class StartTestRequest {

    @NotNull
    @Min(1) @Max(500)
    public Integer concurrency;

    @NotNull
    @Min(1) @Max(10_000_000)
    public Long initialStock;

    @NotNull
    @Min(0) @Max(10_000)
    public Integer backoffMillis;

    @NotNull
    @Min(0) @Max(1_000_000)
    public Integer maxRetriesPerSuccess;

    @Min(1) @Max(10_000_000)
    public Long targetSuccessCount;
}