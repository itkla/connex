package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable included or excluded record in a campaign audience snapshot. */
@Data
@NoArgsConstructor
public class CampaignAudienceMember {
    private int id;
    private int snapshotId;
    private int workspaceId;
    private String recordType;
    private int recordId;
    private String status;
    private String exclusionReason;
}
