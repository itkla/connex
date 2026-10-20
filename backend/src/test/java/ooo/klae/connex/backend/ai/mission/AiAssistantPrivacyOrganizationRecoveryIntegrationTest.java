package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises empty organization recovery and bounded notification authority against real control SQL. */
class AiAssistantPrivacyOrganizationRecoveryIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private AiAssistantPrivacyService authority;
    @Autowired private AiAssistantPrivacyControlMapper control;
    @Autowired private OrganizationMapper organizations;
    @Autowired private TenantWorkScope workScope;

    @Test
    void emptyV236BootstrapCompletesWithoutAnyWorkspaceRoute() {
        Organization empty = organization();
        try {
            workScope.unrouted(() -> {
                transaction().executeWithoutResult(status -> authority.provision(empty.getId()));
                return null;
            });
            assertTrue(control.incompleteOrganizationIdsPage(0, 100).contains(empty.getId()));

            workScope.unrouted(() -> {
                authority.reconcileOrganizationCompletion(empty.getId());
                return null;
            });

            assertNotNull(control.getFence(empty.getId()).getBootstrapCompletedAt());
            assertEquals(0, control.countIncompleteOperations(empty.getId()));
        } finally {
            deleteOrganization(empty.getId());
        }
    }

    @Test
    void organizationCompletionRecoversAfterItsLastVerifiedWorkspaceRootDisappears() {
        Organization next = organization();
        Workspace last = new Workspace();
        last.setOrgId(next.getId());
        last.setName("Last workspace");
        last.setSlug("last-workspace-" + unique());
        try {
            workScope.unrouted(() -> {
                transaction().executeWithoutResult(status -> authority.provision(next.getId()));
                return null;
            });
            workspaceMapper.insert(last);
            String key = control.getFence(next.getId()).getBootstrapOperationId();
            var previous = tenantContext.snapshot();
            try {
                tenantContext.set(last.getId(), next.getId(), currentUser.getId(), "system", null);
                workScope.inWorkspace(last.getId(), () -> {
                    privacyService.initialize(last.getId());
                    for (int batch = 0; batch < 100; batch++) {
                        if (privacyService.batch(last.getId(), key)) return;
                    }
                    throw new AssertionError("Empty workspace bootstrap did not verify");
                });
            } finally {
                tenantContext.restore(previous);
            }
            jdbc.update("UPDATE ai_assistant_privacy_operation SET completed_at=NULL WHERE organization_id=?", next.getId());
            jdbc.update("UPDATE ai_assistant_privacy_fence SET bootstrap_completed_at=NULL WHERE organization_id=?", next.getId());
            jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id=?", last.getId());
            jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", last.getId());
            jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", last.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", last.getId());

            workScope.unrouted(() -> {
                authority.reconcileOrganizationCompletion(next.getId());
                return null;
            });

            assertEquals(0, control.countIncompleteOperations(next.getId()));
            assertNotNull(control.getFence(next.getId()).getBootstrapCompletedAt());
            assertNotNull(control.deletedWorkspaceVerifiedAt(next.getId(), key, last.getId()));
        } finally {
            if (last.getId() != 0) {
                jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id=?", last.getId());
                jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", last.getId());
                jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", last.getId());
                jdbc.update("DELETE FROM workspace WHERE id=?", last.getId());
            }
            deleteOrganization(next.getId());
        }
    }

    @Test
    void unrelatedTenantWorkspacesDoNotExpandRecipientAuthority() {
        var before = authority.notificationWorkspaceGenerations(currentUser.getId(), null);
        List<Organization> unrelated = new ArrayList<>();
        List<Workspace> roots = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                Organization next = organization();
                unrelated.add(next);
                var root = new Workspace();
                root.setOrgId(next.getId());
                root.setName("Unrelated notification authority");
                root.setSlug("unrelated-privacy-" + unique());
                workspaceMapper.insert(root);
                roots.add(root);
                workScope.unrouted(() -> {
                    transaction().executeWithoutResult(status -> authority.provision(next.getId()));
                    return null;
                });
            }

            assertEquals(before, authority.notificationWorkspaceGenerations(currentUser.getId(), null));
            assertEquals(List.of(workspace.getId()), authority.notificationWorkspaceGenerations(currentUser.getId(), null)
                    .stream().map(privacy -> privacy.workspaceId()).toList());
        } finally {
            for (var root : roots) jdbc.update("DELETE FROM workspace WHERE id=?", root.getId());
            for (var next : unrelated) deleteOrganization(next.getId());
        }
    }

    private Organization organization() {
        var next = new Organization();
        next.setName("Organization reconciliation " + unique());
        next.setSlug("organization-reconciliation-" + unique());
        organizations.insert(next);
        return next;
    }

    private void deleteOrganization(int organizationId) {
        jdbc.update("DELETE FROM ai_assistant_privacy_operation_workspace WHERE organization_id=?", organizationId);
        jdbc.update("DELETE FROM ai_assistant_privacy_operation WHERE organization_id=?", organizationId);
        jdbc.update("DELETE FROM ai_assistant_privacy_fence WHERE organization_id=?", organizationId);
        jdbc.update("DELETE FROM organization WHERE id=?", organizationId);
    }
}
