package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateReport;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDefinitionRequest;
import ooo.klae.connex.backend.dto.ReportTemplateDto;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.ReportService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/** Creates an approved saved report from a built-in template without invoking a model. */
@Component
@RequiredArgsConstructor
public class AiAssistantCreateReportWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_report";
    /** Quota attainment remains excluded because that UI template additionally requires GOAL_READ. */
    static final Set<String> TEMPLATE_KEYS = Set.of(
            "sales-performance", "pipeline-health", "forecasting", "relationship-coverage",
            "relationship-health", "network-warm-intros", "employment-moves",
            "commercial-documents", "lead-lifecycle", "activity-team");
    private final ReportService reportService;

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
        return CreateReport.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("workspace");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("report.record");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.REPORT_CREATE, Permission.REPORT_READ);
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
        if (!"workspace".equals(targetKind)) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        AiAssistantCreationTemplatePin.validateShape(targetKind, request, Set.of("template", "name"));
        String template = AiAssistantCreationTemplatePin.requiredText(request, "template", 32);
        AiAssistantCreationTemplatePin.requiredText(request, "name", 128);
        if (!TEMPLATE_KEYS.contains(template)) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
    }

    @Override
    public List<PrincipalRequest> principals(AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Outcome apply(Execution execution) {
        if (!(execution.row().request() instanceof CreateReport request)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        ReportTemplateDto template = reportService.templates().stream()
                .filter(candidate -> TEMPLATE_KEYS.contains(candidate.key()) && candidate.key().equals(request.template()))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("Report template is unavailable"));
        ReportConfig source = template.config();
        ReportConfig config = new ReportConfig(source.widgets().stream()
                .map(widget -> new ReportWidgetConfig(widget.id(), null, widget.dataSource(),
                        widget.measure(), widget.groupBy(), widget.chartType()))
                .toList(), source.filters(), source.range(), source.bucket(), source.layout());
        ReportDefinitionDto created = reportService.create(new ReportDefinitionRequest(
                request.name(), null, template.cadence(), template.key(), config));
        if (created == null) {
            throw new IllegalStateException("Created report could not be read back");
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "report");
        outcome.put("template", template.key());
        if (created.name() != null) {
            outcome.put("name", created.name());
        }
        return new Outcome(outcome, new Inverse("report", created.id(), "", false, Map.of()),
                ReadBack.structural("reportId", created.id()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> requiredRequestText() {
        return Set.of("template", "name");
    }

    @Override
    public Set<String> modelAuthoredDiffFields() {
        return Set.of("report");
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of();
    }

    @Override
    public List<Diff> diffs(Review review) {
        return List.of(
                new Diff("report", null, false, review.requestText("name"), DiffState.CHANGED),
                new Diff("template", null, false, review.requestText("template"), DiffState.CHANGED));
    }

    @Override
    public String requestSummary(Review review) {
        return "Create a report";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Report created";
    }

    @Override
    public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        AiAssistantWriteTool.copyText(storedOutcome, result, "recordType");
        AiAssistantWriteTool.copyText(storedOutcome, result, "template");
        return result;
    }

    @Override
    public List<String> memberOutcomeFields() {
        return List.of("name", "template");
    }
}
