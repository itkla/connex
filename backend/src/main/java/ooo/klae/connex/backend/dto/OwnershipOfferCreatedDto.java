package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** HTTP 202 response from an owner grant; currentMember is null for a new organization member. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OwnershipOfferCreatedDto<T>(String outcome, OwnershipOfferDto offer, T currentMember) {
    /** Maps an offered role change to its HTTP contract. */
    public static <T> OwnershipOfferCreatedDto<T> from(MemberRoleChange<T> result) {
        return new OwnershipOfferCreatedDto<>("ownership_offer", result.offer(), result.currentMember());
    }
}
