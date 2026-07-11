package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Workspace;

class AiOutputCacheMapperTest extends AbstractMapperTest {

    @Autowired AiOutputCacheMapper aiOutputCacheMapper;

    @Test
    void upsert_assignsGeneratedId() {
        AiOutputCache row = save(workspace, "deal.brief", 29, 0, "hash-1", "{\"sections\":[]}", 0);
        assertNotEquals(0, row.getId());
    }

    @Test
    void getBySubject_returnsStoredRow() {
        save(workspace, "deal.brief", 29, 0, "hash-1", "{\"sections\":[{\"title\":\"A\",\"body\":\"B\"}]}", 2);

        AiOutputCache found = aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0);

        assertNotNull(found);
        assertEquals("hash-1", found.getContentHash());
        assertEquals(2, found.getWarnings());
        assertEquals("2026-07-09T18:30:00Z", found.getGeneratedAt());
        assertTrue(found.getPayload().contains("\"title\""));
    }

    @Test
    void getBySubject_nullWhenAbsent() {
        assertNull(aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 999, 0));
    }

    @Test
    void upsert_replacesRowOnSameSubjectKey() {
        save(workspace, "deal.brief", 29, 0, "hash-old", "{\"v\":\"a\"}", 0);
        int firstId = aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0).getId();

        save(workspace, "deal.brief", 29, 0, "hash-new", "{\"v\":\"b\"}", 1);
        AiOutputCache after = aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0);

        assertEquals(firstId, after.getId());
        assertEquals("hash-new", after.getContentHash());
        assertEquals(1, after.getWarnings());
        assertTrue(after.getPayload().contains("\"b\""));
    }

    @Test
    void secondSubjectDistinguishesRows() {
        save(workspace, "intro.rationale", 29, 0, "hash-a", "{\"rationale\":\"a\"}", 0);
        save(workspace, "intro.rationale", 29, 41, "hash-b", "{\"rationale\":\"b\"}", 0);

        assertTrue(aiOutputCacheMapper.getBySubject(workspace.getId(), "intro.rationale", 29, 0)
                .getPayload().contains("\"a\""));
        assertTrue(aiOutputCacheMapper.getBySubject(workspace.getId(), "intro.rationale", 29, 41)
                .getPayload().contains("\"b\""));
    }

    @Test
    void outputs_areIsolatedByWorkspace() {
        Workspace other = newWorkspace();
        save(workspace, "deal.brief", 29, 0, "hash-here", "{\"where\":\"here\"}", 0);
        save(other, "deal.brief", 29, 0, "hash-there", "{\"where\":\"there\"}", 0);

        assertTrue(aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0)
                .getPayload().contains("here"));
        assertTrue(aiOutputCacheMapper.getBySubject(other.getId(), "deal.brief", 29, 0)
                .getPayload().contains("there"));
    }

    private AiOutputCache save(
            Workspace ws, String feature, int subjectAId, int subjectBId, String hash, String payload, int warnings) {
        AiOutputCache row = new AiOutputCache();
        row.setWorkspaceId(ws.getId());
        row.setFeature(feature);
        row.setSubjectAId(subjectAId);
        row.setSubjectBId(subjectBId);
        row.setContentHash(hash);
        row.setPayload(payload);
        row.setWarnings(warnings);
        row.setGeneratedAt("2026-07-09T18:30:00Z");
        aiOutputCacheMapper.upsert(row);
        return row;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
