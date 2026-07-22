package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Verifies Smart Segment relation predicates render with active-workspace overlays. */
class SegmentMapperXmlTest {

    @Test
    void noteExistencePredicatesScopeRelationRowsByWorkspace() throws Exception {
        Configuration configuration = configuration();

        String personSql = sql(configuration, "personExistence", "has_note");
        String dealSql = sql(configuration, "dealExistence", "has_note");

        assertTrue(personSql.contains(
            "WHERE n.workspace_id = ? AND n.person_id = p.id AND n.visibility = 'workspace'"));
        assertTrue(dealSql.contains(
            "WHERE n.workspace_id = ? AND n.deal_id = d.id AND n.visibility = 'workspace'"));
    }

    @Test
    void attachmentExistencePredicatesScopeRelationRowsByWorkspace() throws Exception {
        Configuration configuration = configuration();

        String companySql = sql(configuration, "companyExistence", "has_attachment");
        String personSql = sql(configuration, "personExistence", "has_attachment");
        String dealSql = sql(configuration, "dealExistence", "has_attachment");

        assertTrue(companySql.contains(
            "WHERE att.workspace_id = ? AND att.entity_type = 'company' AND att.entity_id = c.id"));
        assertTrue(personSql.contains(
            "WHERE att.workspace_id = ? AND att.entity_type = 'person' AND att.entity_id = p.id"));
        assertTrue(dealSql.contains(
            "WHERE att.workspace_id = ? AND att.entity_type = 'deal' AND att.entity_id = d.id"));
    }

    private static String sql(Configuration configuration, String statement, String predicate) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("predicate", predicate);
        parameters.put("days", 30);
        parameters.put("includeRestrictedPeople", false);
        return configuration.getMappedStatement(SegmentMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        String resource = "mappers/SegmentMapper.xml";
        try (InputStream input = SegmentMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
