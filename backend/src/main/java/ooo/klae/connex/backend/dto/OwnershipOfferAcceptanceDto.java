package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.services.PrivilegedMfaStanding;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Acceptance response; membership is the existing workspace or organization member contract. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OwnershipOfferAcceptanceDto(OwnershipOfferDto offer, Object membership,
        PrivilegedMfaStanding standing, PrivilegedMfaStanding enforcedStanding) {}
