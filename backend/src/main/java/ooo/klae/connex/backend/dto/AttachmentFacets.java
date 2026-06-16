package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Filter facets for the Files library, computed across the whole attachment table
 * (not just the current page) so the filter chips stay stable as the user pages.
 * {@code sources} counts by owning entity type, {@code kinds} by derived file kind.
 */
public record AttachmentFacets(
    List<FacetCount> sources,
    List<FacetCount> kinds,
    List<FacetCount> tags,
    long orphaned,
    long total,
    long totalSize
) {}