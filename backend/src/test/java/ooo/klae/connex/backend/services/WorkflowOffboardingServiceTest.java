package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkflowOffboardingService.OffboardingPlan;

@ExtendWith(MockitoExtension.class)
class WorkflowOffboardingServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private WorkspaceMapper workspaceMapper;

    @Test
    void candidateWorkspaceRootsLockInAscendingOrderBeforeMemberLocksCanBegin() {
        WorkflowOffboardingService service = service();
        Workflow workflow = workflow(7, 20, 200, 300L, 9, null, true);
        Rule rule = rule(3, 100, 9, null, true);
        OffboardingPlan plan = new OffboardingPlan(
            List.of(workflow), List.of(), List.of(rule));
        when(workspaceMapper.lockWorkspaceForShare(3)).thenReturn(3);
        when(workspaceMapper.lockWorkspaceForShare(7)).thenReturn(7);

        service.lockWorkspaceRoots(plan);

        InOrder order = inOrder(workspaceMapper);
        order.verify(workspaceMapper).lockWorkspaceForShare(3);
        order.verify(workspaceMapper).lockWorkspaceForShare(7);
    }

    @Test
    void pairedAndUnpairedRulesDisableOnceAfterSortedExactPointLocks() {
        WorkflowOffboardingService service = service();
        Workflow workflow = workflow(7, 20, 200, 300L, 9, null, true);
        WorkflowVersion version = version(7, 20, 300L, 9, 9, null);
        Rule linked = rule(7, 200, null, null, true);
        Rule unpaired = rule(5, 100, null, 9, true);
        OffboardingPlan plan = new OffboardingPlan(
            List.of(workflow), List.of(version), List.of(linked, unpaired));
        when(workflowMapper.getByIdForUpdate(7, 20)).thenReturn(workflow);
        when(workflowVersionMapper.getByIdForUpdate(7, 20, 300L)).thenReturn(version);
        when(ruleMapper.getByIdForUpdate(5, 100)).thenReturn(unpaired);
        when(ruleMapper.getByIdForUpdate(7, 200)).thenReturn(linked);
        when(workflowMapper.disableForOffboarding(7, 20)).thenReturn(1);
        when(ruleMapper.updateEnabled(5, 100, false)).thenReturn(1);
        when(ruleMapper.updateEnabled(7, 200, false)).thenReturn(1);

        service.offboard(9, plan);

        InOrder locks = inOrder(workflowMapper, workflowVersionMapper, ruleMapper);
        locks.verify(workflowMapper).getByIdForUpdate(7, 20);
        locks.verify(workflowVersionMapper).getByIdForUpdate(7, 20, 300L);
        locks.verify(ruleMapper).getByIdForUpdate(5, 100);
        locks.verify(ruleMapper).getByIdForUpdate(7, 200);
        InOrder disables = inOrder(workflowMapper, ruleMapper);
        disables.verify(workflowMapper).disableForOffboarding(7, 20);
        disables.verify(ruleMapper).updateEnabled(5, 100, false);
        disables.verify(ruleMapper).updateEnabled(7, 200, false);
        verify(workflowMapper).redactUserReferences(7, 20, 9);
        verify(ruleMapper).redactUserReferences(5, 100, 9);
        verify(ruleMapper).redactUserReferences(7, 200, 9);
    }

    @Test
    void updatedByOnlyReferenceIsRedactedWithoutDisablingThePairedRuntime() {
        WorkflowOffboardingService service = service();
        Workflow workflow = workflow(7, 20, 200, 300L, 41, null, true);
        workflow.setUpdatedById(9);
        WorkflowVersion version = version(7, 20, 300L, 41, 41, 41);
        Rule linked = rule(7, 200, 41, 41, true);
        OffboardingPlan plan = new OffboardingPlan(
            List.of(workflow), List.of(version), List.of(linked));
        when(workflowMapper.getByIdForUpdate(7, 20)).thenReturn(workflow);
        when(workflowVersionMapper.getByIdForUpdate(7, 20, 300L)).thenReturn(version);
        when(ruleMapper.getByIdForUpdate(7, 200)).thenReturn(linked);

        service.offboard(9, plan);

        verify(workflowMapper, never()).disableForOffboarding(7, 20);
        verify(ruleMapper, never()).updateEnabled(7, 200, false);
        verify(workflowMapper).redactUserReferences(7, 20, 9);
    }

    @Test
    void exactLockedRowsAreRevalidatedBeforeAnyDisableOrRedaction() {
        WorkflowOffboardingService service = service();
        Workflow discovered = workflow(7, 20, null, null, 9, null, true);
        Workflow changed = workflow(7, 20, null, null, 41, null, true);
        OffboardingPlan plan = new OffboardingPlan(
            List.of(discovered), List.of(), List.of());
        when(workflowMapper.getByIdForUpdate(7, 20)).thenReturn(changed);

        assertThrows(ConflictException.class, () -> service.offboard(9, plan));

        verify(workflowMapper, never()).disableForOffboarding(7, 20);
        verify(workflowMapper, never()).redactUserReferences(7, 20, 9);
    }

    private WorkflowOffboardingService service() {
        return new WorkflowOffboardingService(
            workflowMapper, workflowVersionMapper, ruleMapper, workspaceMapper);
    }

    private static Workflow workflow(
            int workspaceId,
            int id,
            Integer ruleId,
            Long versionId,
            Integer creatorId,
            Integer runAsId,
            boolean enabled) {
        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(workspaceId);
        workflow.setId(id);
        workflow.setLegacyRuleId(ruleId);
        workflow.setActiveVersionId(versionId);
        workflow.setCreatedById(creatorId);
        workflow.setUpdatedById(creatorId);
        workflow.setDraftRunAsUserId(runAsId);
        workflow.setEnabled(enabled);
        return workflow;
    }

    private static WorkflowVersion version(
            int workspaceId,
            int workflowId,
            long id,
            Integer creatorId,
            Integer runAsId,
            Integer publisherId) {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(workspaceId);
        version.setWorkflowId(workflowId);
        version.setId(id);
        version.setCreatedById(creatorId);
        version.setRunAsUserId(runAsId);
        version.setPublishedById(publisherId);
        return version;
    }

    private static Rule rule(
            int workspaceId,
            int id,
            Integer creatorId,
            Integer runAsId,
            boolean enabled) {
        Rule rule = new Rule();
        rule.setWorkspaceId(workspaceId);
        rule.setId(id);
        rule.setCreatedById(creatorId);
        rule.setRunAsUserId(runAsId);
        rule.setEnabled(enabled);
        return rule;
    }
}
