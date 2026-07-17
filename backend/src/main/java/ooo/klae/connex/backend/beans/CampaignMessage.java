package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A workspace-scoped campaign message definition. */
@Data
@NoArgsConstructor
public class CampaignMessage {
    private int id;
    private int workspaceId;
    private int campaignId;
    private String channel;
    private String name;
    private String status;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
