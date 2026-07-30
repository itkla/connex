package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.DataSubjectRequest;

/** Verifies control-plane data-subject request SQL stays bound and plane-pure. */
class DataSubjectRequestMapperXmlTest {

    @Test
    void mapperXmlParsesAndUsesBoundParametersWithoutTenantTables() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("DataSubjectRequest", DataSubjectRequest.class);
        String resource = "mappers/DataSubjectRequestMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("orgId", 3);
        parameters.put("personId", 5);
        parameters.put("workspaceIds", List.of(4, 6));
        parameters.put("status", "received");
        parameters.put("limit", 1_000);
        parameters.put("offset", 0);
        String auditSql = configuration.getMappedStatement(
            DataSubjectRequestMapper.class.getName() + ".findDisclosureAudit")
            .getBoundSql(parameters)
            .getSql();
        assertTrue(auditSql.contains("workspace_id IN"));
        assertTrue(auditSql.contains("org_id = ?"));
        assertTrue(auditSql.contains("entity_id = ?"));

        Set<String> expectedStatements = Set.of(
            "insert", "update", "findById", "findByIdForUpdate", "findByOrg",
            "findDisclosureAudit", "countDisclosureAudit");
        parameters.put("requestId", 7L);
        String lockingSql = configuration.getMappedStatement(
            DataSubjectRequestMapper.class.getName() + ".findByIdForUpdate")
            .getBoundSql(parameters)
            .getSql();
        assertTrue(lockingSql.contains("org_id = ?"));
        assertTrue(lockingSql.contains("id = ?"));
        assertTrue(lockingSql.contains("FOR UPDATE"));
        String namespacePrefix = DataSubjectRequestMapper.class.getName() + ".";
        for (MappedStatement statement : new HashSet<>(configuration.getMappedStatements())) {
            if (!statement.getId().startsWith(namespacePrefix) || statement.getId().contains("!")) {
                continue;
            }
            assertTrue(expectedStatements.contains(statement.getId().substring(namespacePrefix.length())));
        }
        assertFalse(xml.contains("${"));
        for (String table : Set.of(
                "activity", "attachment", "company", "custom_field_definition", "custom_field_value",
                "deal", "deal_person", "introduction", "note", "person", "person_edge",
                "person_employment", "person_share", "person_tag", "stage", "tag", "task")) {
            assertFalse(xml.matches("(?s).*(?:FROM|JOIN|INTO|UPDATE)\\s+" + table + "\\b.*"), table);
        }
    }
}
