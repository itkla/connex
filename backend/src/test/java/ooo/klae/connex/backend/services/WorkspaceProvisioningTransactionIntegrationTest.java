package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

/**
 * Self-service workspace creation provisions in one transaction (#1982). It used to call its
 * {@code @Transactional} helper on {@code this}, which bypasses the proxy, so every statement
 * autocommitted on its own and a failure after the organization insert left an organization and
 * its founding owner behind without a workspace.
 *
 * <p>The bean overrides match {@code OwnerChangeConcurrencyIntegrationTest}'s exactly, field names
 * included, so the classes can share one cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkspaceProvisioningTransactionIntegrationTest {
    @Autowired private WorkspaceService workspaceService;
    @Autowired private UserMapper userMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoSpyBean private NotificationMapper notificationMapper;
    @MockitoSpyBean private DealMapper dealMapper;
    @MockitoSpyBean private PersonMapper personMapper;
    @MockitoSpyBean private CompanyMapper companyMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private NotificationStateVersionService notificationStateVersionService;
    @MockitoBean private ReferenceService referenceService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private User owner;
    private String name;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        owner = new User();
        owner.setUsername("provisioning-" + unique);
        owner.setDisplayName("provisioning-" + unique);
        owner.setEmail("provisioning-" + unique + "@example.com");
        owner.setPasswordHash("hash-provisioning-" + unique);
        owner.setTimezone("UTC");
        userMapper.insert(owner);
        name = "Provisioning " + unique;
    }

    @AfterEach
    void cleanUp() {
        if (owner == null) {
            return;
        }
        List<Integer> orgIds = jdbcTemplate.queryForList(
            "SELECT org_id FROM org_member WHERE user_id = ?", Integer.class, owner.getId());
        jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", owner.getId());
        for (int orgId : orgIds) {
            jdbcTemplate.update("DELETE FROM workspace WHERE org_id = ?", orgId);
            jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", orgId);
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
    }

    /** A failure after the new organization and its founding owner are written leaves nothing behind. */
    @Test
    void aFailureAfterTheOrganizationInsertLeavesNothingBehind() {
        doThrow(new IllegalStateException("workspace insert failed"))
            .when(workspaceMapper).insert(any(Workspace.class));

        assertThrows(IllegalStateException.class, () -> workspaceService.createWorkspace(name, owner.getId()));

        assertEquals(0, count("SELECT COUNT(*) FROM organization WHERE name = ?", name));
        assertEquals(0, count("SELECT COUNT(*) FROM org_member WHERE user_id = ?", owner.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM workspace_member WHERE user_id = ?", owner.getId()));
    }

    /** A successful creation commits the organization, its founding owner and the owned workspace together. */
    @Test
    void aSuccessfulCreationCommitsTheOrganizationOwnerAndWorkspace() {
        WorkspaceMembershipDto created = workspaceService.createWorkspace(name, owner.getId());

        assertEquals(1, count("SELECT COUNT(*) FROM org_member WHERE user_id = ? AND org_id = ?",
            owner.getId(), created.getOrgId()));
        assertEquals(1, count(
            "SELECT COUNT(*) FROM workspace_member WHERE user_id = ? AND workspace_id = ? AND role = 'owner'",
            owner.getId(), created.getId()));
    }

    private int count(String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }
}
