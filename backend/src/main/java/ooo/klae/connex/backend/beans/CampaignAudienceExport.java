package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A record of one push of a frozen audience snapshot's eligible included members to a third-party
 * marketing connector. Bound to an immutable snapshot; the counts are the eligible total after a fresh
 * eligibility re-check and the connector-reported pushed and failed tallies.
 */
@Data
@NoArgsConstructor
public class CampaignAudienceExport {
    private int id;
    private int workspaceId;
    private int campaignId;
    private int snapshotId;
    private String connector;
    private String externalListId;
    private String status;
    private int totalMembers;
    private int pushedCount;
    private int failedCount;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
