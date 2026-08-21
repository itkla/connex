package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Member-scoped deal filter facets, independent of the current page and non-member filters.
 */
public record DealFacets(
    List<FacetCount> status,
    List<FacetCount> stages,
    List<FacetCount> pipelines,
    List<FacetCount> companies,
    List<FacetCount> people,
    List<FacetCount> owners,
    List<FacetCount> currencies,
    List<FacetCount> risk
) {}
