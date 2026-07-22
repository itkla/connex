package ooo.klae.connex.backend.dto;

import java.util.List;

/** Distinct company, title, and owner facets for the contact records filter menu. */
public record PersonFacets(
    List<String> companies,
    List<String> titles,
    boolean hasNoCompany,
    List<FacetCount> owners
) {}
