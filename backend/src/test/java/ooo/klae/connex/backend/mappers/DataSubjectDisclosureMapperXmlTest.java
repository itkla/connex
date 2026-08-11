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

/** Verifies disclosure SQL stays parameter-bound and tenant-plane pure. */
class DataSubjectDisclosureMapperXmlTest {

    @Test
    void mapperXmlParsesAndPinsEverySectionToTheSubjectAndAllowlist() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/DataSubjectDisclosureMapper.xml";
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
        parameters.put("workspaceId", 4);
        parameters.put("personId", 5);
        parameters.put("workspaceIds", List.of(4, 6));
        Set<String> expectedStatements = Set.of(
            "subjectPersonExists", "lockSubjectPersonForShare", "findPerson", "findIdentities", "findTags",
            "findCustomFields", "findActivities", "findNotes", "findTasks", "findAttachments",
            "findEmployment", "findEdges", "findDeals", "findIntroductions", "findProvisions",
            "findProviderCaptureEvidence", "findRecordCommentThreads", "findRecordComments");
        String namespacePrefix = DataSubjectDisclosureMapper.class.getName() + ".";
        Set<String> found = new HashSet<>();
        for (MappedStatement statement : new HashSet<>(configuration.getMappedStatements())) {
            if (!statement.getId().startsWith(namespacePrefix) || statement.getId().contains("!")) {
                continue;
            }
            String statementName = statement.getId().substring(namespacePrefix.length());
            found.add(statementName);
            String sql = statement.getBoundSql(parameters).getSql();
            assertTrue(sql.contains("workspace_id = ?"), statementName);
            assertTrue(sql.contains("id = ?"), statementName);
            if (!Set.of("subjectPersonExists", "lockSubjectPersonForShare")
                    .contains(statementName)) {
                assertTrue(sql.contains(" IN"), statementName);
            }
            if ("lockSubjectPersonForShare".equals(statementName)) {
                assertTrue(sql.contains("FOR SHARE"), statementName);
            }
        }
        assertTrue(found.equals(expectedStatements), found.toString());
        assertFalse(xml.contains("${"));
        for (String table : Set.of("workspace", "audit_log", "data_subject_request")) {
            assertFalse(xml.matches("(?s).*(?:FROM|JOIN|INTO|UPDATE)\\s+" + table + "\\b.*"), table);
        }
    }
}
