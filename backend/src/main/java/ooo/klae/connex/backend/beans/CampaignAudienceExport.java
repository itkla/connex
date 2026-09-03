package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A record of one push of a frozen audience snapshot's eligible included members to a third-party
 * marketing connector. Bound to an immutable snapshot; the frozen member ids retain the exact set
 * admitted by preparation and the pushed member ids retain the exact identities placed in the
 * provider request. Null member-id fields identify historical rows whose exact identities were not
 * recorded. Running rows are leased so an interrupted request becomes reconciliation-required rather
 * than being retried silently.
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
    private String frozenMemberIdsJson;
    private String pushedMemberIdsJson;
    private String status;
    private int attempt;
    private LocalDateTime leaseUntil;
    private int totalMembers;
    private Integer pushedCount;
    private Integer failedCount;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
