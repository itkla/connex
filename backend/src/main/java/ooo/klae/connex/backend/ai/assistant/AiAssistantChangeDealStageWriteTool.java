package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.ChangeDealStage;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * Moves one deal to a named stage of its own pipeline, only after the member approves it.
 *
 * <p>The stage is resolved once, before the lock, against the deal's own pipeline; the framework
 * locks the board rows that move toward that stage and refuses the approval if the deal was written
 * after the proposal. The write is read back by stage id, while the stored {@code stage} label stays
 * the name resolved before the lock. A stage change records no inverse.
 */
@Component
@RequiredArgsConstructor
public class AiAssistantChangeDealStageWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "change_deal_stage";
    private static final String STAGE_FIELD = "stage";

    private final DealService dealService;
    private final PipelineService pipelineService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolTier tier() {
        return ToolTier.CONFIRM;
    }

    @Override
    public Class<? extends AiAssistantWriteToolRequest> requestType() {
        return ChangeDealStage.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("deal");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("deal.stageId");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.DEAL_UPDATE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.DEAL_STAGE_CHANGE);
    }

    @Override
    public List<PrincipalRequest> principals(AiAssistantWriteToolRequest request) {
        return List.of();
    }

    @Override
    public Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        if (!(request instanceof ChangeDealStage stageRequest)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        Deal deal = dealService.getDealById(target.id());
        List<Stage> matches = pipelineService.getAllStages().stream()
                .filter(stage -> stage.getPipeline() != null
                        && Objects.equals(deal.getPipelineId(), stage.getPipeline().getId()))
                .filter(stage -> stage.getName() != null
                        && stage.getName().equalsIgnoreCase(stageRequest.stage().trim()))
                .toList();
        if (matches.size() != 1) {
            throw new ResourceNotFoundException("Deal stage is unavailable or ambiguous");
        }
        Stage stage = matches.getFirst();
        return new Resolution(STAGE_FIELD, stage.getId(), stage.getName());
    }

    @Override
    public Outcome apply(Execution execution) {
        Resolution resolution = execution.resolution();
        DealService.LockedStageChange stageChange = execution.lockedTarget().stageChange();
        if (stageChange == null || resolution == null) {
            throw new ConflictException("Prepared deal stage mutation is unavailable");
        }
        Deal changed = dealService.changeStage(stageChange);
        if (changed == null) {
            throw new IllegalStateException("Assistant deal stage change could not be read back");
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "deal");
        outcome.put(STAGE_FIELD, resolution.label());
        if (changed.getClosedAt() != null) {
            outcome.put("closedAt", changed.getClosedAt());
        }
        return new Outcome(
                outcome, null, new ReadBack("stageId", resolution.id(), changed.getStageId()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    /**
     * The deal's current and proposed stage, compared on stage ids rather than names.
     *
     * <p>A proposed stage that no longer resolves in the deal's own pipeline is reported as
     * unresolved rather than echoed back, because the value the model chose is never shown as a
     * stage the workspace has.
     */
    @Override
    public Diff diff(Review review) {
        RecordSnapshot target = review.target();
        String current = currentStageName(review.stages(), target.stageId());
        boolean currentUnresolved = target.stageId() != null && current == null;
        Stage proposed = requestedStage(
                review.requestText(STAGE_FIELD), target.pipelineId(), review.stages());
        if (proposed == null) {
            return new Diff(STAGE_FIELD, current, currentUnresolved, null, DiffState.UNRESOLVED);
        }
        return new Diff(
                STAGE_FIELD,
                current,
                currentUnresolved,
                proposed.getName(),
                target.stageId() != null && target.stageId() == proposed.getId()
                        ? DiffState.UNCHANGED
                        : DiffState.CHANGED);
    }

    @Override
    public String requestSummary(Review review) {
        if (review.detailsReadable()) {
            Stage matched = requestedStage(
                    review.requestText(STAGE_FIELD), review.target().pipelineId(), review.stages());
            if (matched != null && matched.getName() != null) {
                return "Change deal stage to: " + matched.getName();
            }
        }
        return "Change the deal stage";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Deal stage changed";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, STAGE_FIELD);
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of(STAGE_FIELD);
    }

    private static Stage requestedStage(
            String requestedStage, Integer pipelineId, List<Stage> stages) {
        if (pipelineId == null || requestedStage == null) {
            return null;
        }
        List<Stage> matches = stages.stream()
                .filter(stage -> stage.getPipeline() != null
                        && stage.getPipeline().getId() == pipelineId)
                .filter(stage -> stage.getName() != null
                        && stage.getName().equalsIgnoreCase(requestedStage.trim()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String currentStageName(List<Stage> stages, Integer stageId) {
        if (stageId == null) {
            return null;
        }
        for (Stage stage : stages) {
            if (stage.getId() == stageId && stage.getName() != null && !stage.getName().isBlank()) {
                return stage.getName();
            }
        }
        return null;
    }
}
