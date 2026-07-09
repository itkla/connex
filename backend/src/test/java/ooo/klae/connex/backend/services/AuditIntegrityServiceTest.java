package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.AuditLog;

class AuditIntegrityServiceTest extends AbstractServiceTest {

    private static final String HASH_GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TEST_SECRET = "test-audit-integrity-hmac-secret-change-me";

    @Autowired private AuditService auditService;
    @Autowired private AuditIntegrityService auditIntegrityService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void workspaceEventsChainByWorkspaceScope() {
        String firstSummary = "first-" + unique();
        String secondSummary = "second-" + unique();

        auditService.record("test.integrity", "company", 1, "Acme", firstSummary, null);
        auditService.record("test.integrity", "company", 1, "Acme", secondSummary, null);

        AuditLog first = findBySummary(auditService.recent(20, 0), firstSummary);
        AuditLog second = findBySummary(auditService.recent(20, 0), secondSummary);

        assertEquals("workspace", first.getChainScopeType());
        assertEquals(workspace.getId(), first.getChainScopeId());
        assertEquals("workspace", second.getChainScopeType());
        assertEquals(workspace.getId(), second.getChainScopeId());
        assertNotNull(first.getRowHash());
        assertEquals(64, first.getRowHash().length());
        assertEquals(first.getChainIndex() + 1, second.getChainIndex());
        assertEquals(first.getRowHash(), second.getPrevHash());
        assertNotEquals(first.getRowHash(), second.getRowHash());
        assertEquals(expectedHmac(auditIntegrityService.integrityPayload(first)), first.getRowHash());
        assertEquals(1, checkpointCount(first.getId()));
    }

    @Test
    void orgEventsChainByOrganizationScope() {
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        String summary = "org-" + unique();

        auditService.record("test.org_integrity", "organization", orgId, "Org", summary, null);

        AuditLog entry = findBySummary(auditService.recentForOrg(orgId, 20, 0), summary);

        assertEquals("organization", entry.getChainScopeType());
        assertEquals(orgId, entry.getChainScopeId());
        assertEquals(HASH_GENESIS, entry.getPrevHash());
        assertNotNull(entry.getRowHash());
        assertEquals(64, entry.getRowHash().length());
    }

    private int checkpointCount(int auditLogId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log_integrity_checkpoint WHERE audit_log_id = ?",
                Integer.class, auditLogId);
        return count == null ? 0 : count;
    }

    private static String expectedHmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AuditLog findBySummary(List<AuditLog> entries, String summary) {
        return entries.stream()
            .filter(entry -> summary.equals(entry.getSummary()))
            .findFirst()
            .orElseThrow();
    }
}
