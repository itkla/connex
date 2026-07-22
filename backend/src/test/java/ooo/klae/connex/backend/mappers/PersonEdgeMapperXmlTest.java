package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class PersonEdgeMapperXmlTest {

    @Test
    void topConnectionsFiltersProcessabilityBeforeDeterministicSqlLimit() throws Exception {
        Configuration configuration = configuration();
        String topConnections = sql(configuration, "getTopConnections", Map.of(
            "workspaceId", 7,
            "personId", 11,
            "limit", 5));

        int workspaceScope = topConnections.indexOf("e.workspace_id = ?");
        int suspendedFilter = topConnections.lastIndexOf("suspended_at");
        int ceasedFilter = topConnections.lastIndexOf("provision_ceased_at");
        int nameFilter = topConnections.indexOf("REGEXP '[^[:space:]\\\\x{001C}-\\\\x{001F}]'");
        int ordering = topConnections.indexOf("ORDER BY e.strength DESC, person_name, person_id");
        int limit = topConnections.indexOf("LIMIT ?");

        assertTrue(workspaceScope >= 0);
        assertTrue(suspendedFilter > workspaceScope);
        assertTrue(ceasedFilter > suspendedFilter);
        assertTrue(nameFilter > ceasedFilter);
        assertTrue(ordering > nameFilter);
        assertTrue(limit > ordering);

        String allConnections = sql(configuration, "getConnections", Map.of(
            "workspaceId", 7,
            "personId", 11));
        assertFalse(allConnections.contains("REGEXP"));
        assertFalse(allConnections.contains("LIMIT"));
        assertTrue(allConnections.endsWith("ORDER BY e.strength DESC, person_name"));
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        String resource = "mappers/PersonEdgeMapper.xml";
        try (InputStream input = PersonEdgeMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(Configuration configuration, String statement, Object parameters) {
        return configuration.getMappedStatement(PersonEdgeMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }
}
