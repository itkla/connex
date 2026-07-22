package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.BoardPositionUpdate;

/** Verifies task batch position SQL remains bounded to its workspace and status column. */
class TaskMapperXmlTest {

    @Test
    void boardRootLockUsesTenantBoundAtomicUpsert() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of("workspaceId", 11);

        BoundSql boundSql = configuration
            .getMappedStatement(TaskMapper.class.getName() + ".lockTaskBoard")
            .getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertEquals(
            "INSERT INTO task_board_lock (workspace_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE workspace_id = ?",
            sql
        );
        assertEquals(2, boundSql.getParameterMappings().size());

        PreparedStatement statement = mock(PreparedStatement.class);
        configuration.newParameterHandler(
            configuration.getMappedStatement(TaskMapper.class.getName() + ".lockTaskBoard"),
            parameters,
            boundSql
        ).setParameters(statement);
        verify(statement).setInt(1, 11);
        verify(statement).setInt(2, 11);
    }

    @Test
    void batchPositionUpdateKeepsWorkspaceAndStatusPredicates() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("status", "todo");
        parameters.put("positions", List.of(
            new BoardPositionUpdate(23, 0),
            new BoardPositionUpdate(29, 1)
        ));

        BoundSql boundSql = configuration
            .getMappedStatement(TaskMapper.class.getName() + ".setPositions")
            .getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.startsWith("UPDATE task SET position = CASE id"));
        assertTrue(sql.contains("WHEN ? THEN ? WHEN ? THEN ? ELSE position END"));
        assertTrue(sql.contains("WHERE workspace_id = ? AND status = ?"));
        assertTrue(sql.endsWith("AND id IN ( ? , ? )"));
        assertEquals(8, boundSql.getParameterMappings().size());

        PreparedStatement statement = mock(PreparedStatement.class);
        configuration.newParameterHandler(
            configuration.getMappedStatement(TaskMapper.class.getName() + ".setPositions"),
            parameters,
            boundSql
        ).setParameters(statement);
        verify(statement).setInt(1, 23);
        verify(statement).setInt(2, 0);
        verify(statement).setInt(3, 29);
        verify(statement).setInt(4, 1);
        verify(statement).setInt(5, 11);
        verify(statement).setString(6, "todo");
        verify(statement).setInt(7, 23);
        verify(statement).setInt(8, 29);
    }

    @Test
    void deleteLockReadKeepsWorkspacePredicateAndLocksRow() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of("workspaceId", 11, "id", 23);

        BoundSql boundSql = configuration
            .getMappedStatement(TaskMapper.class.getName() + ".getTaskByIdForUpdate")
            .getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.contains(
            "FROM task FORCE INDEX (uq_task_workspace_id) WHERE workspace_id = ? AND id = ? FOR UPDATE"));
        assertEquals(2, boundSql.getParameterMappings().size());

        PreparedStatement statement = mock(PreparedStatement.class);
        configuration.newParameterHandler(
            configuration.getMappedStatement(TaskMapper.class.getName() + ".getTaskByIdForUpdate"),
            parameters,
            boundSql
        ).setParameters(statement);
        verify(statement).setInt(1, 11);
        verify(statement).setInt(2, 23);
    }

    @Test
    void boardMoveDiscoveryIsNonLockingAndWorkspaceScoped() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = Map.of("workspaceId", 11);

        BoundSql boundSql = configuration
            .getMappedStatement(TaskMapper.class.getName() + ".listWorkspaceTaskIds")
            .getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertEquals(
            "SELECT id FROM task WHERE workspace_id = ?",
            sql
        );
        assertEquals(1, boundSql.getParameterMappings().size());

        PreparedStatement statement = mock(PreparedStatement.class);
        configuration.newParameterHandler(
            configuration.getMappedStatement(TaskMapper.class.getName() + ".listWorkspaceTaskIds"),
            parameters,
            boundSql
        ).setParameters(statement);
        verify(statement).setInt(1, 11);
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        String resource = "mappers/TaskMapper.xml";
        try (InputStream input = TaskMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
