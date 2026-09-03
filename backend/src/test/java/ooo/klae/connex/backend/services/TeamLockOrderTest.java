package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Team;
import ooo.klae.connex.backend.beans.TeamMember;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.TeamRequest;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.TeamMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies manager replacement and account erasure share the team-parent lock order in MySQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamLockOrderTest {
    @Autowired private TeamService teamService;
    @Autowired private UserOffboardingService userOffboardingService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private RoleMapper roleMapper;
    @MockitoSpyBean private TeamMapper teamMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private AuditService auditService;

    private Organization organization;
    private Workspace workspace;
    private User actor;
    private User outgoingManager;
    private User incomingManager;
    private Team team;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Team lock order " + suffix);
        organization.setSlug("team-lock-order-" + suffix);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Team lock order " + suffix);
        workspace.setSlug("team-lock-order-" + suffix);
        workspaceMapper.insert(workspace);

        actor = user("team_lock_actor_" + suffix);
        outgoingManager = user("team_lock_outgoing_" + suffix);
        incomingManager = user("team_lock_incoming_" + suffix);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), outgoingManager.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), incomingManager.getId(), "member");

        tenantContext.set(
            workspace.getId(), organization.getId(), actor.getId(), "member", null);
        WorkspaceRole actorRole = new WorkspaceRole();
        actorRole.setWorkspaceId(workspace.getId());
        actorRole.setName("Team lock manager " + suffix);
        roleMapper.insertRole(actorRole);
        roleMapper.insertPermissions(
            workspace.getId(), actorRole.getId(), List.of(Permission.TEAM_MANAGE.name()));
        workspaceMapper.setMemberCustomRole(
            workspace.getId(), actor.getId(), actorRole.getId());

        team = new Team();
        team.setWorkspaceId(workspace.getId());
        team.setName("Lock order team " + suffix);
        team.setManagerUserId(outgoingManager.getId());
        teamMapper.insert(team);
        teamMapper.upsertMember(
            member(outgoingManager.getId(), "manager"));
        teamMapper.upsertMember(
            member(incomingManager.getId(), "member"));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM team_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM team WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (actor != null) {
            userMapper.delete(actor.getId());
        }
        if (outgoingManager != null) {
            userMapper.delete(outgoingManager.getId());
        }
        if (incomingManager != null) {
            userMapper.delete(incomingManager.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void managerReplacementAndAccountErasureSerializeOnTheTeamParent() throws Exception {
        TeamMapper realTeamMapper = sqlSessionTemplate.getMapper(TeamMapper.class);
        CountDownLatch replacementLockedTeam = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        CountDownLatch erasureRequestedTeamLock = new CountDownLatch(1);
        CountDownLatch erasureAcquiredTeamLock = new CountDownLatch(1);
        AtomicInteger teamLockAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            int attempt = teamLockAttempts.incrementAndGet();
            if (attempt == 1) {
                Team locked = realTeamMapper.getByIdForUpdate(workspace.getId(), team.getId());
                replacementLockedTeam.countDown();
                if (!releaseReplacement.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Manager replacement was not released");
                }
                return locked;
            }
            erasureRequestedTeamLock.countDown();
            Team locked = realTeamMapper.getByIdForUpdate(workspace.getId(), team.getId());
            erasureAcquiredTeamLock.countDown();
            return locked;
        }).when(teamMapper).getByIdForUpdate(workspace.getId(), team.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> replacement = executor.submit(this::replaceManager);
            assertTrue(replacementLockedTeam.await(10, TimeUnit.SECONDS));
            Future<?> erasure = executor.submit(this::eraseOutgoingManagerReferences);
            assertTrue(erasureRequestedTeamLock.await(10, TimeUnit.SECONDS));
            assertFalse(erasureAcquiredTeamLock.await(750, TimeUnit.MILLISECONDS));
            releaseReplacement.countDown();
            replacement.get(20, TimeUnit.SECONDS);
            erasure.get(20, TimeUnit.SECONDS);
        } finally {
            releaseReplacement.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        Team persisted = teamMapper.getById(workspace.getId(), team.getId());
        assertNotNull(persisted);
        assertEquals(incomingManager.getId(), persisted.getManagerUserId());
        assertFalse(teamMapper.hasMember(
            workspace.getId(), team.getId(), outgoingManager.getId()));
        assertEquals(
            "manager",
            teamMapper.getMembersForTeams(workspace.getId(), List.of(team.getId())).stream()
                .filter(seat -> seat.getUserId() == incomingManager.getId())
                .findFirst()
                .orElseThrow()
                .getRole());
        InOrder managerLockOrder = inOrder(teamMapper);
        managerLockOrder.verify(teamMapper).getByIdForUpdate(workspace.getId(), team.getId());
        managerLockOrder.verify(teamMapper).lockMember(
            workspace.getId(), team.getId(), incomingManager.getId());
    }

    private void replaceManager() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(
            workspace.getId(), organization.getId(), actor.getId(), "member", null);
        try {
            teamService.update(
                team.getId(),
                new TeamRequest(team.getName(), null, incomingManager.getId()));
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private void eraseOutgoingManagerReferences() {
        tenantWorkScope.inWorkspace(workspace.getId(), () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> userOffboardingService.eraseOrgDataReferences(outgoingManager.getId()));
            return null;
        });
    }

    private TeamMember member(int userId, String role) {
        TeamMember member = new TeamMember();
        member.setWorkspaceId(workspace.getId());
        member.setTeamId(team.getId());
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash("hash-" + username);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
