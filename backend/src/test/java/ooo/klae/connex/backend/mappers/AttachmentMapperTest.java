package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class AttachmentMapperTest extends AbstractMapperTest {

    @Autowired AttachmentMapper attachmentMapper;

    private Attachment build(int workspaceId, String fileName, String entityType, int entityId, User uploadedBy) {
        Attachment a = new Attachment();
        a.setWorkspaceId(workspaceId);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setFileName(fileName);
        a.setUrl("https://files.example.com/" + unique());
        a.setContentType("application/pdf");
        a.setSize(1234L);
        a.setUploadedBy(uploadedBy);
        return a;
    }

    /**
     * Inserts a new attachment and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Attachment a = build(workspace.getId(), "doc.pdf", "deal", 1, newUser());

        attachmentMapper.insert(a);

        assertNotEquals(0, a.getId());
    }

    /**
     * Gets an attachment by ID and checks the round-tripped fields.
     */
    @Test
    void getById_returnsInsertedRow() {
        User user = newUser();
        Attachment a = build(workspace.getId(), "report.pdf", "company", 7, user);
        attachmentMapper.insert(a);

        Attachment found = attachmentMapper.getById(workspace.getId(), a.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals("report.pdf", found.getFileName());
        assertEquals("company", found.getEntityType());
        assertEquals(7, found.getEntityId());
        assertEquals(user.getId(), found.getUploadedBy().getId());
    }

    /**
     * Gets an attachment by ID and checks if the returned attachment is null when the ID is missing.
     */
    @Test
    void getById_returnsNullWhenMissing() {
        assertNull(attachmentMapper.getById(workspace.getId(), -1));
    }

    /**
     * Gets attachments by entity and checks only matching rows are returned.
     */
    @Test
    void getByEntity_filtersByEntity() {
        User user = newUser();
        Attachment onDeal = build(workspace.getId(), "deal.pdf", "deal", 42, user);
        Attachment onCompany = build(workspace.getId(), "co.pdf", "company", 42, user);
        attachmentMapper.insert(onDeal);
        attachmentMapper.insert(onCompany);

        List<Attachment> matched = attachmentMapper.getByEntity(workspace.getId(), "deal", 42);

        assertTrue(matched.stream().anyMatch(x -> x.getId() == onDeal.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == onCompany.getId()));
    }

    /**
     * Gets all attachments and checks if the returned list includes the inserted attachment.
     */
    @Test
    void getAll_includesInsertedRow() {
        Attachment a = build(workspace.getId(), "listed.pdf", "deal", 1, newUser());
        attachmentMapper.insert(a);

        List<Attachment> all = attachmentMapper.getAll(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == a.getId()));
    }

    /**
     * An attachment in another workspace is invisible and immutable from this workspace,
     * and excluded from this workspace's facet counts.
     */
    @Test
    void attachments_areIsolatedByWorkspace() {
        User user = newUser();
        Attachment mine = build(workspace.getId(), "mine.pdf", "deal", 1, user);
        attachmentMapper.insert(mine);

        Workspace other = newWorkspace();
        Attachment foreign = build(other.getId(), "foreign.pdf", "deal", 1, user);
        attachmentMapper.insert(foreign);

        assertNull(attachmentMapper.getById(workspace.getId(), foreign.getId()));
        assertTrue(attachmentMapper.getAll(workspace.getId()).stream().noneMatch(a -> a.getId() == foreign.getId()));
        assertTrue(attachmentMapper.getByEntity(workspace.getId(), "deal", 1).stream().noneMatch(a -> a.getId() == foreign.getId()));

        // cross-workspace delete affects zero rows; the foreign row survives in its own workspace
        assertEquals(0, attachmentMapper.delete(workspace.getId(), foreign.getId()));
        assertNotNull(attachmentMapper.getById(other.getId(), foreign.getId()));

        // facet counts are per-workspace
        long otherTotal = attachmentMapper.totalCount(other.getId());
        assertEquals(1, otherTotal);
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
