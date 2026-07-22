package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.mappers.DealStageHistoryMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;

/**
 * Records and reads the per-deal stage-achievement log. The write path
 * ({@code recordTransition}) is invoked from {@code DealService} / {@code ImportService} inside each
 * stage-changing or deal-reopening transaction; the read path backs the deal lifecycle progress
 * view. The table is append-only, so re-entering or reopening in a stage adds another row.
 */
@Service
@RequiredArgsConstructor
public class DealStageHistoryService {
    private final DealStageHistoryMapper historyMapper;
    private final PipelineMapper pipelineMapper;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Records a stage transition and whether the deal's outcome was still pending when it began.
     * A transition that closes or reopens the deal remains eligible; a move while it stays closed
     * does not.
     */
    public void recordTransition(
            int workspaceId,
            int dealId,
            int stageId,
            Boolean previousOutcome,
            Boolean currentOutcome) {
        boolean conversionEligible = previousOutcome == null || currentOutcome == null;
        recordAt(workspaceId, dealId, stageId, conversionEligible);
    }

    /**
     * Records the first stage a deal occupies at creation or new-row import. Unlike a transition,
     * there is no prior state to reason from, so eligibility is fail-closed on the current outcome:
     * a deal that starts open counts as reached-while-open (conversion-eligible), while one imported
     * or created already won or lost never occupied the stage while open and is ineligible — so it
     * cannot bias forecast conversion rates.
     */
    public void recordInitial(int workspaceId, int dealId, int stageId, Boolean currentOutcome) {
        recordAt(workspaceId, dealId, stageId, currentOutcome == null);
    }

    private void recordAt(int workspaceId, int dealId, int stageId, boolean conversionEligible) {
        Stage stage = pipelineMapper.getVisibleStageById(workspaceId, stageId);
        DealStageHistory history = new DealStageHistory();
        history.setWorkspaceId(workspaceId);
        history.setDealId(dealId);
        history.setStageId(stageId);
        history.setStageName(stage == null ? null : stage.getName());
        history.setAchievedAt(now());
        history.setConversionEligible(conversionEligible);
        historyMapper.insert(history);
    }

    /** Stage-achievement history for a deal in the active workspace, earliest first. */
    public List<DealStageHistory> getHistory(int dealId) {
        return historyMapper.getByDealId(workspaceService.getCurrentWorkspaceId(), dealId);
    }

    private String now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).format(MYSQL_DATETIME);
    }
}
