package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable frozen campaign audience definition and exclusion totals. */
@Data
@NoArgsConstructor
public class CampaignAudienceSnapshot {
    private int id;
    private int campaignId;
    private int workspaceId;
    private int version;
    private String recordType;
    private String definitionJson;
    private String channel;
    private String purpose;
    private int estimatedIncluded;
    private int excludedTotal;
    private int excludedConsent;
    private int excludedSuppressed;
    private int excludedRestricted;
    private int excludedNoAddress;
    private Integer createdById;
    private LocalDateTime createdAt;
}
