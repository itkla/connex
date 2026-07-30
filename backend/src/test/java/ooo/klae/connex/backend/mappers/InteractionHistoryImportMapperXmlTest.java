package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.HistoricalNotificationBaseline;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.Direct;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

class InteractionHistoryImportMapperXmlTest {

    @Test
    void provenanceLookupsParseAndBindEveryWorkspaceAndKey() throws Exception {
        for (Class<?> mapper : List.of(
                ActivityMapper.class,
                NoteMapper.class,
                TaskMapper.class)) {
            Configuration configuration = configuration(mapper);
            BoundSql boundSql = configuration
                .getMappedStatement(mapper.getName() + ".findHistoryImports")
                .getBoundSql(Map.of(
                    "workspaceId", 11,
                    "historyImportKeys", List.of("a".repeat(64), "b".repeat(64))));
            String sql = compact(boundSql.getSql());

            assertTrue(sql.contains("WHERE workspace_id = ?"));
            assertTrue(sql.contains("history_import_key IN ( ? , ? )"));
            assertEquals(3, boundSql.getParameterMappings().size());
            assertFalse(resource(mapper).contains("${"));
        }
    }

    @Test
    void historicalNotificationBaselineSqlIsWorkspaceBoundAndParameterOnly() throws Exception {
        Configuration configuration = configuration(NotificationMapper.class);
        HistoricalNotificationBaseline baseline = new HistoricalNotificationBaseline();
        baseline.setWorkspaceId(11);
        baseline.setRecipientId(42);
        baseline.setDedupeKey("task.due:91");
        baseline.setNotificationType("task.due");
        baseline.setBaselineSeverity("warning");
        baseline.setSourceStateHash("e".repeat(64));
        baseline.setImportRunId("f".repeat(64));

        BoundSql find = configuration
            .getMappedStatement(
                NotificationMapper.class.getName()
                    + ".findHistoricalNotificationBaselines")
            .getBoundSql(Map.of("workspaceId", 11));
        BoundSql delete = configuration
            .getMappedStatement(
                NotificationMapper.class.getName()
                    + ".deleteHistoricalNotificationBaselines")
            .getBoundSql(Map.of(
                "workspaceId", 11,
                "baselines", List.of(baseline)));

        assertTrue(compact(find.getSql()).contains("WHERE workspace_id = ?"));
        assertTrue(compact(delete.getSql()).contains(
            "WHERE workspace_id = ? AND ( recipient_id, dedupe_key, "
                + "notification_type, baseline_severity, source_state_hash, "
                + "import_run_id ) IN ( ( ?, ?, ?, ?, UNHEX(?), UNHEX(?) ) )"));
        assertEquals(7, delete.getParameterMappings().size());
        assertFalse(resource(NotificationMapper.class).contains("${"));
    }

    @Test
    void batchWritesUseHistoricalTimestampsAndFixedRelationshipSemantics() throws Exception {
        String activity = compact(resource(ActivityMapper.class));
        String note = compact(resource(NoteMapper.class));
        String task = compact(resource(TaskMapper.class));

        assertTrue(activity.contains(
            "#{row.personId}, NULL, #{row.actorId}, #{row.occurredAt}"));
        assertTrue(note.contains(
            "'workspace', #{row.actorId}, #{row.personId}, NULL, "
                + "#{row.occurredAt}, #{row.occurredAt}"));
        assertTrue(task.contains(
            "#{row.dueDate}, #{row.actorId}, #{row.personId}, NULL, "
                + "#{row.occurredAt}, #{row.occurredAt}"));
    }

    @Test
    void counterfactualWarmthQueryExcludesOnlyBoundImportedEntityIds() throws Exception {
        Configuration configuration = configuration(PersonMapper.class);
        BoundSql boundSql = configuration
            .getMappedStatement(
                PersonMapper.class.getName()
                    + ".getRelationshipScoreAggregatesExcludingHistoryImports")
            .getBoundSql(Map.of(
                "workspaceId", 11,
                "reference", LocalDateTime.parse("2026-07-30T12:00:00"),
                "model", RelationshipWarmthModel.current().sqlParameters(),
                "excludedActivityIds", List.of(81, 82),
                "excludedNoteIds", List.of(86),
                "excludedTaskIds", List.of(91, 92)));
        String sql = compact(boundSql.getSql());

        assertTrue(sql.contains("a.id NOT IN ( ? , ? )"));
        assertTrue(sql.contains("n.id NOT IN ( ? )"));
        assertTrue(sql.contains("t.id NOT IN ( ? , ? )"));
        assertFalse(resource(PersonMapper.class).contains("${"));
    }

    @Test
    void historicalBaselineIsEnrolledBeforeNotificationsInTheTenantPlane() {
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains(
            "historical_notification_baseline"));
        TenantLifecycleRegistry.TableLifecycle baseline =
            TenantLifecycleRegistry.require("historical_notification_baseline");
        TenantLifecycleRegistry.TableLifecycle notification =
            TenantLifecycleRegistry.require("notification");

        Direct direct = assertInstanceOf(Direct.class, baseline.reach());
        assertEquals("workspace_id", direct.workspaceColumn());
        assertTrue(baseline.deleteOrder() < notification.deleteOrder());
    }

    @Test
    void historyImportMigrationsCheckpointEveryAtomicDdlStatement() throws Exception {
        for (String resource : List.of(
                "db/migration/tenant/V133__activity_history_import_provenance.sql",
                "db/migration/tenant/V134__note_history_import_provenance.sql",
                "db/migration/tenant/V135__task_history_import_provenance.sql",
                "db/migration/tenant/V136__historical_notification_baseline.sql")) {
            String sql;
            try (InputStream input = InteractionHistoryImportMapperXmlTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(1, sql.chars().filter(character -> character == ';').count());
        }
    }

    private static Configuration configuration(Class<?> mapper) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.dto");
        String resource = resourceName(mapper);
        try (InputStream input = InteractionHistoryImportMapperXmlTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                resource,
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String resource(Class<?> mapper) throws Exception {
        String resource = resourceName(mapper);
        try (InputStream input = InteractionHistoryImportMapperXmlTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String resourceName(Class<?> mapper) {
        return "mappers/" + mapper.getSimpleName() + ".xml";
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
