package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;

/** Verifies workflow runtime SQL scoping, locking, checkpoints, and keyset ordering. */
class WorkflowRunMapperXmlTest {

    @Test
    void everyRuntimeStatementIsWorkspaceScopedAndUsesBoundParameters() throws Exception {
        Configuration configuration = configuration();
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 12, 0);
        WorkflowRun run = run(now);
        WorkflowStepRun step = step(now);
        Map<String, Object> runIdentity = Map.of("workspaceId", 7, "id", 31L);
        Map<String, Object> workflowIdentity = Map.of(
            "workspaceId", 7, "workflowId", 11, "id", 31L);

        assertScoped(configuration, "insertRun", run);
        assertScoped(configuration, "getByDedupe", Map.of(
            "workspaceId", 7, "workflowId", 11, "dedupeKey", "dedupe"));
        assertScoped(configuration, "getById", workflowIdentity);
        assertScoped(configuration, "getByIdInWorkspace", runIdentity);
        assertScoped(configuration, "getByIdForUpdate", runIdentity);
        assertScoped(configuration, "getOwnedByIdForUpdate", Map.of(
            "workspaceId", 7, "id", 31L, "leaseOwner", "owner"));
        assertScoped(configuration, "getRunningByTrigger", Map.of(
            "workspaceId", 7, "workflowId", 11, "triggerKey", "bucket", "limit", 129));
        assertScoped(configuration, "nextSequence", Map.of(
            "workspaceId", 7, "workflowRunId", 31L));
        assertScoped(configuration, "insertStep", step);
        assertScoped(configuration, "findDueRunForUpdate", Map.of("workspaceId", 7));
        assertScoped(configuration, "leaseRun", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "leaseSeconds", 120L,
            "maxDispatches", 256));
        assertScoped(configuration, "clearClaimedRetryWait", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "expectedNodeId", "action",
            "leaseOwner", "owner"));
        assertScoped(configuration, "advanceRun", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "expectedNodeId", "action",
            "nextNodeId", "end"));
        assertScoped(configuration, "completeRun", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "expectedNodeId", "end",
            "finishedAt", now));
        assertScoped(configuration, "failRun", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "expectedNodeId", "action",
            "status", "failed",
            "failureCode", "execution_failed",
            "failureMessage", "Action failed",
            "finishedAt", now));
        assertScoped(configuration, "hasRunHistory", Map.of(
            "workspaceId", 7, "workflowId", 11));
        assertScoped(configuration, "currentTimestamp", Map.of(
            "workspaceId", 7, "workflowId", 11));
        assertScoped(configuration, "getPage", Map.of(
            "workspaceId", 7,
            "workflowId", 11,
            "asOf", now,
            "beforeStartedAt", now.minusMinutes(1),
            "beforeId", 30L,
            "limit", 21));
        assertScoped(configuration, "getViewById", workflowIdentity);
        assertScoped(configuration, "getSteps", Map.of(
            "workspaceId", 7, "workflowRunId", 31L));

        String xml = resource("mappers/WorkflowRunMapper.xml");
        assertFalse(xml.contains("${"));
        assertFalse(xml.contains("<delete"));
    }

    @Test
    void runtimeSqlPinsLocksAndExclusiveKeysets() throws Exception {
        Configuration configuration = configuration();
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 12, 0);

        assertTrue(sql(configuration, "getByIdForUpdate", Map.of(
            "workspaceId", 7, "id", 31L)).endsWith("FOR UPDATE"));
        String page = sql(configuration, "getPage", Map.of(
            "workspaceId", 7,
            "workflowId", 11,
            "asOf", now,
            "beforeStartedAt", now,
            "beforeId", 31L,
            "limit", 21));
        assertTrue(page.contains("wr.started_at <= ?"));
        assertTrue(page.contains("wr.started_at < ?"));
        assertTrue(page.contains("wr.started_at = ? AND wr.id < ?"));
        assertTrue(page.endsWith("ORDER BY wr.started_at DESC, wr.id DESC LIMIT ?"));
        assertTrue(sql(configuration, "getSteps", Map.of(
            "workspaceId", 7, "workflowRunId", 31L))
            .endsWith("ORDER BY sequence_number, id"));
        assertTrue(sql(configuration, "advanceRun", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "expectedNodeId", "action",
            "nextNodeId", "end"))
            .contains("status = 'running' AND current_node_id = ?"));
        String lease = sql(configuration, "leaseRun", Map.of(
            "workspaceId", 7,
            "id", 31L,
            "leaseOwner", "owner",
            "leaseSeconds", 120L,
            "maxDispatches", 256));
        assertFalse(lease.contains("wait_kind = NULL"));
        assertFalse(lease.contains("resume_at = NULL"));
        assertTrue(lease.contains("w.intake_paused_at IS NULL"));
        assertTrue(sql(configuration, "findDueRunForUpdate", Map.of("workspaceId", 7))
            .contains("w.intake_paused_at IS NULL"));
        String runHistory = sql(configuration, "hasRunHistory", Map.of(
            "workspaceId", 7, "workflowId", 11));
        assertTrue(runHistory.startsWith("SELECT EXISTS("));
        assertTrue(runHistory.contains("LIMIT 1 FOR SHARE"));
        assertFalse(runHistory.contains("started_at"));
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        try (InputStream input = WorkflowRunMapperXmlTest.class.getClassLoader()
                .getResourceAsStream("mappers/WorkflowRunMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                "mappers/WorkflowRunMapper.xml",
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static void assertScoped(
            Configuration configuration, String statement, Object parameters) {
        String sql = sql(configuration, statement, parameters);
        assertTrue(sql.contains("workspace_id"), statement);
        assertTrue(sql.contains("?"), statement);
        MappedStatement mapped = configuration.getMappedStatement(
            WorkflowRunMapper.class.getName() + "." + statement);
        assertTrue(mapped.getBoundSql(parameters).getParameterMappings().stream()
            .anyMatch(mapping -> mapping.getProperty().endsWith("workspaceId")), statement);
    }

    private static String sql(
            Configuration configuration, String statement, Object parameters) {
        return configuration.getMappedStatement(
                WorkflowRunMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = WorkflowRunMapperXmlTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static WorkflowRun run(LocalDateTime now) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(19L);
        run.setStatus("running");
        run.setTriggerType("entity_change");
        run.setTriggerEvent("deal.won");
        run.setTriggerKey("trigger");
        run.setRecordType("deal");
        run.setRecordId(23);
        run.setDedupeKey("dedupe");
        run.setExecutionMode("user");
        run.setActorUserId(17);
        run.setAttributionUserId(17);
        run.setCurrentNodeId("trigger");
        run.setStartedAt(now);
        return run;
    }

    private static WorkflowStepRun step(LocalDateTime now) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(7);
        step.setWorkflowRunId(31L);
        step.setSequenceNumber(0);
        step.setNodeId("trigger");
        step.setNodeType("trigger");
        step.setStatus("succeeded");
        step.setAttemptCount(1);
        step.setSelectedOutcome("next");
        step.setSelectedEdgeId("trigger-action");
        step.setNextNodeId("action");
        step.setStartedAt(now);
        step.setFinishedAt(now);
        return step;
    }
}
