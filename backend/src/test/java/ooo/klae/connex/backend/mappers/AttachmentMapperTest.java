package ooo.klae.connex.backend.mappers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.FacetCount;

class AttachmentMapperTest extends AbstractMapperTest {

    @Autowired AttachmentMapper attachmentMapper;
    @Autowired NoteMapper noteMapper;

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
        assertNull(found.getUploadedBy().getDisplayName());
    }

    @Test
    void hydrationSensitiveReadsKeepLocalLabelsAndLeaveUserLabelsUnresolved() {
        User user = newUser();
        Company company = newCompany();
        Attachment companyAttachment = build(
            workspace.getId(), "company.pdf", "company", company.getId(), user);
        Attachment userAttachment = build(
            workspace.getId(), "user.pdf", "user", user.getId(), user);
        attachmentMapper.insert(companyAttachment);
        attachmentMapper.insert(userAttachment);

        Attachment companyResult = attachmentMapper.getById(
            workspace.getId(), companyAttachment.getId());
        Attachment userResult = attachmentMapper.getById(
            workspace.getId(), userAttachment.getId());

        assertNotNull(companyResult);
        assertEquals(company.getName(), companyResult.getEntityLabel());
        assertNull(companyResult.getUploadedBy().getDisplayName());
        assertNotNull(userResult);
        assertNull(userResult.getEntityLabel());
        assertNull(userResult.getUploadedBy().getDisplayName());
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
        User user = newUser();
        Attachment a = build(workspace.getId(), "listed.pdf", "deal", 1, user);
        attachmentMapper.insert(a);

        List<Attachment> all = attachmentMapper.getAll(workspace.getId(), user.getId());

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
        assertTrue(attachmentMapper.getAll(workspace.getId(), user.getId()).stream()
            .noneMatch(a -> a.getId() == foreign.getId()));
        assertTrue(attachmentMapper.getByEntity(workspace.getId(), "deal", 1).stream().noneMatch(a -> a.getId() == foreign.getId()));

        // cross-workspace delete affects zero rows; the foreign row survives in its own workspace
        assertEquals(0, attachmentMapper.delete(workspace.getId(), foreign.getId()));
        assertNotNull(attachmentMapper.getById(other.getId(), foreign.getId()));

        // facet counts are per-workspace
        long otherTotal = attachmentMapper.totalCount(other.getId(), user.getId());
        assertEquals(1, otherTotal);
    }

    @Test
    void aggregateReadsFilterPrivateAndDanglingNoteAttachments() {
        Workspace visibilityWorkspace = newWorkspace();
        User author = newUser();
        User reader = newUser();
        workspaceMapper.addMember(visibilityWorkspace.getId(), author.getId(), "member");
        workspaceMapper.addMember(visibilityWorkspace.getId(), reader.getId(), "member");
        Note privateNote = newNote(visibilityWorkspace.getId(), author, "private");
        Note workspaceNote = newNote(visibilityWorkspace.getId(), author, "workspace");
        Company company = newCompany(visibilityWorkspace.getId());
        Tag privateTag = newTag(visibilityWorkspace.getId());
        Tag visibleTag = newTag(visibilityWorkspace.getId());
        Tag danglingTag = newTag(visibilityWorkspace.getId());
        Attachment privateAttachment = build(
            visibilityWorkspace.getId(), "private-secret.zip", "note", privateNote.getId(), author);
        privateAttachment.setContentType("application/zip");
        privateAttachment.setSize(101L);
        Attachment workspaceAttachment = build(
            visibilityWorkspace.getId(), "workspace-visible.pdf", "note", workspaceNote.getId(), author);
        workspaceAttachment.setSize(20L);
        Attachment danglingAttachment = build(
            visibilityWorkspace.getId(), "dangling.txt", "note", -1, author);
        danglingAttachment.setContentType("text/plain");
        danglingAttachment.setSize(1_000L);
        Attachment companyAttachment = build(
            visibilityWorkspace.getId(), "company.png", "company", company.getId(), author);
        companyAttachment.setContentType("image/png");
        companyAttachment.setSize(30L);
        attachmentMapper.insert(privateAttachment);
        attachmentMapper.insert(workspaceAttachment);
        attachmentMapper.insert(danglingAttachment);
        attachmentMapper.insert(companyAttachment);
        attachmentMapper.addTag(
            visibilityWorkspace.getId(), privateAttachment.getId(), privateTag.getId());
        attachmentMapper.addTag(
            visibilityWorkspace.getId(), workspaceAttachment.getId(), visibleTag.getId());
        attachmentMapper.addTag(
            visibilityWorkspace.getId(), companyAttachment.getId(), visibleTag.getId());
        attachmentMapper.addTag(
            visibilityWorkspace.getId(), danglingAttachment.getId(), danglingTag.getId());
        List<Integer> authorIds = sortedIds(
            privateAttachment, workspaceAttachment, companyAttachment);
        List<Integer> readerIds = sortedIds(workspaceAttachment, companyAttachment);

        assertEquals(authorIds, sortedIds(
            attachmentMapper.getAll(visibilityWorkspace.getId(), author.getId())));
        assertEquals(readerIds, sortedIds(
            attachmentMapper.getAll(visibilityWorkspace.getId(), reader.getId())));
        assertEquals(authorIds, sortedIds(attachmentMapper.getPage(
            visibilityWorkspace.getId(), author.getId(), null, null,
            null, null, null, null, 100, 0)));
        assertEquals(readerIds, sortedIds(attachmentMapper.getPage(
            visibilityWorkspace.getId(), reader.getId(), null, null,
            null, null, null, null, 100, 0)));
        assertEquals(3, attachmentMapper.countPage(
            visibilityWorkspace.getId(), author.getId(), null, null,
            null, null, null));
        assertEquals(2, attachmentMapper.countPage(
            visibilityWorkspace.getId(), reader.getId(), null, null,
            null, null, null));
        assertEquals(List.of(privateAttachment.getId()), sortedIds(attachmentMapper.getPage(
            visibilityWorkspace.getId(), author.getId(), "%private-secret%", null,
            null, null, null, null, 100, 0)));
        assertEquals(List.of(), sortedIds(attachmentMapper.getPage(
            visibilityWorkspace.getId(), reader.getId(), "%private-secret%", null,
            null, null, null, null, 100, 0)));
        assertEquals(1, attachmentMapper.countPage(
            visibilityWorkspace.getId(), author.getId(), "%private-secret%",
            null, null, null, null));
        assertEquals(0, attachmentMapper.countPage(
            visibilityWorkspace.getId(), reader.getId(), "%private-secret%",
            null, null, null, null));
        assertEquals(sortedIds(privateAttachment, workspaceAttachment),
            sortedIds(attachmentMapper.getPage(
                visibilityWorkspace.getId(), author.getId(), null, null,
                null, null, null, true, 100, 0)));
        assertEquals(List.of(workspaceAttachment.getId()),
            sortedIds(attachmentMapper.getPage(
                visibilityWorkspace.getId(), reader.getId(), null, null,
                null, null, null, true, 100, 0)));
        assertEquals(2, attachmentMapper.countPage(
            visibilityWorkspace.getId(), author.getId(), null,
            null, null, null, true));
        assertEquals(1, attachmentMapper.countPage(
            visibilityWorkspace.getId(), reader.getId(), null,
            null, null, null, true));
        assertEquals(Map.of("note", 2L, "company", 1L), facetCounts(
            attachmentMapper.countsBySource(visibilityWorkspace.getId(), author.getId())));
        assertEquals(Map.of("note", 1L, "company", 1L), facetCounts(
            attachmentMapper.countsBySource(visibilityWorkspace.getId(), reader.getId())));
        assertEquals(Map.of("archive", 1L, "pdf", 1L, "image", 1L), facetCounts(
            attachmentMapper.countsByKind(visibilityWorkspace.getId(), author.getId())));
        assertEquals(Map.of("pdf", 1L, "image", 1L), facetCounts(
            attachmentMapper.countsByKind(visibilityWorkspace.getId(), reader.getId())));
        assertEquals(Map.of(
            String.valueOf(privateTag.getId()), 1L,
            String.valueOf(visibleTag.getId()), 2L), facetCounts(
                attachmentMapper.countsByTag(visibilityWorkspace.getId(), author.getId())));
        assertEquals(Map.of(String.valueOf(visibleTag.getId()), 2L), facetCounts(
            attachmentMapper.countsByTag(visibilityWorkspace.getId(), reader.getId())));
        assertEquals(2, attachmentMapper.countOrphaned(
            visibilityWorkspace.getId(), author.getId()));
        assertEquals(1, attachmentMapper.countOrphaned(
            visibilityWorkspace.getId(), reader.getId()));
        assertEquals(3, attachmentMapper.totalCount(
            visibilityWorkspace.getId(), author.getId()));
        assertEquals(2, attachmentMapper.totalCount(
            visibilityWorkspace.getId(), reader.getId()));
        assertEquals(151, attachmentMapper.totalSize(
            visibilityWorkspace.getId(), author.getId()));
        assertEquals(50, attachmentMapper.totalSize(
            visibilityWorkspace.getId(), reader.getId()));
        assertEquals(List.of(privateAttachment.getId()), sortedIds(attachmentMapper.search(
            visibilityWorkspace.getId(), "%private-secret%", author.getId())));
        assertEquals(List.of(), sortedIds(attachmentMapper.search(
            visibilityWorkspace.getId(), "%private-secret%", reader.getId())));
    }

    /**
     * A tag write issued with another workspace's id must not associate the tag.
     */
    @Test
    void addTag_fromAnotherWorkspace_doesNotAssociate() {
        Attachment a = build(workspace.getId(), "tagged.pdf", "deal", 1, newUser());
        attachmentMapper.insert(a);
        Tag tag = newTag();
        Workspace other = newWorkspace();

        int affected = attachmentMapper.addTag(other.getId(), a.getId(), tag.getId());

        assertEquals(0, affected, "cross-workspace addTag must affect no rows");
        assertTrue(tagMapper.getTagsByAttachmentId(workspace.getId(), a.getId()).isEmpty());
    }

    @Test
    void getByUrlPrefersTheVisibleRowWhenOneUrlIsSharedByTwoAttachments() {
        for (boolean privateFirst : List.of(true, false)) {
            Workspace sharedWorkspace = newWorkspace();
            User author = newUser();
            User reader = newUser();
            workspaceMapper.addMember(sharedWorkspace.getId(), author.getId(), "member");
            workspaceMapper.addMember(sharedWorkspace.getId(), reader.getId(), "member");
            Note privateNote = newNote(sharedWorkspace.getId(), author, "private");
            Company company = newCompany(sharedWorkspace.getId());
            String sharedUrl = "https://files.example.com/shared-" + unique();
            Attachment privateAttachment = build(
                sharedWorkspace.getId(), "private.zip", "note", privateNote.getId(), author);
            privateAttachment.setUrl(sharedUrl);
            Attachment visibleAttachment = build(
                sharedWorkspace.getId(), "visible.pdf", "company", company.getId(), author);
            visibleAttachment.setUrl(sharedUrl);

            if (privateFirst) {
                attachmentMapper.insert(privateAttachment);
                attachmentMapper.insert(visibleAttachment);
            } else {
                attachmentMapper.insert(visibleAttachment);
                attachmentMapper.insert(privateAttachment);
            }

            Attachment forReader = attachmentMapper.getByUrl(
                sharedWorkspace.getId(), sharedUrl, reader.getId());
            assertNotNull(forReader, "a reader must still resolve the visible row");
            assertEquals(visibleAttachment.getId(), forReader.getId(),
                "the invisible row must never shadow the visible one");

            Attachment forAuthor = attachmentMapper.getByUrl(
                sharedWorkspace.getId(), sharedUrl, author.getId());
            assertNotNull(forAuthor);
            assertTrue(
                forAuthor.getId() == privateAttachment.getId()
                    || forAuthor.getId() == visibleAttachment.getId());
        }
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Note newNote(int workspaceId, User author, String visibility) {
        Note note = new Note();
        note.setWorkspaceId(workspaceId);
        note.setContent("Attachment visibility " + unique());
        note.setVisibility(visibility);
        note.setAuthor(author);
        noteMapper.insert(note);
        return note;
    }

    private Company newCompany(int workspaceId) {
        Company company = new Company();
        company.setWorkspaceId(workspaceId);
        company.setName("Attachment company " + unique());
        companyMapper.insert(company);
        return company;
    }

    private Tag newTag(int workspaceId) {
        Tag tag = new Tag();
        tag.setWorkspaceId(workspaceId);
        tag.setName("attachment_tag_" + unique());
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        return tag;
    }

    private static List<Integer> sortedIds(Attachment... attachments) {
        return List.of(attachments).stream().map(Attachment::getId).sorted().toList();
    }

    private static List<Integer> sortedIds(List<Attachment> attachments) {
        return attachments.stream().map(Attachment::getId).sorted().toList();
    }

    private static Map<String, Long> facetCounts(List<FacetCount> facets) {
        return facets.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }
}
