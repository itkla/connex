package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.dto.MailConfigDto;
import ooo.klae.connex.backend.dto.MailConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.secrets.LegacySecretRewrapRunner;
import ooo.klae.connex.backend.tenant.Permission;

/** Exercises SMTP generation consistency at the actual workspace root lock statements. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "connex.mail.secret-key=AAAAAAAAAAAAAAAAAAAAAA==")
class WorkspaceMailConfigConcurrencyIntegrationTest extends CampaignRealDbTestSupport {

    @Autowired private WorkspaceMailConfigService configService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private MailConfigResolver resolver;
    @Autowired private MailConfigMapper configMapper;
    @Autowired private SecretCipher secretCipher;
    @Autowired private LegacySecretRewrapRunner rewrapRunner;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private WorkspaceMapper workspaceMapperSpy;
    @MockitoSpyBean private UserMapper userMapperSpy;
    @MockitoBean private SmtpDestinationGuard destinationGuard;

    private final ThreadLocal<String> operation = new ThreadLocal<>();

    @Override
    protected User newCampaignAdmin(Workspace targetWorkspace) {
        return newCampaignActor(targetWorkspace, List.of(Permission.WORKSPACE_SETTINGS)).actor();
    }

    @AfterEach
    void cleanSmtpFixtures() {
        jdbcTemplate.update("DELETE FROM workspace_mail_config WHERE workspace_id = ?", workspace.getId());
        jdbcTemplate.update("DELETE FROM secret_value WHERE scope_type = 'workspace' AND scope_id = ?",
                workspace.getId());
    }

    @Test
    void staleBlankPasswordSaveWaitsForRotationAndRejectsTheOldBinding() throws Exception {
        save("smtp.b.test", "password-b");
        CountDownLatch rotationLocked = new CountDownLatch(1);
        CountDownLatch staleAttempted = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        pauseExclusiveWriter(rotationLocked, staleAttempted, releaseRotation);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> rotation = executor.submit(() -> asActor("writer", () -> save("smtp.a.test", "password-a")));
            assertTrue(rotationLocked.await(20, TimeUnit.SECONDS));
            Future<?> stale = executor.submit(() -> asActor("contender", () -> save("smtp.b.test", "")));
            assertTrue(staleAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> stale.get(500, TimeUnit.MILLISECONDS));
            releaseRotation.countDown();
            rotation.get(20, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> stale.get(20, TimeUnit.SECONDS));
            assertInstanceOf(BadRequestException.class, rejected.getCause());
            assertPair(resolver.resolveWorkspaceOnly(workspace.getId()), "smtp.a.test", "password-a");
        } finally {
            releaseRotation.countDown();
            stop(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resolutionHoldsOneGenerationWhileRotationOrDeletionWaits(boolean delete) throws Exception {
        save("smtp.b.test", "password-b");
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch mutationAttempted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        WorkspaceMapper realMapper = sqlSession.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            Integer locked = realMapper.lockWorkspaceForShare(workspace.getId());
            if ("reader".equals(operation.get()) && firstRead.compareAndSet(true, false)) {
                readerLocked.countDown();
                await(releaseReader);
            }
            return locked;
        }).when(workspaceMapperSpy).lockWorkspaceForShare(workspace.getId());
        doAnswer(invocation -> {
            mutationAttempted.countDown();
            return realMapper.lockActiveIdentity(workspace.getId());
        }).when(workspaceMapperSpy).lockActiveIdentity(workspace.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResolvedMailConfig> reader = executor.submit(() -> asActor("reader",
                    () -> resolver.resolveWorkspaceOnly(workspace.getId())));
            assertTrue(readerLocked.await(20, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> asActor("writer", () -> {
                if (delete) {
                    configService.deleteConfig(workspace.getId(), currentUser.getId());
                    return null;
                }
                return save("smtp.a.test", "password-a");
            }));
            assertTrue(mutationAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> mutation.get(500, TimeUnit.MILLISECONDS));
            releaseReader.countDown();
            assertPair(reader.get(20, TimeUnit.SECONDS), "smtp.b.test", "password-b");
            mutation.get(20, TimeUnit.SECONDS);
            if (delete) {
                assertNull(resolver.resolveWorkspaceOnly(workspace.getId()));
            } else {
                assertPair(resolver.resolveWorkspaceOnly(workspace.getId()), "smtp.a.test", "password-a");
            }
        } finally {
            releaseReader.countDown();
            stop(executor);
        }
    }

    @Test
    void resolutionKeepsActorRootAheadOfAnExclusiveUserWaiter() throws Exception {
        save("smtp.a.test", "password-a");
        CountDownLatch workspaceLocked = new CountDownLatch(1);
        CountDownLatch exclusiveUserAttempted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        WorkspaceMapper realWorkspaceMapper = sqlSession.getMapper(WorkspaceMapper.class);
        UserMapper realUserMapper = sqlSession.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            Integer locked = realWorkspaceMapper.lockWorkspaceForShare(workspace.getId());
            if ("reader".equals(operation.get()) && firstRead.compareAndSet(true, false)) {
                workspaceLocked.countDown();
                await(releaseReader);
            }
            return locked;
        }).when(workspaceMapperSpy).lockWorkspaceForShare(workspace.getId());
        doAnswer(invocation -> {
            exclusiveUserAttempted.countDown();
            return realUserMapper.lockById(currentUser.getId());
        }).when(userMapperSpy).lockById(currentUser.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResolvedMailConfig> reader = executor.submit(() -> asActor("reader",
                    () -> resolver.resolveWorkspaceOnly(workspace.getId())));
            assertTrue(workspaceLocked.await(20, TimeUnit.SECONDS));
            Future<?> exclusiveUser = executor.submit(() -> asActor("user-writer", () -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        assertEquals(currentUser.getId(), userMapperSpy.lockById(currentUser.getId())));
                return null;
            }));
            assertTrue(exclusiveUserAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> exclusiveUser.get(500, TimeUnit.MILLISECONDS));
            releaseReader.countDown();
            assertPair(reader.get(20, TimeUnit.SECONDS), "smtp.a.test", "password-a");
            exclusiveUser.get(20, TimeUnit.SECONDS);
        } finally {
            releaseReader.countDown();
            stop(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingDeleteOrDisableRemovesASecretCreatedAfterItsInitialSnapshot(boolean disable) throws Exception {
        CountDownLatch firstSaveLocked = new CountDownLatch(1);
        CountDownLatch deletionAttempted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        pauseExclusiveWriter(firstSaveLocked, deletionAttempted, releaseSave);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> asActor("writer", () -> save("smtp.a.test", "password-a")));
            assertTrue(firstSaveLocked.await(20, TimeUnit.SECONDS));
            Future<?> deletion = executor.submit(() -> asActor("contender", () -> {
                if (disable) {
                    MailConfigRequest request = request("smtp.a.test", "");
                    request.setEnabled(false);
                    return configService.saveConfig(workspace.getId(), currentUser.getId(), request);
                }
                configService.deleteConfig(workspace.getId(), currentUser.getId());
                return null;
            }));
            assertTrue(deletionAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> deletion.get(500, TimeUnit.MILLISECONDS));
            releaseSave.countDown();
            creation.get(20, TimeUnit.SECONDS);
            deletion.get(20, TimeUnit.SECONDS);
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM secret_value"
                            + " WHERE scope_type = 'workspace' AND scope_id = ?"
                            + " AND purpose = 'workspace.smtp.password'",
                    Integer.class, workspace.getId()));
            assertNull(resolver.resolveWorkspaceOnly(workspace.getId()));
        } finally {
            releaseSave.countDown();
            stop(executor);
        }
    }

    @Test
    void waitingSaveRechecksPermissionsAfterTheWorkspaceLock() throws Exception {
        save("smtp.a.test", "password-a");
        int roleId = Objects.requireNonNull(workspaceMapper.getMemberRoleId(workspace.getId(), currentUser.getId()));
        CountDownLatch revocationLocked = new CountDownLatch(1);
        CountDownLatch saveAttempted = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        pauseExclusiveWriter(revocationLocked, saveAttempted, releaseRevocation);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> revocation = executor.submit(() -> asActor("writer", () -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    workspaceService.lockAndRequirePermissionsWithWorkspaceMutex(workspace.getId(),
                            Map.of(currentUser.getId(), Set.of(Permission.WORKSPACE_SETTINGS)));
                    jdbcTemplate.update("DELETE FROM workspace_role_permission WHERE workspace_role_id = ?", roleId);
                });
                return null;
            }));
            assertTrue(revocationLocked.await(20, TimeUnit.SECONDS));
            Future<?> save = executor.submit(() -> asActor("contender", () -> save("smtp.a.test", "replacement")));
            assertTrue(saveAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> save.get(500, TimeUnit.MILLISECONDS));
            releaseRevocation.countDown();
            revocation.get(20, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> save.get(20, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, rejected.getCause());
            assertPair(resolver.resolveWorkspaceOnly(workspace.getId()), "smtp.a.test", "password-a");
        } finally {
            releaseRevocation.countDown();
            stop(executor);
        }
    }

    @Test
    void legacyRewrapReloadsAfterAConcurrentCredentialReplacement() throws Exception {
        WorkspaceMailConfig legacy = new WorkspaceMailConfig();
        legacy.setWorkspaceId(workspace.getId());
        legacy.setEnabled(true);
        legacy.setAuth(true);
        legacy.setStarttls(true);
        legacy.setHost("smtp.b.test");
        legacy.setPort(587);
        legacy.setFromAddress("sender@test.invalid");
        legacy.setPasswordEnc(secretCipher.encryptLegacy("password-b"));
        configMapper.upsert(legacy);
        CountDownLatch rotationLocked = new CountDownLatch(1);
        CountDownLatch rewrapAttempted = new CountDownLatch(1);
        CountDownLatch releaseRotation = new CountDownLatch(1);
        pauseExclusiveWriter(rotationLocked, new CountDownLatch(1), releaseRotation);
        WorkspaceMapper realMapper = sqlSession.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            rewrapAttempted.countDown();
            return realMapper.lockWorkspace(workspace.getId());
        }).when(workspaceMapperSpy).lockWorkspace(workspace.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> rotation = executor.submit(() -> asActor("writer", () -> save("smtp.a.test", "password-a")));
            assertTrue(rotationLocked.await(20, TimeUnit.SECONDS));
            Future<?> rewrap = executor.submit(() -> asActor("rewrap", () -> {
                rewrapRunner.run(null);
                return null;
            }));
            assertTrue(rewrapAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> rewrap.get(500, TimeUnit.MILLISECONDS));
            releaseRotation.countDown();
            rotation.get(20, TimeUnit.SECONDS);
            rewrap.get(20, TimeUnit.SECONDS);
            assertPair(resolver.resolveWorkspaceOnly(workspace.getId()), "smtp.a.test", "password-a");
        } finally {
            releaseRotation.countDown();
            stop(executor);
        }
    }

    private void pauseExclusiveWriter(CountDownLatch locked, CountDownLatch attempted, CountDownLatch release) {
        WorkspaceMapper realMapper = sqlSession.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if ("contender".equals(operation.get())) {
                attempted.countDown();
            }
            Workspace result = realMapper.lockActiveIdentity(workspace.getId());
            if ("writer".equals(operation.get())) {
                locked.countDown();
                await(release);
            }
            return result;
        }).when(workspaceMapperSpy).lockActiveIdentity(workspace.getId());
    }

    private MailConfigDto save(String host, String password) {
        return configService.saveConfig(workspace.getId(), currentUser.getId(), request(host, password));
    }

    private static MailConfigRequest request(String host, String password) {
        MailConfigRequest request = new MailConfigRequest();
        request.setEnabled(true);
        request.setHost(host);
        request.setPort(587);
        request.setUsername("sender@test.invalid");
        request.setPassword(password);
        request.setFromAddress("sender@test.invalid");
        return request;
    }

    private <T> T asActor(String name, Supplier<T> action) {
        operation.set(name);
        authenticateAs(currentUser, workspace.getId());
        try {
            return action.get();
        } finally {
            clearAuthentication();
            operation.remove();
        }
    }

    private static void assertPair(ResolvedMailConfig config, String host, String password) {
        assertEquals(host, Objects.requireNonNull(config).host());
        assertEquals(password, config.password());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(20, TimeUnit.SECONDS));
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
    }
}
