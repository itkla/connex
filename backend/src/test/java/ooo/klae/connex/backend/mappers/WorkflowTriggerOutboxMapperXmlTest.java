package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;

/** Verifies bounded tenant-scoped outbox discovery, claiming, and retention SQL. */
class WorkflowTriggerOutboxMapperXmlTest {

    @Test
    void outboxStatementsAreBoundedScopedAndOwnerFenced() throws Exception {
        Configuration configuration = configuration();
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 12, 0);
        assertScoped(configuration, "findEntityTargets", Map.of(
            "workspaceId", 7,
            "recordType", "company",
            "triggerEvent", "company.updated",
            "limit", 129));
        assertScoped(configuration, "findScheduleTargets", Map.of(
            "workspaceId", 7, "cadence", "daily", "limit", 129));
        assertScoped(configuration, "insert", outbox(now));
        assertScoped(configuration, "ensureWorkspaceGate", Map.of("workspaceId", 7));
        assertScoped(configuration, "getNextQueueForUpdate", Map.of("workspaceId", 7));
        assertScoped(configuration, "findDueIdForUpdate", Map.of("workspaceId", 7));
        assertScoped(configuration, "lease", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "leaseSeconds", 120L,
            "maxAttempts", 8));
        assertScoped(configuration, "complete", Map.of(
            "workspaceId", 7, "id", 31L, "leaseOwner", "owner"));
        assertScoped(configuration, "releaseForRetry", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "delaySeconds", 30L,
            "errorCode", "failure"));
        assertScoped(configuration, "purgeCompletedBefore", Map.of(
            "workspaceId", 7, "cutoff", now, "limit", 100));

        String due = sql(configuration, "findDueIdForUpdate", Map.of("workspaceId", 7));
        assertTrue(due.endsWith("FOR UPDATE SKIP LOCKED"));
        assertTrue(due.contains("w.intake_paused_at IS NULL"));
        String complete = sql(configuration, "complete", Map.of(
            "workspaceId", 7, "id", 31L, "leaseOwner", "owner"));
        assertTrue(complete.contains("lease_owner = ?"));
        assertTrue(complete.contains("lease_until >= CURRENT_TIMESTAMP(6)"));
        String release = sql(configuration, "releaseForRetry", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "delaySeconds", 30L,
            "errorCode", "failure"));
        assertTrue(release.contains("lease_until >= CURRENT_TIMESTAMP(6)"));
        String lease = sql(configuration, "lease", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "leaseSeconds", 120L,
            "maxAttempts", 8));
        assertTrue(lease.contains("w.intake_paused_at IS NULL"));
        String schedulePage = sql(configuration, "saveSchedulePage", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "recordScanAfterId", 100,
            "scheduleMatchCount", 3,
            "completed", false));
        assertTrue(schedulePage.contains("delivery_attempt_count = 0"));
        assertTrue(schedulePage.contains("schedule_match_count = ?"));
        assertTrue(schedulePage.contains("lease_until >= CURRENT_TIMESTAMP(6)"));
        String dead = sql(configuration, "deadLetter", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "errorCode", "failure"));
        assertTrue(dead.contains("lease_until >= CURRENT_TIMESTAMP(6)"));
        String resolved = sql(configuration, "resolveDeadForWorkflow", Map.of(
            "workspaceId", 7, "workflowId", 11));
        assertTrue(resolved.contains("status = 'invalidated'"));
        assertTrue(resolved.contains("status = 'dead'"));
        String purge = sql(configuration, "purgeCompletedBefore", Map.of(
            "workspaceId", 7, "cutoff", now, "limit", 100));
        assertTrue(purge.contains("status IN ('completed', 'invalidated')"));
        assertFalse(purge.contains("'dead'"));
        String source = resource("mappers/WorkflowTriggerOutboxMapper.xml");
        assertFalse(source.contains("${"));
        assertFalse(source.contains("purgeDeadBefore"));
    }

    private static WorkflowTriggerOutbox outbox(LocalDateTime now) {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(19L);
        outbox.setWorkflowRuntimeGeneration(3L);
        outbox.setTriggerType("entity_change");
        outbox.setTriggerEvent("company.updated");
        outbox.setTriggerKey("event");
        outbox.setRecordType("company");
        outbox.setRecordId(41);
        outbox.setOccurredAt(now);
        outbox.setDedupeKey("entity:event");
        return outbox;
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        try (InputStream input = WorkflowTriggerOutboxMapperXmlTest.class
                .getClassLoader()
                .getResourceAsStream("mappers/WorkflowTriggerOutboxMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                "mappers/WorkflowTriggerOutboxMapper.xml",
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static void assertScoped(
            Configuration configuration, String statement, Object parameters) {
        String sql = sql(configuration, statement, parameters);
        assertTrue(sql.contains("workspace_id"), statement);
        assertTrue(sql.contains("?"), statement);
        assertTrue(configuration.getMappedStatement(
            WorkflowTriggerOutboxMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getParameterMappings().stream()
            .anyMatch(mapping -> mapping.getProperty().endsWith("workspaceId")), statement);
    }

    private static String sql(
            Configuration configuration, String statement, Object parameters) {
        return configuration.getMappedStatement(
                WorkflowTriggerOutboxMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = WorkflowTriggerOutboxMapperXmlTest.class
                .getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
