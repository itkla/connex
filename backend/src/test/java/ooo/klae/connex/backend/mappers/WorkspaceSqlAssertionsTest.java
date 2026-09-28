package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins scope isolation and parameter identity for both report mapper guards. */
class WorkspaceSqlAssertionsTest {

    @ParameterizedTest
    @MethodSource("workspaceScopes")
    void requiresWorkspaceBindingInEveryScope(String sql, List<String> properties, boolean scoped) {
        Configuration configuration = new Configuration();
        BoundSql bound = new BoundSql(configuration, sql, properties.stream()
            .map(property -> new ParameterMapping.Builder(configuration, property, Integer.class).build())
            .toList(), Map.of());

        if (scoped) {
            assertDoesNotThrow(() -> WorkspaceSqlAssertions.assertWorkspacePredicates(bound, "fixture"));
        } else {
            assertThrows(AssertionError.class,
                () -> WorkspaceSqlAssertions.assertWorkspacePredicates(bound, "fixture"));
        }
    }

    private static Stream<Arguments> workspaceScopes() {
        return Stream.of(
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ?", List.of("workspaceId"), true),
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ?", List.of("id"), false),
            Arguments.of("SELECT workspace_id FROM report_goal WHERE id = ?", List.of("id"), false),
            Arguments.of("SELECT * FROM report_goal WHERE id = ? UNION ALL"
                + " SELECT * FROM report_goal WHERE workspace_id = ?", List.of("id", "workspaceId"), false),
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ? UNION ALL"
                + " SELECT * FROM report_goal WHERE id = ?", List.of("workspaceId", "id"), false),
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ? UNION ALL"
                + " SELECT * FROM report_goal WHERE workspace_id = ?", List.of("workspaceId", "workspaceId"), true),
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ? UNION ALL"
                + " SELECT * FROM report_goal WHERE workspace_id = ?", List.of("workspaceId", "id"), false),
            Arguments.of("SELECT * FROM report_goal WHERE EXISTS"
                + " (SELECT * FROM report_goal WHERE workspace_id = ?)", List.of("workspaceId"), false),
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ? AND EXISTS"
                + " (SELECT * FROM report_goal WHERE id = ?)", List.of("workspaceId", "id"), false),
            Arguments.of("SELECT * FROM report_goal WHERE workspace_id = ? AND EXISTS"
                + " (SELECT * FROM report_goal WHERE workspace_id = ?)", List.of("workspaceId", "workspaceId"), true),
            Arguments.of("SELECT (SELECT id FROM report_goal WHERE workspace_id = ?) FROM report_goal"
                + " WHERE workspace_id = ?", List.of("workspaceId", "workspaceId"), true),
            Arguments.of("SELECT (SELECT id FROM report_goal WHERE workspace_id = ?) FROM report_goal"
                + " WHERE workspace_id = ?", List.of("workspaceId", "id"), false),
            Arguments.of("SELECT (SELECT id FROM report_goal WHERE workspace_id = ?) FROM report_goal"
                + " WHERE workspace_id = ?", List.of("id", "workspaceId"), false),
            Arguments.of("SELECT * FROM report_schedule WHERE workspace_id = ? FOR UPDATE",
                List.of("workspaceId"), true),
            Arguments.of("UPDATE report_schedule SET cadence = ? WHERE workspace_id = ?",
                List.of("cadence", "workspaceId"), true),
            Arguments.of("UPDATE report_schedule SET cadence = ? WHERE EXISTS"
                + " (SELECT id FROM report_goal WHERE workspace_id = ?)", List.of("cadence", "workspaceId"), false),
            Arguments.of("UPDATE report_schedule SET cadence = ? WHERE workspace_id = ? AND EXISTS"
                + " (SELECT id FROM report_goal)", List.of("cadence", "workspaceId"), false),
            Arguments.of("UPDATE report_schedule SET cadence = ? WHERE workspace_id = ? AND EXISTS"
                + " (SELECT id FROM report_goal WHERE workspace_id = ?)",
                List.of("cadence", "workspaceId", "workspaceId"), true),
            Arguments.of("WITH scoped AS (SELECT id FROM report_goal WHERE workspace_id = ?)"
                + " UPDATE report_schedule SET cadence = ? WHERE workspace_id = ?",
                List.of("workspaceId", "cadence", "workspaceId"), true),
            Arguments.of("WITH scoped AS (SELECT id FROM report_goal WHERE workspace_id = ?)"
                + " UPDATE report_schedule SET cadence = ? WHERE id = ?",
                List.of("workspaceId", "cadence", "id"), false),
            Arguments.of("DELETE FROM report_schedule WHERE EXISTS"
                + " (SELECT id FROM report_goal WHERE workspace_id = ?)", List.of("workspaceId"), false),
            Arguments.of("DELETE FROM report_schedule WHERE workspace_id = ? AND EXISTS"
                + " (SELECT id FROM report_goal)", List.of("workspaceId"), false),
            Arguments.of("DELETE FROM report_schedule WHERE workspace_id = ? AND EXISTS"
                + " (SELECT id FROM report_goal WHERE workspace_id = ?)",
                List.of("workspaceId", "workspaceId"), true));
    }
}
