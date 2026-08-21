package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.beans.WorkflowInvocationRecord;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowInvocationResultDto;
import ooo.klae.connex.backend.dto.WorkflowManualConfirmRequest;
import ooo.klae.connex.backend.dto.WorkflowManualPreparationDto;
import ooo.klae.connex.backend.dto.WorkflowManualPrepareRequest;
import ooo.klae.connex.backend.dto.WorkflowManualScope;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowActionRetryPolicy.RetrySafety;

@ExtendWith(MockitoExtension.class)
class WorkflowManualRunServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowActionRetryPolicy retryPolicy;
    @Mock private WorkflowActionGuard actionGuard;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private WorkflowManualRunConfirmationTransaction confirmationTransaction;
    @Mock private WorkflowManualRunDispatchTransaction dispatchTransaction;
    @Mock private WorkflowRunOperationService runOperationService;
    @Mock private PersonService personService;
    @Mock private CompanyService companyService;
    @Mock private DealService dealService;
    @Mock private SegmentService segmentService;
    @Mock private SavedViewService savedViewService;
    @Mock private MemberScopeResolver memberScopeResolver;
    @Mock private WorkspaceService workspaceService;
    @Mock private SystemActor systemActor;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private WorkflowManualRunService service;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
    }

    @Test
    void idempotentConfirmDoesNotReviveCancelledInvocation() {
        when(workspaceService.getCurrentUserId()).thenReturn(41);
        WorkflowInvocation invocation = invocation("cancelled");
        invocation.setCompletedAt(LocalDateTime.of(2026, 8, 3, 10, 0));
        when(confirmationTransaction.confirm(
            anyInt(), anyInt(), anyInt(), any(), any(), any()))
            .thenReturn(invocation);
        when(operationsMapper.getInvocation(7, 11, 31L)).thenReturn(invocation);
        when(operationsMapper.getInvocationRecords(7, 31L)).thenReturn(List.of());

        WorkflowInvocationResultDto result = service.confirm(
            11,
            "fcb8df35-f2a8-42c2-af3e-69fe1d06a6ce",
            new WorkflowManualConfirmRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
                "00".repeat(32)));

        assertEquals("cancelled", result.status());
        verifyNoInteractions(dispatchTransaction);
        verify(operationsMapper, never()).markInvocationRunning(anyInt(), anyLong());
    }

    @Test
    void terminalInvocationPersistsOneStableCompletionTime() {
        WorkflowInvocation invocation = invocation("running");
        WorkflowInvocationRecord record = new WorkflowInvocationRecord();
        record.setRecordId(91);
        record.setPreviewStatus("ready");
        record.setExecutionStatus("succeeded");
        when(operationsMapper.getInvocation(7, 11, 31L)).thenReturn(invocation);
        when(operationsMapper.getInvocationForUpdate(7, 11, 31L)).thenReturn(invocation);
        when(operationsMapper.getInvocationRecords(7, 31L)).thenReturn(List.of(record));
        doAnswer(call -> {
            invocation.setStatus(call.getArgument(2, String.class));
            invocation.setCompletedAt(call.getArgument(3, LocalDateTime.class));
            return 1;
        }).when(operationsMapper).completeInvocationIfActive(
            anyInt(), anyLong(), anyString(), any(LocalDateTime.class));

        WorkflowInvocationResultDto first = service.get(11, 31L);
        WorkflowInvocationResultDto second = service.get(11, 31L);

        assertEquals("succeeded", first.status());
        assertNotNull(first.completedAt());
        assertEquals(first.completedAt(), second.completedAt());
        verify(operationsMapper).completeInvocationIfActive(
            anyInt(), anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void terminalReadReturnsConcurrentCancellationWinner() {
        WorkflowInvocation active = invocation("running");
        WorkflowInvocation cancelled = invocation("cancelled");
        LocalDateTime cancelledAt = LocalDateTime.of(2026, 8, 3, 10, 30);
        cancelled.setCompletedAt(cancelledAt);
        WorkflowInvocationRecord record = new WorkflowInvocationRecord();
        record.setRecordId(91);
        record.setPreviewStatus("ready");
        record.setExecutionStatus("succeeded");
        when(operationsMapper.getInvocation(7, 11, 31L)).thenReturn(active);
        when(operationsMapper.getInvocationForUpdate(7, 11, 31L))
            .thenReturn(cancelled);
        when(operationsMapper.getInvocationRecords(7, 31L)).thenReturn(List.of(record));
        when(operationsMapper.completeInvocationIfActive(
            anyInt(), anyLong(), anyString(), any(LocalDateTime.class)))
            .thenReturn(0);

        WorkflowInvocationResultDto result = service.get(11, 31L);

        assertEquals("cancelled", result.status());
        assertEquals(cancelledAt, result.completedAt());
    }

    @Test
    void zeroResultSavedViewSegmentWithoutNativeFiltersStaysEmpty() throws Exception {
        stubSavedView("{\"segments\":{\"match\":\"all\",\"conditions\":[]}}");

        WorkflowManualPreparationDto result = service.prepare(
            11,
            new WorkflowManualPrepareRequest(
                "saved_view", new WorkflowManualScope.SavedView(5)));

        assertEquals(0, result.exactCount());
        assertEquals(List.of("scope_empty"), result.blockers());
        verifyNoInteractions(dealService);
    }

    @Test
    void zeroResultSavedViewSegmentIntersectsRatherThanSubstitutesNativeFilters()
            throws Exception {
        stubSavedView(
            "{\"segments\":{\"match\":\"all\",\"conditions\":[]},"
                + "\"filters\":{\"status\":[\"open\"]}}");
        when(dealService.getMatchingDealIds(
            null, null, null, null, null, null, false, List.of("open"), null, null))
            .thenReturn(List.of(91, 92));

        WorkflowManualPreparationDto result = service.prepare(
            11,
            new WorkflowManualPrepareRequest(
                "saved_view", new WorkflowManualScope.SavedView(5)));

        assertEquals(0, result.exactCount());
        assertEquals(List.of("scope_empty"), result.blockers());
        verify(dealService).getMatchingDealIds(
            null, null, null, null, null, null, false, List.of("open"), null, null);
    }

    @Test
    void savedViewContactFacetNarrowsTheManualDealScope() throws Exception {
        stubSavedView("{\"filters\":{\"contact\":[\"13\",\"13\"]}}");
        when(dealService.getMatchingDealIds(
            null, null, null, null, null, List.of(13, 13), false, null, null, null))
            .thenReturn(List.of());

        WorkflowManualPreparationDto result = service.prepare(
            11,
            new WorkflowManualPrepareRequest(
                "saved_view", new WorkflowManualScope.SavedView(5)));

        assertEquals(0, result.exactCount());
        verify(dealService).getMatchingDealIds(
            null, null, null, null, null, List.of(13, 13), false, null, null, null);
    }

    @Test
    void preparationLabelsTheActorAndAccessibleSkippedRecordsWithoutLeakingMissingIds() {
        when(workspaceService.getCurrentUserId()).thenReturn(41);
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setName("Deal workflow");
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(19L);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        version.setWorkflowId(11);
        version.setWorkspaceId(7);
        version.setVersionNumber(1);
        version.setRecordType("deal");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        version.setDefinitionHash(new byte[32]);
        version.setDefinitionJson("{}");
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        User actor = new User();
        actor.setId(17);
        actor.setDisplayName("Workflow Owner");
        when(workspaceService.getMembers(7)).thenReturn(List.of(actor));
        when(workspaceService.getRole(7, 17)).thenReturn("member");
        RuleAction action = new RuleAction();
        action.setType("create_task");
        WorkflowDefinition definition = new WorkflowDefinition(
            1, "action", List.of(new WorkflowNode.Action("action", action)), List.of());
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        when(retryPolicy.safety(action)).thenReturn(RetrySafety.TRANSACTIONAL);
        WorkflowDiagnosticDto blocker = new WorkflowDiagnosticDto(
            WorkflowDiagnosticCode.ACTION_PERMISSION_MISSING,
            "action",
            null,
            null,
            Map.of("permission", "TASK_CREATE"));
        when(actionGuard.blocker(7, 17, "deal", 91, "action", action)).thenReturn(blocker);
        doAnswer(call -> {
            if (call.getArgument(2, Integer.class) == 92) {
                throw new WorkflowExecutionException(
                    "record_unavailable", "Record unavailable", true);
            }
            return null;
        }).when(recordGuard).requireAccessible(anyInt(), anyString(), anyInt());
        Deal deal = new Deal();
        deal.setId(91);
        deal.setName("Strategic Renewal");
        when(dealService.getDealById(91)).thenReturn(deal);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doAnswer(call -> {
            call.<WorkflowInvocation>getArgument(0).setId(31L);
            return null;
        }).when(operationsMapper).insertInvocation(any());

        WorkflowManualPreparationDto result = service.prepare(
            11,
            new WorkflowManualPrepareRequest(
                "record_list", new WorkflowManualScope.PageSelection(List.of(91, 92))));

        assertEquals("Workflow Owner", result.actorLabel());
        assertEquals(
            List.of(new WorkflowManualPreparationDto.Sample(91, "Strategic Renewal")),
            result.skippedSamples());
        assertEquals(1, result.expectedSkips().missingReference());
        assertEquals(1, result.expectedSkips().unsupportedContext());
    }

    @Test
    void manualRunRefusesARecordTypeThisSurfaceCannotScope() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setName("Document workflow");
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(19L);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        version.setWorkflowId(11);
        version.setWorkspaceId(7);
        version.setVersionNumber(1);
        version.setRecordType("document");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);

        BadRequestException failure = assertThrows(
            BadRequestException.class,
            () -> service.prepare(
                11,
                new WorkflowManualPrepareRequest(
                    "record", new WorkflowManualScope.SingleRecord(91))));

        assertEquals("Unsupported workflow record type", failure.getMessage());
        verify(operationsMapper, never()).insertInvocation(any());
    }

    private void stubSavedView(String configJson) throws Exception {
        when(workspaceService.getCurrentUserId()).thenReturn(41);
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setName("Saved view workflow");
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(19L);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        version.setWorkflowId(11);
        version.setWorkspaceId(7);
        version.setName("Saved view workflow");
        version.setVersionNumber(1);
        version.setRecordType("deal");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        version.setDefinitionHash(new byte[32]);
        version.setDefinitionJson("{}");
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        SavedView view = new SavedView();
        view.setId(5);
        view.setRecordType("deal");
        view.setConfig(JsonMapper.builder().build().readTree(configJson));
        when(savedViewService.getById(5)).thenReturn(view);
        if (view.getConfig().get("segments") != null) {
            when(objectMapper.treeToValue(
                view.getConfig().get("segments"), SegmentDefinition.class))
                .thenReturn(new SegmentDefinition());
            when(segmentService.evaluate(eq("deal"), any(SegmentDefinition.class)))
                .thenReturn(List.of());
        }
        WorkflowDefinition definition = new WorkflowDefinition(
            1, "trigger", List.of(), List.of());
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        when(workspaceService.getRole(7, 17)).thenReturn("member");
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doAnswer(call -> {
            call.<WorkflowInvocation>getArgument(0).setId(31L);
            return null;
        }).when(operationsMapper).insertInvocation(any());
    }

    private static WorkflowInvocation invocation(String status) {
        WorkflowInvocation invocation = new WorkflowInvocation();
        invocation.setId(31L);
        invocation.setWorkspaceId(7);
        invocation.setWorkflowId(11);
        invocation.setWorkflowVersionId(19L);
        invocation.setRequestedById(41);
        invocation.setExactCount(1);
        invocation.setReadyCount(1);
        invocation.setStatus(status);
        invocation.setCreatedAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        invocation.setConfirmedAt(LocalDateTime.of(2026, 8, 3, 9, 1));
        return invocation;
    }
}
