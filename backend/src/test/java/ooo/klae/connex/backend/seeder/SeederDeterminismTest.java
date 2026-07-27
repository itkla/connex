package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.services.IdentityKind;
import ooo.klae.connex.backend.services.MatchingService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@Rollback
class SeederDeterminismTest {

    private static final long SEED = 853L;
    private static final LocalDate ANCHOR_DATE = LocalDate.of(2026, 1, 15);

    @Autowired
    private SeederService seederService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MatchingService matchingService;

    @Test
    void sameSeedAndAnchorProduceIdenticalIdIndependentContentWithoutNotifications() {
        SeedRunSummary firstRun =
            seederService.seed(SeederProperties.Profile.SMALL, SEED, 1, ANCHOR_DATE);
        int firstWorkspaceId = workspaceId(firstRun);
        Snapshot first = snapshot(firstWorkspaceId);
        assertExpectedCounts(firstRun, first);
        assertEquals(0, first.counts().get("notification"));
        assertRealismEdges(firstWorkspaceId);

        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();

        SeedRunSummary secondRun =
            seederService.seed(SeederProperties.Profile.SMALL, SEED, 1, ANCHOR_DATE);
        int secondWorkspaceId = workspaceId(secondRun);
        Snapshot second = snapshot(secondWorkspaceId);
        assertExpectedCounts(secondRun, second);
        assertEquals(0, second.counts().get("notification"));
        TestTransaction.flagForRollback();

        assertEquals(firstRun, secondRun);
        assertEquals(first.counts(), second.counts());
        assertEquals(first.contentDigest(), second.contentDigest());
    }

    private int workspaceId(SeedRunSummary run) {
        String slug = run.workspaces().getFirst().slug();
        return jdbcTemplate.queryForObject(
            "SELECT id FROM workspace WHERE slug = ?",
            Integer.class,
            slug
        );
    }

    private Snapshot snapshot(int workspaceId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("organization", count(
            "SELECT COUNT(*) FROM organization o "
                + "JOIN workspace w ON w.org_id = o.id WHERE w.id = ?",
            workspaceId));
        counts.put("workspace", count("SELECT COUNT(*) FROM workspace WHERE id = ?", workspaceId));
        counts.put("app_user", count(
            "SELECT COUNT(*) FROM app_user u "
                + "JOIN workspace_member wm ON wm.user_id = u.id WHERE wm.workspace_id = ?",
            workspaceId));
        counts.put("org_member", count(
            "SELECT COUNT(*) FROM org_member om "
                + "JOIN workspace w ON w.org_id = om.org_id WHERE w.id = ?",
            workspaceId));
        counts.put("workspace_member", count(
            "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ?",
            workspaceId));
        for (String table : List.of(
                "pipeline",
                "stage",
                "tag",
                "company",
                "person",
                "person_employment",
                "deal",
                "deal_stage_history",
                "activity",
                "note",
                "task",
                "attachment",
                "notification")) {
            counts.put(table, count(
                "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
                workspaceId
            ));
        }
        counts.put("company_tag", count(
            "SELECT COUNT(*) FROM company_tag ct "
                + "JOIN company c ON c.id = ct.company_id WHERE c.workspace_id = ?",
            workspaceId));
        counts.put("person_tag", count(
            "SELECT COUNT(*) FROM person_tag pt "
                + "JOIN person p ON p.id = pt.person_id WHERE p.workspace_id = ?",
            workspaceId));
        counts.put("deal_person", count(
            "SELECT COUNT(*) FROM deal_person dp "
                + "JOIN deal d ON d.id = dp.deal_id WHERE d.workspace_id = ?",
            workspaceId));
        counts.put("deal_tag", count(
            "SELECT COUNT(*) FROM deal_tag dt "
                + "JOIN deal d ON d.id = dt.deal_id WHERE d.workspace_id = ?",
            workspaceId));
        return new Snapshot(counts, digest(workspaceId));
    }

    private String digest(int workspaceId) {
        MessageDigest digest = sha256();
        append(digest, "organization-workspace", workspaceId,
            "SELECT CAST(JSON_ARRAY(o.name, o.slug, w.name, w.slug) AS CHAR) "
                + "FROM workspace w JOIN organization o ON o.id = w.org_id "
                + "WHERE w.id = ? ORDER BY o.slug, w.slug");
        append(digest, "users-memberships", workspaceId,
            "SELECT CAST(JSON_ARRAY(u.username, u.display_name, u.email, u.email_verified, "
                + "u.password_hash, u.timezone, u.locale, u.profile_picture_url, "
                + "wm.role, wm.status, om.org_role) AS CHAR) AS signature "
                + "FROM workspace_member wm "
                + "JOIN workspace w ON w.id = wm.workspace_id "
                + "JOIN app_user u ON u.id = wm.user_id "
                + "JOIN org_member om ON om.org_id = w.org_id AND om.user_id = u.id "
                + "WHERE wm.workspace_id = ? ORDER BY signature");
        append(digest, "pipelines-stages", workspaceId,
            "SELECT CAST(JSON_ARRAY(p.name, s.name, s.position, s.is_success, s.is_failure) AS CHAR) "
                + "AS signature FROM pipeline p JOIN stage s ON s.pipeline_id = p.id "
                + "AND s.workspace_id = p.workspace_id WHERE p.workspace_id = ? ORDER BY signature");
        append(digest, "tags", workspaceId,
            "SELECT CAST(JSON_ARRAY(name, color) AS CHAR) AS signature "
                + "FROM tag WHERE workspace_id = ? ORDER BY signature");
        append(digest, "companies", workspaceId,
            "SELECT CAST(JSON_ARRAY(c.name, c.website, c.industry, c.phone, c.address, c.logo_url, "
                + "u.username, DATE_FORMAT(c.created_at, '%Y-%m-%d %H:%i:%s')) AS CHAR) AS signature "
                + "FROM company c LEFT JOIN app_user u ON u.id = c.owner_id "
                + "WHERE c.workspace_id = ? ORDER BY signature");
        append(digest, "company-tags", workspaceId,
            "SELECT CAST(JSON_ARRAY(c.name, c.website, t.name) AS CHAR) AS signature "
                + "FROM company_tag ct JOIN company c ON c.id = ct.company_id "
                + "JOIN tag t ON t.id = ct.tag_id WHERE c.workspace_id = ? ORDER BY signature");
        append(digest, "persons", workspaceId,
            "SELECT CAST(JSON_ARRAY(p.name, p.email, p.phone, c.name, c.website, p.title, p.image_url, "
                + "u.username, p.risk_excluded, p.intro_excluded, p.suspended_at, p.provision_ceased_at, "
                + "DATE_FORMAT(p.created_at, '%Y-%m-%d %H:%i:%s')) AS CHAR) AS signature "
                + "FROM person p LEFT JOIN company c ON c.id = p.company_id "
                + "LEFT JOIN app_user u ON u.id = p.owner_id "
                + "WHERE p.workspace_id = ? ORDER BY signature");
        append(digest, "employment", workspaceId,
            "SELECT CAST(JSON_ARRAY(p.name, p.email, pe.company_name, c.name, pe.title, "
                + "DATE_FORMAT(pe.started_at, '%Y-%m-%d %H:%i:%s'), "
                + "DATE_FORMAT(pe.ended_at, '%Y-%m-%d %H:%i:%s')) AS CHAR) AS signature "
                + "FROM person_employment pe JOIN person p ON p.id = pe.person_id "
                + "LEFT JOIN company c ON c.id = pe.company_id "
                + "WHERE pe.workspace_id = ? ORDER BY signature");
        append(digest, "person-tags", workspaceId,
            "SELECT CAST(JSON_ARRAY(p.name, p.email, t.name) AS CHAR) AS signature "
                + "FROM person_tag pt JOIN person p ON p.id = pt.person_id "
                + "JOIN tag t ON t.id = pt.tag_id WHERE p.workspace_id = ? ORDER BY signature");
        append(digest, "deals", workspaceId,
            "SELECT CAST(JSON_ARRAY(d.name, d.value, d.actual_value, d.currency, pl.name, s.name, "
                + "s.position, d.position, c.name, c.website, u.username, d.expected_close_date, "
                + "DATE_FORMAT(d.closed_at, '%Y-%m-%d %H:%i:%s'), d.closed_reason, d.won, "
                + "d.risk_excluded, DATE_FORMAT(d.created_at, '%Y-%m-%d %H:%i:%s')) AS CHAR) AS signature "
                + "FROM deal d JOIN pipeline pl ON pl.id = d.pipeline_id "
                + "JOIN stage s ON s.id = d.stage_id LEFT JOIN company c ON c.id = d.company_id "
                + "LEFT JOIN app_user u ON u.id = d.owner_id "
                + "WHERE d.workspace_id = ? ORDER BY signature");
        append(digest, "deal-person", workspaceId,
            "SELECT CAST(JSON_ARRAY(d.name, p.name, p.email, dp.role) AS CHAR) AS signature "
                + "FROM deal_person dp JOIN deal d ON d.id = dp.deal_id "
                + "JOIN person p ON p.id = dp.person_id WHERE d.workspace_id = ? ORDER BY signature");
        append(digest, "deal-history", workspaceId,
            "SELECT CAST(JSON_ARRAY(d.name, pl.name, s.name, s.position, dsh.stage_name, "
                + "DATE_FORMAT(dsh.achieved_at, '%Y-%m-%d %H:%i:%s'), dsh.conversion_eligible) AS CHAR) "
                + "AS signature FROM deal_stage_history dsh JOIN deal d ON d.id = dsh.deal_id "
                + "JOIN pipeline pl ON pl.id = d.pipeline_id LEFT JOIN stage s ON s.id = dsh.stage_id "
                + "WHERE dsh.workspace_id = ? ORDER BY signature");
        append(digest, "deal-tags", workspaceId,
            "SELECT CAST(JSON_ARRAY(d.name, t.name) AS CHAR) AS signature "
                + "FROM deal_tag dt JOIN deal d ON d.id = dt.deal_id "
                + "JOIN tag t ON t.id = dt.tag_id WHERE d.workspace_id = ? ORDER BY signature");
        append(digest, "activities", workspaceId,
            "SELECT CAST(JSON_ARRAY(a.type, a.subject, a.notes, p.name, p.email, d.name, u.username, "
                + "DATE_FORMAT(a.timestamp, '%Y-%m-%d %H:%i:%s')) AS CHAR) AS signature "
                + "FROM activity a LEFT JOIN person p ON p.id = a.person_id "
                + "LEFT JOIN deal d ON d.id = a.deal_id JOIN app_user u ON u.id = a.created_by_id "
                + "WHERE a.workspace_id = ? ORDER BY signature");
        append(digest, "notes", workspaceId,
            "SELECT CAST(JSON_ARRAY(n.title, n.content, n.visibility, u.username, p.name, p.email, d.name, "
                + "DATE_FORMAT(n.created_at, '%Y-%m-%d %H:%i:%s')) AS CHAR) AS signature "
                + "FROM note n JOIN app_user u ON u.id = n.author_id "
                + "LEFT JOIN person p ON p.id = n.person_id LEFT JOIN deal d ON d.id = n.deal_id "
                + "WHERE n.workspace_id = ? ORDER BY signature");
        append(digest, "tasks", workspaceId,
            "SELECT CAST(JSON_ARRAY(t.description, t.completed, t.status, t.position, t.due_date, "
                + "u.username, p.name, p.email, d.name, DATE_FORMAT(t.created_at, '%Y-%m-%d %H:%i:%s')) "
                + "AS CHAR) AS signature FROM task t JOIN app_user u ON u.id = t.assigned_to_id "
                + "LEFT JOIN person p ON p.id = t.person_id LEFT JOIN deal d ON d.id = t.deal_id "
                + "WHERE t.workspace_id = ? ORDER BY signature");
        append(digest, "attachments", workspaceId,
            "SELECT CAST(JSON_ARRAY(a.entity_type, "
                + "CASE a.entity_type WHEN 'person' THEN ep.name WHEN 'company' THEN ec.name "
                + "WHEN 'deal' THEN ed.name END, ep.email, ec.website, "
                + "a.file_name, a.url, a.content_type, a.size, u.username) AS CHAR) AS signature "
                + "FROM attachment a "
                + "LEFT JOIN person ep ON a.entity_type = 'person' AND ep.id = a.entity_id "
                + "LEFT JOIN company ec ON a.entity_type = 'company' AND ec.id = a.entity_id "
                + "LEFT JOIN deal ed ON a.entity_type = 'deal' AND ed.id = a.entity_id "
                + "LEFT JOIN app_user u ON u.id = a.uploaded_by_id "
                + "WHERE a.workspace_id = ? ORDER BY signature");
        return HexFormat.of().formatHex(digest.digest());
    }

    private void append(MessageDigest digest, String label, int workspaceId, String sql) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        for (String row : jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> resultSet.getString(1),
                workspaceId)) {
            digest.update(row.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
    }

    private int count(String sql, int workspaceId) {
        return jdbcTemplate.queryForObject(sql, Integer.class, workspaceId);
    }

    private void assertExpectedCounts(SeedRunSummary run, Snapshot snapshot) {
        assertEquals(run.workspaces().getFirst().rowCounts(), snapshot.counts());
        assertEquals(50, snapshot.counts().get("person"));
        assertEquals(10, snapshot.counts().get("company"));
        assertEquals(20, snapshot.counts().get("deal"));
        assertEquals(200, snapshot.counts().get("activity"));
    }

    private void assertRealismEdges(int workspaceId) {
        assertTrue(count(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND email IS NULL",
            workspaceId) > 0);
        assertTrue(count(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND company_id IS NULL",
            workspaceId) > 0);
        assertTrue(count(
            "SELECT COUNT(*) FROM deal WHERE workspace_id = ? AND company_id IS NULL",
            workspaceId) > 0);
        assertEquals(3, count(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? "
                + "AND name IN ('山田 太郎', 'ヤマダ タロウ', 'やまだ たろう')",
            workspaceId));
        assertSeededPhonesAreMatchable(workspaceId);
    }

    private void assertSeededPhonesAreMatchable(int workspaceId) {
        List<String> phones = new ArrayList<>();
        phones.addAll(jdbcTemplate.queryForList(
            "SELECT phone FROM person WHERE workspace_id = ? AND phone IS NOT NULL AND phone <> ''",
            String.class,
            workspaceId));
        phones.addAll(jdbcTemplate.queryForList(
            "SELECT phone FROM company WHERE workspace_id = ? AND phone IS NOT NULL AND phone <> ''",
            String.class,
            workspaceId));
        assertTrue(phones.size() > 0, "the fixture must seed some phone numbers");
        List<String> unmatchable = phones.stream()
            .filter(phone -> matchingService.normalizeIdentifier(IdentityKind.PHONE, phone).isEmpty())
            .toList();
        assertEquals(List.of(), unmatchable,
            "every seeded phone must normalize to E.164 or the identity backfill silently drops it");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record Snapshot(Map<String, Integer> counts, String contentDigest) {

        private Snapshot {
            counts = Map.copyOf(counts);
        }
    }
}
