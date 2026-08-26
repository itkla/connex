package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
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

    @Test
    void personCompanyFieldRequiresCurrentCompanyVisibility() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("field", "company");
        parameters.put("op", "is");
        parameters.put("id", 17);

        String normalSql = sql(configuration, "personIdsMatching", parameters);
        String restrictedSql = sql(configuration, "personIdsMatchingIncludingRestricted", parameters);
        parameters.put("op", "in");
        parameters.put("ids", List.of(17, 19));
        String inSql = sql(configuration, "personIdsMatching", parameters);

        assertCompanyVisibility(normalSql);
        assertCompanyVisibility(restrictedSql);
        assertCompanyVisibility(inSql);
        assertTrue(normalSql.contains("AND company_id = ?"));
        assertTrue(inSql.contains("AND company_id IN ( ? , ? )"));
    }

    @Test
    void tagFieldPredicatesRequireTagFromEvaluationWorkspace() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("field", "tag");
        parameters.put("op", "has");
        parameters.put("id", 17);

        assertTagBoundary(sql(configuration, "companyIdsMatching", parameters), "company_tag");
        assertTagBoundary(sql(configuration, "personIdsMatching", parameters), "person_tag");
        assertTagBoundary(sql(configuration, "personIdsMatchingIncludingRestricted", parameters), "person_tag");
        assertTagBoundary(sql(configuration, "dealIdsMatching", parameters), "deal_tag");
    }

    @Test
    void entityMembershipCheckUsesBoundWorkspaceAndEntityIds() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("entityId", 17);

        parameters.put("recordType", "company");
        assertTrue(sql(configuration, "entityIdInWorkspace", parameters).contains(
            "SELECT 1 FROM company WHERE workspace_id = ? AND id = ?"));
        parameters.put("recordType", "person");
        assertTrue(sql(configuration, "entityIdInWorkspace", parameters).contains(
            "SELECT 1 FROM person WHERE workspace_id = ? AND id = ? AND suspended_at IS NULL"));
        parameters.put("recordType", "deal");
        assertTrue(sql(configuration, "entityIdInWorkspace", parameters).contains(
            "SELECT 1 FROM deal WHERE workspace_id = ? AND id = ?"));
        parameters.put("recordType", "document");
        assertTrue(sql(configuration, "entityIdInWorkspace", parameters).contains(
            "SELECT 1 FROM deal_document WHERE workspace_id = ? AND id = ?"));
    }

    private static String sql(Configuration configuration, String statement, String predicate) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("predicate", predicate);
        parameters.put("days", 30);
        parameters.put("includeRestrictedPeople", false);
        return sql(configuration, statement, parameters);
    }

    private static String sql(
            Configuration configuration, String statement, Map<String, Object> parameters) {
        return configuration.getMappedStatement(SegmentMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static void assertCompanyVisibility(String sql) {
        assertTrue(sql.contains("visible_company.id = person.company_id"));
        assertTrue(sql.contains("visible_company.workspace_id = ?"));
        assertTrue(sql.contains("visible_share.workspace_id = ?"));
        assertTrue(sql.contains("owner_workspace.org_id = viewer_workspace.org_id"));
    }

    private static void assertTagBoundary(String sql, String junction) {
        assertTrue(sql.contains("SELECT " + junction + "."));
        assertTrue(sql.contains("segment_tag.id = " + junction + ".tag_id"));
        assertTrue(sql.contains("segment_tag.workspace_id = ?"));
        assertTrue(sql.contains("WHERE " + junction + ".tag_id = ?"));
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
