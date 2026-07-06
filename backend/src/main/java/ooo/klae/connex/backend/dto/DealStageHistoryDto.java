package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.DealStageHistory;

/**
 * API view of one stage-achievement entry: the stage the deal reached and when it reached it.
 */
@Data
@NoArgsConstructor
public class DealStageHistoryDto {
    private int id;
    private int stageId;
    private String achievedAt;

    public static DealStageHistoryDto from(DealStageHistory h) {
        if (h == null) return null;
        DealStageHistoryDto dto = new DealStageHistoryDto();
        dto.id = h.getId();
        dto.stageId = h.getStageId();
        dto.achievedAt = h.getAchievedAt();
        return dto;
    }
}
