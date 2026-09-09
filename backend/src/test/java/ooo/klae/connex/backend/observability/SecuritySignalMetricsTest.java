package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
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
