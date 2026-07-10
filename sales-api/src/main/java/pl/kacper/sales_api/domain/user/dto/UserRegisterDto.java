package pl.kacper.sales_api.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserRegisterDto(
        @Email(message = "Invalid email address") String email,
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*[0-9]).{6,}$",
                message = "Password must consist of at least 6 characters, must contains at least one big letter and one digit."
        )
        String password,
        @NotBlank(message = "Firstname cannot be empty") String firstname,
        @NotBlank(message = "Lastname cannot be empty") String lastname
) {
}
