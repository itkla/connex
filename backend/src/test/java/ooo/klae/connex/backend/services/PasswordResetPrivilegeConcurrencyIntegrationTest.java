package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.password.BreachedPasswordLookup;
import ooo.klae.connex.backend.password.BreachedPasswordSourceUnavailableException;
import ooo.klae.connex.backend.password.BreachedPasswordUnavailableReason;
import ooo.klae.connex.backend.tenant.Permission;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetPrivilegeConcurrencyIntegrationTest {
    private static final String TOKEN_HASH = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String EXCHANGE_OWNER = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private static final String OLD_PASSWORD = "Old-Credential-2026!";

    @Autowired private PasswordResetService passwordResetService;
    @Autowired private RoleService roleService;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private UserMapper userMapper;
    @MockitoSpyBean private RoleMapper roleMapper;
    @MockitoBean private BreachedPasswordLookup breachedPasswordLookup;
    @MockitoBean private AuditService auditService;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Organization organization;
    private Workspace workspace;
    private WorkspaceRole role;
    private User actor;
    private User user;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Reset race " + suffix);
        organization.setSlug("reset-race-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Reset race " + suffix);
        workspace.setSlug("reset-race-" + suffix);
        workspaceMapper.insert(workspace);
        actor = newUser("reset_actor_" + suffix, "Reset Actor " + suffix,
                "actor-" + suffix + "@reset-race.example.com");
        user = newUser("reset_race_" + suffix, "Reset Race " + suffix,
                suffix + "@reset-race.example.com");
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "owner");
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Reset role " + suffix);
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("REPORT_READ"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), user.getId(), role.getId());
        passwordResetTokenMapper.insert(user.getId(), TOKEN_HASH, "127.0.0.1", 30);
        assertEquals(1, passwordResetTokenMapper.claimExchange(TOKEN_HASH, EXCHANGE_OWNER));
        when(breachedPasswordLookup.isBreached(anyString())).thenThrow(
                new BreachedPasswordSourceUnavailableException(
                        BreachedPasswordUnavailableReason.TIMEOUT));
        assertFalse(userMapper.isPrivilegedAccount(user.getId()));
    }

    @AfterEach
    void cleanUp() {
        if (user != null) {
            passwordResetTokenMapper.invalidateForUser(user.getId());
            passwordResetTokenMapper.deleteExpired();
        }
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            if (role != null) {
                jdbcTemplate.update(
                        "DELETE FROM workspace_role_permission WHERE workspace_role_id = ?", role.getId());
                jdbcTemplate.update("DELETE FROM workspace_role WHERE id = ?", role.getId());
            }
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (user != null) {
            userMapper.delete(user.getId());
        }
        if (actor != null) {
            userMapper.delete(actor.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void committedCustomRolePromotionIsRevalidatedAfterWaitingForTheRoleRoot() throws Exception {
        CountDownLatch promotionLocked = new CountDownLatch(1);
        CountDownLatch resetLockAttempted = new CountDownLatch(1);
        CountDownLatch releasePromotion = new CountDownLatch(1);
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        RoleMapper realRoleMapper = sqlSessionTemplate.getMapper(RoleMapper.class);
        doAnswer(invocation -> {
            Integer locked = realRoleMapper.lockRole(workspace.getId(), role.getId());
            promotionLocked.countDown();
            if (!releasePromotion.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Role promotion did not resume");
            }
            return locked;
        }).when(roleMapper).lockRole(workspace.getId(), role.getId());
        doAnswer(invocation -> {
            resetLockAttempted.countDown();
            return realUserMapper.lockAssignedCustomRoleIds(user.getId());
        }).when(userMapper).lockAssignedCustomRoleIds(user.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceRole> promotion = executor.submit(() -> roleService.updateRole(
                    workspace.getId(),
                    actor.getId(),
                    role.getId(),
                    role.getName(),
                    List.of(Permission.MEMBER_MANAGE.name())));
            assertTrue(promotionLocked.await(10, TimeUnit.SECONDS));

            Future<BreachedPasswordCheckUnavailableException> reset = executor.submit(() -> assertThrows(
                    BreachedPasswordCheckUnavailableException.class,
                    () -> passwordResetService.resetPasswordByHash(
                            TOKEN_HASH, "Replacement-Credential-2026!")));
            assertTrue(resetLockAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> reset.get(500, TimeUnit.MILLISECONDS));
            releasePromotion.countDown();

            promotion.get(20, TimeUnit.SECONDS);
            assertEquals("newPassword", reset.get(20, TimeUnit.SECONDS).getField());
        } finally {
            releasePromotion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertTrue(userMapper.isPrivilegedAccount(user.getId()));
        assertTrue(passwordEncoder.matches(
                OLD_PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));
        assertTrue(passwordResetTokenMapper.existsExchangedRedeemableByHash(TOKEN_HASH));
    }

    private User newUser(String username, String displayName, String email) {
        User created = new User();
        created.setUsername(username);
        created.setDisplayName(displayName);
        created.setEmail(email);
        created.setPasswordHash(passwordEncoder.encode(OLD_PASSWORD));
        created.setTimezone("UTC");
        userMapper.insert(created);
        return created;
    }
}
