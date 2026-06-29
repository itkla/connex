package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;

class TagMapperTest extends AbstractMapperTest {

    /**
     * Inserts a new tag and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Tag tag = newTag();
        assertNotEquals(0, tag.getId());
    }

    /**
     * Gets a tag by ID and checks if the returned tag is not null.
     */
    @Test
    void getTagById_returnsInsertedRow() {
        Tag tag = newTag();

        Tag found = tagMapper.getTagById(workspace.getId(), tag.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals(tag.getName(), found.getName());
        assertEquals("#abcdef", found.getColor());
    }

    /**
     * Gets a tag by name and checks if the returned tag is not null.
     */
    @Test
    void getTagByName_returnsRow() {
        Tag tag = newTag();

        Tag found = tagMapper.getTagByName(workspace.getId(), tag.getName());

        assertNotNull(found);
        assertEquals(tag.getId(), found.getId());
    }

    /**
     * Gets all tags and checks if the returned list includes the inserted tag.
     */
    @Test
    void getAllTags_includesInsertedRow() {
        Tag tag = newTag();

        List<Tag> all = tagMapper.getAllTags(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == tag.getId()));
    }

    /**
     * Updates a tag and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Tag tag = newTag();
        tag.setName("renamed_" + unique());
        tag.setColor("#123456");

        tagMapper.update(tag);

        Tag found = tagMapper.getTagById(workspace.getId(), tag.getId());
        assertEquals(tag.getName(), found.getName());
        assertEquals("#123456", found.getColor());
    }

    /**
     * Deletes a tag and checks if the tag is removed.
     */
    @Test
    void delete_removesRow() {
        Tag tag = newTag();

        tagMapper.delete(workspace.getId(), tag.getId());

        assertNull(tagMapper.getTagById(workspace.getId(), tag.getId()));
    }

    /**
     * Gets tags by person ID and checks if the returned list includes the inserted tag.
     */
    @Test
    void getTagsByPersonId_returnsTagsLinkedToPerson() {
        Tag tag = newTag();
        Company company = newCompany();
        Person p = newPerson(company);
        personMapper.addTag(p.getId(), tag.getId());

        List<Tag> tags = tagMapper.getTagsByPersonId(workspace.getId(), p.getId());
        assertTrue(tags.stream().anyMatch(x -> x.getId() == tag.getId()));

        Workspace other = newWorkspace();
        assertTrue(tagMapper.getTagsByPersonId(other.getId(), p.getId()).isEmpty(),
            "tags linked in another workspace must not hydrate here");
    }

    /**
     * Gets tags by company ID and checks if the returned list includes the inserted tag.
     */
    @Test
    void getTagsByCompanyId_returnsTagsLinkedToCompany() {
        Tag tag = newTag();
        Company company = newCompany();
        companyMapper.addTag(company.getId(), tag.getId());

        List<Tag> tags = tagMapper.getTagsByCompanyId(workspace.getId(), company.getId());
        assertTrue(tags.stream().anyMatch(x -> x.getId() == tag.getId()));

        Workspace other = newWorkspace();
        assertTrue(tagMapper.getTagsByCompanyId(other.getId(), company.getId()).isEmpty(),
            "tags linked in another workspace must not hydrate here");
    }

    /**
     * Gets tags by deal ID and checks if the returned list includes the inserted tag.
     */
    @Test
    void getTagsByDealId_returnsTagsLinkedToDeal() {
        Tag tag = newTag();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.addTag(workspace.getId(), deal.getId(), tag.getId());

        List<Tag> tags = tagMapper.getTagsByDealId(workspace.getId(), deal.getId());
        assertTrue(tags.stream().anyMatch(x -> x.getId() == tag.getId()));

        Workspace other = newWorkspace();
        assertTrue(tagMapper.getTagsByDealId(other.getId(), deal.getId()).isEmpty(),
            "tags linked in another workspace must not hydrate here");
    }

    /**
     * A tag in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void tags_areIsolatedByWorkspace() {
        Tag mine = newTag();
        Workspace other = newWorkspace();
        Tag foreign = newTagIn(other);

        assertNull(tagMapper.getTagById(workspace.getId(), foreign.getId()));
        assertFalse(tagMapper.exists(workspace.getId(), foreign.getId()));
        assertTrue(tagMapper.getAllTags(workspace.getId()).stream().noneMatch(t -> t.getId() == foreign.getId()));
        assertTrue(tagMapper.getAllTags(workspace.getId()).stream().anyMatch(t -> t.getId() == mine.getId()));

        assertEquals(0, tagMapper.delete(workspace.getId(), foreign.getId()));
        assertTrue(tagMapper.exists(other.getId(), foreign.getId()));
    }

    /**
     * The same tag name can exist in two workspaces (per-tenant uniqueness replaced the global one).
     */
    @Test
    void sameTagName_allowedInDifferentWorkspaces() {
        Tag mine = newTag();
        Workspace other = newWorkspace();

        Tag clone = new Tag();
        clone.setName(mine.getName());
        clone.setColor("#000000");
        clone.setWorkspaceId(other.getId());
        tagMapper.insert(clone);

        assertNotEquals(0, clone.getId());
        assertNotEquals(mine.getId(), clone.getId());
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Tag newTagIn(Workspace ws) {
        Tag tag = new Tag();
        tag.setName("tag_" + unique());
        tag.setColor("#abcdef");
        tag.setWorkspaceId(ws.getId());
        tagMapper.insert(tag);
        return tag;
    }
}
