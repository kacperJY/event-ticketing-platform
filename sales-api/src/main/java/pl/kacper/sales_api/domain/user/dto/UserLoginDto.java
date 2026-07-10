package pl.kacper.sales_api.domain.user.dto;

public record UserLoginDto(
        String email,
        String password
) {

}
