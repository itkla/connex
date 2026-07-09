package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.AppiIncident;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AppiIncidentScopeDto;

class AppiIncidentMapperTest extends AbstractMapperTest {
    @Autowired private AppiIncidentMapper appiIncidentMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private OrganizationMapper organizationMapper;

    private long nextChainIndex = 1;

    @Test
    void findByOrgStaysOrgScoped() {
        Organization mine = newOrg();
        Organization other = newOrg();
        AppiIncident mineIncident = newIncident(mine.getId(), "Mine");
        AppiIncident otherIncident = newIncident(other.getId(), "Other");

        assertEquals(mineIncident.getId(), appiIncidentMapper.findById(mine.getId(), mineIncident.getId()).getId());
        assertNull(appiIncidentMapper.findById(mine.getId(), otherIncident.getId()));
        assertEquals(List.of(mineIncident.getId()), appiIncidentMapper.findByOrg(mine.getId(), 50, 0).stream()
            .map(AppiIncident::getId)
            .toList());
    }

    @Test
    void scopeFromAuditAggregatesOnlyRequestedOrg() {
        Organization mine = newOrg();
        Organization other = newOrg();
        Workspace mineWorkspace = newWorkspace(mine.getId());
        Workspace otherWorkspace = newWorkspace(other.getId());
        insertAudit(mine.getId(), mineWorkspace.getId(), "person", "person.read", "success");
        insertAudit(mine.getId(), mineWorkspace.getId(), "person", "person.read", "success");
        insertAudit(mine.getId(), null, "organization", "org.member.set", "success");
        insertAudit(other.getId(), otherWorkspace.getId(), "person", "person.read", "success");

        List<AppiIncidentScopeDto> rows = appiIncidentMapper.scopeFromAudit(mine.getId(),
            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), 50);

        assertEquals(2, rows.size());
        assertEquals(3, rows.stream().mapToLong(AppiIncidentScopeDto::getEventCount).sum());
        assertTrue(rows.stream().noneMatch(row -> Integer.valueOf(otherWorkspace.getId()).equals(row.getWorkspaceId())));
        assertTrue(rows.stream().anyMatch(row -> row.getWorkspaceId() == null
            && "organization".equals(row.getEntityType())
            && row.getEventCount() == 1));
        assertTrue(rows.stream().anyMatch(row -> Integer.valueOf(mineWorkspace.getId()).equals(row.getWorkspaceId())
            && "person".equals(row.getEntityType())
            && row.getEventCount() == 2));
    }

    @Test
    void scopeFromAuditDoesNotAttachForeignWorkspaceName() {
        Organization mine = newOrg();
        Organization other = newOrg();
        Workspace otherWorkspace = newWorkspace(other.getId());
        insertAudit(mine.getId(), otherWorkspace.getId(), "person", "person.read", "success");

        List<AppiIncidentScopeDto> rows = appiIncidentMapper.scopeFromAudit(mine.getId(),
            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), 50);

        assertEquals(1, rows.size());
        assertNull(rows.getFirst().getWorkspaceId());
        assertNull(rows.getFirst().getWorkspaceName());
        assertEquals(1, rows.getFirst().getEventCount());
    }

    @Test
    void updateRequiresMatchingOrg() {
        Organization mine = newOrg();
        Organization other = newOrg();
        AppiIncident incident = newIncident(mine.getId(), "Before");
        incident.setOrgId(other.getId());
        incident.setTitle("After");

        assertEquals(0, appiIncidentMapper.update(incident));
        assertNotEquals("After", appiIncidentMapper.findById(mine.getId(), incident.getId()).getTitle());
    }

    private Organization newOrg() {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        return org;
    }

    private Workspace newWorkspace(int orgId) {
        Workspace ws = new Workspace();
        ws.setOrgId(orgId);
        ws.setName("WS " + unique());
        ws.setSlug("ws-" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private AppiIncident newIncident(int orgId, String title) {
        AppiIncident incident = new AppiIncident();
        incident.setOrgId(orgId);
        incident.setTitle(title + " " + unique());
        incident.setStatus("triage");
        incident.setSeverity("undetermined");
        incident.setDetectedAt(LocalDateTime.now());
        appiIncidentMapper.insert(incident);
        return incident;
    }

    private void insertAudit(int orgId, Integer workspaceId, String entityType, String action, String outcome) {
        AuditLog entry = new AuditLog();
        entry.setOrgId(orgId);
        entry.setWorkspaceId(workspaceId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(1);
        entry.setOutcome(outcome);
        entry.setSummary("summary");
        entry.setChainScopeType(workspaceId == null ? "organization" : "workspace");
        entry.setChainScopeId(workspaceId == null ? orgId : workspaceId);
        entry.setChainIndex(nextChainIndex);
        entry.setPrevHash(hash(nextChainIndex - 1));
        entry.setRowHash(hash(nextChainIndex));
        nextChainIndex++;
        auditLogMapper.insert(entry);
    }

    private static String hash(long index) {
        return String.format("%064d", index);
    }
}
