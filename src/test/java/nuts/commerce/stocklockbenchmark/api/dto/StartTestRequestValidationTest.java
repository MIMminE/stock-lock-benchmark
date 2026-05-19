package nuts.commerce.stocklockbenchmark.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartTestRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsValidBenchmarkParameters() {
        StartTestRequest request = validRequest();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsConcurrencyOutsideBoundary() {
        StartTestRequest request = validRequest();
        request.concurrency = 501;

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("concurrency"));
    }

    @Test
    void targetSuccessCountCanBeOmitted() {
        StartTestRequest request = validRequest();
        request.targetSuccessCount = null;

        assertThat(validator.validate(request)).isEmpty();
    }

    private static StartTestRequest validRequest() {
        StartTestRequest request = new StartTestRequest();
        request.concurrency = 50;
        request.initialStock = 20000L;
        request.backoffMillis = 5;
        request.maxRetriesPerSuccess = 500;
        request.targetSuccessCount = 20000L;
        return request;
    }
}
