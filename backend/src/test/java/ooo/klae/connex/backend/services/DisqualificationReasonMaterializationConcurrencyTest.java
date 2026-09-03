package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.DisqualificationReasonMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Real-transaction proofs for vocabulary materialization and lifecycle/configuration serialization. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(DisqualificationReasonMaterializationConcurrencyTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DisqualificationReasonMaterializationConcurrencyTest {
    private static final String WORKSPACE_LOCK =
        WorkspaceMapper.class.getName() + ".lockActiveIdentity";
    private static final String REASON_LOCK_BY_ID =
        DisqualificationReasonMapper.class.getName() + ".getByIdForUpdate";
    private static final String REASON_LOCK_BY_CODE =
        DisqualificationReasonMapper.class.getName() + ".getByCodeForUpdate";
    private static final String REASON_INSERT_BUILT_IN =
        DisqualificationReasonMapper.class.getName() + ".insertBuiltIn";

    @Autowired private DisqualificationReasonService reasonService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RoleService roleService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private PersonLifecycleService personLifecycleService;
    @Autowired private PersonMapper personMapper;
    @Autowired private StatementProbe statementProbe;

    private Organization organization;
    private Workspace workspace;
    private User owner;
    private User firstMember;
    private User secondMember;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Reason materialization " + unique);
        organization.setSlug("reason-materialization-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setName("Reason materialization " + unique);
        workspace.setSlug("reason-materialization-" + unique);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);

        owner = new User();
        owner.setUsername("reason-materialization-" + unique);
        owner.setDisplayName("Reason materialization " + unique);
        owner.setEmail("reason-materialization-" + unique + "@example.com");
        owner.setPasswordHash("hash-" + unique);
        owner.setTimezone("UTC");
        userMapper.insert(owner);
        workspaceMapper.addMember(workspace.getId(), owner.getId(), "owner");

        firstMember = newMember("first", unique);
        secondMember = newMember("second", unique);
        authenticate(owner);
        var firstRole = roleService.createRole(
            workspace.getId(), owner.getId(), "First reason manager " + unique,
            List.of("WORKSPACE_SETTINGS", "PERSON_UPDATE"));
        var secondRole = roleService.createRole(
            workspace.getId(), owner.getId(), "Second reason manager " + unique,
            List.of("WORKSPACE_SETTINGS", "PERSON_UPDATE"));
        workspaceService.assignCustomRole(
            workspace.getId(), owner.getId(), firstMember.getId(), firstRole.getId());
        workspaceService.assignCustomRole(
            workspace.getId(), owner.getId(), secondMember.getId(), secondRole.getId());
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
        jdbcTemplate.update(
            "DELETE FROM disqualification_reason WHERE workspace_id = ?", workspace.getId());
        jdbcTemplate.update(
            "DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
        jdbcTemplate.update(
            "DELETE FROM workspace_role_permission WHERE workspace_role_id IN "
                + "(SELECT id FROM workspace_role WHERE workspace_id = ?)", workspace.getId());
        jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", workspace.getId());
        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", owner.getId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", firstMember.getId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", secondMember.getId());
        jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @Test
    void archiveSerializesBeforeDisqualificationAndTheWaitingTransitionRechecksArchivedState()
            throws Exception {
        authenticate(firstMember);
        var reason = reasonService.create(request("LEGAL_HOLD", "Legal hold", false, 20));
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(firstMember.getId());
        person.setName("Lifecycle race " + UUID.randomUUID().toString().substring(0, 8));
        personMapper.insert(person);

        CountDownLatch reasonLocked = new CountDownLatch(1);
        CountDownLatch releaseArchive = new CountDownLatch(1);
        CountDownLatch lifecycleMutexRequested = new CountDownLatch(1);
        AtomicLong lifecycleConnectionId = new AtomicLong();
        ProbePlan archiveProbe = ProbePlan.after(REASON_LOCK_BY_ID, (invocation, result) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())
                    && hasParameter(invocation, "id", reason.id())) {
                reasonLocked.countDown();
                assertTrue(releaseArchive.await(30, TimeUnit.SECONDS));
            }
        });
        ProbePlan lifecycleProbe = ProbePlan.before(WORKSPACE_LOCK, (invocation, executor) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())) {
                lifecycleConnectionId.set(currentConnectionId(executor));
                lifecycleMutexRequested.countDown();
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> archive = executor.submit(() -> asMember(firstMember, archiveProbe, () -> {
                reasonService.archive(reason.id());
                return null;
            }));
            assertTrue(reasonLocked.await(10, TimeUnit.SECONDS));
            Future<?> disqualify = executor.submit(() -> asMember(secondMember, lifecycleProbe, () -> {
                PersonLifecycleRequest request = new PersonLifecycleRequest();
                request.setStage(PersonLifecycleStage.DISQUALIFIED);
                request.setReason(reason.code());
                personLifecycleService.updateLifecycle(person.getId(), request);
                return null;
            }));
            assertTrue(lifecycleMutexRequested.await(10, TimeUnit.SECONDS));
            awaitMySqlWorkspaceMutex(lifecycleConnectionId.get(), workspace.getId());
            releaseArchive.countDown();
            archive.get(20, TimeUnit.SECONDS);
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, () -> disqualify.get(20, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof BadRequestException);
        } finally {
            releaseAndShutDown(executor, releaseArchive);
        }

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId())
            .getLifecycleStage());
    }

    @Test
    void simultaneousFirstEditsDoNotDuplicateBuiltIns() throws Exception {
        CountDownLatch firstInsertReached = new CountDownLatch(1);
        CountDownLatch releaseFirstInsert = new CountDownLatch(1);
        CountDownLatch secondMutexRequested = new CountDownLatch(1);
        AtomicLong secondConnectionId = new AtomicLong();
        ProbePlan secondEditProbe = ProbePlan.before(WORKSPACE_LOCK, (invocation, executor) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())) {
                secondConnectionId.set(currentConnectionId(executor));
                secondMutexRequested.countDown();
            }
        });
        ProbePlan firstEditProbe = ProbePlan.before(REASON_INSERT_BUILT_IN, (invocation, ignored) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())
                    && hasParameter(
                        invocation, "code", PersonDisqualificationReason.NO_BUDGET)) {
                firstInsertReached.countDown();
                assertTrue(releaseFirstInsert.await(30, TimeUnit.SECONDS));
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> asMember(firstMember, firstEditProbe, () -> {
                editFallback("Budget unavailable");
                return null;
            }));
            assertTrue(firstInsertReached.await(10, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> asMember(secondMember, secondEditProbe, () -> {
                editFallback("No budget available");
                return null;
            }));
            assertTrue(secondMutexRequested.await(10, TimeUnit.SECONDS));
            awaitMySqlWorkspaceMutex(secondConnectionId.get(), workspace.getId());
            releaseFirstInsert.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            releaseAndShutDown(executor, releaseFirstInsert);
        }

        assertEquals(9, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM disqualification_reason WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
        assertEquals(9, jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT code) FROM disqualification_reason WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
    }

    @Test
    void lifecycleRowlessDecisionSerializesBeforeAConcurrentFirstEdit() throws Exception {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setOwnerId(firstMember.getId());
        person.setName("Rowless lifecycle " + UUID.randomUUID().toString().substring(0, 8));
        personMapper.insert(person);
        authenticate(firstMember);
        PersonLifecycleRequest enterLifecycle = new PersonLifecycleRequest();
        enterLifecycle.setStage(PersonLifecycleStage.NEW);
        personLifecycleService.updateLifecycle(person.getId(), enterLifecycle);

        CountDownLatch lifecycleMissObserved = new CountDownLatch(1);
        CountDownLatch releaseLifecycleMiss = new CountDownLatch(1);
        CountDownLatch editMutexRequested = new CountDownLatch(1);
        AtomicLong editConnectionId = new AtomicLong();
        ProbePlan firstEditProbe = ProbePlan.before(WORKSPACE_LOCK, (invocation, executor) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())) {
                editConnectionId.set(currentConnectionId(executor));
                editMutexRequested.countDown();
            }
        });
        ProbePlan lifecycleProbe = ProbePlan.after(REASON_LOCK_BY_CODE, (invocation, result) -> {
            if (hasParameter(invocation, "workspaceId", workspace.getId())
                    && hasParameter(invocation, "code", PersonDisqualificationReason.NO_FIT)) {
                assertNull(singleResult(result));
                lifecycleMissObserved.countDown();
                assertTrue(releaseLifecycleMiss.await(30, TimeUnit.SECONDS));
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> lifecycle = executor.submit(() -> asMember(firstMember, lifecycleProbe, () -> {
                PersonLifecycleRequest request = new PersonLifecycleRequest();
                request.setStage(PersonLifecycleStage.DISQUALIFIED);
                request.setReason(PersonDisqualificationReason.NO_FIT);
                personLifecycleService.updateLifecycle(person.getId(), request);
                return null;
            }));
            assertTrue(lifecycleMissObserved.await(10, TimeUnit.SECONDS));
            Future<?> firstEdit = executor.submit(() -> asMember(secondMember, firstEditProbe, () -> {
                DisqualificationReasonRequest request = request(
                    PersonDisqualificationReason.NO_FIT, "Fit changed after transition", true, 1);
                reasonService.update(-2, request);
                return null;
            }));
            assertTrue(editMutexRequested.await(10, TimeUnit.SECONDS));
            awaitMySqlWorkspaceMutex(editConnectionId.get(), workspace.getId());
            releaseLifecycleMiss.countDown();
            lifecycle.get(20, TimeUnit.SECONDS);
            firstEdit.get(20, TimeUnit.SECONDS);
        } finally {
            releaseAndShutDown(executor, releaseLifecycleMiss);
        }

        assertEquals(PersonLifecycleStage.DISQUALIFIED,
            personMapper.getPersonById(workspace.getId(), person.getId()).getLifecycleStage());
        assertEquals(PersonDisqualificationReason.NO_FIT,
            personMapper.getPersonById(workspace.getId(), person.getId()).getDisqualifiedReason());
        assertEquals(9, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM disqualification_reason WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
        assertEquals("Fit changed after transition", jdbcTemplate.queryForObject(
            "SELECT label FROM disqualification_reason WHERE workspace_id = ? AND code = ?",
            String.class, workspace.getId(), PersonDisqualificationReason.NO_FIT));
    }

    private void editFallback(String label) {
        DisqualificationReasonRequest request = new DisqualificationReasonRequest();
        request.setCode(PersonDisqualificationReason.NO_BUDGET);
        request.setLabel(label);
        request.setRequiresNote(false);
        request.setPosition(0);
        reasonService.update(-1, request);
    }

    private static long currentConnectionId(Executor executor) throws SQLException {
        try (Statement statement = executor.getTransaction().getConnection().createStatement();
                ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            if (!result.next()) {
                throw new AssertionError("MySQL did not return the current connection id");
            }
            long connectionId = result.getLong(1);
            if (result.wasNull()) {
                throw new AssertionError("MySQL did not return the current connection id");
            }
            return connectionId;
        }
    }

    private static Object singleResult(Object result) {
        if (!(result instanceof List<?> rows)) {
            throw new AssertionError("A MyBatis query probe did not receive a result list");
        }
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() != 1) {
            throw new AssertionError("A single-row mapper query returned multiple rows");
        }
        return rows.getFirst();
    }

    private static boolean hasParameter(Invocation invocation, String name, Object expected) {
        Object parameter = invocation.getArgs()[1];
        if (!(parameter instanceof Map<?, ?> parameters)) {
            return false;
        }
        return Objects.equals(expected, parameters.get(name));
    }

    private void awaitMySqlWorkspaceMutex(long connectionId, int workspaceId) {
        if (connectionId <= 0) {
            throw new AssertionError("The waiting transaction did not expose its connection id");
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadlineNanos) {
            Integer waiting = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits lock_wait
                JOIN performance_schema.data_locks requested_lock
                  ON requested_lock.ENGINE = lock_wait.ENGINE
                 AND requested_lock.ENGINE_LOCK_ID = lock_wait.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blocking_lock
                  ON blocking_lock.ENGINE = lock_wait.ENGINE
                 AND blocking_lock.ENGINE_LOCK_ID = lock_wait.BLOCKING_ENGINE_LOCK_ID
                JOIN performance_schema.threads waiting_thread
                  ON waiting_thread.THREAD_ID = lock_wait.REQUESTING_THREAD_ID
                WHERE waiting_thread.PROCESSLIST_ID = ?
                  AND requested_lock.OBJECT_SCHEMA = DATABASE()
                  AND requested_lock.OBJECT_NAME = 'workspace'
                  AND requested_lock.INDEX_NAME = 'PRIMARY'
                  AND requested_lock.LOCK_TYPE = 'RECORD'
                  AND requested_lock.LOCK_MODE LIKE 'X%'
                  AND requested_lock.LOCK_STATUS = 'WAITING'
                  AND requested_lock.LOCK_DATA = CAST(? AS CHAR)
                  AND blocking_lock.OBJECT_SCHEMA = requested_lock.OBJECT_SCHEMA
                  AND blocking_lock.OBJECT_NAME = requested_lock.OBJECT_NAME
                  AND blocking_lock.INDEX_NAME = requested_lock.INDEX_NAME
                  AND blocking_lock.LOCK_TYPE = requested_lock.LOCK_TYPE
                  AND blocking_lock.LOCK_STATUS = 'GRANTED'
                  AND blocking_lock.LOCK_DATA = requested_lock.LOCK_DATA
                """,
                Integer.class,
                connectionId,
                workspaceId);
            if (waiting != null && waiting > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError(
            "The waiting transaction did not block on workspace PRIMARY key " + workspaceId);
    }

    private static void releaseAndShutDown(
            ExecutorService executor, CountDownLatch... releases) throws InterruptedException {
        for (CountDownLatch release : releases) {
            release.countDown();
        }
        executor.shutdown();
        if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertTrue(
                executor.awaitTermination(10, TimeUnit.SECONDS),
                "Concurrent transactions did not terminate after barrier release");
        }
    }

    private <T> T asMember(User actor, ProbePlan probe, Callable<T> work) {
        authenticate(actor);
        try {
            return statementProbe.execute(probe, work);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            tenantContext.clear();
        }
    }

    private static DisqualificationReasonRequest request(
            String code, String label, boolean requiresNote, int position) {
        DisqualificationReasonRequest request = new DisqualificationReasonRequest();
        request.setCode(code);
        request.setLabel(label);
        request.setRequiresNote(requiresNote);
        request.setPosition(position);
        return request;
    }

    private User newMember(String qualifier, String unique) {
        User member = new User();
        member.setUsername("reason-materialization-" + qualifier + "-" + unique);
        member.setDisplayName("Reason materialization " + qualifier + " " + unique);
        member.setEmail("reason-materialization-" + qualifier + "-" + unique + "@example.com");
        member.setPasswordHash("hash-" + unique);
        member.setTimezone("UTC");
        userMapper.insert(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        return member;
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(
            workspace.getId(), organization.getId(), user.getId(),
            workspaceMapper.getRole(workspace.getId(), user.getId()), null);
    }

    private record ProbePlan(String statementId, BeforeProbe before, AfterProbe after) {
        static ProbePlan before(String statementId, BeforeProbe before) {
            return new ProbePlan(statementId, before, (invocation, result) -> {
            });
        }

        static ProbePlan after(String statementId, AfterProbe after) {
            return new ProbePlan(statementId, (invocation, executor) -> {
            }, after);
        }
    }

    @FunctionalInterface
    private interface BeforeProbe {
        void execute(Invocation invocation, Executor executor) throws Throwable;
    }

    @FunctionalInterface
    private interface AfterProbe {
        void execute(Invocation invocation, Object result) throws Throwable;
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "update",
            args = { MappedStatement.class, Object.class }),
        @Signature(type = Executor.class, method = "query",
            args = { MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class })
    })
    static final class StatementProbe implements Interceptor {
        private final ThreadLocal<ProbePlan> armed = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            ProbePlan probe = armed.get();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (probe == null || !probe.statementId().equals(statement.getId())) {
                return invocation.proceed();
            }
            if (!(invocation.getTarget() instanceof Executor executor)) {
                throw new AssertionError("A MyBatis statement probe requires an Executor target");
            }
            probe.before().execute(invocation, executor);
            Object result = invocation.proceed();
            probe.after().execute(invocation, result);
            return result;
        }

        <T> T execute(ProbePlan probe, Callable<T> work) throws Exception {
            if (armed.get() != null) {
                throw new IllegalStateException("A statement probe is already armed on this thread");
            }
            armed.set(probe);
            try {
                return work.call();
            } finally {
                armed.remove();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        StatementProbe statementProbe() {
            return new StatementProbe();
        }
    }
}
