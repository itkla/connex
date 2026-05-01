package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Tag found = tagMapper.getTagById(tag.getId());

        assertNotNull(found);
        assertEquals(tag.getName(), found.getName());
        assertEquals("#abcdef", found.getColor());
    }

    /**
     * Gets a tag by name and checks if the returned tag is not null.
     */
    @Test
    void getTagByName_returnsRow() {
        Tag tag = newTag();

        Tag found = tagMapper.getTagByName(tag.getName());

        assertNotNull(found);
        assertEquals(tag.getId(), found.getId());
    }

    /**
     * Gets all tags and checks if the returned list includes the inserted tag.
     */
    @Test
    void getAllTags_includesInsertedRow() {
        Tag tag = newTag();

        List<Tag> all = tagMapper.getAllTags();

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

        Tag found = tagMapper.getTagById(tag.getId());
        assertEquals(tag.getName(), found.getName());
        assertEquals("#123456", found.getColor());
    }

    /**
     * Deletes a tag and checks if the tag is removed.
     */
    @Test
    void delete_removesRow() {
        Tag tag = newTag();

        tagMapper.delete(tag.getId());

        assertNull(tagMapper.getTagById(tag.getId()));
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

        List<Tag> tags = tagMapper.getTagsByPersonId(p.getId());

        assertTrue(tags.stream().anyMatch(x -> x.getId() == tag.getId()));
    }

    /**
     * Gets tags by company ID and checks if the returned list includes the inserted tag.
     */
    @Test
    void getTagsByCompanyId_returnsTagsLinkedToCompany() {
        Tag tag = newTag();
        Company company = newCompany();
        companyMapper.addTag(company.getId(), tag.getId());

        List<Tag> tags = tagMapper.getTagsByCompanyId(company.getId());

        assertTrue(tags.stream().anyMatch(x -> x.getId() == tag.getId()));
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
        dealMapper.addTag(deal.getId(), tag.getId());

        List<Tag> tags = tagMapper.getTagsByDealId(deal.getId());

        assertTrue(tags.stream().anyMatch(x -> x.getId() == tag.getId()));
    }
}
