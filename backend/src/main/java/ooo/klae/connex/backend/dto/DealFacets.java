package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Workspace-wide deal filter facets, independent of the current page and filters.
 */
public record DealFacets(
    List<FacetCount> status,
    List<FacetCount> stages,
    List<FacetCount> pipelines,
    List<FacetCount> companies,
    List<FacetCount> currencies
) {}
