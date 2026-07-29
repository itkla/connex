package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.dto.OrganizationLifecycleRef;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;

/** Verifies APPI, SSO, and audit work serialize with tenant teardown fences. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataSubjectRequestLifecycleConcurrencyIntegrationTest {
    @Autowired private DataSubjectRequestControlOperations requestOperations;
    @Autowired private DataSubjectRequestService requestService;
    @Autowired private TenantLifecycleControlOperations lifecycleOperations;
    @Autowired private SsoLoginService ssoLoginService;
    @Autowired private SsoLinkService ssoLinkService;
    @Autowired private AuditIntegrityService auditIntegrityService;
    @Autowired private FederatedIdentityMapper federatedIdentityMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private SsoConnectionMapper ssoConnectionMapper;
    @Autowired private SsoDomainMapper ssoDomainMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private TenantLifecycleControlMapper lifecycleMapperSpy;
    @MockitoSpyBean private DataSubjectRequestMapper requestMapperSpy;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoBean private AuditService auditService;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Organization organization;
    private Workspace workspace;
    private User owner;
    private int personId;
    private final List<String> provisionedUserEmails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("DSR race " + unique);
        organization.setSlug("dsr-race-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("DSR race " + unique);
        workspace.setSlug("dsr-race-" + unique);
        workspaceMapper.insert(workspace);

        owner = new User();
        owner.setUsername("dsr-race-" + unique);
        owner.setDisplayName("DSR Race Owner");
        owner.setEmail("dsr-race-" + unique + "@example.test");
        userMapper.insert(owner);
        orgMemberMapper.addMember(organization.getId(), owner.getId(), "owner");
        String personEmail = "dsr-race-person-" + unique + "@example.test";
        jdbcTemplate.update(
            "INSERT INTO person (workspace_id, name, email) VALUES (?, ?, ?)",
            workspace.getId(),
            "DSR Race Subject",
            personEmail);
        personId = jdbcTemplate.queryForObject(
            "SELECT id FROM person WHERE workspace_id = ? AND email = ?",
            Integer.class,
            workspace.getId(),
            personEmail);
    }

    @AfterEach
    void cleanUp() {
        if (organization == null) {
            return;
        }
        jdbcTemplate.update(
            "DELETE FROM tenant_operation_lease WHERE org_id = ?",
            organization.getId());
        jdbcTemplate.update(
            "DELETE FROM data_subject_request WHERE org_id = ?",
            organization.getId());
        jdbcTemplate.update(
            "DELETE FROM federated_identity WHERE org_id = ?",
            organization.getId());
        jdbcTemplate.update(
            "DELETE FROM sso_domain WHERE org_id = ?",
            organization.getId());
        jdbcTemplate.update(
            "DELETE FROM sso_connection WHERE org_id = ?",
            organization.getId());
        jdbcTemplate.update(
            "DELETE FROM sso_link_challenge WHERE user_id = ?",
            owner.getId());
        jdbcTemplate.update(
            "DELETE FROM person WHERE workspace_id = ?",
            workspace.getId());
        jdbcTemplate.update(
            "DELETE FROM workspace_member WHERE workspace_id = ?",
            workspace.getId());
        jdbcTemplate.update(
            "DELETE FROM workspace WHERE id = ?",
            workspace.getId());
        jdbcTemplate.update(
            "DELETE FROM org_member WHERE org_id = ?",
            organization.getId());
        jdbcTemplate.update(
            "DELETE FROM organization WHERE id = ?",
            organization.getId());
        for (String email : provisionedUserEmails) {
            jdbcTemplate.update("DELETE FROM app_user WHERE email = ?", email);
        }
        provisionedUserEmails.clear();
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
    }

    @Test
    void linkedSubjectMutationRetainsControlAndPersonLocksAndMakesTeardownWait()
            throws Exception {
        DataSubjectRequestMapper realRequest =
            sqlSessionTemplate.getMapper(DataSubjectRequestMapper.class);
        TenantLifecycleControlMapper realLifecycle =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch mutationLocked = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch teardownAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            mutationLocked.countDown();
            await(releaseMutation);
            return realRequest.insert(invocation.getArgument(0));
        }).when(requestMapperSpy).insert(
            org.mockito.ArgumentMatchers.any(DataSubjectRequest.class));
        doAnswer(invocation -> {
            teardownEntered.countDown();
            WorkspaceLifecycleRef result =
                realLifecycle.lockWorkspaceInOrg(workspace.getId());
            teardownAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceInOrg(workspace.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> mutation = executor.submit(() ->
                requestService.create(
                    organization.getId(),
                    owner.getId(),
                    upsertRequest(workspace.getId(), personId)));
            assertTrue(mutationLocked.await(10, TimeUnit.SECONDS));
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.acquireWorkspaceTeardown(
                    organization.getId(),
                    workspace.getId(),
                    owner.getId()));
            assertTrue(teardownEntered.await(10, TimeUnit.SECONDS));
            assertFalse(teardownAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseMutation.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            assertConflict(teardown);
        } finally {
            releaseMutation.countDown();
        }
    }

    @Test
    void workspaceTeardownWinnerMakesLinkedMutationWaitAndThenRefuse() throws Exception {
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch teardownLocked = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch mutationAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkspaceLifecycleRef result = real.lockWorkspaceInOrg(workspace.getId());
            teardownLocked.countDown();
            await(releaseTeardown);
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceInOrg(workspace.getId());
        doAnswer(invocation -> {
            mutationEntered.countDown();
            WorkspaceLifecycleRef result = real.lockWorkspaceForShare(workspace.getId());
            mutationAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceForShare(workspace.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AcquiredWorkspace> teardown = executor.submit(() ->
                lifecycleOperations.acquireWorkspaceTeardown(
                    organization.getId(),
                    workspace.getId(),
                    owner.getId()));
            assertTrue(teardownLocked.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() ->
                requestOperations.create(
                    organization.getId(),
                    owner.getId(),
                    request(workspace.getId())));
            assertTrue(mutationEntered.await(10, TimeUnit.SECONDS));
            assertFalse(mutationAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseTeardown.countDown();
            AcquiredWorkspace acquired = teardown.get(10, TimeUnit.SECONDS);
            assertConflict(mutation);
            assertEquals(0, subjectRequestCount());
            lifecycleOperations.releaseIfPresent(acquired.lease());
        } finally {
            releaseTeardown.countDown();
        }
    }

    @Test
    void unlinkedMutationWinnerMakesOrganizationTeardownWaitAndThenRefuse() throws Exception {
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch mutationLocked = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch teardownAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            Integer result = real.lockActiveOrganizationForShare(organization.getId());
            mutationLocked.countDown();
            await(releaseMutation);
            return result;
        }).when(lifecycleMapperSpy).lockActiveOrganizationForShare(organization.getId());
        doAnswer(invocation -> {
            teardownEntered.countDown();
            OrganizationLifecycleRef result = real.lockOrganization(organization.getId());
            teardownAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockOrganization(organization.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> mutation = executor.submit(() ->
                requestOperations.create(
                    organization.getId(),
                    owner.getId(),
                    request(null)));
            assertTrue(mutationLocked.await(10, TimeUnit.SECONDS));
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.markOrganizationTearingDown(
                    organization.getId(),
                    owner.getId()));
            assertTrue(teardownEntered.await(10, TimeUnit.SECONDS));
            assertFalse(teardownAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseMutation.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            assertConflict(teardown);
        } finally {
            releaseMutation.countDown();
        }
    }

    @Test
    void organizationTeardownWinnerMakesUnlinkedMutationWaitAndThenRefuse() throws Exception {
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch teardownLocked = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch mutationAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            OrganizationLifecycleRef result = real.lockOrganization(organization.getId());
            teardownLocked.countDown();
            await(releaseTeardown);
            return result;
        }).when(lifecycleMapperSpy).lockOrganization(organization.getId());
        doAnswer(invocation -> {
            mutationEntered.countDown();
            Integer result = real.lockActiveOrganizationForShare(organization.getId());
            mutationAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockActiveOrganizationForShare(organization.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.markOrganizationTearingDown(
                    organization.getId(),
                    owner.getId()));
            assertTrue(teardownLocked.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() ->
                requestOperations.create(
                    organization.getId(),
                    owner.getId(),
                    request(null)));
            assertTrue(mutationEntered.await(10, TimeUnit.SECONDS));
            assertFalse(mutationAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseTeardown.countDown();
            teardown.get(10, TimeUnit.SECONDS);
            assertConflict(mutation);
            assertEquals(0, subjectRequestCount());
        } finally {
            releaseTeardown.countDown();
        }
    }

    @Test
    void workspaceRemovedAfterInitialValidationReturnsConflictWithoutAControlWrite()
            throws Exception {
        CountDownLatch workspaceLockEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkspaceLock = new CountDownLatch(1);
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        doAnswer(invocation -> {
            workspaceLockEntered.countDown();
            await(releaseWorkspaceLock);
            return real.lockWorkspaceForShare(workspace.getId());
        }).when(lifecycleMapperSpy).lockWorkspaceForShare(workspace.getId());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> mutation = executor.submit(() ->
                requestService.create(
                    organization.getId(),
                    owner.getId(),
                    upsertRequest(workspace.getId(), personId)));
            assertTrue(workspaceLockEntered.await(10, TimeUnit.SECONDS));

            assertEquals(
                1,
                jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    personId));
            assertEquals(
                1,
                jdbcTemplate.update(
                    "DELETE FROM workspace WHERE id = ?",
                    workspace.getId()));
            releaseWorkspaceLock.countDown();

            assertConflict(mutation);
            assertEquals(0, subjectRequestCount());
        } finally {
            releaseWorkspaceLock.countDown();
        }
    }

    @Test
    void personRemovedAfterInitialValidationReturnsConflictWithoutAControlWrite()
            throws Exception {
        CountDownLatch workspaceLockEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkspaceLock = new CountDownLatch(1);
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        doAnswer(invocation -> {
            workspaceLockEntered.countDown();
            await(releaseWorkspaceLock);
            return real.lockWorkspaceForShare(workspace.getId());
        }).when(lifecycleMapperSpy).lockWorkspaceForShare(workspace.getId());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> mutation = executor.submit(() ->
                requestService.create(
                    organization.getId(),
                    owner.getId(),
                    upsertRequest(workspace.getId(), personId)));
            assertTrue(workspaceLockEntered.await(10, TimeUnit.SECONDS));

            assertEquals(
                1,
                jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    personId));
            releaseWorkspaceLock.countDown();

            assertConflict(mutation);
            assertEquals(0, subjectRequestCount());
        } finally {
            releaseWorkspaceLock.countDown();
        }
    }

    @Test
    void subjectPersonLockIsRetainedUntilTheControlWriteExits() throws Exception {
        CountDownLatch controlWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseControlWrite = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        DataSubjectRequestMapper real =
            sqlSessionTemplate.getMapper(DataSubjectRequestMapper.class);
        doAnswer(invocation -> {
            controlWriteEntered.countDown();
            await(releaseControlWrite);
            return real.insert(invocation.getArgument(0));
        }).when(requestMapperSpy).insert(
            org.mockito.ArgumentMatchers.any(DataSubjectRequest.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> mutation = executor.submit(() ->
                requestService.create(
                    organization.getId(),
                    owner.getId(),
                    upsertRequest(workspace.getId(), personId)));
            assertTrue(controlWriteEntered.await(10, TimeUnit.SECONDS));
            Future<Integer> deletion = executor.submit(() -> {
                deletionStarted.countDown();
                return jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    personId);
            });
            assertTrue(deletionStarted.await(10, TimeUnit.SECONDS));

            assertThrows(
                TimeoutException.class,
                () -> deletion.get(500, TimeUnit.MILLISECONDS));
            releaseControlWrite.countDown();
            mutation.get(10, TimeUnit.SECONDS);
            assertEquals(1, deletion.get(10, TimeUnit.SECONDS));
            assertEquals(1, subjectRequestCount());
        } finally {
            releaseControlWrite.countDown();
        }
    }

    @Test
    void concurrentLinkedMutationsCompleteWithOneReservedTenantAndOneControlConnectionEach()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> mutations = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            for (int index = 0; index < 5; index++) {
                mutations.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    requestService.create(
                        organization.getId(),
                        owner.getId(),
                        upsertRequest(workspace.getId(), personId));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> mutation : mutations) {
                mutation.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
        }

        assertEquals(5, subjectRequestCount());
    }

    @Test
    void returningSsoWinnerMakesOrganizationTeardownWaitWithoutALockCycle() throws Exception {
        String subject = "returning-winner-" + UUID.randomUUID();
        FederatedIdentity identity = identity(subject);
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch loginLocked = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch teardownAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            Integer result = real.lockActiveOrganizationForShare(organization.getId());
            loginLocked.countDown();
            await(releaseLogin);
            return result;
        }).when(lifecycleMapperSpy).lockActiveOrganizationForShare(organization.getId());
        doAnswer(invocation -> {
            teardownEntered.countDown();
            OrganizationLifecycleRef result = real.lockOrganization(organization.getId());
            teardownAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockOrganization(organization.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SsoLoginResult> login = executor.submit(() ->
                ssoLoginService.resolve(
                    "oidc",
                    "https://issuer.example",
                    subject,
                    owner.getEmail(),
                    true,
                    organization.getId(),
                    owner.getDisplayName()));
            assertTrue(loginLocked.await(10, TimeUnit.SECONDS));
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.markOrganizationTearingDown(
                    organization.getId(),
                    owner.getId()));
            assertTrue(teardownEntered.await(10, TimeUnit.SECONDS));
            assertFalse(teardownAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseLogin.countDown();
            assertInstanceOf(SsoLoginResult.Login.class, login.get(10, TimeUnit.SECONDS));
            teardown.get(10, TimeUnit.SECONDS);
            assertTrue(federatedIdentityMapper.findByProviderIssuerSubject(
                identity.getProvider(),
                identity.getIssuer(),
                subject).getLastLoginAt() != null);
        } finally {
            releaseLogin.countDown();
        }
    }

    @Test
    void organizationTeardownWinnerRefusesReturningSsoWithoutTouchingIdentity() throws Exception {
        String subject = "teardown-winner-" + UUID.randomUUID();
        FederatedIdentity identity = identity(subject);
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch teardownLocked = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch loginEntered = new CountDownLatch(1);
        CountDownLatch loginAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            OrganizationLifecycleRef result = real.lockOrganization(organization.getId());
            teardownLocked.countDown();
            await(releaseTeardown);
            return result;
        }).when(lifecycleMapperSpy).lockOrganization(organization.getId());
        doAnswer(invocation -> {
            loginEntered.countDown();
            Integer result = real.lockActiveOrganizationForShare(organization.getId());
            loginAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockActiveOrganizationForShare(organization.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.markOrganizationTearingDown(
                    organization.getId(),
                    owner.getId()));
            assertTrue(teardownLocked.await(10, TimeUnit.SECONDS));
            Future<?> login = executor.submit(() ->
                ssoLoginService.resolve(
                    "oidc",
                    "https://issuer.example",
                    subject,
                    owner.getEmail(),
                    true,
                    organization.getId(),
                    owner.getDisplayName()));
            assertTrue(loginEntered.await(10, TimeUnit.SECONDS));
            assertFalse(loginAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseTeardown.countDown();
            teardown.get(10, TimeUnit.SECONDS);
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> login.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, failure.getCause());
            assertTrue(federatedIdentityMapper.findByProviderIssuerSubject(
                identity.getProvider(),
                identity.getIssuer(),
                subject).getLastLoginAt() == null);
        } finally {
            releaseTeardown.countDown();
        }
    }

    @Test
    void workspaceTeardownWinnerRefusesNewJitWithoutProvisioningOrLinking() throws Exception {
        String domain = "jit-" + UUID.randomUUID() + ".test";
        String email = "new-user@" + domain;
        String subject = "new-jit-" + UUID.randomUUID();
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(organization.getId());
        connection.setProtocol("oidc");
        connection.setEnabled(true);
        connection.setJitWorkspaceId(workspace.getId());
        connection.setDefaultRole("member");
        connection.setOidcIssuer("https://issuer.example");
        connection.setOidcClientId("client");
        connection.setOidcScopes("openid,email,profile");
        ssoConnectionMapper.upsert(connection);
        ssoDomainMapper.insert(domain, organization.getId());
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch teardownLocked = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch loginEntered = new CountDownLatch(1);
        CountDownLatch loginAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkspaceLifecycleRef result = real.lockWorkspaceInOrg(workspace.getId());
            teardownLocked.countDown();
            await(releaseTeardown);
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceInOrg(workspace.getId());
        doAnswer(invocation -> {
            loginEntered.countDown();
            WorkspaceLifecycleRef result = real.lockWorkspaceForShare(workspace.getId());
            loginAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceForShare(workspace.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AcquiredWorkspace> teardown = executor.submit(() ->
                lifecycleOperations.acquireWorkspaceTeardown(
                    organization.getId(),
                    workspace.getId(),
                    owner.getId()));
            assertTrue(teardownLocked.await(10, TimeUnit.SECONDS));
            Future<?> login = executor.submit(() ->
                ssoLoginService.resolve(
                    "oidc",
                    "https://issuer.example",
                    subject,
                    email,
                    true,
                    organization.getId(),
                    "New JIT User"));
            assertTrue(loginEntered.await(10, TimeUnit.SECONDS));
            assertFalse(loginAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseTeardown.countDown();
            AcquiredWorkspace acquired = teardown.get(10, TimeUnit.SECONDS);
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> login.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, failure.getCause());
            assertTrue(userMapper.getUserByEmail(email) == null);
            assertTrue(federatedIdentityMapper.findByProviderIssuerSubject(
                "oidc",
                "https://issuer.example",
                subject) == null);
            lifecycleOperations.releaseIfPresent(acquired.lease());
        } finally {
            releaseTeardown.countDown();
        }
    }

    @Test
    void newJitWinnerMakesWorkspaceTeardownWaitThroughProvisioningAndLinking()
            throws Exception {
        String domain = "jit-winner-" + UUID.randomUUID() + ".test";
        String email = "new-user@" + domain;
        provisionedUserEmails.add(email);
        String subject = "new-jit-winner-" + UUID.randomUUID();
        SsoConnection connection = new SsoConnection();
        connection.setOrgId(organization.getId());
        connection.setProtocol("oidc");
        connection.setEnabled(true);
        connection.setJitWorkspaceId(workspace.getId());
        connection.setDefaultRole("member");
        connection.setOidcIssuer("https://issuer.example");
        connection.setOidcClientId("client");
        connection.setOidcScopes("openid,email,profile");
        ssoConnectionMapper.upsert(connection);
        ssoDomainMapper.insert(domain, organization.getId());
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch loginLocked = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch teardownAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkspaceLifecycleRef result = real.lockWorkspaceForShare(workspace.getId());
            loginLocked.countDown();
            await(releaseLogin);
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceForShare(workspace.getId());
        doAnswer(invocation -> {
            teardownEntered.countDown();
            WorkspaceLifecycleRef result = real.lockWorkspaceInOrg(workspace.getId());
            teardownAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceInOrg(workspace.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SsoLoginResult> login = executor.submit(() ->
                ssoLoginService.resolve(
                    "oidc",
                    "https://issuer.example",
                    subject,
                    email,
                    true,
                    organization.getId(),
                    "New JIT Winner"));
            assertTrue(loginLocked.await(10, TimeUnit.SECONDS));
            Future<AcquiredWorkspace> teardown = executor.submit(() ->
                lifecycleOperations.acquireWorkspaceTeardown(
                    organization.getId(),
                    workspace.getId(),
                    owner.getId()));
            assertTrue(teardownEntered.await(10, TimeUnit.SECONDS));
            assertFalse(teardownAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseLogin.countDown();
            assertInstanceOf(
                SsoLoginResult.Login.class,
                login.get(10, TimeUnit.SECONDS));
            AcquiredWorkspace acquired = teardown.get(10, TimeUnit.SECONDS);
            assertTrue(userMapper.getUserByEmail(email) != null);
            assertTrue(federatedIdentityMapper.findByProviderIssuerSubject(
                "oidc",
                "https://issuer.example",
                subject) != null);
            lifecycleOperations.releaseIfPresent(acquired.lease());
        } finally {
            releaseLogin.countDown();
        }
    }

    @Test
    void organizationTeardownWinnerRefusesSsoLinkChallengeWithoutWriting() throws Exception {
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch teardownLocked = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch challengeEntered = new CountDownLatch(1);
        CountDownLatch challengeAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            OrganizationLifecycleRef result = real.lockOrganization(organization.getId());
            teardownLocked.countDown();
            await(releaseTeardown);
            return result;
        }).when(lifecycleMapperSpy).lockOrganization(organization.getId());
        doAnswer(invocation -> {
            challengeEntered.countDown();
            Integer result = real.lockActiveOrganizationForShare(organization.getId());
            challengeAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockActiveOrganizationForShare(organization.getId());
        SsoLoginResult.LinkRequired request = new SsoLoginResult.LinkRequired(
            owner.getId(),
            "oidc",
            "https://issuer.example",
            "link-" + UUID.randomUUID(),
            organization.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.markOrganizationTearingDown(
                    organization.getId(),
                    owner.getId()));
            assertTrue(teardownLocked.await(10, TimeUnit.SECONDS));
            Future<?> challenge = executor.submit(() -> ssoLinkService.createChallenge(request));
            assertTrue(challengeEntered.await(10, TimeUnit.SECONDS));
            assertFalse(challengeAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseTeardown.countDown();
            teardown.get(10, TimeUnit.SECONDS);
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> challenge.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, failure.getCause());
            assertEquals(
                0,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sso_link_challenge WHERE user_id = ?",
                    Integer.class,
                    owner.getId()));
        } finally {
            releaseTeardown.countDown();
        }
    }

    @Test
    void ssoLinkChallengeWinnerMakesOrganizationTeardownWaitThroughInsertion()
            throws Exception {
        jdbcTemplate.update(
            "UPDATE app_user SET password_hash = ? WHERE id = ?",
            "encoded-password",
            owner.getId());
        TenantLifecycleControlMapper real =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch challengeLocked = new CountDownLatch(1);
        CountDownLatch releaseChallenge = new CountDownLatch(1);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch teardownAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            Integer result = real.lockActiveOrganizationForShare(organization.getId());
            challengeLocked.countDown();
            await(releaseChallenge);
            return result;
        }).when(lifecycleMapperSpy).lockActiveOrganizationForShare(organization.getId());
        doAnswer(invocation -> {
            teardownEntered.countDown();
            OrganizationLifecycleRef result = real.lockOrganization(organization.getId());
            teardownAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockOrganization(organization.getId());
        SsoLoginResult.LinkRequired request = new SsoLoginResult.LinkRequired(
            owner.getId(),
            "oidc",
            "https://issuer.example",
            "link-winner-" + UUID.randomUUID(),
            organization.getId());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> challenge = executor.submit(() ->
                ssoLinkService.createChallenge(request));
            assertTrue(challengeLocked.await(10, TimeUnit.SECONDS));
            Future<?> teardown = executor.submit(() ->
                lifecycleOperations.markOrganizationTearingDown(
                    organization.getId(),
                    owner.getId()));
            assertTrue(teardownEntered.await(10, TimeUnit.SECONDS));
            assertFalse(teardownAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseChallenge.countDown();
            assertTrue(!challenge.get(10, TimeUnit.SECONDS).isBlank());
            teardown.get(10, TimeUnit.SECONDS);
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sso_link_challenge WHERE user_id = ?",
                    Integer.class,
                    owner.getId()));
        } finally {
            releaseChallenge.countDown();
        }
    }

    @Test
    void auditWinnerMakesWorkspaceTeardownWaitWithoutAUserWorkspaceOrganizationCycle()
            throws Exception {
        WorkspaceMapper realWorkspaceMapper =
            sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        TenantLifecycleControlMapper realLifecycleMapper =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        CountDownLatch auditLocked = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch teardownAcquired = new CountDownLatch(1);
        doAnswer(invocation -> {
            Integer result = realWorkspaceMapper.lockWorkspaceForShare(workspace.getId());
            auditLocked.countDown();
            await(releaseAudit);
            return result;
        }).when(workspaceMapper).lockWorkspaceForShare(workspace.getId());
        doAnswer(invocation -> {
            teardownEntered.countDown();
            WorkspaceLifecycleRef result =
                realLifecycleMapper.lockWorkspaceInOrg(workspace.getId());
            teardownAcquired.countDown();
            return result;
        }).when(lifecycleMapperSpy).lockWorkspaceInOrg(workspace.getId());
        AuditLog entry = new AuditLog();
        entry.setWorkspaceId(workspace.getId());
        entry.setOrgId(organization.getId());
        entry.setActorId(owner.getId());
        entry.setAction("test.lifecycle.concurrent_audit");
        entry.setEntityType("workspace");
        entry.setEntityId(workspace.getId());
        entry.setTargetLabel(workspace.getSlug());
        entry.setOutcome("success");
        entry.setSummary("Concurrent lifecycle audit");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> audit = executor.submit(() -> auditIntegrityService.appendIndependent(entry));
            assertTrue(auditLocked.await(10, TimeUnit.SECONDS));
            Future<AcquiredWorkspace> teardown = executor.submit(() ->
                lifecycleOperations.acquireWorkspaceTeardown(
                    organization.getId(),
                    workspace.getId(),
                    owner.getId()));
            assertTrue(teardownEntered.await(10, TimeUnit.SECONDS));
            assertFalse(teardownAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseAudit.countDown();
            audit.get(10, TimeUnit.SECONDS);
            AcquiredWorkspace acquired = teardown.get(10, TimeUnit.SECONDS);
            lifecycleOperations.releaseIfPresent(acquired.lease());
            assertTrue(entry.getId() > 0);
        } finally {
            releaseAudit.countDown();
        }
    }

    private DataSubjectRequest request(Integer workspaceId) {
        DataSubjectRequest request = new DataSubjectRequest();
        request.setOrgId(organization.getId());
        request.setRequestType("disclosure");
        request.setStatus("received");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setSubjectWorkspaceId(workspaceId);
        request.setSubjectPersonId(workspaceId == null ? null : personId);
        request.setCreatedBy(owner.getId());
        request.setUpdatedBy(owner.getId());
        return request;
    }

    private static DataSubjectRequestUpsertRequest upsertRequest(int workspaceId, int subjectPersonId) {
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        request.setRequestType("disclosure");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setSubjectWorkspaceId(workspaceId);
        request.setSubjectPersonId(subjectPersonId);
        return request;
    }

    private FederatedIdentity identity(String subject) {
        FederatedIdentity identity = new FederatedIdentity();
        identity.setUserId(owner.getId());
        identity.setOrgId(organization.getId());
        identity.setProvider("oidc");
        identity.setIssuer("https://issuer.example");
        identity.setExternalSubject(subject);
        federatedIdentityMapper.insert(identity);
        return identity;
    }

    private int subjectRequestCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM data_subject_request WHERE org_id = ?",
            Integer.class,
            organization.getId());
    }

    private static void assertConflict(Future<?> future) throws Exception {
        ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(
            ExecutionException.class,
            () -> future.get(10, TimeUnit.SECONDS));
        assertInstanceOf(ConflictException.class, exception.getCause());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
