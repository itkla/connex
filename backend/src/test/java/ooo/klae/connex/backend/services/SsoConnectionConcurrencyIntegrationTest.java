package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

/** Verifies credential transitions against real organization lock contention and repeatable-read snapshots. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SsoConnectionConcurrencyIntegrationTest {

    @Autowired private SsoConnectionService service;
    @Autowired private SsoConnectionMapper connectionMapper;
    @Autowired private SsoSecretCipher cipher;
    @Autowired private UserMapper userMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private WorkspaceMapper workspaceMapper;
    @MockitoSpyBean private OrganizationMapper organizationMapper;
    @MockitoBean private SessionSecurityService sessionSecurityService;

    private Workspace workspace;
    private User actor;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        Organization organization = new Organization();
        organization.setName("SSO credential contention");
        organization.setSlug("sso-contention-" + unique);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("SSO credential contention");
        workspace.setSlug("sso-contention-" + unique);
        workspaceMapper.insert(workspace);
        actor = new User();
        actor.setUsername("sso-contention-" + unique);
        actor.setEmail(unique + "@example.test");
        actor.setDisplayName("SSO configuration owner");
        actor.setTimezone("UTC");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        orgMemberMapper.addMember(organization.getId(), actor.getId(), "owner");
        service.save(workspace.getId(), actor.getId(), request("secret-B"));
    }

    @ParameterizedTest
    @CsvSource({"issuer,false,false", "issuer,true,false", "client,false,false", "client,true,false",
            "issuer,true,true", "client,true,true"})
    void saveRevalidatesCredentialIdentityAfterConcurrentSwitch(
            String changedField, boolean contendOnLock, boolean freshSecret) throws Exception {
        int orgId = workspace.getOrgId();
        CountDownLatch switchLocked = new CountDownLatch(1);
        CountDownLatch releaseSwitch = new CountDownLatch(1);
        CountDownLatch staleSnapshot = new CountDownLatch(1);
        CountDownLatch releaseStale = new CountDownLatch(1);
        CountDownLatch staleLockAttempted = new CountDownLatch(1);
        AtomicReference<Thread> switchThread = new AtomicReference<>();
        AtomicReference<Thread> staleThread = new AtomicReference<>();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        WorkspaceMapper realWorkspaceMapper = sqlSessionTemplate.getMapper(WorkspaceMapper.class);
        OrganizationMapper realOrganizationMapper = sqlSessionTemplate.getMapper(OrganizationMapper.class);
        doAnswer(invocation -> {
            Integer discovered = realWorkspaceMapper.getOrgId(workspace.getId());
            if (Thread.currentThread() == staleThread.get() && firstRead.compareAndSet(true, false)) {
                staleSnapshot.countDown();
                if (!contendOnLock) {
                    await(releaseStale);
                }
            }
            return discovered;
        }).when(workspaceMapper).getOrgId(workspace.getId());
        doAnswer(invocation -> {
            if (Thread.currentThread() == staleThread.get()) {
                staleLockAttempted.countDown();
            }
            Integer locked = realOrganizationMapper.lockById(orgId);
            if (Thread.currentThread() == switchThread.get()) {
                switchLocked.countDown();
                await(releaseSwitch);
            }
            return locked;
        }).when(organizationMapper).lockById(orgId);

        SsoConnectionRequest switchRequest = request("secret-A");
        if ("issuer".equals(changedField)) {
            switchRequest.setOidcIssuer("https://issuer-a.example.test");
        } else {
            switchRequest.setOidcClientId("client-A");
        }
        SsoConnectionRequest staleRequest = request(freshSecret ? "fresh-secret-B" : " ");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SsoConnection> switched = executor.submit(() -> {
                switchThread.set(Thread.currentThread());
                return saveInRepeatableRead(switchRequest);
            });
            assertTrue(switchLocked.await(15, TimeUnit.SECONDS));
            Future<SsoConnection> stale = executor.submit(() -> {
                staleThread.set(Thread.currentThread());
                return saveInRepeatableRead(staleRequest);
            });
            assertTrue(staleSnapshot.await(15, TimeUnit.SECONDS));
            if (contendOnLock) {
                assertTrue(staleLockAttempted.await(15, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> stale.get(500, TimeUnit.MILLISECONDS));
            }
            releaseSwitch.countDown();
            SsoConnection accepted = switched.get(20, TimeUnit.SECONDS);
            assertNotNull(accepted);
            releaseStale.countDown();
            if (freshSecret) {
                SsoConnection replacement = stale.get(20, TimeUnit.SECONDS);
                assertNotNull(replacement);
                assertNotEquals(accepted.getOidcClientSecretEnc(), replacement.getOidcClientSecretEnc());
                assertEquals("https://issuer-b.example.test", replacement.getOidcIssuer());
                assertEquals("client-B", replacement.getOidcClientId());
                assertEquals("fresh-secret-B",
                        cipher.decryptOidcClientSecret(orgId, replacement.getOidcClientSecretEnc()));
                assertThrows(ResourceNotFoundException.class,
                        () -> cipher.decryptOidcClientSecret(orgId, accepted.getOidcClientSecretEnc()));
            } else {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> stale.get(20, TimeUnit.SECONDS));
                assertInstanceOf(BadRequestException.class, failure.getCause());
                SsoConnection current = connectionMapper.findByOrg(orgId);
                assertNotNull(current);
                assertEquals(accepted, current);
                assertEquals("secret-A", cipher.decryptOidcClientSecret(orgId, current.getOidcClientSecretEnc()));
            }
        } finally {
            releaseSwitch.countDown();
            releaseStale.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private SsoConnection saveInRepeatableRead(SsoConnectionRequest request) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
            service.save(workspace.getId(), actor.getId(), request);
            return connectionMapper.findByOrg(workspace.getOrgId());
        });
    }

    private SsoConnectionRequest request(String secret) {
        SsoConnectionRequest request = new SsoConnectionRequest();
        request.setProtocol("oidc");
        request.setEnabled(true);
        request.setJitWorkspaceId(workspace.getId());
        request.setOidcIssuer("https://issuer-b.example.test");
        request.setOidcClientId("client-B");
        request.setOidcClientSecret(secret);
        request.setDomains(List.of());
        return request;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("SSO credential transaction did not resume");
        }
    }
}
