package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateCompany;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.RecordCreationPresetService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Proposes a company in the active workspace through pinned, duplicate-reviewed quick creation. */
@Component
@RequiredArgsConstructor
public class AiAssistantCreateCompanyWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_company";
    private final GuidedRecordCreationService creationService;
    private final RecordCreationPresetService presetService;
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
        return CreateCompany.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("workspace");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("company.record");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.COMPANY_CREATE);
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
    public Set<String> identifierValueFields() {
        return Set.of("name", "website");
    }

    @Override
    public void validateFor(String targetKind, JsonNode request) {
        if (!"workspace".equals(targetKind)) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
        AiAssistantCreationTemplatePin.validateShape(
                targetKind, request, Set.of("name", "website", "industry"));
        AiAssistantCreationTemplatePin.requiredText(request, "name", 255);
        AiAssistantCreationTemplatePin.optionalText(request, "industry", 128);
        String website = AiAssistantCreationTemplatePin.optionalText(request, "website", 255);
        if (website != null && !website.matches("^(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?:/\\S*)?$")) {
            throw AiAssistantLoopException.refusedArguments("invalid_tool_arguments");
        }
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Map<String, Object> pin(Target target, AiAssistantWriteToolRequest request) {
        return pin(presetService.companies(RecordCreationEntryPoint.quick_create), request(request));
    }

    @Override
    public Preparation prepare(Target target, AiAssistantWriteToolRequest request) {
        CreateCompany company = request(request);
        RecordCreationPresetCatalogDto catalog = presetService.companies(RecordCreationEntryPoint.quick_create);
        List<String> websites = company.website() == null
                ? AiAssistantCreationTemplatePin.identityDefault(catalog, "website") : List.of(company.website());
        return new Preparation(pin(catalog, company), new DuplicateProbe(
                "company", company.name(), null, null, List.of(),
                AiAssistantCreationTemplatePin.identityDefault(catalog, "phone"), websites));
    }

    private static Map<String, Object> pin(RecordCreationPresetCatalogDto catalog, CreateCompany company) {
        Set<String> supplied = new java.util.HashSet<>(Set.of("name"));
        if (company.website() != null) {
            supplied.add("website");
        }
        if (company.industry() != null) {
            supplied.add("industry");
        }
        return AiAssistantCreationTemplatePin.prepare(catalog, supplied, Set.of("owner"));
    }

    @Override
    public DuplicateProbe duplicateProbe(Target target, AiAssistantWriteToolRequest request) {
        CreateCompany company = request(request);
        return new DuplicateProbe("company", company.name(), null, null, List.of(), List.of(),
                company.website() == null ? List.of() : List.of(company.website()));
    }

    @Override
    public Outcome apply(Execution execution) {
        CreateCompany request = request(execution.row().request());
        Company created = creationService.createCompany(new GuidedCompanyCreateRequestDto(
                new GuidedCompanyRecordDto(request.name(), request.website(), request.industry(), null, null, null),
                AiAssistantCreationTemplatePin.use(execution.pinned(), null), Map.of(), List.of()));
        if (created == null) {
            throw new IllegalStateException("Created company could not be read back");
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "company");
        if (created.getName() != null) {
            outcome.put("name", created.getName());
        }
        return new Outcome(
                outcome,
                new Inverse("company", created.getId(), "", false, Map.of()),
                ReadBack.structural("companyId", created.getId()));
    }

    @Override
    public boolean inverseAvailable() {
        return false;
    }

    @Override
    public Set<String> requiredRequestText() {
        return Set.of("name");
    }

    @Override
    public Set<String> modelAuthoredDiffFields() {
        return Set.of("name", "website", "industry");
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of();
    }

    @Override
    public List<Diff> diffs(Review review) {
        List<Diff> rows = new ArrayList<>();
        rows.add(new Diff("name", null, false, review.requestText("name"), DiffState.CHANGED));
        for (String field : List.of("website", "industry")) {
            String value = review.requestText(field);
            if (value != null) {
                rows.add(new Diff(field, null, false, value, DiffState.CHANGED));
            }
        }
        rows.addAll(AiAssistantCreationTemplatePin.diffs(review.pinned(), objectMapper));
        return List.copyOf(rows);
    }

    @Override
    public String requestSummary(Review review) {
        return "Create a company";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Company created";
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
        return List.of("name");
    }

    private static CreateCompany request(AiAssistantWriteToolRequest request) {
        if (!(request instanceof CreateCompany company)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        return company;
    }
}
