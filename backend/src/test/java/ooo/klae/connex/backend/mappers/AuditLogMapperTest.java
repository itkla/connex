package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AuditSupportRowDto;

class AuditLogMapperTest extends AbstractMapperTest {
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private OrganizationMapper organizationMapper;

    private long nextChainIndex = 1;

    @Test
    void findRecentPagesThroughWorkspaceScopedEventsWithoutOverlap() {
        Workspace ws = newWorkspace();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertAudit(ws.getId(), "company", 1, "event-" + i));
        }
        List<Integer> newestFirst = new ArrayList<>(ids);
        Collections.reverse(newestFirst);

        List<Integer> paged = new ArrayList<>();
        paged.addAll(idsOf(auditLogMapper.findRecent(ws.getId(), 2, 0)));
        paged.addAll(idsOf(auditLogMapper.findRecent(ws.getId(), 2, 2)));
        paged.addAll(idsOf(auditLogMapper.findRecent(ws.getId(), 2, 4)));

        assertEquals(newestFirst, paged);
    }

    @Test
    void findRecentStaysScopedToItsWorkspace() {
        Workspace mine = newWorkspace();
        Workspace other = newWorkspace();
        insertAudit(mine.getId(), "company", 1, "mine");
        int otherId = insertAudit(other.getId(), "company", 1, "theirs");

        List<Integer> mineIds = idsOf(auditLogMapper.findRecent(mine.getId(), 50, 0));
        assertTrue(mineIds.stream().noneMatch(id -> id == otherId));
        assertEquals(List.of(otherId), idsOf(auditLogMapper.findRecent(other.getId(), 50, 0)));
    }

    @Test
    void findByEntityPagesOnlyMatchingEntity() {
        Workspace ws = newWorkspace();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(insertAudit(ws.getId(), "deal", 9, "deal-" + i));
        }
        insertAudit(ws.getId(), "company", 9, "noise");
        List<Integer> newestFirst = new ArrayList<>(ids);
        Collections.reverse(newestFirst);

        List<Integer> paged = new ArrayList<>();
        paged.addAll(idsOf(auditLogMapper.findByEntity(ws.getId(), "deal", 9, 3, 0)));
        paged.addAll(idsOf(auditLogMapper.findByEntity(ws.getId(), "deal", 9, 3, 3)));

        assertEquals(newestFirst, paged);
    }

    @Test
    void findWorkspaceExportOrdersByChainIndex() {
        Workspace ws = newWorkspace();
        int second = insertAuditWithChain(ws.getId(), 2, "second");
        int first = insertAuditWithChain(ws.getId(), 1, "first");

        assertEquals(List.of(first, second), idsOf(auditLogMapper.findWorkspaceExport(ws.getId(), 50, 0)));
    }

    @Test
    void clientAssertedCorrelationFilterCannotPullAnotherTenantsAuditRows() {
        Workspace mine = newWorkspace();
        int mineOrgId = workspaceMapper.getOrgId(mine.getId());
        Workspace sameOrgOtherWorkspace = newWorkspace(mineOrgId);
        Organization foreignOrg = newOrganization();
        Workspace foreign = newWorkspace(foreignOrg.getId());
        String rawCorrelationId = "client-correlation-123";
        String correlationHmac = "pseudonymized-client-correlation";
        int mineCurrentId = insertAudit(
            mine.getId(), "person", 412, "mine-current", correlationHmac);
        int mineLegacyId = insertAudit(
            mine.getId(), "person", 412, "mine-legacy", rawCorrelationId);
        insertAudit(
            sameOrgOtherWorkspace.getId(), "person", 412, "same-org-other-workspace",
            correlationHmac);
        insertAudit(
            mine.getId(), "person", 412, "same-workspace-other-correlation",
            "different-correlation-456");
        insertAudit(foreign.getId(), "person", 412, "foreign", correlationHmac);
        Instant now = Instant.now();

        List<AuditSupportRowDto> rows = auditLogMapper.findEntitySupportSlice(
            mine.getId(),
            workspaceMapper.getOrgId(mine.getId()),
            "person",
            412,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(1, ChronoUnit.DAYS),
            correlationHmac,
            rawCorrelationId,
            10);

        assertEquals(
            List.of((long) mineCurrentId, (long) mineLegacyId),
            rows.stream().map(AuditSupportRowDto::auditId).toList());
    }

    @Test
    void organizationCorrelationFilterCannotPullUnrelatedOrForeignAuditRows() {
        Organization mine = newOrganization();
        Organization foreign = newOrganization();
        String rawCorrelationId = "client-correlation-123";
        String correlationHmac = "pseudonymized-client-correlation";
        int mineCurrentId = insertOrgAudit(
            mine.getId(), "mine-current", correlationHmac);
        int mineLegacyId = insertOrgAudit(
            mine.getId(), "mine-legacy", rawCorrelationId);
        insertOrgAudit(
            mine.getId(), "same-org-other-correlation", "different-correlation-456");
        insertOrgAudit(foreign.getId(), "foreign", correlationHmac);
        Instant now = Instant.now();

        List<AuditSupportRowDto> rows = auditLogMapper.findOrgSupportSlice(
            mine.getId(),
            now.minus(1, ChronoUnit.DAYS),
            now.plus(1, ChronoUnit.DAYS),
            correlationHmac,
            rawCorrelationId,
            10);

        assertEquals(
            List.of((long) mineCurrentId, (long) mineLegacyId),
            rows.stream().map(AuditSupportRowDto::auditId).toList());
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws-" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Organization newOrganization() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Organization " + suffix);
        organization.setSlug("organization-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(int orgId) {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setOrgId(orgId);
        ws.setSlug("workspace-" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private int insertAudit(Integer workspaceId, String entityType, Integer entityId, String summary) {
        return insertAudit(workspaceId, entityType, entityId, summary, null);
    }

    private int insertAudit(
            Integer workspaceId,
            String entityType,
            Integer entityId,
            String summary,
            String untrustedClientAssertedCorrelationHmac) {
        AuditLog entry = new AuditLog();
        entry.setWorkspaceId(workspaceId);
        entry.setAction("company.update");
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setOutcome("success");
        entry.setSummary(summary);
        entry.setUntrustedClientAssertedCorrelationHmac(
            untrustedClientAssertedCorrelationHmac);
        entry.setOrgId(workspaceMapper.getOrgId(workspaceId));
        entry.setChainScopeType("workspace");
        entry.setChainScopeId(workspaceId);
        entry.setChainIndex(nextChainIndex);
        entry.setPrevHash(hash(nextChainIndex - 1));
        entry.setRowHash(hash(nextChainIndex));
        nextChainIndex++;
        auditLogMapper.insert(entry);
        return entry.getId();
    }

    private int insertAuditWithChain(Integer workspaceId, long chainIndex, String summary) {
        AuditLog entry = new AuditLog();
        entry.setWorkspaceId(workspaceId);
        entry.setOrgId(workspaceMapper.getOrgId(workspaceId));
        entry.setAction("company.update");
        entry.setEntityType("company");
        entry.setEntityId(1);
        entry.setOutcome("success");
        entry.setSummary(summary);
        entry.setChainScopeType("workspace");
        entry.setChainScopeId(workspaceId);
        entry.setChainIndex(chainIndex);
        entry.setPrevHash(hash(chainIndex - 1));
        entry.setRowHash(hash(chainIndex));
        auditLogMapper.insert(entry);
        return entry.getId();
    }

    private int insertOrgAudit(int orgId, String summary, String correlationValue) {
        AuditLog entry = new AuditLog();
        entry.setOrgId(orgId);
        entry.setAction("organization.update");
        entry.setEntityType("organization");
        entry.setEntityId(orgId);
        entry.setOutcome("success");
        entry.setSummary(summary);
        entry.setUntrustedClientAssertedCorrelationHmac(correlationValue);
        entry.setChainScopeType("organization");
        entry.setChainScopeId(orgId);
        entry.setChainIndex(nextChainIndex);
        entry.setPrevHash(hash(nextChainIndex - 1));
        entry.setRowHash(hash(nextChainIndex));
        nextChainIndex++;
        auditLogMapper.insert(entry);
        return entry.getId();
    }

    private static String hash(long index) {
        return String.format("%064d", index);
    }

    private static List<Integer> idsOf(List<AuditLog> entries) {
        return entries.stream().map(AuditLog::getId).toList();
    }
}
