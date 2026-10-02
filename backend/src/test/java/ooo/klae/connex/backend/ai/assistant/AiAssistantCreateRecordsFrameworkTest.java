package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/** Drives creates through prepare and approval to pin the framework's domain-independent controls. */
class AiAssistantCreateRecordsFrameworkTest extends AbstractAiAssistantWriteToolTest {
    private static final String PERSON = "{\"handle\":\"r1\",\"name\":\"New colleague\",\"title\":\"Director\"}";
    private static final String DEAL = "{\"handle\":\"r1\",\"name\":\"New opportunity\",\"stage\":\"Proposal\","
            + "\"value\":\"1250.50\",\"currency\":\"JPY\"}";

    @ParameterizedTest
    @ValueSource(strings = {"create_person", "create_deal"})
    void embeddedIssuedNamesAndDemaskedIdentifierSubstringsRefuseBeforePreparation(String tool) {
        MaskingContext masking = new MaskingContext();
        masking.tokenFor(EntityKind.PERSON, "Morgan Vale");
        AiChatResourceRegistry resources = new AiChatResourceRegistry(masking);
        resources.register("company", 31);
        AiAssistantWriteToolService service = service();
        for (String name : List.of("{{P1}} Jr", "｛｛ Ｐ１ ｝｝ Jr", "Morgan Vale Jr", "MORGAN VALE Jr")) {
            var arguments = objectMapper.createObjectNode().put("handle", "r1").put("name", name);
            if ("create_deal".equals(tool)) {
                arguments.put("stage", "Proposal").put("value", "100").put("currency", "JPY");
            }
            if (name.contains("{{") || name.contains("｛｛")) {
                var output = objectMapper.createObjectNode();
                output.putObject("tool").put("name", tool).set("args", arguments);
                output.putNull("final");
                Set<String> refused = new HashSet<>();
                var schema = new AiAssistantStepGuard(catalog).forStep(
                        Set.of(AiAssistantToolCatalog.Toolset.CORE, AiAssistantToolCatalog.Toolset.WRITE_CREATE),
                        Set.of("{{P1}}"));
                assertNull(service.guardRawIdentifiers(schema, masking, refused).rejectionReason(output));
                assertEquals(Set.of(tool), refused);
            }
            assertRefusal("identifier_from_another_record",
                    () -> service.prepare(tool, arguments, resources, TURN.restrictionEpoch()));
        }
        verifyNoInteractions(chatMapper, presetService, duplicatePreflightService, creationService, pipelineService);
    }

    @Test
    void templateIdentityDefaultsReachPreflightWithoutEnteringDurablePins() throws Exception {
        ResolvedCreationFieldDto email = defaultField("email", "private@example.test");
        ResolvedCreationFieldDto phone = defaultField("phone", "+12025550123");
        when(presetService.persons(RecordCreationEntryPoint.quick_create, 31))
                .thenReturn(preset(RecordCreationRecordType.person, email, phone));
        when(duplicatePreflightService.preflightPerson(any()))
                .thenReturn(new DuplicatePreflightResponse("person", List.of(), false, null));

        AiAssistantPreparedWrite prepared = propose(service(), "create_person", PERSON, "company", 31);

        ArgumentCaptor<PersonDuplicatePreflightRequest> captured =
                ArgumentCaptor.forClass(PersonDuplicatePreflightRequest.class);
        verify(duplicatePreflightService).preflightPerson(captured.capture());
        assertEquals(List.of("private@example.test"), captured.getValue().emails());
        assertEquals(List.of("+12025550123"), captured.getValue().phones());
        assertFalse(prepared.argumentsJson().contains("private@example.test"));
        assertFalse(prepared.argumentsJson().contains("+12025550123"));
        verifyNoInteractions(duplicateDecisionLockService, creationService);
    }

    @Test
    void aTruncatedPersonCheckRefusesBeforeAnyProposalOrCreate() {
        stubPerson();
        when(duplicatePreflightService.preflightPerson(any()))
                .thenReturn(new DuplicatePreflightResponse("person", List.of(), true, null));
        AiAssistantWriteToolService service = service();
        assertRefusal("possible_duplicate", () -> propose(service, "create_person", PERSON, "company", 31));
        verifyNoInteractions(creationService);
        verify(chatMapper, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void anUnavailableCheckFailsClosedWithoutCreating() {
        stubPerson();
        when(duplicatePreflightService.preflightPerson(any()))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        AiAssistantWriteToolService service = service();
        assertRefusal("duplicate_check_unavailable",
                () -> propose(service, "create_person", PERSON, "company", 31));
        verifyNoInteractions(creationService);
    }

    @Test
    void anUnsuppliedRequiredTemplateFieldStopsBeforeTheDuplicatePreflight() {
        ResolvedCreationFieldDto required = new ResolvedCreationFieldDto("leadSource", null, null,
                null, null, null, null, null, true, false, false, null, null, List.of());
        when(presetService.persons(RecordCreationEntryPoint.quick_create, 31))
                .thenReturn(new RecordCreationPresetCatalogDto(RecordCreationRecordType.person,
                        RecordCreationEntryPoint.quick_create, 4, "workspace:12",
                        List.of(new ResolvedCreationTemplateDto("workspace:12", RecordCreationRecordType.person,
                                false, 3, new LocalizedTextDto("Reviewed", "確認済み"), null,
                                RecordCreationTemplateAvailability.available,
                                List.of(new ResolvedCreationGroupDto("basics", null, null, List.of(required))),
                                List.of())), false, List.of()));
        AiAssistantWriteToolService service = service();

        assertRefusal("template_requires_fields",
                () -> propose(service, "create_person", PERSON, "company", 31));

        verifyNoInteractions(duplicatePreflightService, creationService);
    }

    @Test
    void aRateLimitedCheckRefusesRecoverablyAndAnUnavailableAnchorIsUnresolved() {
        stubPerson();
        when(duplicatePreflightService.preflightPerson(any()))
                .thenThrow(new ooo.klae.connex.backend.exceptions.TooManyRequestsException("limited"));
        AiAssistantWriteToolService service = service();
        assertRefusal("duplicate_check_unavailable",
                () -> propose(service, "create_person", PERSON, "company", 31));
        clearInvocations(presetService, duplicatePreflightService);
        when(companyService.getCompanyById(31)).thenThrow(new ResourceNotFoundException("Company not found"));
        assertRefusal("unresolved_reference", () -> propose(service, "create_person", PERSON, "company", 31));
        verifyNoInteractions(presetService, duplicatePreflightService, creationService);
    }

    @Test
    void theOriginalPinReplaysWithoutReselectingOrRecheckingDuplicates() throws Exception {
        stubPerson();
        AiAssistantWriteToolService service = service();
        AiAssistantPreparedWrite prepared = propose(service, "create_person", PERSON, "company", 31);
        JsonNode root = objectMapper.readTree(prepared.argumentsJson());
        assertEquals(3, root.path("pinned").path("templateVersion").asInt());
        assertEquals(4, root.path("pinned").path("templateSetRevision").asInt());
        clearInvocations(presetService, duplicatePreflightService, companyService);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.register("company", 31);
        AiAssistantPreparedWrite replayed = service.prepareReplay("create_person", objectMapper.readTree(PERSON),
                resources, TURN.restrictionEpoch(), prepared.argumentsJson());
        assertEquals(root, objectMapper.readTree(replayed.argumentsJson()));
        verifyNoInteractions(presetService, duplicatePreflightService, companyService, creationService);
    }

    @Test
    void approvalSubmitsPinnedDefaultsWithoutLockingOrFreshnessCheckingTheAnchor() throws Exception {
        stubPerson();
        Company anchor = new Company();
        anchor.setId(31);
        anchor.setUpdatedAt("2026-01-01 00:00:00");
        when(companyService.getCompanyById(31)).thenReturn(anchor);
        Person person = new Person();
        person.setId(75);
        person.setName("New colleague");
        person.setCompany(anchor);
        when(creationService.createPerson(any())).thenReturn(person);
        AiAssistantWriteToolService service = service();
        propose(service, "create_person", PERSON, "company", 31);
        anchor.setUpdatedAt("2099-01-01 00:00:00");
        clearInvocations(companyService, presetService, duplicatePreflightService);

        assertEquals("executed", service.approve(TURN.sessionId(), TOOL_CALL_ID).status());

        var ordered = inOrder(chatMapper, companyService, creationService);
        ordered.verify(chatMapper).getToolCallBySessionForUpdate(TURN.workspaceId(), TURN.sessionId(), TOOL_CALL_ID);
        ordered.verify(companyService).getCompanyById(31);
        ordered.verify(creationService).createPerson(any());
        verify(companyService, never()).lockOwnedCompanyForUpdate(31);
        verifyNoInteractions(duplicateDecisionLockService, presetService, duplicatePreflightService);
        ArgumentCaptor<GuidedPersonCreateRequestDto> request = ArgumentCaptor.forClass(GuidedPersonCreateRequestDto.class);
        verify(creationService).createPerson(request.capture());
        assertEquals(3, request.getValue().templateUse().templateVersion());
        assertEquals(4, request.getValue().templateUse().templateSetRevision());
        assertEquals(31, request.getValue().templateUse().context().relatedCompanyId());
        assertNull(request.getValue().record().duplicateReviewToken());
        JsonNode result = objectMapper.readTree(capturedExecutedResult());
        assertEquals("unavailable", result.path("undo").path("status").asString());
        assertEquals("person", result.path("undo").path("entityKind").asString());
        assertFalse(result.has("verification"));
    }

    @Test
    void aDealDuplicateRefusesAndADriftedStageCannotCreate() throws Exception {
        stubDeal();
        when(duplicatePreflightService.dealCandidatesExist(any())).thenReturn(true);
        AiAssistantWriteToolService service = service();
        assertRefusal("possible_duplicate", () -> propose(service, "create_deal", DEAL, "company", 31));
        when(duplicatePreflightService.dealCandidatesExist(any())).thenReturn(false);
        propose(service, "create_deal", DEAL, "company", 31);
        when(pipelineService.getAllStages()).thenReturn(List.of(stage(9, "Proposal")));
        assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verifyNoInteractions(creationService);
    }

    @Test
    void revokedPermissionAndLostAnchorAccessPreventTheDelegate() throws Exception {
        stubPerson();
        AiAssistantWriteToolService service = service();
        propose(service, "create_person", PERSON, "company", 31);
        when(authority.effectiveFor(TURN.userId())).thenReturn(Set.of(Permission.AI_USE));
        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("Requires the PERSON_CREATE permission in this workspace", refusal.getMessage());
        when(authority.effectiveFor(TURN.userId())).thenReturn(Set.of(Permission.AI_USE, Permission.PERSON_CREATE));
        when(companyService.getCompanyById(31)).thenThrow(new ResourceNotFoundException("Company not found"));
        assertThrows(ResourceNotFoundException.class, () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verifyNoInteractions(creationService);
    }

    @Test
    void pinMetadataRejectsNonDurableValuesAndRoundTripsNestedMaps() {
        Map<String, Object> pin = Map.of("templateVersion", 3, "defaultedFields", Map.of("leadSource", "REFERRAL"));
        assertEquals(pin, AiAssistantToolProposalPin.read(objectMapper.valueToTree(Map.of("pinned", pin))));
        for (Object invalid : List.of(1L, List.of("private"), 1.5)) {
            assertThrows(IllegalArgumentException.class, () -> AiAssistantToolProposalPin.copy(Map.of("bad", invalid)));
        }
    }

    private void stubPerson() {
        when(presetService.persons(RecordCreationEntryPoint.quick_create, 31))
                .thenReturn(preset(RecordCreationRecordType.person));
        when(duplicatePreflightService.preflightPerson(any()))
                .thenReturn(new DuplicatePreflightResponse("person", List.of(), false, null));
    }

    private void stubDeal() {
        when(presetService.deals(RecordCreationEntryPoint.quick_create, 31))
                .thenReturn(preset(RecordCreationRecordType.deal));
        when(pipelineService.getAllStages()).thenReturn(List.of(stage(6, "Proposal")));
    }

    private ResolvedCreationFieldDto defaultField(String key, String value) {
        return new ResolvedCreationFieldDto(key, null, null, null, null, null, null, null,
                false, false, false, objectMapper.valueToTree(value), null, List.of());
    }

    private static RecordCreationPresetCatalogDto preset(
            RecordCreationRecordType kind, ResolvedCreationFieldDto... fields) {
        return new RecordCreationPresetCatalogDto(kind, RecordCreationEntryPoint.quick_create, 4,
                "workspace:12", List.of(new ResolvedCreationTemplateDto("workspace:12", kind, false, 3,
                        new LocalizedTextDto("Reviewed", "確認済み"), null,
                        RecordCreationTemplateAvailability.available,
                        List.of(new ResolvedCreationGroupDto("basics", null, null, List.of(fields))), List.of())),
                false, List.of());
    }

    private static void assertRefusal(String reason, org.junit.jupiter.api.function.Executable action) {
        AiAssistantLoopException refusal = assertThrows(AiAssistantLoopException.class, action);
        assertEquals(reason, refusal.detailReason());
        assertTrue(refusal.recoverable());
    }
}
