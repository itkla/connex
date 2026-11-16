package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins both copies of the cutover population. A later divergence from UserMapper's
 * privilegedOrgBranches calls for a follow-up review of grandfathered coverage, never an edit
 * to the shipped V225 migration.
 */
class PrivilegedMfaCutoverMigrationTest {
    static final String RESOURCE = "db/migration/control/V225__privileged_mfa_cutover.sql";
    private static final String EXPECTED_POPULATION = """
            SELECT om.user_id, o.id AS org_id
            FROM org_member om
            JOIN organization o ON o.id = om.org_id AND o.lifecycle_state = 'active'
            UNION ALL
            SELECT wm.user_id, o.id AS org_id
            FROM workspace_member wm
            JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
            JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
            WHERE wm.status = 'active'
              AND wm.role_id IS NULL
              AND wm.role IN ('admin', 'owner')
            UNION ALL
            SELECT wm.user_id, o.id AS org_id
            FROM workspace_member wm
            JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
            JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
            JOIN workspace_role_permission wrp ON wrp.workspace_role_id = wm.role_id
            WHERE wm.status = 'active'
              AND wrp.permission IN (
                'API_CREDENTIAL_MANAGE', 'AUDIT_READ', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
                'CUSTOM_FIELD_MANAGE', 'DOCUMENT_MANAGE', 'MEMBER_MANAGE', 'PIPELINE_MANAGE',
                'PRODUCT_MANAGE', 'ROLE_MANAGE', 'RULE_MANAGE', 'SEQUENCE_MANAGE',
                'SHARE_MANAGE', 'TAG_MANAGE', 'TEAM_MANAGE', 'WORKSPACE_SETTINGS'
              )
        """;

    @Test
    void bothPopulationCopiesStayVerbatimPinned() throws IOException {
        List<String> blocks = populationBlocks();
        assertEquals(2, blocks.size());
        for (String block : blocks) {
            assertEquals(EXPECTED_POPULATION, block);
        }
    }

    @Test
    void cutoverHasOnlyTheFourOrderedDmlStatements() throws IOException {
        String sql = migrationSql().replaceAll("--[^\\n]*", "");
        List<String> verbs = Pattern.compile(";").splitAsStream(sql)
            .map(String::strip).filter(statement -> !statement.isEmpty())
            .map(statement -> statement.split("\\s+", 2)[0]).toList();
        assertEquals(List.of("UPDATE", "DELETE", "INSERT", "UPDATE"), verbs);
    }

    static List<String> populationBlocks() throws IOException {
        Matcher matcher = Pattern.compile(
            "-- BEGIN PRIVILEGED CUTOVER POPULATION\\n(.*?)-- END PRIVILEGED CUTOVER POPULATION",
            Pattern.DOTALL).matcher(migrationSql());
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        return blocks;
    }

    static String migrationSql() throws IOException {
        return new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
    }
}
