package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreatePerson;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonRecordDto;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.RecordCreationPresetService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Proposes a contact at a visible company through pinned, duplicate-reviewed quick creation. */
@Component
@RequiredArgsConstructor
public class AiAssistantCreatePersonWriteTool implements AiAssistantWriteTool {
    private static final String NAME = "create_person";
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
        return CreatePerson.class;
    }

    @Override
    public Set<String> acceptedTargetKinds() {
        return Set.of("company");
    }

    @Override
    public Set<String> declaredWritableFields() {
        return Set.of("person.record");
    }

    @Override
    public Set<Permission> requiredPermissions(String targetKind) {
        return Set.of(Permission.PERSON_CREATE);
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
        AiAssistantCreationTemplatePin.validateShape(
                targetKind, request, Set.of("handle", "name", "title"));
        AiAssistantCreationTemplatePin.requiredText(request, "name", 255);
        AiAssistantCreationTemplatePin.optionalText(request, "title", 128);
    }

    @Override
    public List<PrincipalRequest> principals(
            AiAssistantWriteToolRequest request, MemberDirectory directory) {
        return List.of();
    }

    @Override
    public Map<String, Object> pin(Target target, AiAssistantWriteToolRequest request) {
        CreatePerson person = request(request);
        Set<String> supplied = person.title() == null
                ? Set.of("name", "company") : Set.of("name", "company", "title");
        return AiAssistantCreationTemplatePin.prepare(
                presetService.persons(RecordCreationEntryPoint.quick_create, target.id()),
                supplied, Set.of("owner", "consentStatus"));
    }

    @Override
    public DuplicateProbe duplicateProbe(Target target, AiAssistantWriteToolRequest request) {
        CreatePerson person = request(request);
        return new DuplicateProbe("person", person.name(), target.id(), person.title());
    }

    @Override
    public Outcome apply(Execution execution) {
        CreatePerson request = request(execution.row().request());
        int companyId = execution.row().target().id();
        Person created = creationService.createPerson(new GuidedPersonCreateRequestDto(
                new GuidedPersonRecordDto(request.name(), null, null, companyId,
                        request.title(), null, null, null, null),
                AiAssistantCreationTemplatePin.use(execution.pinned(), companyId),
                Map.of(), List.of()));
        if (created == null) {
            throw new IllegalStateException("Created contact could not be read back");
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", "executed");
        outcome.put("recordType", "person");
        if (created.getName() != null) {
            outcome.put("name", created.getName());
        }
        return new Outcome(
                outcome,
                new Inverse("person", created.getId(), "", false, Map.of()),
                new ReadBack("companyId", companyId,
                        created.getCompany() == null ? null : created.getCompany().getId()));
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
        return Set.of("name", "title");
    }

    @Override
    public Set<ReviewInput> reviewInputs() {
        return Set.of();
    }

    @Override
    public List<Diff> diffs(Review review) {
        List<Diff> rows = new ArrayList<>();
        rows.add(new Diff("name", null, false, review.requestText("name"), DiffState.CHANGED));
        String title = review.requestText("title");
        if (title != null) {
            rows.add(new Diff("title", null, false, title, DiffState.CHANGED));
        }
        rows.addAll(AiAssistantCreationTemplatePin.diffs(review.pinned(), objectMapper));
        return List.copyOf(rows);
    }

    @Override
    public String requestSummary(Review review) {
        return "Create a contact";
    }

    @Override
    public String outcomeSummary(Review review) {
        return "Contact created";
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

    private static CreatePerson request(AiAssistantWriteToolRequest request) {
        if (!(request instanceof CreatePerson person)) {
            throw new IllegalStateException("Assistant tool request is invalid");
        }
        return person;
    }
}
