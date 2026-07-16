package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.DataSubjectRequest;

/** Verifies data-subject request SQL parses and keeps every value parameter-bound. */
class DataSubjectRequestMapperXmlTest {

    @Test
    void mapperXmlParsesAndUsesBoundParameters() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("DataSubjectRequest", DataSubjectRequest.class);
        String resource = "mappers/DataSubjectRequestMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("orgId", 3);
        parameters.put("workspaceId", 4);
        parameters.put("personId", 5);
        parameters.put("limit", 1_000);
        MappedStatement person = configuration.getMappedStatement(
            DataSubjectRequestMapper.class.getName() + ".findDisclosurePerson");
        String sql = person.getBoundSql(parameters).getSql();
        assertTrue(sql.contains("subject_workspace.org_id = ?"));
        assertTrue(sql.contains("p.workspace_id = ?"));
        assertTrue(sql.contains("p.id = ?"));

        Map<String, String> sectionOrgAnchors = Map.ofEntries(
            Map.entry("findDisclosureTags", "tag_workspace.org_id = ?"),
            Map.entry("findDisclosureCustomFields", "value_workspace.org_id = ?"),
            Map.entry("findDisclosureActivities", "activity_workspace.org_id = ?"),
            Map.entry("findDisclosureNotes", "note_workspace.org_id = ?"),
            Map.entry("findDisclosureTasks", "task_workspace.org_id = ?"),
            Map.entry("findDisclosureAttachments", "attachment_workspace.org_id = ?"),
            Map.entry("findDisclosureEmployment", "employment_workspace.org_id = ?"),
            Map.entry("findDisclosureEdges", "edge_workspace.org_id = ?"),
            Map.entry("findDisclosureDeals", "deal_workspace.org_id = ?"),
            Map.entry("findDisclosureIntroductions", "introduction_workspace.org_id = ?"),
            Map.entry("findDisclosureAudit", "audit_workspace.org_id = ?"),
            Map.entry("countDisclosureAudit", "audit_workspace.org_id = ?"));
        for (Map.Entry<String, String> section : sectionOrgAnchors.entrySet()) {
            String sectionSql = configuration.getMappedStatement(
                DataSubjectRequestMapper.class.getName() + "." + section.getKey())
                .getBoundSql(parameters)
                .getSql();
            assertTrue(sectionSql.contains("subject_workspace.org_id = ?"), section.getKey());
            assertTrue(sectionSql.contains("p.workspace_id = ?"), section.getKey());
            assertTrue(sectionSql.contains("p.id = ?"), section.getKey());
            assertTrue(sectionSql.contains(section.getValue()), section.getKey());
        }

        String provisionsSql = configuration.getMappedStatement(
            DataSubjectRequestMapper.class.getName() + ".findDisclosureProvisions")
            .getBoundSql(parameters)
            .getSql();
        assertTrue(provisionsSql.contains("subject_workspace.org_id = ?"));
        assertTrue(provisionsSql.contains("target_workspace.org_id = ?"));

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(xml.contains("${"));
        }

        Set<String> pinnedStatements = new HashSet<>(sectionOrgAnchors.keySet());
        pinnedStatements.addAll(Set.of("insert", "update", "findById", "findByOrg", "subjectPersonInOrg",
            "findDisclosurePerson", "findDisclosureProvisions"));
        String namespacePrefix = DataSubjectRequestMapper.class.getName() + ".";
        for (MappedStatement statement : new HashSet<>(configuration.getMappedStatements())) {
            if (!statement.getId().startsWith(namespacePrefix) || statement.getId().contains("!")) {
                continue;
            }
            String statementName = statement.getId().substring(namespacePrefix.length());
            assertTrue(pinnedStatements.contains(statementName),
                "New statement '" + statementName + "' must get an org-anchor assertion in this test: "
                    + "this namespace is exempt from TenantScopeInterceptor, so org scoping is only "
                    + "guarded here");
            assertTrue(statement.getBoundSql(exampleRequest(statementName, parameters)).getSql()
                    .contains("org_id"),
                statementName + " must bind an org_id predicate or column");
        }
    }

    private static Object exampleRequest(String statementName, Map<String, Object> parameters) {
        if (!"insert".equals(statementName) && !"update".equals(statementName)) {
            return parameters;
        }
        DataSubjectRequest request = new DataSubjectRequest();
        request.setOrgId(3);
        request.setRequestType("disclosure");
        request.setStatus("received");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        return request;
    }
}
