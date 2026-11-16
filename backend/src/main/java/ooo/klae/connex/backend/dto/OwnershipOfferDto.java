package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import ooo.klae.connex.backend.beans.OwnershipOffer;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Public consent card; BIGINT identifiers remain lossless decimal strings in JSON. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OwnershipOfferDto(String id, String scopeKind, int orgId, Integer workspaceId,
        int offerorUserId, int offereeUserId, String state, String createdAt, String expiresAt,
        String closedAt, Integer closedByUserId, String closureReason, String scopeName,
        String offerorDisplayName, String offereeDisplayName, String offereeEmail) {
    /** Maps the durable ledger, normalizing database timestamps to UTC instants. */
    public static OwnershipOfferDto from(OwnershipOffer offer) {
        return new OwnershipOfferDto(Long.toString(offer.getId()), offer.getScopeKind(), offer.getOrgId(),
            offer.getWorkspaceId(), offer.getOfferorUserId(), offer.getOffereeUserId(), offer.getState(),
            instant(offer.getCreatedAt()), instant(offer.getExpiresAt()), instant(offer.getClosedAt()),
            offer.getClosedByUserId(), offer.getClosureReason(), null, null, null, null);
    }

    /** Adds optional, caller-authorized display labels without changing the durable offer. */
    public OwnershipOfferDto withDisplayLabels(String scopeName, String offerorDisplayName,
            String offereeDisplayName, String offereeEmail) {
        return new OwnershipOfferDto(id, scopeKind, orgId, workspaceId, offerorUserId, offereeUserId,
            state, createdAt, expiresAt, closedAt, closedByUserId, closureReason, scopeName,
            offerorDisplayName, offereeDisplayName, offereeEmail);
    }

    private static String instant(String timestamp) {
        return timestamp == null ? null : LocalDateTime.parse(timestamp.replace(' ', 'T'))
            .toInstant(ZoneOffset.UTC).toString();
    }
}
