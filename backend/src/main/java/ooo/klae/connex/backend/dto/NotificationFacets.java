package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Stable filter facets across every notification visible to the authenticated recipient.
 */
public record NotificationFacets(
    List<FacetCount> categories,
    List<FacetCount> severities,
    List<FacetCount> workspaces
) {}
