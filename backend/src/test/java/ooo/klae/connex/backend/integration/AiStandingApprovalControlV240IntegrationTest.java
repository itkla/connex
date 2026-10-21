package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import ooo.klae.connex.backend.beans.AiStandingApprovalPolicy;
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Real MySQL online-DDL, mapper persistence and physical policy/role contention drills. */
class AiStandingApprovalControlV240IntegrationTest {
    private MySqlScratchCatalog catalog;
    private UnpooledDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;

    @BeforeEach
    void retainedControlSchema() throws Exception {
        String username = Objects.requireNonNull(System.getenv("CONNEX_DB_USERNAME"), "MySQL username required");
        String password = Objects.requireNonNull(System.getenv("CONNEX_DB_PASSWORD"), "MySQL password required");
        catalog = MySqlScratchCatalog.create(Objects.requireNonNull(System.getenv("CONNEX_DB_URL")),
                username, password,
                "standing_v240_", "utf8mb4", "utf8mb4_unicode_ci");
        dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", catalog.url(), username, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE organization (id INT PRIMARY KEY, lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'active') ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workspace_member (workspace_id INT NOT NULL, user_id INT NOT NULL, membership_id BIGINT NOT NULL AUTO_INCREMENT UNIQUE, role VARCHAR(16) NOT NULL, role_id INT NULL, status VARCHAR(16) NOT NULL DEFAULT 'active', grant_authorization_version INT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(workspace_id,user_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workspace_role (id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(128) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workspace_role_permission (workspace_role_id INT NOT NULL, permission VARCHAR(64) NOT NULL, PRIMARY KEY(workspace_role_id,permission)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO organization (id) VALUES (1), (2)");
        jdbc.update("INSERT INTO workspace_member (workspace_id,user_id,role) VALUES (10,7,'member')");
        jdbc.update("INSERT INTO workspace_role (id,workspace_id,name) VALUES (12,10,'Reviewed role')");
        migrate();
        Configuration configuration = new Configuration(new Environment("standing-control", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String name : List.of("AiStandingApprovalPolicyMapper", "WorkspaceMapper", "RoleMapper")) {
            String path = "mappers/" + name + ".xml";
            try (var input = Files.newInputStream(Path.of("src/main/resources", path))) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    private void migrate() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    "src/main/resources/db/migration/control/V240__ai_standing_approval_control.sql"));
        }
    }

    @AfterEach
    void dropCatalog() throws SQLException {
        if (catalog != null) catalog.close();
    }

    @Test
    void retainedRowsReceiveEpochsWithoutAConfigurationBackfill() {
        assertEquals(1L, jdbc.queryForObject("SELECT authorization_epoch FROM workspace_member", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT permission_epoch FROM workspace_role", Long.class));
        assertEquals("member", jdbc.queryForObject("SELECT role FROM workspace_member", String.class));
        assertEquals("Reviewed role", jdbc.queryForObject("SELECT name FROM workspace_role", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM organization_ai_standing_approval_policy", Integer.class));
        try (SqlSession session = sessions.openSession()) {
            var policies = session.getMapper(AiStandingApprovalPolicyMapper.class);
            policies.provision(1);
            policies.provision(1);
            AiStandingApprovalPolicy policy = policies.get(1);
            assertFalse(policy.isEnabled());
            assertEquals(0, policy.getAllowedToolMask());
            assertEquals(1L, policy.getStateVersion());
            assertEquals(1L, policy.getKillEpoch());
            assertEquals(100, policy.getMaximumDailyCap());
            assertEquals(10, policy.getMaximumPerRunCap());
            assertNull(policies.get(2));
            session.commit(true);
        }
        jdbc.update("DELETE FROM organization WHERE id=1");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM organization_ai_standing_approval_policy", Integer.class));
    }

    @Test
    void membershipAndRoleDownUpPersistMonotonicHistoryAndOverflowRollsBack() {
        try (SqlSession session = sessions.openSession()) {
            var members = session.getMapper(WorkspaceMapper.class);
            var roles = session.getMapper(RoleMapper.class);
            long membershipId = members.lockAuthorizationMembership(10, 7).getMembershipId();
            members.setMemberCustomRole(10, 7, 12);
            members.updateMemberRole(10, 7, "admin");
            members.updateMemberRole(10, 7, "member");
            var restored = members.lockAuthorizationMembership(10, 7);
            assertEquals(membershipId, restored.getMembershipId());
            assertEquals(4L, restored.getAuthorizationEpoch());
            roles.lockRole(10, 12);
            roles.advancePermissionEpoch(10, 12);
            roles.insertPermissions(10, 12, List.of("AI_USE"));
            roles.advancePermissionEpoch(10, 12);
            roles.clearPermissions(10, 12);
            roles.advancePermissionEpoch(10, 12);
            roles.insertPermissions(10, 12, List.of("AI_USE"));
            assertEquals(4L, roles.lockPermissionEpoch(10, 12));
            session.commit(true);
        }
        jdbc.update("UPDATE workspace_member SET authorization_epoch=?", Long.MAX_VALUE);
        jdbc.update("UPDATE workspace_role SET permission_epoch=?", Long.MAX_VALUE);
        try (SqlSession session = sessions.openSession()) {
            assertThrows(RuntimeException.class, () -> session.getMapper(WorkspaceMapper.class).updateMemberRole(10, 7, "owner"));
            session.rollback(true);
            assertThrows(RuntimeException.class, () -> session.getMapper(RoleMapper.class).advancePermissionEpoch(10, 12));
            session.rollback(true);
        }
        assertEquals("member", jdbc.queryForObject("SELECT role FROM workspace_member", String.class));
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT authorization_epoch FROM workspace_member", Long.class));
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT permission_epoch FROM workspace_role", Long.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void policyReadersAndKillWritersSerializeAtTheExactPolicyRow(boolean readerFirst) throws Exception {
        jdbc.update("INSERT INTO organization_ai_standing_approval_policy(org_id) VALUES (1)");
        AtomicLong waitingConnection = new AtomicLong();
        CountDownLatch atPolicyLock = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor(); SqlSession holder = sessions.openSession()) {
            var policy = holder.getMapper(AiStandingApprovalPolicyMapper.class);
            if (readerFirst) policy.lockForShare(1);
            else policy.lockForUpdate(1);
            var waiter = executor.submit(() -> {
                try (SqlSession session = sessions.openSession()) {
                    waitingConnection.set(connectionId(session));
                    var mapper = session.getMapper(AiStandingApprovalPolicyMapper.class);
                    atPolicyLock.countDown();
                    AiStandingApprovalPolicy current = readerFirst ? mapper.lockForUpdate(1) : mapper.lockForShare(1);
                    if (readerFirst) {
                        current.setKillEpoch(current.getKillEpoch() + 1);
                        current.setStateVersion(current.getStateVersion() + 1);
                        assertEquals(1, mapper.replace(current));
                    }
                    session.commit(true);
                    return current.getKillEpoch();
                }
            });
            try {
                assertTrue(atPolicyLock.await(10, TimeUnit.SECONDS));
                if (readerFirst) MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(),
                        "organization_ai_standing_approval_policy", "1");
                else MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, waitingConnection.get(),
                        "organization_ai_standing_approval_policy", "1");
                assertFalse(waiter.isDone());
                if (!readerFirst) {
                    var current = policy.get(1);
                    current.setKillEpoch(2);
                    current.setStateVersion(2);
                    assertEquals(1, policy.replace(current));
                }
            } finally {
                holder.commit(true);
            }
            assertEquals(2L, waiter.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentProvisioningWaitsAtUniquePolicyKeyAndPreservesOneDisabledRow() throws Exception {
        AtomicLong connection = new AtomicLong();
        CountDownLatch atInsert = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor(); SqlSession holder = sessions.openSession()) {
            holder.getMapper(AiStandingApprovalPolicyMapper.class).provision(1);
            var waiter = executor.submit(() -> {
                try (SqlSession session = sessions.openSession()) {
                    connection.set(connectionId(session));
                    atInsert.countDown();
                    session.getMapper(AiStandingApprovalPolicyMapper.class).provision(1);
                    session.commit(true);
                    return null;
                }
            });
            try {
                assertTrue(atInsert.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(),
                        "organization_ai_standing_approval_policy", "1");
            } finally {
                holder.commit(true);
            }
            waiter.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM organization_ai_standing_approval_policy WHERE org_id=1 AND enabled=FALSE AND allowed_tool_mask=0 AND state_version=1 AND kill_epoch=1", Integer.class));
    }

    @Test
    void permissionRestorationWhileAuthorizationWaitsReturnsTheAdvancedEpoch() throws Exception {
        jdbc.update("INSERT INTO workspace_role_permission(workspace_role_id, permission) VALUES (12,'AI_USE')");
        AtomicLong connection = new AtomicLong();
        CountDownLatch atRoleLock = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor(); SqlSession holder = sessions.openSession()) {
            var roles = holder.getMapper(RoleMapper.class);
            roles.lockRole(10, 12);
            var waiter = executor.submit(() -> {
                try (SqlSession session = sessions.openSession()) {
                    connection.set(connectionId(session));
                    atRoleLock.countDown();
                    long epoch = session.getMapper(RoleMapper.class).lockPermissionEpoch(10, 12);
                    session.commit(true);
                    return epoch;
                }
            });
            try {
                assertTrue(atRoleLock.await(10, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "workspace_role", "12");
                roles.advancePermissionEpoch(10, 12);
                roles.clearPermissions(10, 12);
                roles.advancePermissionEpoch(10, 12);
                roles.insertPermissions(10, 12, List.of("AI_USE"));
            } finally {
                holder.commit(true);
            }
            assertEquals(3L, waiter.get(15, TimeUnit.SECONDS));
        }
    }

    private long connectionId(SqlSession session) throws SQLException {
        try (var statement = session.getConnection().createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
