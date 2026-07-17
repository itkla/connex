package ooo.klae.connex.backend.beans;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Workspace-scoped marketing campaign record. */
@Data
@NoArgsConstructor
public class Campaign {
    private int id;
    private int workspaceId;
    private String name;
    private String objective;
    private String type;
    private String status;
    private Integer ownerUserId;
    private BigDecimal budgetAmount;
    private String budgetCurrency;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer parentCampaignId;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
