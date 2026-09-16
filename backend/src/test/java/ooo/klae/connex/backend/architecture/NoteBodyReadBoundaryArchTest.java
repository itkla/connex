package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Restricts note bodies to bounded lists, bounded assistant history, and singleton reads.
 * The caller inventory covers timelines, search, briefs through record services, and note mutations.
 * Attachment upload admission and its locking recheck each read one workspace-visible note by exact ID.
 * Subject disclosure serializes complete exports in pages of at most 100 preflight IDs.
 * Its SQL flushes earlier pages from the session cache and applies no visibility filter, because a
 * statutory disclosure must carry every note held about the subject, private ones included.
 * Aggregate, scoring, cooling, and risk consumers must read metadata or SQL aggregates instead.
 */
class NoteBodyReadBoundaryArchTest {
    private static final String NOTE_MAPPER = "ooo.klae.connex.backend.mappers.NoteMapper.";
    private static final String DISCLOSURE_NOTE_READ =
        "ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper.findNotePage";
    private static final Map<String, Set<String>> APPROVED_CALLERS = Map.ofEntries(
        Map.entry(NOTE_MAPPER + "getNoteById", Set.of()),
        Map.entry(NOTE_MAPPER + "getVisibleNoteById",
            Set.of("NoteService", "AttachmentService", "AttachmentWriteOperations", "ReferenceService")),
        Map.entry(NOTE_MAPPER + "getVisibleNoteByIdForUpdate",
            Set.of("NoteService", "AttachmentWriteOperations")),
        Map.entry(NOTE_MAPPER + "getVisibleNotesPage", Set.of("NoteService", "SearchService")),
        Map.entry(NOTE_MAPPER + "getWorkspaceNotesPage", Set.of("NoteService")),
        Map.entry(NOTE_MAPPER + "getVisibleNotesFilteredPage", Set.of("NoteService")),
        Map.entry(NOTE_MAPPER + "getVisibleNotesByPersonId", Set.of("NoteService", "PersonService")),
        Map.entry(NOTE_MAPPER + "getVisibleNotesByDealId", Set.of("NoteService", "DealService")),
        Map.entry(NOTE_MAPPER + "getVisibleNotesByAuthorId", Set.of("NoteService", "UserService")),
        Map.entry(NOTE_MAPPER + "getVisibleCompanyNotes", Set.of("CompanyService")),
        Map.entry(NOTE_MAPPER + "getAiAssistantVisibleNotesByCompanyId", Set.of("AiAssistantHistoryService")),
        Map.entry(NOTE_MAPPER + "getNotesReferencing", Set.of("NoteService")),
        Map.entry(NOTE_MAPPER + "searchVisible", Set.of("SearchService")),
        Map.entry(DISCLOSURE_NOTE_READ, Set.of("DataSubjectDisclosureReadTransaction")));
    private static final Set<String> SINGLE_NOTE_READS = Set.of(
        NOTE_MAPPER + "getNoteById", NOTE_MAPPER + "getVisibleNoteById",
        NOTE_MAPPER + "getVisibleNoteByIdForUpdate");
    private static final Pattern NOTE_TABLE = Pattern.compile(
        "\\b(?:from|join)\\s+(?:\\w+\\.)?note\\b(?:\\s+(?:as\\s+)?(\\w+))?",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "where", "join", "left", "right", "inner", "outer", "on", "order", "group",
        "limit", "offset", "union", "having", "for", "cross", "use", "force");
    private static final Pattern SELECT_START = Pattern.compile("\\bselect\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROJECTION_BOUNDARY = Pattern.compile("[()]|\\bfrom\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_STRING = Pattern.compile("'(?:''|\\\\.|[^'\\\\])*'");
    private static final Pattern VISIBILITY_FILTER = Pattern.compile(
        "visibility\\s*(?:=|!=|<>|(?:not\\s+)?in\\b)");

    @Test
    void onlyApprovedBoundedStatementsSelectNoteBodies() throws Exception {
        Map<String, String> bodyReads = noteBodyReads();
        assertEquals(APPROVED_CALLERS.keySet(), bodyReads.keySet(),
            "Note body SELECT inventory changed; remove aggregate readers or document a bounded read boundary");

        for (Map.Entry<String, String> statement : bodyReads.entrySet()) {
            String id = statement.getKey();
            String sql = statement.getValue().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("workspace_id = #{workspaceid}"),
                id + " must bind the server-resolved workspace before returning note bodies");
            if (SINGLE_NOTE_READS.contains(id)) {
                assertTrue(Pattern.compile("\\bid\\s*=\\s*#\\{id}").matcher(sql).find(),
                    id + " must select one exact note id");
            } else if (id.equals(DISCLOSURE_NOTE_READ)) {
                assertTrue(sql.contains("limit 100"), id + " must cap each body page at 100 rows");
                assertTrue(sql.contains("n.id in"), id + " must use the preflight note IDs");
                assertTrue(sql.contains("order by n.id"), id + " must preserve preflight order");
                assertFalse(VISIBILITY_FILTER.matcher(sql).find(),
                    id + " must not drop a note visibility class from a statutory disclosure");
            } else {
                assertTrue(sql.contains("limit #{limit}"),
                    id + " must apply its caller's bounded limit in SQL before materialization");
            }
            if (!id.equals(NOTE_MAPPER + "getNoteById") && !id.equals(DISCLOSURE_NOTE_READ)) {
                assertTrue(sql.contains("visibility = 'workspace'"),
                    id + " must apply note visibility in SQL before materialization");
            }
        }
    }

    @Test
    void onlyDocumentedCallersMayReadNoteBodies() throws Exception {
        Map<String, Set<String>> callers = new HashMap<>();
        APPROVED_CALLERS.keySet().forEach(id -> callers.put(id, new HashSet<>()));
        for (Path source : javaSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String id : APPROVED_CALLERS.keySet()) {
                String namespace = id.substring(0, id.lastIndexOf('.'));
                String mapperType = namespace.substring(namespace.lastIndexOf('.') + 1);
                Matcher receivers = Pattern.compile("\\b" + Pattern.quote(mapperType) + "\\s+(\\w+)\\b")
                    .matcher(text);
                while (receivers.find()) {
                    String receiver = receivers.group(1);
                    if (referenceTo(receiver, methodName(id)).matcher(text).find()) {
                        callers.get(id).add(source.getFileName().toString().replace(".java", ""));
                    }
                }
            }
        }
        assertEquals(APPROVED_CALLERS, callers,
            "New note-body callers require an explicit bounded-reader review; metadata consumers cannot opt in");
    }

    @Test
    void scoringCoolingAndRiskNeverReferenceBodySelectingStatements() throws Exception {
        Set<String> bodyMethods = noteBodyReads().keySet().stream()
            .map(NoteBodyReadBoundaryArchTest::methodName).collect(Collectors.toSet());
        List<String> violations = new ArrayList<>();
        int inspected = 0;
        for (Path source : javaSources()) {
            String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.contains("scor") && !name.contains("cooling") && !name.contains("risk")) {
                continue;
            }
            inspected++;
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String method : bodyMethods) {
                if (Pattern.compile("(?:\\.|::)\\s*" + Pattern.quote(method) + "\\b").matcher(text).find()) {
                    violations.add(source.getFileName() + ": " + method);
                }
            }
        }
        assertTrue(inspected >= 2, "Scoring and risk sources must be included in the scan");
        assertTrue(violations.isEmpty(),
            "Scoring, cooling, and risk require SQL aggregates or body-free projections: " + violations);
    }

    @Test
    void scannerDetectsBodiesAndWildcardsButIgnoresFilterOnlyContent() {
        for (String sql : List.of(
                "SELECT content FROM note",
                "SELECT n.body FROM note n",
                "SELECT n.content FROM person p JOIN note n ON n.person_id = p.id",
                "SELECT * FROM note",
                "SELECT DISTINCT n.* FROM note AS n",
                "SELECT n.id, n.* FROM note n",
                "SELECT (SELECT COUNT(*) FROM person) AS people, n.* FROM note n",
                "SELECT LEFT(n.content, 500) FROM note n",
                "SELECT n.`content` FROM `note` n")) {
            assertTrue(selectsNoteBody(sql), sql);
        }
        for (String sql : List.of(
                "SELECT COUNT(*) FROM note WHERE content LIKE #{query}",
                "SELECT n.created_at FROM note n WHERE n.content LIKE #{query}",
                "SELECT p.* FROM person p WHERE EXISTS (SELECT 1 FROM note n WHERE n.person_id = p.id)",
                "SELECT n.body FROM notification n WHERE EXISTS (SELECT 1 FROM note source_note)",
                "SELECT interaction.body FROM provider_captured_interaction interaction")) {
            assertFalse(selectsNoteBody(sql), sql);
        }
    }

    @Test
    void scannerExpandsLocalAndQualifiedMapperIncludes() throws Exception {
        Element first = parse("""
            <mapper namespace="first.Mapper">
              <sql id="columns"><include refid="second.Mapper.columns"/></sql>
              <select id="read">SELECT <include refid="columns"/> FROM note n</select>
            </mapper>
            """);
        Element second = parse("""
            <mapper namespace="second.Mapper"><sql id="columns">n.content</sql></mapper>
            """);
        Map<String, Element> fragments = fragments(List.of(first, second));
        Node select = first.getElementsByTagName("select").item(0);
        assertNotNull(select);
        assertTrue(selectsNoteBody(expand(select, "first.Mapper", fragments, Set.of())));
    }

    private static Map<String, String> noteBodyReads() throws Exception {
        List<Element> mappers = new ArrayList<>();
        try (var paths = Files.walk(repoRoot().resolve("backend/src/main/resources/mappers"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".xml")).sorted().toList()) {
                mappers.add(parse(Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        assertFalse(mappers.isEmpty(), "Mapper source scan found no XML files");
        Map<String, Element> fragments = fragments(mappers);
        Map<String, String> bodyReads = new LinkedHashMap<>();
        for (Element mapper : mappers) {
            String namespace = mapper.getAttribute("namespace");
            NodeList selects = mapper.getElementsByTagName("select");
            for (int index = 0; index < selects.getLength(); index++) {
                if (selects.item(index) instanceof Element select) {
                    String sql = expand(select, namespace, fragments, Set.of());
                    if (selectsNoteBody(sql)) {
                        bodyReads.put(namespace + "." + select.getAttribute("id"), sql);
                    }
                }
            }
        }
        return bodyReads;
    }

    private static boolean selectsNoteBody(String source) {
        String sql = SQL_STRING.matcher(source).replaceAll("''").replace("`", "")
            .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\r\\n]*", " ");
        Set<String> aliases = new HashSet<>(Set.of("note"));
        Matcher noteTables = NOTE_TABLE.matcher(sql);
        boolean readsNotes = false;
        while (noteTables.find()) {
            readsNotes = true;
            String alias = noteTables.group(1);
            if (alias != null && !SQL_KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
                aliases.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        if (!readsNotes) {
            return false;
        }
        String qualifier = aliases.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        Pattern body = Pattern.compile(
            "(?<![\\w.])(?:(?:" + qualifier + ")\\s*\\.\\s*)?(?:content|body)\\b",
            Pattern.CASE_INSENSITIVE);
        Pattern wildcard = Pattern.compile(
            "(?:^|,)\\s*(?:(?:distinct|all)\\s+)?(?:(?:" + qualifier
                + ")\\s*\\.\\s*)?\\*\\s*(?:,|$)", Pattern.CASE_INSENSITIVE);
        for (String projection : projections(sql)) {
            if (body.matcher(projection).find() || wildcard.matcher(projection).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> projections(String sql) {
        List<String> projections = new ArrayList<>();
        Matcher starts = SELECT_START.matcher(sql);
        while (starts.find()) {
            Matcher boundary = PROJECTION_BOUNDARY.matcher(sql);
            boundary.region(starts.end(), sql.length());
            int depth = 0;
            while (boundary.find()) {
                String token = boundary.group();
                if (token.equals("(")) {
                    depth++;
                } else if (token.equals(")")) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (depth == 0) {
                    projections.add(sql.substring(starts.end(), boundary.start()));
                    break;
                }
            }
        }
        return projections;
    }

    private static Map<String, Element> fragments(List<Element> mappers) {
        Map<String, Element> fragments = new HashMap<>();
        for (Element mapper : mappers) {
            NodeList entries = mapper.getElementsByTagName("sql");
            for (int index = 0; index < entries.getLength(); index++) {
                if (entries.item(index) instanceof Element fragment) {
                    fragments.put(mapper.getAttribute("namespace") + "." + fragment.getAttribute("id"), fragment);
                }
            }
        }
        return fragments;
    }

    private static String expand(
            Node node, String namespace, Map<String, Element> fragments, Set<String> included) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            return node.getNodeValue();
        }
        if (node instanceof Element element && element.getTagName().equals("include")) {
            String ref = element.getAttribute("refid");
            String qualified = ref.contains(".") ? ref : namespace + "." + ref;
            Element fragment = fragments.get(qualified);
            assertNotNull(fragment, "Unresolved mapper include: " + qualified);
            assertFalse(included.contains(qualified), "Recursive mapper include: " + qualified);
            Set<String> next = new HashSet<>(included);
            next.add(qualified);
            String fragmentNamespace = qualified.substring(0, qualified.lastIndexOf('.'));
            return expand(fragment, fragmentNamespace, fragments, next);
        }
        StringBuilder sql = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            sql.append(' ').append(expand(children.item(index), namespace, fragments, included));
        }
        return sql.toString();
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    private static List<Path> javaSources() throws Exception {
        try (var paths = Files.walk(repoRoot().resolve("backend/src/main/java"))) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Pattern referenceTo(String receiver, String method) {
        return Pattern.compile("\\b" + Pattern.quote(receiver) + "\\s*(?:\\.|::)\\s*"
            + Pattern.quote(method) + "\\b");
    }

    private static String methodName(String statementId) {
        return statementId.substring(statementId.lastIndexOf('.') + 1);
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(current.resolve("backend"))) {
            return current;
        }
        Path parent = current.getParent();
        return parent == null ? current : parent;
    }
}
