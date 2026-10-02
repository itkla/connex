package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDefinitionRequest;
import ooo.klae.connex.backend.dto.ReportTemplateDto;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/** Exercises workspace creation through the same preparation, replay and approval gates as CRM writes. */
class AiAssistantWorkspaceCreatesFrameworkTest extends AbstractAiAssistantWriteToolTest {
    private static final String COMPANY = "{\"name\":\"New company\",\"website\":\"new.example\"}";
    private static final String REPORT = "{\"template\":\"sales-performance\",\"name\":\"営業実績\"}";

    @Test
    void workspaceIdentityIsServerFilledAndCannotBeRebasedOnReplayOrApproval() {
        AiAssistantWriteToolService service = service();
        AiAssistantPreparedWrite write = workspaceProposal(service, "create_report", REPORT);
        JsonNode root = objectMapper.readTree(write.argumentsJson());
        assertEquals("workspace", root.path("target").path("kind").asString());
        assertEquals(TURN.workspaceId(), root.path("target").path("id").asInt());
        assertFalse(root.path("request").has("handle"));
        assertNull(objectMapper.treeToValue(root.path("request"), AiAssistantWriteToolRequest.CreateReport.class).handle());
        assertEquals(write.argumentsJson(), service.prepareReplay("create_report", objectMapper.readTree(REPORT),
                new AiChatResourceRegistry(), TURN.restrictionEpoch(), write.argumentsJson()).argumentsJson());
        var forged = objectMapper.readTree(write.argumentsJson()).deepCopy();
        if (!(forged.get("target") instanceof tools.jackson.databind.node.ObjectNode target)) {
            throw new AssertionError("Missing target");
        }
        target.put("id", TURN.workspaceId() + 1);
        String foreign = objectMapper.writeValueAsString(forged);
        assertThrows(ConflictException.class, () -> service.prepareReplay("create_report",
                objectMapper.readTree(REPORT), new AiChatResourceRegistry(), TURN.restrictionEpoch(), foreign));
        storedToolCall.setArgumentsJson(foreign);
        assertThrows(IllegalStateException.class, () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verifyNoInteractions(reportService, creationService, duplicatePreflightService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"handle", "workspaceId", "id"})
    void modelCannotChooseAWorkspaceTarget(String field) {
        var args = objectMapper.createObjectNode().put("template", "sales-performance").put("name", "Sales");
        args.put(field, "r1");
        assertRefusal("invalid_tool_arguments", () -> service().prepare(
                "create_report", args, new AiChatResourceRegistry(), TURN.restrictionEpoch()));
        verifyNoInteractions(reportService, creationService);
    }

    @Test
    void companyDefaultsAreCheckedFromThePinnedSnapshotAndNeverPersisted() {
        stubCompany(defaultField("website", "private.example"), defaultField("phone", "+12025550123"));
        AiAssistantWriteToolService service = service();
        AiAssistantPreparedWrite write = workspaceProposal(service, "create_company", "{\"name\":\"New company\"}");
        ArgumentCaptor<CompanyDuplicatePreflightRequest> probe = ArgumentCaptor.forClass(CompanyDuplicatePreflightRequest.class);
        verify(duplicatePreflightService).preflightCompany(probe.capture());
        assertEquals(List.of("private.example"), probe.getValue().websites());
        assertEquals(List.of("+12025550123"), probe.getValue().phones());
        assertFalse(write.argumentsJson().contains("private.example"));
        assertFalse(write.argumentsJson().contains("+12025550123"));
        clearInvocations(presetService, duplicatePreflightService);
        assertEquals(write.argumentsJson(), service.prepareReplay("create_company",
                objectMapper.readTree("{\"name\":\"New company\"}"), new AiChatResourceRegistry(),
                TURN.restrictionEpoch(), write.argumentsJson()).argumentsJson());
        verifyNoInteractions(presetService, duplicatePreflightService, creationService, duplicateDecisionLockService);
    }

    @Test
    void explicitWebsiteWinsAndApprovalSubmitsOnlyThePinnedTemplate() {
        stubCompany(defaultField("website", "default.example"));
        Company created = new Company();
        created.setId(75);
        created.setName("New company");
        when(creationService.createCompany(any())).thenReturn(created);
        AiAssistantWriteToolService service = service();
        workspaceProposal(service, "create_company", COMPANY);
        ArgumentCaptor<CompanyDuplicatePreflightRequest> probe = ArgumentCaptor.forClass(CompanyDuplicatePreflightRequest.class);
        verify(duplicatePreflightService).preflightCompany(probe.capture());
        assertEquals(List.of("new.example"), probe.getValue().websites());
        verifyNoInteractions(creationService);
        clearInvocations(presetService, duplicatePreflightService);
        assertEquals("executed", service.approve(TURN.sessionId(), TOOL_CALL_ID).status());
        ArgumentCaptor<GuidedCompanyCreateRequestDto> request = ArgumentCaptor.forClass(GuidedCompanyCreateRequestDto.class);
        verify(creationService).createCompany(request.capture());
        assertEquals("new.example", request.getValue().record().website());
        assertNull(request.getValue().record().duplicateReviewToken());
        assertNull(request.getValue().templateUse().context().relatedCompanyId());
        assertEquals(3, request.getValue().templateUse().templateVersion());
        assertEquals(4, request.getValue().templateUse().templateSetRevision());
        verifyNoInteractions(presetService, duplicatePreflightService, duplicateDecisionLockService, companyService);
    }

    @Test
    void companyTruncationAndUnavailableChecksRefuseBeforeCreatingAnything() {
        stubCompany();
        AiAssistantWriteToolService service = service();
        when(duplicatePreflightService.preflightCompany(any()))
                .thenReturn(new DuplicatePreflightResponse("company", List.of(), true, null));
        assertRefusal("possible_duplicate", () -> workspaceProposal(service, "create_company", COMPANY));
        when(duplicatePreflightService.preflightCompany(any()))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertRefusal("duplicate_check_unavailable", () -> workspaceProposal(service, "create_company", COMPANY));
        verifyNoInteractions(creationService, duplicateDecisionLockService);
    }

    @Test
    void invalidCompanyInputsAndRedactionMarkersRefuseBeforePresetOrPreflight() {
        AiAssistantWriteToolService service = service();
        for (String website : List.of("https://acme.example", "invalid", "")) {
            assertRefusal("invalid_tool_arguments", () -> service.prepare("create_company",
                    objectMapper.createObjectNode().put("name", "New company").put("website", website),
                    new AiChatResourceRegistry(), TURN.restrictionEpoch()));
        }
        assertRefusal("redacted_value", () -> service.prepare("create_company",
                objectMapper.createObjectNode().put("name", "New company").put("website", "[redacted]"),
                new AiChatResourceRegistry(), TURN.restrictionEpoch()));
        verifyNoInteractions(presetService, duplicatePreflightService, creationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REPORT_CREATE", "REPORT_READ"})
    void reportPermissionLossIsRefusedByTheFrameworkBeforeAnyDelegate(String permission) {
        AiAssistantWriteToolService service = service();
        workspaceProposal(service, "create_report", REPORT);
        var granted = java.util.EnumSet.allOf(Permission.class);
        granted.remove(Permission.valueOf(permission));
        when(authority.effectiveFor(TURN.userId())).thenReturn(granted);
        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("Requires the " + permission + " permission in this workspace", refusal.getMessage());
        verifyNoInteractions(reportService, creationService);
    }

    @Test
    void reportApprovalClearsTitlesAndDescriptionAndKeepsMemberTextOutOfTheModelOutcome() {
        ReportWidgetConfig widget = new ReportWidgetConfig("revenue", "English title", "deals", "won_revenue", "date", "bar");
        ReportConfig config = new ReportConfig(List.of(widget), null, null, "month", List.of());
        when(reportService.templates()).thenReturn(List.of(new ReportTemplateDto(
                "sales-performance", "English name", "English description", "monthly", config)));
        when(reportService.create(any())).thenAnswer(invocation -> {
            ReportDefinitionRequest request = invocation.getArgument(0);
            return new ReportDefinitionDto(75, request.name(), request.description(), request.cadence(),
                    request.templateKey(), request.config(), TURN.userId(), null, null);
        });
        AiAssistantWriteToolService service = service();
        workspaceProposal(service, "create_report", REPORT);
        verify(reportService, never()).create(any());
        assertEquals("executed", service.approve(TURN.sessionId(), TOOL_CALL_ID).status());
        ArgumentCaptor<ReportDefinitionRequest> request = ArgumentCaptor.forClass(ReportDefinitionRequest.class);
        verify(reportService).create(request.capture());
        assertEquals("営業実績", request.getValue().name());
        assertNull(request.getValue().description());
        assertNull(request.getValue().config().widgets().getFirst().title());
        assertEquals(config.layout(), request.getValue().config().layout());
        JsonNode result = objectMapper.readTree(capturedExecutedResult());
        assertEquals("report", result.path("undo").path("entityKind").asString());
        assertEquals("unavailable", result.path("undo").path("status").asString());
        assertEquals(Map.of("recordType", "report", "template", "sales-performance"),
                new AiAssistantCreateReportWriteTool(reportService).modelOutcome(result.path("outcome")));
    }

    private AiAssistantPreparedWrite workspaceProposal(AiAssistantWriteToolService service, String tool, String json) {
        AiAssistantPreparedWrite write = service.prepare(tool, objectMapper.readTree(json),
                new AiChatResourceRegistry(), TURN.restrictionEpoch());
        storedToolCall.setToolName(tool);
        storedToolCall.setArgumentsJson(write.argumentsJson());
        return write;
    }

    private void stubCompany(ResolvedCreationFieldDto... fields) {
        when(presetService.companies(RecordCreationEntryPoint.quick_create)).thenReturn(
                new RecordCreationPresetCatalogDto(RecordCreationRecordType.company, RecordCreationEntryPoint.quick_create,
                        4, "workspace:12", List.of(new ResolvedCreationTemplateDto("workspace:12", RecordCreationRecordType.company,
                                false, 3, new LocalizedTextDto("Reviewed", "確認済み"), null,
                                RecordCreationTemplateAvailability.available,
                                List.of(new ResolvedCreationGroupDto("basics", null, null, List.of(fields))), List.of())),
                        false, List.of()));
        when(duplicatePreflightService.preflightCompany(any()))
                .thenReturn(new DuplicatePreflightResponse("company", List.of(), false, null));
    }

    private ResolvedCreationFieldDto defaultField(String key, String value) {
        return new ResolvedCreationFieldDto(key, null, null, null, null, null, null, null,
                false, false, false, objectMapper.valueToTree(value), null, List.of());
    }

    private static void assertRefusal(String reason, org.junit.jupiter.api.function.Executable action) {
        AiAssistantLoopException refusal = assertThrows(AiAssistantLoopException.class, action);
        assertEquals(reason, refusal.detailReason());
        assertTrue(refusal.recoverable());
    }
}
