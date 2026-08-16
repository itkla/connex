package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Distinct company, title, owner, and lead-lifecycle facets for the contact records filter menu,
 * plus how many contacts are currently archived so the browser can offer its archived toggle.
 * Contacts outside the lead lifecycle are counted under the {@code __none__} lifecycle key.
 */
public record PersonFacets(
    List<String> companies,
    List<String> titles,
    boolean hasNoCompany,
    List<FacetCount> owners,
    long archivedCount,
    List<FacetCount> lifecycleStages
) {}
