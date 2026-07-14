package pl.kacper.sales_api.domain.dto;

import java.util.List;

public record ElementsPageDto<E>(
        int pageNumber,
        int elementsOnPage,
        int pageSize,
        long totalElements,
        List<E> elements
) {
}
