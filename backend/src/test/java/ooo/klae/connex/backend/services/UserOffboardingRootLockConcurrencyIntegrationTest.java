package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Real teardown/departure waits pin workspace-before-organization ordering. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserOffboardingRootLockConcurrencyIntegrationTest extends AbstractServiceTest {
    @MockitoSpyBean private TenantLifecycleControlMapper lifecycleMapper;
    @MockitoSpyBean private WorkspaceMapper roots;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private WorkspaceService workspaces;
    @Autowired private UserOffboardingService offboarding;
    @Autowired private TenantLifecycleControlOperations lifecycle;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accounts = new ArrayList<>();
    private int organizationId;
    private int workspaceId;

    @Override
    protected User newUser() {
        User user = super.newUser();
        accounts.add(user.getId());
        return user;
    }

    @BeforeEach
    void isolatedLifecycleRoots() {
        Organization org = new Organization();
        org.setName("Departure lock " + unique());
        org.setSlug("departure-lock-" + unique());
        organizationMapper.insert(org);
        organizationId = org.getId();
        orgMemberMapper.addMember(organizationId, currentUser.getId(), "owner");
        Workspace isolated = new Workspace();
        isolated.setOrgId(organizationId);
        isolated.setName("Departure lock workspace");
        isolated.setSlug("departure-lock-" + unique());
        roots.insert(isolated);
        workspaceId = isolated.getId();
        roots.addMember(workspaceId, currentUser.getId(), "owner");
        workspace = isolated;
    }

    @AfterEach
    void removeCommittedFixtures() {
        reset(lifecycleMapper, roots);
        jdbc.update("DELETE FROM tenant_operation_lease WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        jdbc.update("DELETE FROM organization WHERE id = ?", organizationId);
        accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        accounts.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"leave", "fresh"})
    void teardownHoldingWorkspaceCompletesBeforeDepartureRequestsOrganization(String path) throws Exception {
        User target = newUser();
        if ("fresh".equals(path)) {
            roots.removeMember(workspaceId, target.getId());
        }
        CountDownLatch workspaceHeld = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch requestingWorkspace = new CountDownLatch(1);
        AtomicLong departureConnection = new AtomicLong();
        TenantLifecycleControlMapper realLifecycle = sql.getMapper(TenantLifecycleControlMapper.class);
        WorkspaceMapper realRoots = sql.getMapper(WorkspaceMapper.class);
        doAnswer(call -> {
            workspaceHeld.countDown();
            assertTrue(releaseTeardown.await(30, TimeUnit.SECONDS));
            return realLifecycle.lockOrganization(call.getArgument(0));
        }).when(lifecycleMapper).lockOrganization(organizationId);
        doAnswer(call -> {
            departureConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            requestingWorkspace.countDown();
            return realRoots.lockWorkspaceOrgIdForShare(call.getArgument(0));
        }).when(roots).lockWorkspaceOrgIdForShare(anyInt());

        try (var workers = Executors.newFixedThreadPool(2)) {
            var teardown = workers.submit(() -> lifecycle.acquireWorkspaceTeardown(
                organizationId, workspaceId, currentUser.getId()));
            try {
                assertTrue(workspaceHeld.await(30, TimeUnit.SECONDS));
                var departure = workers.submit(() -> {
                    authenticateAs(target, workspaceId);
                    try {
                        if ("leave".equals(path)) {
                            workspaces.leaveWorkspace(workspaceId, target.getId());
                        } else {
                            TransactionTemplate tx = new TransactionTemplate(transactionManager);
                            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                            tx.executeWithoutResult(status -> offboarding.prepareFreshMembership(workspaceId, target.getId()));
                        }
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(requestingWorkspace.await(30, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, departureConnection.get(),
                    "workspace", Integer.toString(workspaceId));
                releaseTeardown.countDown();
                assertNotNull(teardown.get(10, TimeUnit.SECONDS));
                departure.get(10, TimeUnit.SECONDS);
                assertNull(roots.getMember(workspaceId, target.getId()));
            } finally {
                releaseTeardown.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
    }
}
