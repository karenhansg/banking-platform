package com.example.banking.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** A page of results with a stable JSON shape (Spring Data's {@link Page} is not designed to be serialised). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
