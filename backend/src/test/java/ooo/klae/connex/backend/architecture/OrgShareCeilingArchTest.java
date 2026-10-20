package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Organization-ceiling backstop for the sharing control plane (#97, #313 §0.5).
 * The same-org invariant lives in hand-written SQL — the {@code INSERT..SELECT}
 * grants in {@code ShareMapper.xml} (write path) and the {@code EXISTS} share
 * branches of the owned-or-shared visibility predicates in the entity mappers
 * (read path). The two paths express it differently and this test knows which is
 * which: every write grant now receives a trusted control-derived workspace
 * allowlist instead of joining the control table (#811), while on the read path
 * {@code AiAssistantIdentifierMapper}, {@code IdentityMapper}, {@code PersonEdgeMapper},
 * {@code IntroductionMapper}, {@code DuplicateReviewMapper} and {@code SegmentMapper} bind that allowlist — the remaining four mappers in
 * {@code SHARE_READERS} still join the control-plane {@code workspace} table and
 * match {@code ows.org_id = vws.org_id}.
 * Note fragments are checked separately because their legacy unfiltered record ceilings and
 * active-only assistant ceiling deliberately consume different scope types.
 * Report statements are checked separately to pin each aggregate owner and historical share cutoff.
 * The workspace-predicate scan cannot see either model (it only checks
 * {@code #{workspaceId}} is bound). These tests assert both paths carry their
 * reviewed ceiling, so a future shareable entity type copied without it fails
 * the build instead of silently degrading cross-org protection.
 */
class OrgShareCeilingArchTest {

    private static final Pattern OWNER_ALLOWLIST_CEILING = Pattern.compile(
        "JOIN\\s+JSON_TABLE\\s*\\(\\s*#\\{orgWorkspaceIdsJson}[^)]*\\)[^)]*\\)\\s*owner_workspace\\s*"
            + "ON\\s+owner_workspace\\.id\\s*=\\s*\\w+\\.workspace_id",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_ALLOWLIST_CEILING = Pattern.compile(
        "JOIN\\s+JSON_TABLE\\s*\\(\\s*#\\{orgWorkspaceIdsJson}[^)]*\\)[^)]*\\)\\s*target_workspace\\s*"
            + "ON\\s+target_workspace\\.id\\s*=\\s*#\\{targetWorkspaceId}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_PLANE_WORKSPACE_TABLE = Pattern.compile(
        "(?:FROM|JOIN)\\s+workspace\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern READ_CEILING = Pattern.compile("ows\\.org_id\\s*=\\s*vws\\.org_id");
    private static final Pattern CONTROL_DERIVED_READ_CEILING = Pattern.compile(
        "JOIN\\s+JSON_TABLE\\s*\\(\\s*#\\{orgWorkspaceIdsJson}", Pattern.CASE_INSENSITIVE);

    /**
     * Every entity mapper whose visibility predicate reads a {@code *_share} table
     * must pair each such read with the reviewed read-path organization ceiling.
     * Keyed by mapper resource to the share table it references.
     */
    private static final java.util.Map<String, Pattern> SHARE_READERS = java.util.Map.of(
        "mappers/AiAssistantIdentifierMapper.xml", Pattern.compile("FROM (?:person|company)_share"),
        "mappers/CompanyMapper.xml", Pattern.compile("FROM company_share"),
        "mappers/DealMapper.xml", Pattern.compile("FROM (?:company|person|pipeline)_share"),
        "mappers/DuplicateReviewMapper.xml", Pattern.compile("FROM company_share"),
        "mappers/IdentityMapper.xml", Pattern.compile("FROM (?:person|company)_share"),
        "mappers/IntroductionMapper.xml", Pattern.compile("FROM company_share"),
        "mappers/PersonEdgeMapper.xml", Pattern.compile("FROM (?:person|company)_share"),
        "mappers/PersonMapper.xml", Pattern.compile("FROM (?:person|company)_share"),
        "mappers/PipelineMapper.xml", Pattern.compile("FROM pipeline_share"),
        "mappers/SegmentMapper.xml", Pattern.compile("FROM (?:person|company)_share")
    );

    @Test
    void every_share_read_predicate_carries_the_same_org_ceiling() throws Exception {
        List<String> violations = new ArrayList<>();
        for (var entry : SHARE_READERS.entrySet()) {
            String xml = loadMapperText(entry.getKey());
            int shareReads = count(entry.getValue(), xml);
            Pattern ceiling = entry.getKey().equals("mappers/AiAssistantIdentifierMapper.xml")
                    || entry.getKey().equals("mappers/PersonEdgeMapper.xml")
                    || entry.getKey().equals("mappers/IdentityMapper.xml")
                    || entry.getKey().equals("mappers/IntroductionMapper.xml")
                    || entry.getKey().equals("mappers/DuplicateReviewMapper.xml")
                    || entry.getKey().equals("mappers/SegmentMapper.xml")
                ? CONTROL_DERIVED_READ_CEILING
                : READ_CEILING;
            int ceilings = count(ceiling, xml);
            if (shareReads < 1) {
                violations.add(entry.getKey() + " references its share table 0 times — the scan is misconfigured");
            } else if (ceilings != shareReads) {
                violations.add(entry.getKey() + " has " + shareReads + " share-table reads but " + ceilings
                    + " reviewed org ceilings; every share read must be same-org gated");
            }
        }
        assertTrue(violations.isEmpty(),
            "Read-path share visibility is missing the same-organization ceiling: " + violations);
    }

    @Test
    void every_note_share_fragment_carries_its_reviewed_scope_ceiling() throws Exception {
        Document document = loadMapper("mappers/NoteMapper.xml");
        Pattern shareRead = Pattern.compile("(?:FROM|JOIN)\\s+\\w+_share\\b",
            Pattern.CASE_INSENSITIVE);
        Map<String, String> recordFragments = Map.of("visiblePerson", "p", "visibleCompany", "c");
        int shareReads = 0;
        int recordCeilings = 0;
        int assistantCeilings = 0;
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            String sql = element.getTextContent().replaceAll("\\s+", " ").trim();
            int reads = count(shareRead, sql);
            if (reads == 0) {
                continue;
            }
            shareReads += reads;
            String id = element.getAttribute("id");
            assertEquals("sql", element.getTagName(), id);
            assertEquals(1, reads, id);
            if (recordFragments.containsKey(id)) {
                assertEquals(1, count(CONTROL_DERIVED_READ_CEILING, sql), id);
                assertTrue(sql.contains("ON org_workspace.id = " + recordFragments.get(id) + ".workspace_id"), id);
                recordCeilings++;
            } else {
                assertEquals("assistantProcessablePerson", id, "Unreviewed Note share fragment");
                NodeList allowlists = element.getElementsByTagName("foreach");
                assertEquals(1, allowlists.getLength(), id);
                Element allowlist = (Element) allowlists.item(0);
                assertEquals("organizationWorkspaceIds", allowlist.getAttribute("collection"));
                assertEquals("organizationWorkspaceId", allowlist.getAttribute("item"));
                assertEquals("(", allowlist.getAttribute("open"));
                assertEquals(")", allowlist.getAttribute("close"));
                assertEquals(",", allowlist.getAttribute("separator"));
                assertEquals("#{organizationWorkspaceId}", allowlist.getTextContent().trim());
                for (String column : List.of("archived_at", "suspended_at", "provision_ceased_at")) {
                    assertTrue(sql.contains("p." + column + " IS NULL"), id);
                }
                assertTrue(sql.contains("OR (p.workspace_id IN #{organizationWorkspaceId} AND EXISTS"), id);
                assertTrue(sql.contains("ps.person_id = p.id AND ps.workspace_id = #{workspaceId}"), id);
                assertEquals(0, count(CONTROL_DERIVED_READ_CEILING, sql), id);
                assistantCeilings++;
            }
        }
        assertEquals(3, shareReads);
        assertEquals(2, recordCeilings);
        assertEquals(1, assistantCeilings);
        assertEquals(0, count(CONTROL_PLANE_WORKSPACE_TABLE, loadMapperText("mappers/NoteMapper.xml")));
    }

    @Test
    void every_report_share_branch_carries_its_reviewed_scope_and_cutoff() throws Exception {
        Document document = loadMapper("mappers/ReportMapper.xml");
        Map<String, List<String>> owners = Map.of(
            "aggregateCoverageGaps", List.of("gap_person", "c"),
            "aggregateSingleThreadedDeals", List.of("single_company"),
            "getNetworkAccountValues", List.of("network_company"),
            "getVisiblePersonIdsAt", List.of("p"),
            "getVisibleCompanyIdsAt", List.of("c"));
        Pattern branch = Pattern.compile("FROM\\s+(person|company)_share\\s+(\\w+)\\s+"
            + "JOIN\\s+JSON_TABLE\\(#\\{orgWorkspaceIdsJson}, '\\$\\[\\*]' COLUMNS\\(id INT PATH '\\$'\\)\\)"
            + "\\s+org_workspace\\s+ON org_workspace.id = (\\w+).workspace_id\\s+"
            + "WHERE \\2\\.(person|company)_id = \\3.id\\s+AND \\2.workspace_id = #\\{(query\\.)?workspaceId}"
            + "(?:\\s+AND \\2.created_at (<=|<) #\\{(asOf|query.endUtc)})?\\s*\\)");
        int statements = 0;
        int shares = 0;
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element element)) {
                continue;
            }
            String sql = element.getTextContent().replaceAll("\\s+", " ").trim();
            int reads = count(Pattern.compile("FROM (?:person|company)_share\\b"), sql);
            if (reads == 0) {
                continue;
            }
            String id = element.getAttribute("id");
            assertEquals("select", element.getTagName(), id);
            assertTrue(owners.containsKey(id), "Unreviewed Report share reader: " + id);
            assertEquals(owners.get(id).size(), reads, id);
            assertEquals(reads, count(CONTROL_DERIVED_READ_CEILING, sql), id);
            var matcher = branch.matcher(sql);
            List<String> actualOwners = new ArrayList<>();
            while (matcher.find()) {
                actualOwners.add(matcher.group(3));
                assertEquals(matcher.group(1), matcher.group(4), id);
                if (id.equals("aggregateCoverageGaps")) {
                    assertEquals("<", matcher.group(6), id);
                    assertEquals("query.endUtc", matcher.group(7), id);
                } else if (id.startsWith("getVisible")) {
                    assertEquals("<=", matcher.group(6), id);
                    assertEquals("asOf", matcher.group(7), id);
                    assertTrue(sql.contains(matcher.group(3) + ".created_at <= #{asOf}"), id);
                } else {
                    assertEquals(null, matcher.group(6), id);
                }
            }
            assertEquals(owners.get(id), actualOwners, id);
            shares += reads;
            statements++;
        }
        assertEquals(5, statements);
        assertEquals(6, shares);
        assertEquals(0, count(CONTROL_PLANE_WORKSPACE_TABLE, loadMapperText("mappers/ReportMapper.xml")));
    }

    @Test
    void every_segment_share_read_binds_the_owning_workspace_to_the_unfiltered_scope() throws Exception {
        Document document = loadMapper("mappers/SegmentMapper.xml");
        Pattern shareRead = Pattern.compile("(?:FROM|JOIN)\\s+\\w+_share\\b", Pattern.CASE_INSENSITIVE);
        Map<String, List<String>> owners = Map.of(
            "companyIdsNoActivitySince", List.of("processable"),
            "companyIdsForPersonsWithoutUserActivity", List.of("processable"),
            "companyIdsWithWarmIntro", List.of("engaged", "processable"),
            "personCompanyOp", List.of("visible_company"));
        int shareReads = 0;
        int fragments = 0;
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element element)) {
                continue;
            }
            String sql = element.getTextContent().replaceAll("\\s+", " ").trim();
            int reads = count(shareRead, sql);
            if (reads == 0) {
                continue;
            }
            String id = element.getAttribute("id");
            assertTrue(owners.containsKey(id), "Unreviewed Segment share read: " + id);
            assertEquals(owners.get(id).size(), reads, id);
            assertEquals(reads, count(CONTROL_DERIVED_READ_CEILING, sql), id);
            for (String owner : owners.get(id)) {
                Pattern ownerCeiling = Pattern.compile(
                    "JOIN JSON_TABLE\\( #\\{orgWorkspaceIdsJson}, '\\$\\[\\*]' COLUMNS\\(id INT PATH '\\$'\\) "
                        + "\\) org_workspace ON org_workspace\\.id = " + owner + "\\.workspace_id");
                assertEquals(1, count(ownerCeiling, sql), id + ": " + owner);
            }
            shareReads += reads;
            fragments++;
        }
        assertEquals(5, shareReads);
        assertEquals(owners.size(), fragments);
        assertEquals(0, count(CONTROL_PLANE_WORKSPACE_TABLE, loadMapperText("mappers/SegmentMapper.xml")));
    }

    private int count(Pattern pattern, String text) {
        int n = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    private String loadMapperText(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not found on the classpath");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * The grant ceiling is now the control-derived workspace allowlist rather than a join on
     * the control-plane {@code workspace} table (#811). Both ends must still be checked: the
     * owning workspace and the target workspace each have to appear in the allowlist, so an
     * allowlist assembled for some other organization refuses the grant on the owner side.
     */
    @Test
    void every_share_grant_enforces_the_same_org_ceiling() throws Exception {
        Document doc = loadShareMapper();
        List<String> violations = new ArrayList<>();
        int grants = 0;

        NodeList inserts = doc.getElementsByTagName("insert");
        for (int i = 0; i < inserts.getLength(); i++) {
            Element insert = (Element) inserts.item(i);
            String id = insert.getAttribute("id");
            if (!id.startsWith("share")) {
                continue;
            }
            grants++;
            String sql = insert.getTextContent();
            if (!OWNER_ALLOWLIST_CEILING.matcher(sql).find()) {
                violations.add(id + " (owning workspace is not allowlist-checked)");
            }
            if (!TARGET_ALLOWLIST_CEILING.matcher(sql).find()) {
                violations.add(id + " (target workspace is not allowlist-checked)");
            }
        }

        assertTrue(grants >= 3,
            "Only " + grants + " share-grant inserts found in ShareMapper.xml — the scan looks "
                + "misconfigured and this guard would pass vacuously.");
        assertTrue(violations.isEmpty(),
            "These share-grant statements are missing the same-organization ceiling "
                + "(JOIN JSON_TABLE(#{orgWorkspaceIdsJson}) on both the owning and the target "
                + "workspace): " + violations);
    }

    /**
     * The share mapper is tenant-scoped, so nothing in it may read the control-plane
     * {@code workspace} table — not as a grant ceiling and not as name hydration. Those
     * statements cannot execute once an organization's data lives in its own catalog (#811).
     */
    @Test
    void the_share_mapper_reads_org_data_tables_only() throws Exception {
        String xml = loadMapperText("mappers/ShareMapper.xml").replaceAll("(?s)<!--.*?-->", "");

        assertTrue(count(CONTROL_PLANE_WORKSPACE_TABLE, xml) == 0,
            "ShareMapper.xml references the control-plane workspace table; the organization "
                + "ceiling and the workspace names must come from the service's control snapshot");
        assertTrue(xml.contains("#{orgWorkspaceIdsJson}"),
            "ShareMapper.xml no longer binds the control-derived workspace allowlist — the scan "
                + "looks misconfigured and the ceiling guard above would pass vacuously");
    }

    private Document loadShareMapper() throws Exception {
        return loadMapper("mappers/ShareMapper.xml");
    }

    private Document loadMapper(String resource) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not found on the classpath");
            return builder.parse(in);
        }
    }
}
