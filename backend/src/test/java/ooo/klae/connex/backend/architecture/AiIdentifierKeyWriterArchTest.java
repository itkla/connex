package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;

/** Discovers core-name DML rather than relying on the current writer inventory alone. */
class AiIdentifierKeyWriterArchTest {
    private static final Pattern INSERT = Pattern.compile(
            "(?is)\\b(?:INSERT(?:\\s+IGNORE)?|REPLACE)\\s+INTO\\s+`?(person|company|deal)`?\\s*\\((.*?)\\)");
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)\\bUPDATE\\s+`?(person|company|deal)`?(?:\\s+\\w+)?\\s+SET\\s+(.*?)(?:\\bWHERE\\b|$)");
    private static final Pattern NAME_ASSIGNMENT = Pattern.compile("(?is)(?:^|,)\\s*(?:\\w+\\.)?`?name`?\\s*=\\s*((?:#\\{[^}]+}|[^,#])+)");
    private static final Pattern PARAMETER = Pattern.compile("#\\{([\\w.]+)(?:,[^}]+)?}");
    private static final String HANDLER = "ooo.klae.connex.backend.mappers.AiIdentifier";

    @Test
    void everyProductionNameWriterMaintainsTheSameParameterBundle() throws Exception {
        Set<String> discovered = new LinkedHashSet<>();
        Map<Path, org.w3c.dom.Document> documents = new HashMap<>();
        Map<String, Element> fragments = new HashMap<>();
        try (var paths = Files.list(Path.of("src/main/resources/mappers"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                var document = factory.newDocumentBuilder().parse(path.toFile());
                documents.put(path, document);
                String namespace = document.getDocumentElement().getAttribute("namespace");
                var sql = document.getElementsByTagName("sql");
                for (int index = 0; index < sql.getLength(); index++) {
                    Element fragment = (Element) sql.item(index);
                    fragments.put(namespace + "." + fragment.getAttribute("id"), fragment);
                }
            }
        }
        for (var entry : documents.entrySet()) {
            String namespace = entry.getValue().getDocumentElement().getAttribute("namespace");
            var statements = entry.getValue().getDocumentElement().getChildNodes();
            for (int index = 0; index < statements.getLength(); index++) {
                if (!(statements.item(index) instanceof Element statement)
                        || !Set.of("insert", "update").contains(statement.getTagName())) {
                    continue;
                }
                String sql = expand(statement, namespace, fragments, Map.of()).replace('`', ' ');
                String sourceParameter = nameParameter(sql);
                if (sourceParameter == null) {
                    continue;
                }
                discovered.add(entry.getKey().getFileName() + "#" + statement.getAttribute("id"));
                requireBundle(sql, sourceParameter);
                var conditionals = statement.getElementsByTagName("if");
                for (int condition = 0; condition < conditionals.getLength(); condition++) {
                    Node node = conditionals.item(condition);
                    if (nameParameter("UPDATE person SET " + node.getTextContent()) != null) {
                        requireBundle(node.getTextContent(), sourceParameter);
                    }
                }
            }
        }
        assertEquals(Set.of("PersonMapper.xml#insert", "PersonMapper.xml#insertBatch", "PersonMapper.xml#update",
                "CompanyMapper.xml#insert", "CompanyMapper.xml#insertBatch", "CompanyMapper.xml#update",
                "DealMapper.xml#insert", "DealMapper.xml#insertBatch", "DealMapper.xml#update", "DealMapper.xml#updateName"), discovered);
    }

    @Test
    void productionJavaCannotBypassMapperNameBindings() throws Exception {
        List<String> bypasses = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String java = Files.readString(path);
                if (writesName(java)) {
                    bypasses.add(path.toString());
                }
            }
        }
        assertEquals(List.of(), bypasses, "Direct JDBC, migration callbacks and annotated SQL must not write core names");
    }

    @Test
    void detectsNewBatchPartialAnnotationAndJdbcWriters() {
        for (String sql : List.of(
                "INSERT INTO person (workspace_id, name) VALUES (#{p.workspaceId}, #{p.name})",
                "UPDATE company SET name = #{name} WHERE id = #{id}",
                "@Update(\"UPDATE deal SET name = #{name} WHERE id = #{id}\")",
                "jdbc.update(\"INSERT INTO person (name) VALUES (?)\", name)",
                "UPDATE person SET name = 'literal' WHERE id = 1")) {
            assertTrue(writesName(sql), sql);
            assertThrows(AssertionError.class, () -> requireBundle(sql, "name"));
        }
        assertFalse(writesName("UPDATE deal SET duplicate_normalized_name = #{name} WHERE id = #{id}"));
        assertEquals("name", nameParameter("UPDATE person SET name = #{name,jdbcType=VARCHAR}, archived_at = NULL WHERE id = #{id}"));
        assertEquals("name", nameParameter("UPDATE person SET name = CONVERT(#{name,jdbcType=VARCHAR,typeHandler="
                + HANDLER + "Utf8TextTypeHandler} USING utf8mb4), archived_at = NULL WHERE id = #{id}"));
        assertThrows(AssertionError.class, () -> requireBundle(
                "ai_identifier_literal_key = CONVERT(#{other,typeHandler=" + HANDLER + "LiteralKeyTypeHandler} USING utf8mb4), "
                        + "ai_identifier_label_key = CONVERT(#{name,typeHandler=" + HANDLER + "LabelKeyTypeHandler} USING utf8mb4), "
                        + "ai_identifier_source_name = CONVERT(#{name,typeHandler=" + HANDLER + "Utf8TextTypeHandler} USING utf8mb4), "
                        + "ai_identifier_key_version = " + AiIdentifierLookupKeys.VERSION + ",", "name"));
    }

    private static String expand(Node node, String namespace, Map<String, Element> fragments, Map<String, String> properties) {
        if (node instanceof Element element && element.getTagName().equals("include")) {
            Map<String, String> local = new HashMap<>(properties);
            var values = element.getElementsByTagName("property");
            for (int index = 0; index < values.getLength(); index++) {
                var value = (Element) values.item(index);
                local.put(value.getAttribute("name"), value.getAttribute("value"));
            }
            String reference = element.getAttribute("refid");
            if (!reference.contains(".")) {
                reference = namespace + "." + reference;
            }
            Element fragment = fragments.get(reference);
            assertTrue(fragment != null, "Unresolved mapper include: " + reference);
            return expand(fragment, reference.substring(0, reference.lastIndexOf('.')), fragments, local);
        }
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            String text = node.getTextContent();
            for (var property : properties.entrySet()) {
                text = text.replace("${" + property.getKey() + "}", property.getValue());
            }
            return text;
        }
        StringBuilder text = new StringBuilder();
        if (node instanceof Element element && Set.of("set", "where").contains(element.getTagName())) {
            text.append(' ').append(element.getTagName()).append(' ');
        }
        var children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            text.append(expand(children.item(index), namespace, fragments, properties));
        }
        return text.toString();
    }

    private static boolean writesName(String sql) {
        var insert = INSERT.matcher(sql);
        while (insert.find()) {
            if (Arrays.stream(insert.group(2).split(",")).anyMatch(column -> column.strip().equalsIgnoreCase("name"))) {
                return true;
            }
        }
        var update = UPDATE.matcher(sql);
        return update.find() && NAME_ASSIGNMENT.matcher(update.group(2)).find();
    }

    private static String nameParameter(String sql) {
        var insert = INSERT.matcher(sql);
        if (insert.find()) {
            List<String> columns = Arrays.stream(insert.group(2).split(",")).map(String::strip).toList();
            int name = columns.indexOf("name");
            if (name >= 0) {
                var parameters = PARAMETER.matcher(sql.substring(insert.end()));
                for (int index = 0; parameters.find(); index++) {
                    if (index == name) {
                        return parameters.group(1);
                    }
                }
                throw new AssertionError("Core-name INSERT must bind its source name");
            }
        }
        var update = UPDATE.matcher(sql);
        if (update.find()) {
            var assignment = NAME_ASSIGNMENT.matcher(update.group(2));
            if (assignment.find()) {
                var parameter = PARAMETER.matcher(assignment.group(1));
                assertTrue(parameter.find(), "Core-name UPDATE must bind its source name");
                return parameter.group(1);
            }
        }
        return null;
    }

    private static void requireBundle(String sql, String parameter) {
        String raw = "CONVERT\\(\\s*#\\{" + Pattern.quote(parameter) + ",[^}]*typeHandler="
                + Pattern.quote(HANDLER + "Utf8TextTypeHandler") + "}\\s+USING\\s+utf8mb4\\s*\\)";
        String literal = "CONVERT\\(\\s*#\\{" + Pattern.quote(parameter) + ",[^}]*typeHandler="
                + Pattern.quote(HANDLER + "LiteralKeyTypeHandler") + "}\\s+USING\\s+utf8mb4\\s*\\)";
        String label = "CONVERT\\(\\s*#\\{" + Pattern.quote(parameter) + ",[^}]*typeHandler="
                + Pattern.quote(HANDLER + "LabelKeyTypeHandler") + "}\\s+USING\\s+utf8mb4\\s*\\)";
        var insert = INSERT.matcher(sql);
        if (insert.find()) {
            assertTrue(Pattern.compile("(?is)(?:^|,)\\s*name\\s*,\\s*ai_identifier_literal_key\\s*,"
                    + "\\s*ai_identifier_label_key\\s*,\\s*ai_identifier_source_name\\s*,\\s*ai_identifier_key_version\\s*(?:,|$)")
                    .matcher(insert.group(2)).find(), "Insert bundle columns must follow their source name");
            assertTrue(Pattern.compile(raw + "\\s*,\\s*" + literal + "\\s*,\\s*" + label
                    + "\\s*,\\s*" + raw + "\\s*,\\s*" + AiIdentifierLookupKeys.VERSION + "\\s*[,)]")
                    .matcher(sql.substring(insert.end())).find(), "Insert bundle must bind the same raw name in column order");
            return;
        }
        var update = UPDATE.matcher(sql);
        if (update.find()) {
            var assignment = NAME_ASSIGNMENT.matcher(update.group(2));
            assertTrue(assignment.find());
            assertTrue(Pattern.compile(raw).matcher(assignment.group(1).strip()).matches(),
                    "Authoritative name must use the same lossless UTF-8 source parameter");
        }
        assertTrue(Pattern.compile("ai_identifier_literal_key\\s*=\\s*" + literal).matcher(sql).find(),
                "Literal key must derive from the exact raw name parameter");
        assertTrue(Pattern.compile("ai_identifier_label_key\\s*=\\s*" + label).matcher(sql).find(),
                "Label key must derive from the exact raw name parameter");
        assertTrue(Pattern.compile("ai_identifier_source_name\\s*=\\s*" + raw).matcher(sql).find(),
                "Snapshot must use the exact raw name parameter");
        assertTrue(Pattern.compile("ai_identifier_key_version\\s*=\\s*"
                + AiIdentifierLookupKeys.VERSION + "\\s*[,)]").matcher(sql).find(), "Writer format must match the running API");
    }
}
