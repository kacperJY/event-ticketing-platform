package pl.kacper.sales_api.domain.order;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;

class OrderRequestDtoValidationTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 1000})
    void shouldAcceptSingleEventAndQuantityWithinLimit(int quantity) {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Assertions.assertThat(validatorFactory.getValidator().validate(new OrderRequestDto(1L, quantity)))
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1001})
    void shouldRejectQuantityOutsideLimit(int quantity) {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Assertions.assertThat(validatorFactory.getValidator().validate(new OrderRequestDto(1L, quantity)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("quantity");
        }
    }

    @Test
    void shouldRejectMissingEventId() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Assertions.assertThat(validatorFactory.getValidator().validate(new OrderRequestDto(null, 1)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("eventId");
        }
    }
}
