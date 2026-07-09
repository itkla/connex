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

        Deal found = dealMapper.getDealById(workspace.getId(), deal.getId());

        assertNotNull(found);
        assertEquals(deal.getName(), found.getName());
        assertEquals(1000.0, found.getValue());
        assertEquals("JPY", found.getCurrency());
        assertEquals(pipeline.getId(), found.getPipelineId());
        assertEquals(stage.getId(), found.getStageId());
        assertEquals(company.getId(), found.getCompanyId());
    }

    /**
     * Gets a deal by ID and checks if the returned deal is null when the ID is negative.
     */
    @Test
    void getDealById_returnsNullWhenMissing() {
        assertNull(dealMapper.getDealById(workspace.getId(), -1));
    }

    /**
     * Gets all deals and checks if the returned list includes the inserted deal.
     */
    @Test
    void getAllDeals_includesInsertedRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        List<Deal> all = dealMapper.getAllDeals(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    @Test
    void getDealsPageLimitsAndCountsWorkspaceRows() {
        Workspace pageWorkspace = newWorkspace();
        Pipeline pipeline = newPipelineIn(pageWorkspace);
        Stage stage = newStageIn(pageWorkspace, pipeline, 0);
        Deal first = newDealIn(pageWorkspace, pipeline, stage);
        Deal second = newDealIn(pageWorkspace, pipeline, stage);
        Deal third = newDealIn(pageWorkspace, pipeline, stage);
        Pipeline foreignPipeline = newPipeline();
        Stage foreignStage = newStage(foreignPipeline, 0);
        Deal foreign = newDeal(foreignPipeline, foreignStage, newCompany());

        List<Deal> page = dealMapper.getDealsPage(pageWorkspace.getId(), 2, 0);

        assertEquals(2, page.size());
        assertEquals(3, dealMapper.countDeals(pageWorkspace.getId()));
        assertTrue(page.stream().noneMatch(deal -> deal.getId() == foreign.getId()));
        assertTrue(page.stream().allMatch(deal -> List.of(first.getId(), second.getId(), third.getId()).contains(deal.getId())));
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
        deal.setStageId(stage2.getId());
        deal.setCompanyId(null);

        dealMapper.update(deal);

        Deal found = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals("Renamed Deal", found.getName());
        assertEquals(2500.50, found.getValue());
        assertEquals("JPY", found.getCurrency());
        assertEquals(stage2.getId(), found.getStageId());
        assertNull(found.getCompanyId());
    }

    /**
     * Deletes a deal and checks if the deal is removed.
     */
    @Test
    void delete_removesRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        dealMapper.delete(workspace.getId(), deal.getId());

        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()));
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

        List<Deal> matched = dealMapper.getDealsByPipelineId(workspace.getId(), pipelineA.getId());

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

        List<Deal> matched = dealMapper.getDealsByStageId(workspace.getId(), stage1.getId());

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

        List<Deal> matched = dealMapper.getDealsByCompanyId(workspace.getId(), company1.getId());

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

        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        List<Deal> matched = dealMapper.getDealsByPersonId(workspace.getId(), person.getId());
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

        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        long matching = dealMapper.getDealsByPersonId(workspace.getId(), person.getId()).stream()
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
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        dealMapper.removePerson(workspace.getId(), deal.getId(), person.getId());

        assertTrue(dealMapper.getDealsByPersonId(workspace.getId(), person.getId()).stream()
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

        dealMapper.addTag(workspace.getId(), deal.getId(), tag.getId());

        List<Deal> matched = dealMapper.getDealsByTagId(workspace.getId(), tag.getId());
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
        dealMapper.addTag(workspace.getId(), deal.getId(), tag.getId());

        dealMapper.removeTag(workspace.getId(), deal.getId(), tag.getId());

        assertTrue(dealMapper.getDealsByTagId(workspace.getId(), tag.getId()).stream()
                .noneMatch(x -> x.getId() == deal.getId()));
    }

    @Test
    void workspaceScopeHidesDealsAndBlocksMutations() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);

        assertNull(dealMapper.getDealById(other.getId(), deal.getId()));
        assertEquals(0, dealMapper.delete(other.getId(), deal.getId()));
        assertNotNull(dealMapper.getDealById(workspace.getId(), deal.getId()));
    }

    /**
     * The risk-evaluation opt-out toggle round-trips and is workspace-scoped.
     */
    @Test
    void updateRiskExcluded_togglesFlagAndIsWorkspaceScoped() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        assertFalse(dealMapper.getDealById(workspace.getId(), deal.getId()).isRiskExcluded());

        assertEquals(1, dealMapper.updateRiskExcluded(workspace.getId(), deal.getId(), true));
        assertTrue(dealMapper.getDealById(workspace.getId(), deal.getId()).isRiskExcluded());

        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        assertEquals(0, dealMapper.updateRiskExcluded(other.getId(), deal.getId(), false));
        assertTrue(dealMapper.getDealById(workspace.getId(), deal.getId()).isRiskExcluded());
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Pipeline newPipelineIn(Workspace ws) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(ws.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setWorkspaceId(ws.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal newDealIn(Workspace ws, Pipeline pipeline, Stage stage) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(ws.getId());
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        return deal;
    }
}
