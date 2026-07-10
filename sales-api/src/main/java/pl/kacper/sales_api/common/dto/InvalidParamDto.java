package pl.kacper.sales_api.common.dto;

public record InvalidParamDto(
        String field,
        String message
) {
}
