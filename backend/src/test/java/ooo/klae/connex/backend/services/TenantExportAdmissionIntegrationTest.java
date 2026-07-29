package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.TenantExportBlockingCursorMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises tenant-export admission and cancellation against real MySQL locks and connections. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantExportAdmissionIntegrationTest extends AbstractServiceTest {
    private static final int MYSQL_NOWAIT_ERROR = 3572;

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TenantLifecycleControlOperations controlOperations;
    @Autowired private TenantExportQueryCancellationInterceptor queryCancellationInterceptor;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private Environment environment;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private TenantLifecycleControlMapper lifecycleMapperSpy;
    @MockitoSpyBean private UserMapper userMapperSpy;

    private final List<Integer> organizationIds = new ArrayList<>();

    @AfterEach
    void cleanAdmissionFixtures() {
        for (int orgId : organizationIds) {
            jdbcTemplate.update(
                "DELETE FROM tenant_operation_lease WHERE org_id = ?",
                orgId);
            jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", orgId);
            jdbcTemplate.update("DELETE FROM workspace WHERE org_id = ?", orgId);
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", orgId);
        }
        organizationIds.clear();
        if (currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE user_id = ?",
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
        jdbcTemplate.update(
            "UPDATE tenant_export_admission_control SET capacity = 4 WHERE id = 1");
    }

    @Test
    void capacityIsGlobalAcrossOrganizationsAndReleaseRequiresTheExactToken() {
        Fixture first = fixture("first");
        Fixture second = fixture("second");
        int existing = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_operation_lease WHERE lease_kind = 'export'",
            Integer.class);
        jdbcTemplate.update(
            "UPDATE tenant_export_admission_control SET capacity = ? WHERE id = 1",
            existing + 1);

        AcquiredWorkspace acquired = acquire(first);

        assertThrows(TooManyRequestsException.class, () -> acquire(second));
        OperationLease wrongToken = new OperationLease(
            acquired.lease().orgId(),
            acquired.lease().workspaceId(),
            acquired.lease().kind(),
            UUID.randomUUID().toString());
        assertThrows(
            IllegalStateException.class,
            () -> release(wrongToken));
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_operation_lease WHERE lease_token = ?",
                Integer.class,
                acquired.lease().token()));

        release(acquired.lease());

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_operation_lease WHERE lease_token = ?",
                Integer.class,
                acquired.lease().token()));
    }

    @Test
    void singletonNowaitContentionReturnsImmediatelyWithoutInsertingALease() throws Exception {
        Fixture fixture = fixture("nowait");
        try (Connection blocker = dataSource.getConnection();
                PreparedStatement lock = blocker.prepareStatement(
                    "SELECT capacity FROM tenant_export_admission_control"
                        + " WHERE id = 1 FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lock.executeQuery();

            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<AcquiredWorkspace> attempt = executor.submit(() -> acquire(fixture));
                ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attempt.get(2, TimeUnit.SECONDS));

                assertInstanceOf(TooManyRequestsException.class, failure.getCause());
            }
            assertEquals(
                0,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant_operation_lease WHERE workspace_id = ?",
                    Integer.class,
                    fixture.workspaceId()));
            blocker.rollback();
        }
    }

    @Test
    void invalidPersistedCapacityFailsClosedWithoutInsertingALease() throws Exception {
        Fixture fixture = fixture("invalid-capacity");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                "ALTER TABLE tenant_export_admission_control"
                    + " ALTER CHECK chk_tenant_export_admission_capacity NOT ENFORCED");
            try {
                for (int invalid : List.of(0, 5)) {
                    statement.executeUpdate(
                        "UPDATE tenant_export_admission_control SET capacity = "
                            + invalid
                            + " WHERE id = 1");

                    assertThrows(
                        IllegalStateException.class,
                        () -> acquire(fixture));
                    assertEquals(
                        0,
                        jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM tenant_operation_lease WHERE workspace_id = ?",
                            Integer.class,
                            fixture.workspaceId()));
                }
            } finally {
                statement.executeUpdate(
                    "UPDATE tenant_export_admission_control SET capacity = 4 WHERE id = 1");
                statement.execute(
                    "ALTER TABLE tenant_export_admission_control"
                        + " ALTER CHECK chk_tenant_export_admission_capacity ENFORCED");
            }
        }
    }

    @Test
    void realLocksProveUserThenWorkspaceThenOrganizationThenMemberOrder() throws Exception {
        Fixture fixture = fixture("order");
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        try (Connection userBlocker = dataSource.getConnection()) {
            userBlocker.setAutoCommit(false);
            lockExact(
                userBlocker,
                "SELECT id FROM app_user WHERE id = ? FOR UPDATE",
                currentUser.getId());
            CountDownLatch userLockEntered = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                userLockEntered.countDown();
                return realUserMapper.lockByIdForShare(currentUser.getId());
            }).when(userMapperSpy).lockByIdForShare(currentUser.getId());
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<AcquiredWorkspace> attempt = executor.submit(() -> acquire(fixture));
                assertTrue(userLockEntered.await(2, TimeUnit.SECONDS));
                assertCanLockNowait(
                    "SELECT id FROM workspace WHERE id = ? FOR UPDATE NOWAIT",
                    fixture.workspaceId());
                assertCanLockNowait(
                    "SELECT id FROM organization WHERE id = ? FOR UPDATE NOWAIT",
                    fixture.orgId());
                userBlocker.rollback();
                release(attempt.get(2, TimeUnit.SECONDS).lease());
            }
        }

        TenantLifecycleControlMapper realLifecycleMapper =
            sqlSessionTemplate.getMapper(TenantLifecycleControlMapper.class);
        try (Connection workspaceBlocker = dataSource.getConnection()) {
            workspaceBlocker.setAutoCommit(false);
            lockExact(
                workspaceBlocker,
                "SELECT id FROM workspace WHERE id = ? FOR UPDATE",
                fixture.workspaceId());
            CountDownLatch workspaceLockEntered = new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                workspaceLockEntered.countDown();
                return realLifecycleMapper.lockWorkspaceForShare(fixture.workspaceId());
            }).when(lifecycleMapperSpy).lockWorkspaceForShare(fixture.workspaceId());
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<AcquiredWorkspace> attempt = executor.submit(() -> acquire(fixture));
                assertTrue(workspaceLockEntered.await(2, TimeUnit.SECONDS));
                assertCanLockNowait(
                    "SELECT id FROM organization WHERE id = ? FOR UPDATE NOWAIT",
                    fixture.orgId());
                workspaceBlocker.rollback();
                release(attempt.get(2, TimeUnit.SECONDS).lease());
            }
        }

        try (Connection actorBlocker = dataSource.getConnection()) {
            actorBlocker.setAutoCommit(false);
            lockExact(
                actorBlocker,
                "SELECT user_id FROM org_member"
                    + " WHERE org_id = ? AND user_id = ? FOR UPDATE",
                fixture.orgId(),
                currentUser.getId());
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<AcquiredWorkspace> attempt = executor.submit(() -> acquire(fixture));
                awaitNowaitConflict(
                    "SELECT id FROM workspace WHERE id = ? FOR UPDATE NOWAIT",
                    fixture.workspaceId());
                awaitNowaitConflict(
                    "SELECT id FROM organization WHERE id = ? FOR UPDATE NOWAIT",
                    fixture.orgId());
                assertTrue(canLockAdmissionSingletonNowait());
                actorBlocker.rollback();
                release(attempt.get(2, TimeUnit.SECONDS).lease());
            }
        }
    }

    @Test
    void cancellingABlockedQueryCancelsItsStatementAndReturnsTheConnection() throws Exception {
        ScheduledThreadPoolExecutor deadlineExecutor =
            new ScheduledThreadPoolExecutor(1);
        deadlineExecutor.setRemoveOnCancelPolicy(true);
        ThreadPoolExecutor cancellationExecutor = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(4));
        cancellationExecutor.prestartAllCoreThreads();
        AtomicBoolean leaseReleased = new AtomicBoolean();
        TenantExportExecution execution = new TenantExportExecution(
            Duration.ofMinutes(1),
            cancellationExecutor,
            failure -> {
                leaseReleased.set(true);
                return failure;
            });
        execution.armDeadline(deadlineExecutor);
        execution.begin();
        CountDownLatch firstRowRead = new CountDownLatch(1);
        AtomicReference<Long> connectionId = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        try (HikariDataSource queryPool = queryPool();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SqlSessionFactory sqlSessionFactory = blockingCursorSessionFactory(queryPool);
            Future<?> blocked = executor.submit(() -> {
                Throwable primary = null;
                try (SqlSession session = sqlSessionFactory.openSession();
                        TenantExportQueryCancellationInterceptor.Scope ignored =
                            TenantExportQueryCancellationInterceptor.openScope(execution)) {
                    TenantExportBlockingCursorMapper mapper =
                        session.getMapper(TenantExportBlockingCursorMapper.class);
                    try (Cursor<Map<String, Object>> cursor = mapper.queryCursor()) {
                        Iterator<Map<String, Object>> rows = cursor.iterator();
                        Map<String, Object> first = rows.next();
                        connectionId.set(connectionId(first));
                        firstRowRead.countDown();
                        rows.next();
                        throw new AssertionError("Cancelled cursor returned its blocked row");
                    }
                } catch (Throwable exception) {
                    primary = exception;
                    workerFailure.set(exception);
                } finally {
                    try {
                        execution.writerFinished(primary);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                }
            });
            assertTrue(
                firstRowRead.await(30, TimeUnit.SECONDS),
                () -> "Query worker failed before execution: " + workerFailure.get());
            awaitBlockingCursorQuery(connectionId.get());

            execution.cancel();
            blocked.get(3, TimeUnit.SECONDS);
            assertEquals(0, queryPool.getHikariPoolMXBean().getActiveConnections());
        } finally {
            deadlineExecutor.shutdownNow();
            cancellationExecutor.shutdownNow();
        }

        assertTrue(workerFailure.get() != null);
        assertTrue(leaseReleased.get());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Integer.class));
    }

    private HikariDataSource queryPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        config.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        config.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setInitializationFailTimeout(10_000);
        config.setPoolName("tenant-export-query-cancellation-it");
        return new HikariDataSource(config);
    }

    private SqlSessionFactory blockingCursorSessionFactory(DataSource queryPool) {
        org.apache.ibatis.mapping.Environment myBatisEnvironment =
            new org.apache.ibatis.mapping.Environment(
                "tenant-export-blocking-cursor",
                new JdbcTransactionFactory(),
                queryPool);
        Configuration configuration = new Configuration(myBatisEnvironment);
        configuration.addInterceptor(queryCancellationInterceptor);
        configuration.addMapper(TenantExportBlockingCursorMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void awaitBlockingCursorQuery(long connectionId) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            int running = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.PROCESSLIST
                WHERE ID = ?
                  AND COMMAND = 'Query'
                  AND INFO LIKE '%SLEEP(30)%'
                  AND INFO LIKE '%export_rows.sequenceId%'
                """,
                Integer.class,
                connectionId);
            if (running == 1) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Exact blocking cursor query did not reach MySQL");
    }

    private static long connectionId(Map<String, Object> row) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if ("connectionId".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        throw new AssertionError("Blocking cursor did not return its connection id");
    }

    private Fixture fixture(String label) {
        String suffix = label + "-" + UUID.randomUUID();
        Organization organization = new Organization();
        organization.setName("Export Admission " + label);
        organization.setSlug("export-admission-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());

        Workspace target = new Workspace();
        target.setOrgId(organization.getId());
        target.setName("Export Admission " + label);
        target.setSlug("export-admission-workspace-" + suffix);
        workspaceMapper.insert(target);
        jdbcTemplate.update(
            "INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')",
            organization.getId(),
            currentUser.getId());
        return new Fixture(organization.getId(), target.getId());
    }

    private AcquiredWorkspace acquire(Fixture fixture) {
        return tenantWorkScope.unrouted(() ->
            controlOperations.acquireExport(
                fixture.orgId(),
                fixture.workspaceId(),
                currentUser.getId()));
    }

    private void release(OperationLease lease) {
        tenantWorkScope.unrouted(() -> {
            controlOperations.release(lease);
            return null;
        });
    }

    private void awaitNowaitConflict(String sql, int... parameters) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                connection.setAutoCommit(false);
                bind(statement, parameters);
                try {
                    statement.executeQuery();
                    connection.rollback();
                } catch (SQLException exception) {
                    connection.rollback();
                    if (exception.getErrorCode() == MYSQL_NOWAIT_ERROR) {
                        return;
                    }
                    throw exception;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected a MySQL NOWAIT lock conflict");
    }

    private boolean canLockAdmissionSingletonNowait() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeQuery(
                    "SELECT capacity FROM tenant_export_admission_control"
                        + " WHERE id = 1 FOR UPDATE NOWAIT");
                connection.rollback();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            }
        }
    }

    private void assertCanLockNowait(String sql, int... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lockExact(connection, sql, parameters);
            connection.rollback();
        }
    }

    private static void lockExact(
            Connection connection,
            String sql,
            int... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
            }
        }
    }

    private static void bind(PreparedStatement statement, int... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setInt(index + 1, parameters[index]);
        }
    }

    private record Fixture(int orgId, int workspaceId) {
    }
}
