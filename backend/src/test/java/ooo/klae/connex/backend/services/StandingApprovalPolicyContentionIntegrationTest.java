package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.AiStandingApprovalControlAccess;
import ooo.klae.connex.backend.ai.AiStandingApprovalControlOperations;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** The production administrator-parent chain must let another administrator reach the policy mutex. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StandingApprovalPolicyContentionIntegrationTest {
    @Autowired AiStandingApprovalControlOperations operations;
    @Autowired AiStandingApprovalControlAccess access;
    @Autowired OrganizationMapper organizations;
    @Autowired WorkspaceMapper workspaces;
    @Autowired OrgMemberMapper members;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionTemplate sql;
    @MockitoSpyBean AiStandingApprovalPolicyMapper policies;
    private Organization organization;
    private Workspace workspace;
    private User first;
    private User second;

    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString();
        organization = new Organization();
        organization.setName("Standing contention");
        organization.setSlug("standing-contention-" + suffix);
        organizations.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Standing contention");
        workspace.setSlug("standing-contention-" + suffix);
        workspaces.insert(workspace);
        TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, workspace.getId());
        first = AuthenticatedSessions.account(users, "standing-contention-first");
        second = AuthenticatedSessions.account(users, "standing-contention-second");
        for (User actor : List.of(first, second)) {
            workspaces.addMember(workspace.getId(), actor.getId(), "member");
            members.addMember(organization.getId(), actor.getId(), "admin");
        }
        policies.provision(organization.getId());
    }

    @AfterEach
    void cleanup() {
        reset(policies);
        if (workspace != null) {
            TestEntityFixtures.deleteChatPrivacy(jdbc, workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id=?", workspace.getId());
        }
        if (organization != null) {
            TestEntityFixtures.deleteOrganizationPrivacy(jdbc, organization.getId());
            jdbc.update("DELETE FROM organization WHERE id=?", organization.getId());
        }
        if (first != null) jdbc.update("DELETE FROM app_user WHERE id=?", first.getId());
        if (second != null) jdbc.update("DELETE FROM app_user WHERE id=?", second.getId());
    }

    @Test
    void twoAdministratorsReachPolicyContentionWithoutUpgradingTheOrganization() throws Exception {
        CountDownLatch firstPolicyLocked = new CountDownLatch(1);
        CountDownLatch secondAtPolicy = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger contenders = new AtomicInteger();
        AtomicLong secondConnection = new AtomicLong();
        var actual = sql.getMapper(AiStandingApprovalPolicyMapper.class);
        doAnswer(invocation -> {
            if (contenders.getAndIncrement() == 0) {
                var locked = actual.lockForUpdate(organization.getId());
                firstPolicyLocked.countDown();
                if (!releaseFirst.await(15, TimeUnit.SECONDS)) throw new AssertionError("Policy holder was not released");
                return locked;
            }
            Long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertNotNull(connectionId);
            secondConnection.set(connectionId);
            secondAtPolicy.countDown();
            return actual.lockForUpdate(organization.getId());
        }).when(policies).lockForUpdate(organization.getId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> replace(first));
            assertTrue(firstPolicyLocked.await(10, TimeUnit.SECONDS));
            var waiter = executor.submit(() -> replace(second));
            try {
                assertTrue(secondAtPolicy.await(10, TimeUnit.SECONDS), "Administrator parents must not serialize on organization X");
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, secondConnection.get(),
                        "organization_ai_standing_approval_policy", Integer.toString(organization.getId()));
            } finally {
                releaseFirst.countDown();
            }
            assertEquals("2", holder.get(15, TimeUnit.SECONDS).killEpoch());
            var stale = assertThrows(ExecutionException.class, () -> waiter.get(15, TimeUnit.SECONDS));
            assertInstanceOf(ConflictException.class, stale.getCause());
        } finally {
            releaseFirst.countDown();
        }
        assertEquals(2L, policies.get(organization.getId()).getKillEpoch());
        assertEquals(2L, policies.get(organization.getId()).getStateVersion());
    }

    private ooo.klae.connex.backend.dto.AiStandingApprovalPolicyDto replace(User actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        try {
            return access.execute(() -> operations.replace(workspace.getId(), organization.getId(), actor.getId(),
                    new AiStandingApprovalPolicyRequest(false, List.of(), 100, 10, "1")));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
