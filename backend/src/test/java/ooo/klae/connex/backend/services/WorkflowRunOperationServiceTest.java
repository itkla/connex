package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowRunOperationDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowRunOperationServiceTest {

    private static final int REQUESTER_ID = 29;
    private static final long PINNED_VERSION_ID = 19L;
    private static final long ACTIVE_VERSION_ID = 23L;
    private static final Set<Permission> PERMITTED = Set.of(
        Permission.RULE_MANAGE, Permission.NOTE_CREATE);
    private static final Set<Permission> MANAGEMENT_ONLY = Set.of(Permission.RULE_MANAGE);

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowOperationsMapper workflowOperationsMapper;
    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowRuntimeClaimService claimService;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowRuntimeProperties properties;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;

    private WorkflowRunOperationService service;
    private WorkflowRun run;
    private final WorkflowDefinition definition = new WorkflowDefinition(
        1, "trigger", List.of(), List.of());

    @BeforeEach
    void setUp() {
        service = new WorkflowRunOperationService(
            workflowMapper,
            workflowOperationsMapper,
            runMapper,
            workflowVersionMapper,
            claimService,
            definitionValidator,
            properties,
            workspaceService,
            auditService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setName("Workflow");
        workflow.setActiveVersionId(ACTIVE_VERSION_ID);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(PINNED_VERSION_ID);
        run.setCurrentNodeId("action");
        when(runMapper.getByIdForUpdate(7, 31L)).thenReturn(run);
    }

    @Test
    void queuedCancellationTerminatesImmediatelyAndAuditsOnce() {
        run.setStatus("queued");
        when(runMapper.cancelImmediately(eq(7), eq(31L), any())).thenReturn(1);

        WorkflowRunOperationDto result = service.cancel(11, "canonical-31");

        assertEquals("cancelled", result.status());
        assertTrue(result.cancellationRequested());
        verify(runMapper).cancelImmediately(eq(7), eq(31L), any());
        verify(auditService).recordStrict(
            eq("workflow.run.cancel"),
            eq("workflow"),
            eq(11),
            eq("Workflow"),
            eq("Workflow run cancellation requested"),
            any());
    }

    @Test
    void duplicateRunningCancellationDoesNotWriteOrAuditAgain() {
        run.setStatus("running");
        run.setCancelRequestedAt(java.time.LocalDateTime.of(2026, 8, 2, 12, 0));

        WorkflowRunOperationDto result = service.cancel(11, "canonical-31");

        assertEquals("running", result.status());
        assertTrue(result.cancellationRequested());
        verify(runMapper, never()).requestCancellation(anyInt(), anyLong(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void manualRetryRequiresPersistedRetrySafety() {
        run.setStatus("intervention_required");
        stubPinnedVersion("user", false, PERMITTED);
        WorkflowStepRun step = failedStep("none");
        when(runMapper.getStepByNodeForUpdate(7, 31L, "action")).thenReturn(step);

        assertThrows(
            ConflictException.class,
            () -> service.retry(11, "canonical-31"));

        verify(runMapper, never()).scheduleManualRetry(anyInt(), anyLong(), any());
    }

    @Test
    void safeManualRetryReusesTheFailedStep() {
        run.setStatus("intervention_required");
        stubPinnedVersion("user", false, PERMITTED);
        WorkflowStepRun step = failedStep("transactional");
        when(properties.maxActionAttempts()).thenReturn(3);
        when(runMapper.getStepByNodeForUpdate(7, 31L, "action")).thenReturn(step);
        when(runMapper.scheduleManualRetry(7, 31L, "action")).thenReturn(1);

        WorkflowRunOperationDto result = service.retry(11, "canonical-31");

        assertEquals("waiting", result.status());
        assertFalse(result.cancellationRequested());
        verify(runMapper).scheduleManualRetry(7, 31L, "action");
        verify(workflowVersionMapper).getById(7, 11, PINNED_VERSION_ID);
        verify(workflowVersionMapper, never()).getById(7, 11, ACTIVE_VERSION_ID);
        verify(workflowOperationsMapper).resolveOpenInterventionsForRun(7, 31L, "resolved");
        verify(auditService).recordStrict(
            eq("workflow.run.retry"),
            eq("workflow"),
            eq(11),
            eq("Workflow"),
            eq("Workflow run retry scheduled"),
            any());
    }

    @Test
    void retryLocksCallerAuthorityBeforeTheRunAndAuthorizesBeforeTheStep() {
        run.setStatus("intervention_required");
        stubPinnedVersion("user", false, PERMITTED);
        when(properties.maxActionAttempts()).thenReturn(3);
        when(runMapper.getStepByNodeForUpdate(7, 31L, "action"))
            .thenReturn(failedStep("transactional"));
        when(runMapper.scheduleManualRetry(7, 31L, "action")).thenReturn(1);

        service.retry(11, "canonical-31");

        InOrder order = inOrder(workspaceService, runMapper, definitionValidator);
        order.verify(workspaceService).isLockedBuiltInAdministrator(7, REQUESTER_ID);
        order.verify(workspaceService).lockedMemberPermissionsFor(7, REQUESTER_ID);
        order.verify(runMapper).getByIdForUpdate(7, 31L);
        order.verify(definitionValidator).validateForManualDispatch(
            "person", "user", definition, false, PERMITTED);
        order.verify(runMapper).getStepByNodeForUpdate(7, 31L, "action");
        order.verify(runMapper).scheduleManualRetry(7, 31L, "action");
        verify(workspaceService, never()).permissionsFor(anyInt(), anyInt());
        verify(workspaceService, never()).requirePermission(any(Permission.class));
    }

    @Test
    void retryRefusesManagerMissingAnActionPermissionOfThePinnedVersion() {
        run.setStatus("intervention_required");
        stubPinnedVersion("user", false, MANAGEMENT_ONLY);
        when(definitionValidator.validateForManualDispatch(
                "person", "user", definition, false, MANAGEMENT_ONLY))
            .thenThrow(new ForbiddenException("Requires the NOTE_CREATE permission in this workspace"));

        assertThrows(ForbiddenException.class, () -> service.retry(11, "canonical-31"));

        assertNoRetryEffect();
    }

    @Test
    void retryOfSystemRunRefusesCallerWhoIsNotALockedBuiltInAdministrator() {
        run.setStatus("intervention_required");
        stubPinnedVersion("system", false, PERMITTED);
        when(definitionValidator.validateForManualDispatch(
                "person", "system", definition, false, PERMITTED))
            .thenThrow(new ForbiddenException("Requires ADMIN role in this workspace"));

        assertThrows(ForbiddenException.class, () -> service.retry(11, "canonical-31"));

        assertNoRetryEffect();
    }

    @Test
    void retryRefusesAPinnedVersionThatFailsItsIntegrityCheck() {
        run.setStatus("intervention_required");
        stubCallerAuthority(false, PERMITTED);
        WorkflowVersion version = version("user");
        when(workflowVersionMapper.getById(7, 11, PINNED_VERSION_ID)).thenReturn(version);
        when(claimService.intactDefinition(version)).thenReturn(Optional.empty());

        ConflictException exception = assertThrows(
            ConflictException.class, () -> service.retry(11, "canonical-31"));

        assertEquals("Workflow run version failed its integrity check", exception.getMessage());
        verify(definitionValidator, never()).validateForManualDispatch(
            anyString(), anyString(), any(), anyBoolean(), any());
        assertNoRetryEffect();
    }

    private void assertNoRetryEffect() {
        verify(runMapper, never()).getStepByNodeForUpdate(anyInt(), anyLong(), any());
        verify(runMapper, never()).scheduleManualRetry(anyInt(), anyLong(), any());
        verify(workflowOperationsMapper, never())
            .resolveOpenInterventionsForRun(anyInt(), anyLong(), any());
        verifyNoInteractions(auditService);
    }

    private void stubPinnedVersion(
            String executionMode, boolean lockedBuiltInAdministrator, Set<Permission> permissions) {
        stubCallerAuthority(lockedBuiltInAdministrator, permissions);
        WorkflowVersion version = version(executionMode);
        when(workflowVersionMapper.getById(7, 11, PINNED_VERSION_ID)).thenReturn(version);
        when(claimService.intactDefinition(version)).thenReturn(Optional.of(definition));
    }

    private void stubCallerAuthority(
            boolean lockedBuiltInAdministrator, Set<Permission> permissions) {
        when(workspaceService.getCurrentUserId()).thenReturn(REQUESTER_ID);
        when(workspaceService.isLockedBuiltInAdministrator(7, REQUESTER_ID))
            .thenReturn(lockedBuiltInAdministrator);
        when(workspaceService.lockedMemberPermissionsFor(7, REQUESTER_ID)).thenReturn(permissions);
    }

    private static WorkflowVersion version(String executionMode) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(PINNED_VERSION_ID);
        version.setWorkspaceId(7);
        version.setWorkflowId(11);
        version.setRecordType("person");
        version.setExecutionMode(executionMode);
        return version;
    }

    private static WorkflowStepRun failedStep(String retrySafety) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setNodeType("action");
        step.setStatus("failed");
        step.setRetrySafety(retrySafety);
        step.setAttemptCount(1);
        return step;
    }
}
