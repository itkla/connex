package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One record of a deal reaching a stage, stamped at the moment of the transition. The table is
 * append-only, so a deal that re-enters a stage has one row per entry. {@code stageName} is a
 * snapshot taken when the row was created, so the history reads correctly even after a stage is
 * renamed or deleted ({@code stageId} becomes null on deletion). Mapped via
 * {@code DealStageHistoryMapper}.
 */
@Data
@NoArgsConstructor
public class DealStageHistory {
    private int id;
    private int workspaceId;
    private int dealId;
    private int stageId;
    private String stageName;
    private String achievedAt;
}
