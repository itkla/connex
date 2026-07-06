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
 * Records and reads the per-deal stage-achievement log. The write path ({@code record}) is invoked
 * from {@code DealService} / {@code ImportService} inside each stage-changing transaction, once the
 * caller has confirmed the stage actually changed; the read path backs the deal lifecycle progress
 * view. The table is append-only, so re-entering a stage adds another row.
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

    /** Records that {@code dealId} reached {@code stageId} as of now, snapshotting the stage name. */
    public void record(int workspaceId, int dealId, int stageId) {
        Stage stage = pipelineMapper.getStageById(workspaceId, stageId);
        DealStageHistory history = new DealStageHistory();
        history.setWorkspaceId(workspaceId);
        history.setDealId(dealId);
        history.setStageId(stageId);
        history.setStageName(stage == null ? null : stage.getName());
        history.setAchievedAt(now());
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
