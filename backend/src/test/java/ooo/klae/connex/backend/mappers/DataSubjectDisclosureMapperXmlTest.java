package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;

/** Verifies disclosure SQL stays parameter-bound and tenant-plane pure. */
class DataSubjectDisclosureMapperXmlTest {

    @Test
    void disclosureSectionsAndExclusionsHaveAnExplicitInventory() throws Exception {
        String procedure = Files.readString(Path.of("../docs/APPI_DATA_SUBJECT_REQUEST_PROCEDURE.md"));
        Set<String> sections = Arrays.stream(DataSubjectDisclosureDto.class.getDeclaredFields())
            .filter(field -> field.getType().equals(List.class) || field.getName().equals("person"))
            .map(java.lang.reflect.Field::getName)
            .collect(Collectors.toSet());
        Set<String> documentedSections = Pattern.compile("(?m)^\\| `([A-Za-z]+)` \\|")
            .matcher(procedure).results().map(result -> result.group(1)).collect(Collectors.toSet());
        assertTrue(sections.equals(documentedSections),
            "Disclosure section inventory must match the response: " + sections);
        for (String exclusion : Set.of(
                "ai_output_cache", "audit_log.changes", "attachment_binaries", "unlinked_free_text")) {
            assertTrue(procedure.contains("| `" + exclusion + "` |"), exclusion);
        }
    }

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
            "findEmployment", "findLifecycleHistory", "findQualificationAnswers", "findLifecyclePasses",
            "findEdges", "findDeals", "findIntroductions",
            "findProvisions", "findConsentState", "findConsentHistory", "findAudienceExportEvidence",
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
        assertTrue(xml.contains("dr.label AS disqualified_reason_label"));
        assertTrue(xml.contains("dr.label AS reason_label"));
        assertTrue(xml.contains("BINARY dr.code = BINARY p.disqualified_reason"));
        assertTrue(xml.contains("BINARY dr.code = BINARY h.reason"));
        for (String table : Set.of("workspace", "audit_log", "data_subject_request")) {
            assertFalse(xml.matches("(?s).*(?:FROM|JOIN|INTO|UPDATE)\\s+" + table + "\\b.*"), table);
        }
    }
}
