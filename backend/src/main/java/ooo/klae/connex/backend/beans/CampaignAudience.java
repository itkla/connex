package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Active smart-segment definition associated with a campaign. */
@Data
@NoArgsConstructor
public class CampaignAudience {
    private int id;
    private int campaignId;
    private int workspaceId;
    private String recordType;
    private String definitionJson;
    private String mode;
    private LocalDateTime updatedAt;
}
