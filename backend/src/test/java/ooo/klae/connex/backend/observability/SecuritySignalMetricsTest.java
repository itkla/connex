package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.mappers.*;
import ooo.klae.connex.backend.services.AuditIntegrityService;
import tools.jackson.databind.ObjectMapper;

class SecuritySignalMetricsTest {
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final SecuritySignalMetrics metrics = new SecuritySignalMetrics(registry,
            Clock.fixed(Instant.ofEpochSecond(2_000_000_000), ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {
            "workspace.role.create", "workspace.role.update", "workspace.role.delete",
            "workspace.member.role", "org.member.set", "org.member.founding_owner",
            "workspace.member.join", "workspace.member.remove", "workspace.member.leave",
            "org.member.remove", "org.workspace_member.sso_provision", "workspace.invite.accept",
            "workspace.share", "workspace.unshare", "workspace.invite_link.accept",
            "org.workspace.create", "user.delete"
    })
    void effectiveAccessActionsWaitForCommitAndExportOnlyScope(String action) throws Exception {
        AuditLog audit = entry(action, "success", action.startsWith("org.") ? null : 7);
        audit.setOrgId(42);
        String scope = action.startsWith("org.") ? "organization:42" : "workspace:7";
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.observeAudit(audit, false);
            assertTrue(registry.getMeters().isEmpty());
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            assertTrue(registry.getMeters().isEmpty());
            audit.setOutcome("failure");
            metrics.observeAudit(audit, false);
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            audit.setOutcome("success");
            metrics.observeAudit(audit, false);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            var gauge = registry.get("connex.security.permission.change.timestamp").gauge();
            assertEquals(2_000_000_000, gauge.value());
            assertEquals(java.util.List.of(io.micrometer.core.instrument.Tag.of("scope", scope)),
                    gauge.getId().getTags());
            String scrape = registry.scrape();
            assertFalse(scrape.contains("sensitive"));
            assertFalse(scrape.contains("@"));
            Path directory = Path.of("build/security-alerts", action);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("membership.prom"), scrape);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void permissionSignalWaitsForCommitAndRollbackDoesNotEmit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.observeAudit(entry("workspace.member.role", "success", 7), false);
            assertNull(registry.find("connex.security.permission.change.timestamp").gauge());
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            metrics.observeAudit(entry("workspace.role.update", "success", 8), false);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            assertNull(registry.find("connex.security.permission.change.timestamp").tag("scope", "workspace:7").gauge());
            assertEquals(2_000_000_000, registry.get("connex.security.permission.change.timestamp")
                    .tag("scope", "workspace:8").gauge().value());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void failedRoleChangesAndUnrelatedFailuresDoNotEmit() {
        metrics.observeAudit(entry("workspace.role.update", "failure", 7), true);
        metrics.observeAudit(entry("auth.register", "failure", 7), true);
        metrics.observeAudit(entry("auth.login", "success", 7), true);
        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    void realHmacCheckAndSecurityEmissionProduceContentFreePrometheusFixtures() throws Exception {
        for (String scope : new String[]{"workspace:7", "workspace:8", "unattributed"}) {
            registry.counter("connex.security.authentication.failures", "scope", scope);
        }
        Path directory = Path.of("build/security-alerts");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("baseline.prom"), registry.scrape());
        for (int i = 0; i < 20; i++) {
            metrics.observeAudit(entry("auth.login.passkey", "failure", 7), true);
        }
        for (int i = 0; i < 100; i++) {
            metrics.observeAudit(entry("auth.login", "failure", null), true);
        }
        metrics.observeAudit(entry("workspace.member.role", "success", 7), true);
        metrics.observeAudit(entry("workspace.role.update", "success", 8), true);
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-alert-integrity-secret-at-least-32-chars");
        AuditIntegrityService integrity = new AuditIntegrityService(mock(AuditLogMapper.class),
                mock(AuditIntegrityMapper.class), mock(UserMapper.class), mock(WorkspaceMapper.class),
                mock(OrganizationMapper.class), properties, new ObjectMapper(), Clock.systemUTC(), metrics);
        AuditLog tampered = entry("workspace.role.update", "success", 7);
        tampered.setIntegrityReferenceState("captured");
        tampered.setRowHash("f".repeat(64));
        assertFalse(integrity.hasValidIntegrity(tampered));
        integrity.observeStoredIntegrity(tampered);
        String scrape = registry.scrape();
        assertTrue(scrape.contains("connex_security_authentication_failures_total{scope=\"workspace:7\"} 20.0"));
        assertTrue(scrape.contains("connex_security_authentication_failures_total{scope=\"unattributed\"} 100.0"));
        assertTrue(scrape.contains("connex_security_audit_integrity_anomaly_timestamp{scope=\"workspace:7\"} 2.0E9"));
        assertFalse(scrape.contains("sensitive"));
        assertFalse(scrape.contains("@"));
        Files.writeString(directory.resolve("triggered.prom"), scrape);
        System.out.println("EMISSION PASS authentication workspace:7=20 workspace:8=0 unattributed=100; role grant and membership change; invalid HMAC; no PII");
    }

    @Test
    void authenticationRegistryFailureDoesNotEscape() {
        var failing = failingRegistry();
        try {
            assertDoesNotThrow(() -> new SecuritySignalMetrics(failing, Clock.systemUTC())
                    .observeAudit(entry("auth.login", "failure", 7), true));
        } finally {
            failing.close();
        }
    }

    @Test
    void independentPermissionRegistryFailureDoesNotEscape() {
        var failing = failingRegistry();
        try {
            assertDoesNotThrow(() -> new SecuritySignalMetrics(failing, Clock.systemUTC())
                    .observeAudit(entry("workspace.role.create", "success", 7), true));
        } finally {
            failing.close();
        }
    }

    @Test
    void conflictingMeterTypeDoesNotEscape() {
        var conflicting = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            conflicting.counter("connex.security.permission.change.timestamp", "scope", "workspace:7");
            assertDoesNotThrow(() -> new SecuritySignalMetrics(conflicting, Clock.systemUTC())
                    .observeAudit(entry("workspace.role.create", "success", 7), true));
        } finally {
            conflicting.close();
        }
    }

    @Test
    void deferredClockFailureDoesNotInterruptLaterCallbacks() {
        Clock failingClock = mock(Clock.class);
        when(failingClock.instant()).thenThrow(new IllegalStateException("sensitive-clock-probe"));
        SecuritySignalMetrics failing = new SecuritySignalMetrics(registry, failingClock);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        var later = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            failing.observeAudit(entry("workspace.role.create", "success", 7), false);
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            later.set(true);
                        }
                    });
            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCommit()));
            assertTrue(later.get());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void fatalRegistryErrorIsNotSwallowed() {
        var failing = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            failing.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
                @Override
                public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id) {
                    throw new AssertionError("fatal-probe");
                }
            });
            assertThrows(AssertionError.class, () -> new SecuritySignalMetrics(failing, Clock.systemUTC())
                    .integrityAnomaly(entry("workspace.role.create", "success", 7)));
        } finally {
            failing.close();
        }
    }

    @Test
    void recentRemainsAvailableWhenAnomalyRegistryThrows() {
        assertDisclosureSurvivesRegistryFailure(false);
    }

    @Test
    void exportRemainsAvailableWhenAnomalyRegistryThrows() {
        assertDisclosureSurvivesRegistryFailure(true);
    }

    private void assertDisclosureSurvivesRegistryFailure(boolean export) {
        var failing = failingRegistry();
        try {
            AuditLogMapper mapper = mock(AuditLogMapper.class);
            var properties = new AuditIntegrityProperties();
            properties.setHmacSecret("test-alert-integrity-secret-at-least-32-chars");
            var integrity = new AuditIntegrityService(mapper, mock(AuditIntegrityMapper.class),
                    mock(UserMapper.class), mock(WorkspaceMapper.class), mock(OrganizationMapper.class),
                    properties, new ObjectMapper(), Clock.systemUTC(),
                    new SecuritySignalMetrics(failing, Clock.systemUTC()));
            var tenant = mock(ooo.klae.connex.backend.tenant.TenantContext.class);
            when(tenant.getWorkspaceId()).thenReturn(7);
            var service = new ooo.klae.connex.backend.services.AuditService(mapper, integrity,
                    new ObjectMapper(), tenant, new ooo.klae.connex.backend.util.ClientIpResolver(""),
                    new ClientAssertedCorrelationPseudonymizer(properties));
            AuditLog invalid = entry("workspace.role.create", "success", 7);
            invalid.setSummary("retained [Private](note:42)");
            invalid.setIntegrityReferenceState("captured");
            invalid.setRowHash("f".repeat(64));
            assertFalse(integrity.hasValidIntegrity(invalid));
            if (export) {
                when(mapper.findWorkspaceExport(7, 20, 0)).thenReturn(java.util.List.of(invalid));
                String csv = assertDoesNotThrow(() -> service.exportRecent(20, 0));
                assertTrue(csv.contains("retained"));
                assertFalse(csv.contains("Private"));
            } else {
                when(mapper.findRecent(7, 20, 0)).thenReturn(java.util.List.of(invalid));
                var rows = assertDoesNotThrow(() -> service.recent(20, 0));
                assertEquals(1, rows.size());
                assertTrue(rows.getFirst().isContentRedacted());
                assertFalse(rows.getFirst().getSummary().contains("Private"));
            }
        } finally {
            failing.close();
        }
    }

    private static io.micrometer.core.instrument.simple.SimpleMeterRegistry failingRegistry() {
        var failing = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        failing.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
            @Override
            public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id) {
                throw new IllegalStateException("sensitive-registry-probe");
            }
        });
        return failing;
    }

    private static AuditLog entry(String action, String outcome, Integer workspaceId) {
        AuditLog entry = new AuditLog();
        entry.setWorkspaceId(workspaceId);
        entry.setAction(action);
        entry.setOutcome(outcome);
        entry.setActorLabel("sensitive-person");
        entry.setTargetLabel("sensitive@example.invalid");
        entry.setSummary("sensitive-secret");
        return entry;
    }
}
