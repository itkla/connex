package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Distinct industry facets for the company records filter menu, plus how many companies are
 * currently archived so the browser can offer its archived toggle.
 */
public record CompanyFacets(
    List<String> industries,
    boolean hasNoIndustry,
    List<FacetCount> owners,
    long archivedCount
) {}
