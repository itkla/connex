package ooo.klae.connex.backend.dto;

import java.util.List;

// adapter object for pagination metadata
// TODO: move this into the bean or service layer instead of it's own dto
public record PageResponse<T>(
    List<T> items, 
    long total
) {}
