package ooo.klae.connex.backend.ai.assistant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateDeal;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.RecordCreationPresetService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Proposes a deal at a visible company, pinning its stage and quick-create template. */
@Component
@RequiredArgsConstructor
public class AiAssistantCreateDealWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_deal";
    private final GuidedRecordCreationService creationService;
    private final RecordCreationPresetService presetService;
    private final PipelineService pipelineService;
    private final ObjectMapper objectMapper;

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
        return CreateDeal.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("company");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("deal.record");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.DEAL_CREATE);
    }

    @Override
    public Lock lock(String targetKind) {
        return new Lock(false, TargetLock.NONE);
    }

    @Override
    public Freshness freshness() {
        return Freshness.NONE;
    }

    @Override
    public void validateFor(String targetKind, JsonNode request) {
        AiAssistantCreationTemplatePin.validateShape(targetKind, request,
                Set.of("handle", "name", "stage", "value", "currency", "expected_close_date"));
        AiAssistantCreationTemplatePin.requiredText(request, "name", 255);
        AiAssistantCreationTemplatePin.requiredText(request, "stage", 128);
        String value = AiAssistantCreationTemplatePin.requiredText(request, "value", 16);
        String currency = AiAssistantCreationTemplatePin.requiredText(request, "currency", 3);
        if (!value.matches("[0-9]{1,13}(\\.[0-9]{1,2})?") || !currency.matches("[A-Z]{3}")) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        String date = AiAssistantCreationTemplatePin.optionalText(request, "expected_close_date", 10);
        if (date != null) {
            try {
                if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
                }
                LocalDate.parse(date);
            } catch (DateTimeParseException exception) {
                throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
            }
        }
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Resolution resolve(Target target, AiAssistantWriteToolRequest request) {
        Stage stage = requestedStage(request(request).stage(), pipelineService.getAllStages());
        if (stage == null) {
            throw new ResourceNotFoundException("Deal stage is unavailable or ambiguous");
        }
        return new Resolution("stage", stage.getId(), stage.getName());
    }

    @Override
    public Map<String, Object> pin(Target target, AiAssistantWriteToolRequest request) {
        CreateDeal deal = request(request);
        Set<String> supplied = deal.expectedCloseDate() == null
                ? Set.of("name", "company", "stage", "pipeline", "value", "currency")
                : Set.of("name", "company", "stage", "pipeline", "value", "currency", "expectedCloseDate");
        return AiAssistantCreationTemplatePin.prepare(
                presetService.deals(RecordCreationEntryPoint.quick_create, target.id()),
                supplied, Set.of("owner"));
    }

    @Override
    public DuplicateProbe duplicateProbe(Target target, AiAssistantWriteToolRequest request) {
        return new DuplicateProbe("deal", request(request).name(), target.id(), null);
    }

    @Override
    public Outcome apply(Execution execution) {
        CreateDeal request = request(execution.row().request());
        Resolution resolution = execution.resolution();
        if (resolution == null) {
            throw new ConflictException("Prepared deal stage is unavailable");
        }
        Stage stage = pipelineService.getStageById(resolution.id());
        if (stage == null || stage.getId() != resolution.id() || stage.getPipeline() == null
                || stage.getName() == null || !stage.getName().equalsIgnoreCase(request.stage().trim())) {
            throw new ConflictException("Assistant proposal target changed");
        }
        int companyId = execution.row().target().id();
        Deal created = creationService.createDeal(new GuidedDealCreateRequestDto(
                new GuidedDealRecordDto(request.name(), new BigDecimal(request.value()), request.currency(),
                        stage.getPipeline().getId(), stage.getId(), companyId,
                        request.expectedCloseDate() == null ? null : LocalDate.parse(request.expectedCloseDate()),
                        null),
                AiAssistantCreationTemplatePin.use(execution.pinned(), companyId), Map.of(), List.of()));
        if (created == null) {
            throw new IllegalStateException("Created deal could not be read back");
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "deal");
        if (created.getName() != null) {
            outcome.put("name", created.getName());
        }
        if (Integer.valueOf(stage.getId()).equals(created.getStageId())) {
            outcome.put("stage", stage.getName());
        }
        if (created.getValue() != null) {
            outcome.put("value", created.getValue().toPlainString());
        }
        if (created.getCurrency() != null) {
            outcome.put("currency", created.getCurrency());
        }
        return new Outcome(
                outcome,
                new Inverse("deal", created.getId(), "", false, Map.of()),
                new ReadBack("stageId", resolution.id(), created.getStageId()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> requiredRequestText() {
        return Set.of("name", "stage");
    }

    @Override
    public Set<String> modelAuthoredDiffFields() {
        return Set.of("name");
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of(ReviewInput.STAGES);
    }

    @Override
    public List<Diff> diffs(Review review) {
        List<Diff> rows = new ArrayList<>();
        rows.add(new Diff("name", null, false, review.requestText("name"), DiffState.CHANGED));
        Stage stage = requestedStage(review.requestText("stage"), review.stages());
        boolean resolved = stage != null && review.pinnedResolutionId() != null
                && stage.getId() == review.pinnedResolutionId()
                && !SpecialCareTextScreen.screen(stage.getName()).excluded();
        rows.add(new Diff("stage", null, false, resolved ? stage.getName() : null,
                resolved ? DiffState.CHANGED : DiffState.UNRESOLVED));
        for (String field : List.of("value", "currency", "expectedCloseDate")) {
            String value = review.requestText("expectedCloseDate".equals(field) ? "expected_close_date" : field);
            if (value != null) {
                rows.add(new Diff(field, null, false, value, DiffState.CHANGED));
            }
        }
        rows.addAll(AiAssistantCreationTemplatePin.diffs(review.pinned(), objectMapper));
        return List.copyOf(rows);
    }

    @Override
    public String requestSummary(Review review) {
        return "Create a deal";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Deal created";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        result.put("created", true);
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("name", "stage", "value", "currency");
    }

    private static Stage requestedStage(String name, List<Stage> stages) {
        if (name == null) {
            return null;
        }
        List<Stage> matches = stages.stream()
                .filter(stage -> stage.getPipeline() != null && stage.getName() != null
                        && stage.getName().equalsIgnoreCase(name.trim()))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static CreateDeal request(AiAssistantWriteToolRequest request) {
        if (!(request instanceof CreateDeal deal)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        return deal;
    }
}
