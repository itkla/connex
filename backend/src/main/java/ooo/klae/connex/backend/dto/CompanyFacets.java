package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Distinct industry facets for the company records filter menu.
 */
public record CompanyFacets(
    List<String> industries,
    boolean hasNoIndustry,
    List<FacetCount> owners
) {}
