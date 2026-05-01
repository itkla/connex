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

class DealMapperTest extends AbstractMapperTest {

    /**
     * Inserts a new deal and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();

        Deal deal = newDeal(pipeline, stage, company);

        // System.out.println("Deal ID: " + deal.getId());
        
        assertNotEquals(0, deal.getId());
    }

    /**
     * Gets a deal by ID and checks if the returned deal is not null.
     */
    @Test
    void getDealById_returnsInsertedRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);

        Deal found = dealMapper.getDealById(deal.getId());

        assertNotNull(found);
        assertEquals(deal.getName(), found.getName());
        assertEquals(1000.0, found.getValue());
        assertEquals("JPY", found.getCurrency());
        assertEquals(pipeline.getId(), found.getPipeline().getId());
        assertEquals(stage.getId(), found.getStage().getId());
        assertEquals(company.getId(), found.getCompany().getId());
    }

    /**
     * Gets a deal by ID and checks if the returned deal is null when the ID is negative.
     */
    @Test
    void getDealById_returnsNullWhenMissing() {
        assertNull(dealMapper.getDealById(-1));
    }

    /**
     * Gets all deals and checks if the returned list includes the inserted deal.
     */
    @Test
    void getAllDeals_includesInsertedRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        List<Deal> all = dealMapper.getAllDeals();

        assertTrue(all.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Updates a deal and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Pipeline pipeline = newPipeline();
        Stage stage1 = newStage(pipeline, 0);
        Stage stage2 = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage1, newCompany());

        deal.setName("Renamed Deal");
        deal.setValue(2500.50);
        deal.setCurrency("JPY");
        deal.setStage(stage2);
        deal.setCompany(null);

        dealMapper.update(deal);

        Deal found = dealMapper.getDealById(deal.getId());
        assertEquals("Renamed Deal", found.getName());
        assertEquals(2500.50, found.getValue());
        assertEquals("JPY", found.getCurrency());
        assertEquals(stage2.getId(), found.getStage().getId());
        assertTrue(found.getCompany() == null || found.getCompany().getId() == 0);
    }

    /**
     * Deletes a deal and checks if the deal is removed.
     */
    @Test
    void delete_removesRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        dealMapper.delete(deal.getId());

        assertNull(dealMapper.getDealById(deal.getId()));
    }

    /**
     * Gets deals by pipeline ID and checks if the returned list includes the inserted deal.
     */
    @Test
    void getDealsByPipelineId_filtersByPipeline() {
        Pipeline pipelineA = newPipeline();
        Pipeline pipelineB = newPipeline();
        Stage stageA = newStage(pipelineA, 0);
        Stage stageB = newStage(pipelineB, 0);
        Deal dealA = newDeal(pipelineA, stageA, newCompany());
        Deal dealB = newDeal(pipelineB, stageB, newCompany());

        List<Deal> matched = dealMapper.getDealsByPipelineId(pipelineA.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == dealA.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == dealB.getId()));
    }

    /**
     * Gets deals by stage ID and checks if the returned list includes the inserted deal.
     */
    @Test
    void getDealsByStageId_filtersByStage() {
        Pipeline pipeline = newPipeline();
        Stage stage1 = newStage(pipeline, 0);
        Stage stage2 = newStage(pipeline, 1);
        Deal deal1 = newDeal(pipeline, stage1, newCompany());
        Deal deal2 = newDeal(pipeline, stage2, newCompany());

        List<Deal> matched = dealMapper.getDealsByStageId(stage1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == deal2.getId()));
    }

    /**
     * Gets deals by company ID and checks if the returned list includes the inserted deal.
     */
    @Test
    void getDealsByCompanyId_filtersByCompany() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company1 = newCompany();
        Company company2 = newCompany();
        Deal deal1 = newDeal(pipeline, stage, company1);
        Deal deal2 = newDeal(pipeline, stage, company2);

        List<Deal> matched = dealMapper.getDealsByCompanyId(company1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == deal2.getId()));
    }

    /**
     * Adds a person to a deal and checks if the returned list includes the inserted deal.
     */
    @Test
    void addPerson_thenGetDealsByPersonId_returnsDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        Person person = newPerson(company);

        dealMapper.addPerson(deal.getId(), person.getId());

        List<Deal> matched = dealMapper.getDealsByPersonId(person.getId());
        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Adds a person to a deal and checks if the person is added only once.
     */
    @Test
    void addPerson_isIdempotent() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Person person = newPerson(newCompany());

        dealMapper.addPerson(deal.getId(), person.getId());
        dealMapper.addPerson(deal.getId(), person.getId());

        long matching = dealMapper.getDealsByPersonId(person.getId()).stream()
                .filter(x -> x.getId() == deal.getId()).count();
        assertEquals(1, matching);
    }

    /**
     * Removes a person from a deal and checks if the person is removed.
     */
    @Test
    void removePerson_dropsAssociation() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Person person = newPerson(newCompany());
        dealMapper.addPerson(deal.getId(), person.getId());

        dealMapper.removePerson(deal.getId(), person.getId());

        assertTrue(dealMapper.getDealsByPersonId(person.getId()).stream()
                .noneMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Adds a tag to a deal and checks if the returned list includes the inserted deal.
     */
    @Test
    void addTag_thenGetDealsByTagId_returnsDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Tag tag = newTag();

        dealMapper.addTag(deal.getId(), tag.getId());

        List<Deal> matched = dealMapper.getDealsByTagId(tag.getId());
        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Removes a tag from a deal and checks if the tag is removed.
     */
    @Test
    void removeTag_dropsAssociation() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Tag tag = newTag();
        dealMapper.addTag(deal.getId(), tag.getId());

        dealMapper.removeTag(deal.getId(), tag.getId());

        assertTrue(dealMapper.getDealsByTagId(tag.getId()).stream()
                .noneMatch(x -> x.getId() == deal.getId()));
    }
}
