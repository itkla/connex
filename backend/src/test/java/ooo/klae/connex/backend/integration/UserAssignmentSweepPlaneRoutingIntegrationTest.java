package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DepartureApprovalGuard;
import ooo.klae.connex.backend.services.OwnershipOfferAccountCleanupService;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.services.UserAccountCatalogOffboardingService;
import ooo.klae.connex.backend.services.UserOffboardingService;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Real catalog fan-out, control-only absence reads, collision safety, and bounded recovery. */
class UserAssignmentSweepPlaneRoutingIntegrationTest {
    private static final List<String> TABLES = List.of("company", "person", "deal", "deal_collaborator", "task",
        "workflow_intervention", "campaign", "person_lifecycle_pass");
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String first = "cnx_sweep_a_" + suffix;
    private final String second = "cnx_sweep_b_" + suffix;
    private final String recovery = "cnx_sweep_r_" + suffix;
    private HikariDataSource pool;
    private JdbcTemplate control;
    private AssignmentSweepMapper mapper;
    private AssignmentSweepMapper realMapper;
    private UserAccountCatalogOffboardingService service;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private String defaultCatalog;
    private int survivor;
    private int absent;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("CONNEX_DB_URL");
        assertNotNull(url, "Database execution is required");
        assertNotNull(System.getenv("CONNEX_DB_USERNAME"), "Database execution is required");
        assertNotNull(System.getenv("CONNEX_DB_PASSWORD"), "Database execution is required");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assertNotNull(defaultCatalog);
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(System.getenv("CONNEX_DB_USERNAME"));
        pool.setPassword(System.getenv("CONNEX_DB_PASSWORD"));
        pool.setMaximumPoolSize(4);
        control = new JdbcTemplate(pool);
        assertTrue(control.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class) > 0);
        createCatalog(first);
        createCatalog(second);
        String username = "sweep_" + suffix;
        control.update("INSERT INTO app_user (username, display_name, email, password_hash) VALUES (?, 'Sweep fixture', ?, 'hash')",
            username, username + "@example.test");
        survivor = control.queryForObject("SELECT id FROM app_user WHERE username = ?", Integer.class, username);
        absent = survivor + 1000000;
        TenantContext context = new TenantContext();
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        var routing = TenantRoutingConfig.decorate(pool, properties, context);
        Configuration configuration = new Configuration(new Environment("assignment-sweep-routing",
            new SpringManagedTransactionFactory(), routing));
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ControlCatalogRoutingInterceptor(context, true));
        configuration.addInterceptor(new TenantScopeInterceptor(context, true, true));
        for (String resource : List.of("mappers/AssignmentSweepMapper.xml", "mappers/UserMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        session.getMapper(UserMapper.class).reserveAccountDeletion(survivor, UUID.randomUUID().toString());
        realMapper = session.getMapper(AssignmentSweepMapper.class);
        mapper = spy(realMapper);
        PlacementRegistry placements = mock(PlacementRegistry.class);
        when(placements.activeCatalogs()).thenReturn(Arrays.asList(null, first, second, recovery));
        TenantWorkScope scope = new TenantWorkScope(context, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        service = new UserAccountCatalogOffboardingService(mock(DepartureApprovalGuard.class), placements,
            mock(UserOffboardingService.class),
            mock(ProviderCapturePurgeService.class), scope, new DataSourceTransactionManager(routing), mapper,
            session.getMapper(UserMapper.class), metrics, mock(OwnershipOfferAccountCleanupService.class));
        ReflectionTestUtils.setField(service, "pageSize", 2);
    }

    @AfterEach
    void cleanUp() {
        if (control != null) {
            for (String catalog : List.of(first, second, recovery)) {
                control.execute("DROP DATABASE IF EXISTS " + catalog);
            }
            if (survivor > 0) {
                control.update("DELETE FROM app_user WHERE id = ?", survivor);
            }
        }
        if (pool != null) {
            pool.close();
        }
    }

    private void createCatalog(String catalog) {
        control.execute("CREATE DATABASE " + catalog);
        for (String table : TABLES) {
            control.execute("CREATE TABLE " + catalog + "." + table + " LIKE " + defaultCatalog + "." + table);
        }
    }

    @Test
    void collidingIdsUseTheirPinnedCatalogAndOnlyControlIdentitiesDetermineAbsence() {
        control.execute("CREATE TABLE " + first + ".app_user (id INT PRIMARY KEY)");
        control.update("INSERT INTO " + first + ".app_user VALUES (?)", absent);
        control.update("INSERT INTO " + first + ".company (id, workspace_id, name, owner_id) VALUES (501, 7, 'orphan', ?)", absent);
        control.update("INSERT INTO " + second + ".company (id, workspace_id, name, owner_id) VALUES (501, 8, 'survivor', ?)", survivor);
        service.sweepAssignmentsAfterDeletion(absent);
        assertEquals(0, referenceCount(first, "company", "owner_id", absent));
        assertEquals(1, referenceCount(second, "company", "owner_id", survivor));
        assertTrue(metrics.get("connex.account.assignment.sweep.failures").tags("mode", "immediate", "phase", "page").counter().count() > 0);
        createCatalog(recovery);
        control.update("INSERT INTO " + recovery + ".company (id, workspace_id, name, owner_id) VALUES (501, 9, 'recovered', ?)", absent);
        service.sweepOrphanAssignments();
        assertEquals(0, referenceCount(recovery, "company", "owner_id", absent));
        assertEquals(1, referenceCount(second, "company", "owner_id", survivor));
    }

    @Test
    void collaboratorTupleWatermarkWrapsWithAppendsOnTheSameDealAndRecoversAFailedSeat() {
        createCatalog(recovery);
        control.update("INSERT INTO " + first + ".deal (id, workspace_id, name, pipeline_id, stage_id) VALUES (501, 7, 'parent', 1, 1)");
        control.update("""
            INSERT INTO %s.deal_collaborator (workspace_id, deal_id, user_id)
            SELECT 7, 501, ? + n FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) ids
            """.formatted(first), absent);
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(call -> {
            AssignmentSweepMapper.Reference ref = call.getArgument(0);
            if (ref.userId() == absent && failFirst.getAndSet(false)) {
                throw new IllegalStateException("retry next traversal");
            }
            return realMapper.removeCollaborator(ref);
        }).when(mapper).removeCollaborator(any());
        for (int run = 0; run < 5; run++) {
            service.sweepOrphanAssignments();
            control.update("""
                INSERT INTO %s.deal_collaborator (workspace_id, deal_id, user_id)
                SELECT 7, 501, ? + n FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2) ids
                """.formatted(first), absent + 10 + run * 3);
        }
        assertFalse(failFirst.get());
        assertEquals(0, referenceCount(first, "deal_collaborator", "user_id", absent));
        assertTrue(referenceCount(first, "deal_collaborator", "user_id", absent + 22) > 0);
        assertEquals(1, metrics.get("connex.account.assignment.sweep.failures")
            .tags("mode", "periodic", "phase", "record").counter().count());
    }

    private int referenceCount(String catalog, String table, String column, int userId) {
        return control.queryForObject("SELECT COUNT(*) FROM " + catalog + "." + table + " WHERE " + column + " = ?",
            Integer.class, userId);
    }
}
