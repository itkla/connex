package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
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
