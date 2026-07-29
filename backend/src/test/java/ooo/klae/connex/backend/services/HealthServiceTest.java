package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.HealthService.Readiness;
import ooo.klae.connex.backend.services.HealthService.Status;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Flyway flyway;
    @Mock private MigrationInfoService migrationInfoService;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private ApplicationAvailability applicationAvailability;

    private TenantContext tenantContext;
    private HealthService healthService;

    @BeforeEach
    void setUp() throws Exception {
        tenantContext = new TenantContext();
        when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        healthService = new HealthService(dataSource, flyway, tenantWorkScope(), applicationAvailability);
    }

    private TenantWorkScope tenantWorkScope() {
        return new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper);
    }

    @Test
    void allChecksPassCloseConnectionAndReuseCache() throws Exception {
        stubDatabaseReady();
        stubMigrationsReady();

        assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), healthService.readiness());
        assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), healthService.readiness());

        verify(dataSource).getConnection();
        verify(connection).close();
        verify(flyway).info();
    }

    @Test
    void invalidDatabaseDoesNotHideMigrationReadiness() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);
        stubMigrationsReady();

        assertEquals(new Readiness(Status.DOWN, Status.UP, Status.UP), healthService.readiness());
    }

    @Test
    void throwingDatabaseDoesNotHideMigrationReadiness() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc:mysql://secret"));
        stubMigrationsReady();

        assertEquals(new Readiness(Status.DOWN, Status.UP, Status.UP), healthService.readiness());
    }

    @Test
    void pendingMigrationDoesNotHideDatabaseReadiness() throws Exception {
        stubDatabaseReady();
        when(flyway.info()).thenReturn(migrationInfoService);
        when(migrationInfoService.pending()).thenReturn(new MigrationInfo[] { mock(MigrationInfo.class) });

        assertEquals(new Readiness(Status.UP, Status.DOWN, Status.UP), healthService.readiness());
    }

    @Test
    void failedMigrationDoesNotHideDatabaseReadiness() throws Exception {
        stubDatabaseReady();
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getState()).thenReturn(MigrationState.FAILED);
        when(flyway.info()).thenReturn(migrationInfoService);
        when(migrationInfoService.pending()).thenReturn(new MigrationInfo[0]);
        when(migrationInfoService.all()).thenReturn(new MigrationInfo[] { migration });

        assertEquals(new Readiness(Status.UP, Status.DOWN, Status.UP), healthService.readiness());
    }

    @Test
    void throwingMigrationInspectionDoesNotHideDatabaseReadiness() throws Exception {
        stubDatabaseReady();
        when(flyway.info()).thenThrow(new IllegalStateException("V999__secret.sql"));

        assertEquals(new Readiness(Status.UP, Status.DOWN, Status.UP), healthService.readiness());
    }

    @Test
    void startupStillRunningFailsReadinessWithoutHidingDependencyChecks() throws Exception {
        when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        stubDatabaseReady();
        stubMigrationsReady();

        Readiness readiness = healthService.readiness();

        assertEquals(new Readiness(Status.UP, Status.UP, Status.DOWN), readiness);
        assertFalse(readiness.isUp());
    }

    @Test
    void startupCompletionIsObservedWithoutWaitingForTheProbeCacheToExpire() throws Exception {
        when(applicationAvailability.getReadinessState())
                .thenReturn(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        stubDatabaseReady();
        stubMigrationsReady();

        assertEquals(new Readiness(Status.UP, Status.UP, Status.DOWN), healthService.readiness());
        assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), healthService.readiness());

        verify(dataSource).getConnection();
        verify(flyway).info();
    }

    @Test
    void probesOnDefaultCatalogAndRestoresTenantCatalog() throws Exception {
        tenantContext.set(7, 8, 9, "member", "connex_tenant");
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return connection;
        });
        when(connection.isValid(2)).thenReturn(true);
        when(flyway.info()).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return migrationInfoService;
        });
        when(migrationInfoService.pending()).thenReturn(new MigrationInfo[0]);
        when(migrationInfoService.all()).thenReturn(new MigrationInfo[0]);

        assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), healthService.readiness());
        assertEquals("connex_tenant", tenantContext.getCatalog());
    }

    @Test
    void concurrentCallersShareOneProbe() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return connection;
        });
        when(connection.isValid(2)).thenReturn(true);
        stubMigrationsReady();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Readiness> first = executor.submit(healthService::readiness);
            entered.await(5, TimeUnit.SECONDS);
            Future<Readiness> second = executor.submit(healthService::readiness);
            release.countDown();

            assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), first.get(5, TimeUnit.SECONDS));
            assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), second.get(5, TimeUnit.SECONDS));
            verify(dataSource).getConnection();
            verify(flyway).info();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void servesStaleSnapshotInsteadOfQueueingBehindASlowProbe() throws Exception {
        HealthService alwaysStale =
                new HealthService(dataSource, flyway, tenantWorkScope(), applicationAvailability, 0L);
        stubDatabaseReady();
        stubMigrationsReady();
        assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), alwaysStale.readiness());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            throw new SQLException("database gone");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Readiness> slowProbe = executor.submit(alwaysStale::readiness);
            entered.await(5, TimeUnit.SECONDS);

            assertEquals(new Readiness(Status.UP, Status.UP, Status.UP), alwaysStale.readiness());

            release.countDown();
            assertEquals(new Readiness(Status.DOWN, Status.UP, Status.UP), slowProbe.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private void stubDatabaseReady() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
    }

    private void stubMigrationsReady() {
        when(flyway.info()).thenReturn(migrationInfoService);
        when(migrationInfoService.pending()).thenReturn(new MigrationInfo[0]);
        when(migrationInfoService.all()).thenReturn(new MigrationInfo[0]);
    }
}
