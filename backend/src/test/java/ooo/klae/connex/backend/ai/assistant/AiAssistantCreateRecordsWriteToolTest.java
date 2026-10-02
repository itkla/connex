package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Authority;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Diff;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.DiffState;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Execution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.LockedTarget;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Outcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.RecordSnapshot;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Resolution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Review;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Row;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Target;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateDeal;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreatePerson;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultOrigin;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldSource;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldValueType;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.RecordCreationPresetService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Pins create delegates, template-required inputs, disclosure, stage identity and pure probes. */
class AiAssistantCreateRecordsWriteToolTest {
    private static final Target COMPANY = new Target("company", 52);
    private static final CreatePerson PERSON = new CreatePerson("r1", "New contact", null);
    private static final CreateDeal DEAL = new CreateDeal(
            "r1", "Renewal", "Qualified", "1250.50", "USD", "2026-12-01");
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final GuidedRecordCreationService creationService = mock(GuidedRecordCreationService.class);
    private final RecordCreationPresetService presetService = mock(RecordCreationPresetService.class);
    private final PipelineService pipelineService = mock(PipelineService.class);
    private final AiAssistantCreatePersonWriteTool personTool = new AiAssistantCreatePersonWriteTool(
            creationService, presetService, objectMapper);
    private final AiAssistantCreateDealWriteTool dealTool = new AiAssistantCreateDealWriteTool(
            creationService, presetService, pipelineService, objectMapper);

    @Test
    void pinKeepsOnlyDefaultsActuallyAppliedAndNeverCopiesPrivateValues() {
        when(presetService.persons(RecordCreationEntryPoint.quick_create, 52)).thenReturn(catalog(
                RecordCreationRecordType.person,
                field("name", true, "Template contact"),
                field("company", true, 52),
                field("owner", true, 17),
                field("consentStatus", true, null),
                field("title", true, "Director"),
                field("leadSource", false, "REFERRAL"),
                field("leadSourceDetail", false, "Private introduction"),
                field("referrerPerson", false, 82),
                customField("custom:7", 7, true, "Private answer")));

        Map<String, Object> pin = personTool.pin(COMPANY, PERSON);

        assertEquals("workspace:8", pin.get("templateId"));
        assertEquals(3, pin.get("templateVersion"));
        assertEquals(12, pin.get("templateSetRevision"));
        assertEquals("Sales intake", pin.get("templateName"));
        assertEquals(Map.of("title", "", "leadSource", "REFERRAL", "leadSourceDetail", "",
                "referrerPerson", "", "customFields", ""), pin.get("defaultedFields"));
        String serialized = objectMapper.writeValueAsString(pin);
        assertFalse(serialized.contains("Private"));
        assertFalse(serialized.contains("Director"));
        verifyNoInteractions(creationService);
    }

    @Test
    void suppliedTitleOverridesItsTemplateDefaultAndRequiredEmailRefuses() {
        when(presetService.persons(RecordCreationEntryPoint.quick_create, 52)).thenReturn(catalog(
                RecordCreationRecordType.person, field("title", true, "Default title")));
        Map<String, Object> pin = personTool.pin(COMPANY, new CreatePerson("r1", "Contact", "Director"));
        assertEquals(Map.of(), pin.get("defaultedFields"));
        when(presetService.persons(RecordCreationEntryPoint.quick_create, 52)).thenReturn(catalog(
                RecordCreationRecordType.person, field("email", true, null)));

        AiAssistantLoopException refused = assertThrows(AiAssistantLoopException.class,
                () -> personTool.pin(COMPANY, PERSON));

        assertEquals("template_requires_fields", refused.detailReason());
        assertTrue(refused.recoverable());
        verifyNoInteractions(creationService);
    }

    @Test
    void requiredCustomAndTagDefaultsUseGuidedPresenceRules() {
        for (Object missing : List.of("", List.of(), Map.of())) {
            var catalog = catalog(RecordCreationRecordType.person,
                    customField("custom:7", 7, true, missing));
            assertThrows(AiAssistantLoopException.class,
                    () -> AiAssistantCreationTemplatePin.prepare(catalog, Set.of(), Set.of()));
        }
        for (Object present : List.of(false, 0, List.of("answer"))) {
            var catalog = catalog(RecordCreationRecordType.person,
                    customField("custom:7", 7, true, present));
            assertDoesNotThrow(() -> AiAssistantCreationTemplatePin.prepare(catalog, Set.of(), Set.of()));
        }
        var noTags = catalog(RecordCreationRecordType.person, field("tags", true, List.of()));
        assertThrows(AiAssistantLoopException.class,
                () -> AiAssistantCreationTemplatePin.prepare(noTags, Set.of(), Set.of()));
    }

    @Test
    void personApplyUsesPinnedTemplateAndNoContactChannelOrDuplicateToken() {
        Person created = new Person();
        created.setId(61);
        created.setName("New contact");
        Company company = new Company();
        company.setId(52);
        created.setCompany(company);
        when(creationService.createPerson(any())).thenReturn(created);

        Outcome outcome = personTool.apply(execution(PERSON, null));

        ArgumentCaptor<GuidedPersonCreateRequestDto> captured =
                ArgumentCaptor.forClass(GuidedPersonCreateRequestDto.class);
        verify(creationService).createPerson(captured.capture());
        GuidedPersonCreateRequestDto request = captured.getValue();
        assertEquals("workspace:8", request.templateUse().templateId());
        assertEquals(3, request.templateUse().templateVersion());
        assertEquals(12, request.templateUse().templateSetRevision());
        assertEquals(RecordCreationEntryPoint.quick_create, request.templateUse().entryPoint());
        assertEquals(52, request.templateUse().context().relatedCompanyId());
        assertEquals(52, request.record().companyId());
        assertNull(request.record().email());
        assertNull(request.record().phone());
        assertNull(request.record().duplicateReviewToken());
        assertTrue(request.customFields().isEmpty());
        assertTrue(request.tagIds().isEmpty());
        assertEquals(52, outcome.readBack().applied());
        assertEquals(61, outcome.inverse().entityId());
        assertFalse(outcome.inverse().available());
        assertEquals(Map.of("status", "executed", "recordType", "person", "name", "New contact"),
                outcome.data());
        assertEquals(Map.of("recordType", "person", "created", true),
                personTool.modelOutcome(objectMapper.valueToTree(outcome.data())));
        verifyNoInteractions(presetService, pipelineService);
    }

    @Test
    void dealApplyUsesPinnedStagePipelineAndTemplateVersion() {
        when(pipelineService.getStageById(7)).thenReturn(stage(7, "Qualified"));
        Deal created = new Deal();
        created.setId(64);
        created.setStageId(7);
        created.setName("Renewal");
        created.setValue(new BigDecimal("1250.50"));
        created.setCurrency("USD");
        when(creationService.createDeal(any(), eq("Qualified"))).thenReturn(created);

        Outcome outcome = dealTool.apply(execution(DEAL, new Resolution("stage", 7, "Qualified")));

        ArgumentCaptor<GuidedDealCreateRequestDto> captured =
                ArgumentCaptor.forClass(GuidedDealCreateRequestDto.class);
        verify(creationService).createDeal(captured.capture(), eq("Qualified"));
        GuidedDealCreateRequestDto request = captured.getValue();
        assertEquals(3, request.templateUse().templateVersion());
        assertEquals(12, request.templateUse().templateSetRevision());
        assertEquals(5, request.record().pipeline());
        assertEquals(7, request.record().stage());
        assertEquals(52, request.record().company());
        assertEquals(new BigDecimal("1250.50"), request.record().value());
        assertEquals(LocalDate.of(2026, 12, 1), request.record().expectedCloseDate());
        assertNull(request.record().duplicateReviewToken());
        assertEquals(7, outcome.readBack().requested());
        assertEquals(7, outcome.readBack().applied());
        assertFalse(outcome.inverse().available());
        assertEquals(Map.of("status", "executed", "recordType", "deal", "name", "Renewal",
                "stage", "Qualified", "value", "1250.50", "currency", "USD"), outcome.data());
        assertEquals(Map.of("recordType", "deal", "created", true),
                dealTool.modelOutcome(objectMapper.valueToTree(outcome.data())));
        verifyNoInteractions(presetService);
    }

    @Test
    void renamedStageRefusesBeforeGuidedCreateAndDuplicateNamesNeverResolve() {
        when(pipelineService.getStageById(7)).thenReturn(stage(7, "Renamed"));
        assertThrows(ConflictException.class,
                () -> dealTool.apply(execution(DEAL, new Resolution("stage", 7, "Qualified"))));
        when(pipelineService.getAllStages()).thenReturn(
                List.of(stage(7, "Qualified"), stage(9, "QUALIFIED")));
        assertThrows(ResourceNotFoundException.class, () -> dealTool.resolve(COMPANY, DEAL));
        when(pipelineService.getAllStages()).thenReturn(List.of(stage(7, "qUALIFIED")));
        assertEquals(7, dealTool.resolve(COMPANY, DEAL).id());
        verifyNoInteractions(creationService, presetService);
    }

    @Test
    void dealPinsTheReviewedPipelineAndRefusesAStageMovedToAnotherPipeline() {
        Stage stage = stage(7, "Qualified");
        when(pipelineService.getAllStages()).thenReturn(List.of(stage));
        when(presetService.deals(RecordCreationEntryPoint.quick_create, 52))
                .thenReturn(catalog(RecordCreationRecordType.deal));
        Map<String, Object> pinned = dealTool.pin(COMPANY, DEAL);
        assertEquals(7, pinned.get("stageId"));
        assertEquals(5, pinned.get("stagePipelineId"));
        assertEquals("Qualified", pinned.get("stageName"));
        assertEquals(pinned, AiAssistantToolProposalPin.read(objectMapper.valueToTree(Map.of("pinned", pinned))));
        stage.getPipeline().setId(8);
        when(pipelineService.getStageById(7)).thenReturn(stage);

        assertEquals("Assistant proposal target changed", assertThrows(ConflictException.class,
                () -> dealTool.apply(execution(DEAL, new Resolution("stage", 7, "Qualified")))).getMessage());
        assertEquals(DiffState.UNRESOLVED,
                dealTool.diffs(review(DEAL, List.of(stage), 7, pinned)).get(1).state());
        verifyNoInteractions(creationService);
    }

    @Test
    void aNameSwapBetweenResolutionAndPinningCannotMixStageIdentities() {
        Stage first = stage(7, "Qualified");
        Stage successor = stage(9, "Qualified");
        successor.getPipeline().setId(8);
        when(pipelineService.getAllStages()).thenReturn(List.of(first), List.of(successor));
        when(presetService.deals(RecordCreationEntryPoint.quick_create, 52))
                .thenReturn(catalog(RecordCreationRecordType.deal));
        Resolution resolution = dealTool.resolve(COMPANY, DEAL);
        Map<String, Object> pinned = dealTool.pin(COMPANY, DEAL);
        first.getPipeline().setId(8);
        when(pipelineService.getStageById(7)).thenReturn(first);

        assertEquals("Assistant proposal target changed", assertThrows(ConflictException.class,
                () -> dealTool.apply(execution(DEAL, resolution, pinned))).getMessage());
        assertEquals(DiffState.UNRESOLVED,
                dealTool.diffs(review(DEAL, List.of(first), resolution.id(), pinned)).get(1).state());
        verifyNoInteractions(creationService);
    }

    @Test
    void probesAndValidationReadNoDependencies() {
        assertEquals(new AiAssistantWriteTool.DuplicateProbe("person", "New contact", 52, null),
                personTool.duplicateProbe(COMPANY, PERSON));
        assertEquals(new AiAssistantWriteTool.DuplicateProbe("deal", "Renewal", 52, null),
                dealTool.duplicateProbe(COMPANY, DEAL));
        assertDoesNotThrow(() -> personTool.validateFor("company", objectMapper.valueToTree(PERSON)));
        assertDoesNotThrow(() -> dealTool.validateFor("company", objectMapper.valueToTree(DEAL)));
        verifyNoInteractions(creationService, presetService, pipelineService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "phone", "owner", "leadSource", "duplicateReviewToken"})
    void personNeverAcceptsIdentityProvenanceOrApprovalInputs(String field) {
        JsonNode arguments = objectMapper.createObjectNode().put("handle", "r1")
                .put("name", "Contact").put(field, "injected");
        AiAssistantLoopException refused = assertThrows(AiAssistantLoopException.class,
                () -> personTool.validateFor("company", arguments));
        assertEquals("invalid_tool_arguments", refused.detailReason());
        verifyNoInteractions(creationService, presetService, pipelineService);
    }

    @Test
    void dealValidationRejectsMalformedAmountDateCurrencyAndWrongKinds() {
        for (String amount : List.of("-1", "1e3", "1.001", "10000000000000", " ")) {
            JsonNode arguments = objectMapper.valueToTree(new CreateDeal(
                    "r1", "Deal", "Qualified", amount, "USD", null));
            assertThrows(AiAssistantLoopException.class, () -> dealTool.validateFor("company", arguments));
        }
        for (String date : List.of("2026-02-30", "tomorrow", "2026-1-01")) {
            JsonNode arguments = objectMapper.valueToTree(new CreateDeal(
                    "r1", "Deal", "Qualified", "0.00", "USD", date));
            assertThrows(AiAssistantLoopException.class, () -> dealTool.validateFor("company", arguments));
        }
        assertThrows(AiAssistantLoopException.class, () -> dealTool.validateFor("person",
                objectMapper.valueToTree(DEAL)));
        assertThrows(AiAssistantLoopException.class, () -> dealTool.validateFor("company",
                objectMapper.valueToTree(new CreateDeal("r1", "Deal", "Qualified", "1", "usd", null))));
        verifyNoInteractions(creationService, presetService, pipelineService);
    }

    @Test
    void cardShowsPinnedTemplateAndDefaultsWhileStageIdentityMustStillMatch() {
        Review review = review(DEAL, List.of(stage(7, "Qualified")), 7, pin());
        List<Diff> rows = dealTool.diffs(review);
        assertEquals(List.of("name", "stage", "value", "currency", "expectedCloseDate",
                "template", "templateDefaults"), rows.stream().map(Diff::field).toList());
        assertEquals("Sales intake", rows.get(5).proposedValue());
        assertEquals(objectMapper.valueToTree(Map.of("leadSource", "REFERRAL", "customFields", "")),
                objectMapper.readTree(rows.get(6).proposedValue()));
        Review changed = review(DEAL, List.of(stage(9, "Qualified")), 7, pin());
        assertEquals(DiffState.UNRESOLVED, dealTool.diffs(changed).get(1).state());
        verifyNoInteractions(creationService, presetService, pipelineService);
    }

    @Test
    void missingOrSpecialCareTemplateNameCannotProduceAnApplicableTemplateRow() {
        assertEquals(DiffState.UNRESOLVED,
                AiAssistantCreationTemplatePin.diffs(Map.of(), objectMapper).getFirst().state());
        Map<String, Object> excluded = Map.of("templateName", "HIV diagnosis",
                "defaultedFields", Map.of());
        assertEquals(DiffState.UNRESOLVED,
                AiAssistantCreationTemplatePin.diffs(excluded, objectMapper).getFirst().state());
        assertThrows(ConflictException.class,
                () -> AiAssistantCreationTemplatePin.use(Map.of("templateId", "workspace:8"), 52));
    }

    private Execution execution(AiAssistantWriteToolRequest request, Resolution resolution) {
        return execution(request, resolution, pin());
    }

    private Execution execution(
            AiAssistantWriteToolRequest request, Resolution resolution, Map<String, Object> pinned) {
        return new Execution(new Authority(4, 17, 91, Instant.EPOCH), new Row(COMPANY, request),
                List.of(), resolution, new LockedTarget(null, null),
                (start, end) -> { throw new AssertionError("Creation has no calendar dependency"); }, pinned);
    }

    private Review review(
            AiAssistantWriteToolRequest request, List<Stage> stages, Integer stageId,
            Map<String, Object> pinned) {
        return new Review("company", 52, true,
                new RecordSnapshot("Company", null, null, null, null, Map.of(), false),
                objectMapper.valueToTree(request), null, List.of(), stages, List.of(), List.of(), Set.of(),
                stageId, List.of(), List.of(), Map.of(), pinned);
    }

    private static Map<String, Object> pin() {
        return Map.of("templateId", "workspace:8", "templateVersion", 3,
                "templateSetRevision", 12, "templateName", "Sales intake",
                "defaultedFields", Map.of("leadSource", "REFERRAL", "customFields", ""),
                "stageId", 7, "stageName", "Qualified", "stagePipelineId", 5);
    }

    private RecordCreationPresetCatalogDto catalog(
            RecordCreationRecordType type, ResolvedCreationFieldDto... fields) {
        LocalizedTextDto label = new LocalizedTextDto("Sales intake", "営業受付");
        ResolvedCreationTemplateDto template = new ResolvedCreationTemplateDto(
                "workspace:8", type, false, 3, label, label, RecordCreationTemplateAvailability.available,
                List.of(new ResolvedCreationGroupDto("main", label, label, List.of(fields))), List.of());
        return new RecordCreationPresetCatalogDto(type, RecordCreationEntryPoint.quick_create,
                12, "workspace:8", List.of(template), false, List.of());
    }

    private ResolvedCreationFieldDto field(String key, boolean required, Object value) {
        return resolvedField(key, null, required, value);
    }

    private ResolvedCreationFieldDto customField(String key, int id, boolean required, Object value) {
        return resolvedField(key, id, required, value);
    }

    private ResolvedCreationFieldDto resolvedField(
            String key, Integer customId, boolean required, Object value) {
        LocalizedTextDto label = new LocalizedTextDto(key, key);
        return new ResolvedCreationFieldDto(key,
                customId == null ? RecordCreationFieldSource.system : RecordCreationFieldSource.custom,
                customId, RecordCreationFieldValueType.text, "fingerprint", label, label, label,
                required, false, false, value == null ? null : objectMapper.valueToTree(value),
                RecordCreationDefaultOrigin.template, List.of());
    }

    private static Stage stage(int id, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(5);
        Stage stage = new Stage();
        stage.setId(id);
        stage.setName(name);
        stage.setPipeline(pipeline);
        return stage;
    }
}
