package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Distinct company, title, owner, and lead-lifecycle facets for the contact records filter menu,
 * plus how many contacts are currently archived so the browser can offer its archived toggle.
 * Contacts outside the lead lifecycle, contacts with no captured lead source, contacts under
 * no first-response SLA, and contacts with no logged interaction at all are counted under the
 * {@code __none__} key of their facet.
 */
public record PersonFacets(
    List<String> companies,
    List<String> titles,
    boolean hasNoCompany,
    List<FacetCount> owners,
    long archivedCount,
    List<FacetCount> lifecycleStages,
    List<FacetCount> leadSources,
    List<FacetCount> firstResponseStates,
    List<FacetCount> warmthBands
) {}
