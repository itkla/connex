package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.services.AbstractServiceTest;

/** Committed, isolated MySQL fixtures for generation, cursor, and allocator assertions. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractAiAgentPrivacyIntegrationTest extends AbstractServiceTest {
    @Autowired protected AiAgentPrivacyService privacyService;
    @Autowired protected AiAgentPrivacyMapper privacyMapper;
    @Autowired protected AiChatMapper chatMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired private OrganizationMapper organizationMapper;
    private Organization organization;

    @Override
    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        String key = unique();
        organization = new Organization();
        organization.setName("Mission privacy " + key);
        organization.setSlug("mission-privacy-" + key);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Mission privacy " + key);
        workspace.setSlug("mission-privacy-" + key);
        workspaceMapper.insert(workspace);
        currentUser = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
        ooo.klae.connex.backend.support.TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, workspace.getId());
        privacyService.initialize(workspace.getId());
    }

    protected TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    protected String invalidate() {
        UUID operation = UUID.randomUUID();
        transaction().executeWithoutResult(status -> privacyService.invalidateWorkspaces(
                List.of(workspace.getId()), operation, "restriction", null, null));
        return operation.toString();
    }

    protected void finish(String operation) {
        for (int count = 0; count < 100; count++) {
            if (privacyService.batch(workspace.getId(), operation)) {
                return;
            }
        }
        throw new AssertionError("Privacy work did not complete within its fixture batch bound");
    }

    @AfterEach
    void removePrivacyFixtures() {
        if (workspace == null) {
            return;
        }
        int id = workspace.getId();
        jdbc.update("UPDATE ai_agent_run SET status='cancelled', active_segment_id=NULL, report_message_id=NULL WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_agent_action WHERE workspace_id=?", id);
        jdbc.update("UPDATE ai_agent_segment SET status='cancelled', settled_at=CURRENT_TIMESTAMP(6), turn_id=NULL WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_turn WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_tool_call WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_message WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_agent_segment WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_agent_run WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_chat_session WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_run_lease WHERE workspace_id=?", id);
        jdbc.update("UPDATE ai_agent_privacy_barrier SET latest_operation_key=NULL WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id=?", id);
        jdbc.update("DELETE FROM workspace WHERE id=?", id);
        if (currentUser != null) {
            jdbc.update("DELETE FROM app_user WHERE id=?", currentUser.getId());
        }
        if (organization != null) {
            jdbc.update("DELETE FROM ai_assistant_privacy_operation_workspace WHERE organization_id=?", organization.getId());
            jdbc.update("DELETE FROM ai_assistant_privacy_operation WHERE organization_id=?", organization.getId());
            jdbc.update("DELETE FROM ai_assistant_privacy_fence WHERE organization_id=?", organization.getId());
            jdbc.update("DELETE FROM organization WHERE id=?", organization.getId());
        }
    }
}
