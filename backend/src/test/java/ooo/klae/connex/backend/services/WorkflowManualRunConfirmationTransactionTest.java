package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowManualRunConfirmationTransactionTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowManualEligibilityService eligibilityService;
    @Mock private WorkflowActionBindingService bindingService;
    @Mock private WorkspaceService workspaceService;
    @Spy private ObjectMapper objectMapper = JsonMapper.builder().build();

    @InjectMocks private WorkflowManualRunConfirmationTransaction transaction;

    @Test
    void rejectsFrozenScopeWithoutRunnableRecords() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(19L);
        WorkflowInvocation invocation = new WorkflowInvocation();
        invocation.setId(31L);
        invocation.setRequestedById(41);
        invocation.setWorkflowVersionId(19L);
        invocation.setScopeHash(new byte[32]);
        invocation.setReadyCount(0);
        invocation.setStatus("prepared");
        invocation.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        WorkflowVersion version = version();
        WorkflowDefinition definition = new WorkflowDefinition(1, null, List.of(), List.of());
        when(operationsMapper.getInvocationByToken(7, 11, new byte[32]))
            .thenReturn(invocation);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        when(canonicalizer.parseDefinition("{}" )).thenReturn(definition);
        when(definitionValidator.validateForMutation("company", "user", definition))
            .thenReturn(Set.of(Permission.TASK_CREATE));
        when(workspaceService.permissionsFor(7, 41))
            .thenReturn(Set.of(Permission.RULE_MANAGE, Permission.TASK_CREATE));
        when(workspaceService.getRole(7, 41)).thenReturn("admin");
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(operationsMapper.getInvocationByTokenForUpdate(
            anyInt(), anyInt(), any()))
            .thenReturn(invocation);

        assertThrows(
            ConflictException.class,
            () -> transaction.confirm(
                7, 11, 41, new byte[32], new byte[32], new byte[16]));

        verify(operationsMapper, never()).confirmInvocation(
            anyInt(), anyLong(), anyInt(), any(), any(LocalDateTime.class));
    }

    @Test
    void confirmationDurablyEnrollsTheWorkspaceForRestartRecovery() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(19L);
        WorkflowInvocation invocation = new WorkflowInvocation();
        invocation.setId(31L);
        invocation.setRequestedById(41);
        invocation.setWorkflowVersionId(19L);
        invocation.setScopeHash(new byte[32]);
        invocation.setReadyCount(1);
        invocation.setStatus("prepared");
        invocation.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        WorkflowVersion version = version();
        WorkflowDefinition definition = new WorkflowDefinition(1, null, List.of(), List.of());
        when(operationsMapper.getInvocationByToken(7, 11, new byte[32]))
            .thenReturn(invocation);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        when(canonicalizer.parseDefinition("{}" )).thenReturn(definition);
        when(definitionValidator.validateForMutation("company", "user", definition))
            .thenReturn(Set.of(Permission.TASK_CREATE));
        when(workspaceService.permissionsFor(7, 41))
            .thenReturn(Set.of(Permission.RULE_MANAGE, Permission.TASK_CREATE));
        when(workspaceService.getRole(7, 41)).thenReturn("admin");
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(operationsMapper.getInvocationByTokenForUpdate(
            anyInt(), anyInt(), any()))
            .thenReturn(invocation);
        when(operationsMapper.confirmInvocation(
            anyInt(), anyLong(), anyInt(), any(), any(LocalDateTime.class)))
            .thenReturn(1);

        transaction.confirm(
            7, 11, 41, new byte[32], new byte[32], new byte[16]);

        verify(outboxMapper).ensureWorkspaceGate(7);
    }

    private static WorkflowVersion version() {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        version.setWorkflowId(11);
        version.setWorkspaceId(7);
        version.setRecordType("company");
        version.setExecutionMode("user");
        version.setRunAsUserId(41);
        version.setCreatedById(41);
        version.setDefinitionJson("{}");
        return version;
    }
}
