package ooo.klae.connex.backend.dto;

import java.util.List;

// adapter object for the distinct filter facets (companies, titles) used by the records filter menu, computed across the whole table rather than one page.
// TODO: move this into the bean or service layer instead of it's own dto
public record PersonFacets(
    List<String> companies, 
    List<String> titles, 
    boolean hasNoCompany
) {}
