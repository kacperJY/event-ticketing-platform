package pl.kacper.sales_api.domain.user;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.kacper.sales_api.domain.user.dto.UserRegisterDto;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class UserRegisterDtoValidationTest {

    @Test
    @DisplayName("Should return not empty set of constraints when UserRegisterDto contains invalid data that not fit to validation constraints")
    void shouldReturnNotEmptyConstraintsWhenRegisteringInvalidUser() {

        // Wrong email
        // Password doesn't contain at least one big letter
        // lastname is empty
        UserRegisterDto userRegisterDto = new UserRegisterDto(
                "kacper.pl",
                "abc123",
                "Kacper",
                ""
        );
        try (
                ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
        ) {
            Validator validator = validatorFactory.getValidator();
            Set<ConstraintViolation<UserRegisterDto>> constraintViolations = validator.validate(userRegisterDto);

            assertThat(constraintViolations).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Should return empty set of constraints when UserRegisterDto fit to validation constraints")
    void shouldReturnEmptyConstraintsWhenRegisteringCorrectUser() {

        // Correct data with validation constraints
        UserRegisterDto userRegisterDto = new UserRegisterDto(
                "kacper@gmaik.com",
                "ABCdef123",
                "Kacper",
                "Lastname"
        );
        try (
                ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
        ) {
            Validator validator = validatorFactory.getValidator();
            Set<ConstraintViolation<UserRegisterDto>> constraintViolations = validator.validate(userRegisterDto);

            assertThat(constraintViolations).isEmpty();
        }
    }
}
