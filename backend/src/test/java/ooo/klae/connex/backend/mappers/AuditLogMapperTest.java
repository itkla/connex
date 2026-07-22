package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Workspace;

class AuditLogMapperTest extends AbstractMapperTest {
    @Autowired private AuditLogMapper auditLogMapper;

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

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws-" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private int insertAudit(Integer workspaceId, String entityType, Integer entityId, String summary) {
        AuditLog entry = new AuditLog();
        entry.setWorkspaceId(workspaceId);
        entry.setAction("company.update");
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setOutcome("success");
        entry.setSummary(summary);
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

    private static String hash(long index) {
        return String.format("%064d", index);
    }

    private static List<Integer> idsOf(List<AuditLog> entries) {
        return entries.stream().map(AuditLog::getId).toList();
    }
}
