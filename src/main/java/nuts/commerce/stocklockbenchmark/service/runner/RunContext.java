package nuts.commerce.stocklockbenchmark.service.runner;

public record RunContext(int concurrency, long initialStock, int backoffMillis, int maxRetriesPerSuccess,
                         long targetSuccessCount) {
}