package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.services.DealService;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Deal} CRUD operations and stage transitions.
 * Accepts and returns {@code DealDto}. Delegates to {@code DealService}.
 */

@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
public class DealController {
    private final DealService dealService;

    /**
     * GET endpoint to retrieve deals, with filtering by pipelineId, stageId, companyId, personId, or tagId.
     * @param pipelineId
     * @param stageId
     * @param companyId
     * @param personId
     * @param tagId
     * @return
     */
    @GetMapping
    public List<Deal> getDeals(
        @RequestParam(required = false) Integer pipelineId,
        @RequestParam(required = false) Integer stageId,
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer tagId
    ) {
        if (stageId != null)    return dealService.getDealsByStageId(stageId);
        if (pipelineId != null) return dealService.getDealsByPipelineId(pipelineId);
        if (companyId != null)  return dealService.getDealsByCompanyId(companyId);
        if (personId != null)   return dealService.getDealsByPersonId(personId);
        if (tagId != null)      return dealService.getDealsByTagId(tagId);
        return dealService.getAllDeals();
    }

    /**
     * GET endpoint to retrieve a single deal by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Deal getDealById(@PathVariable int id) {
        return dealService.getDealById(id);
    }

    /**
     * POST endpoint to create a new deal.
     * @param deal
     * @return
     */
    @PostMapping
    public Deal createDeal(@RequestBody Deal deal) {
        return dealService.create(deal);
    }

    /**
     * PUT endpoint to update an existing deal.
     * @param id
     * @param deal
     * @return
     */
    @PutMapping("/{id}")
    public Deal updateDeal(@PathVariable int id, @RequestBody Deal deal) {
        return dealService.update(id, deal);
    }

    /**
     * DELETE endpoint to delete a deal by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteDeal(@PathVariable int id) {
        dealService.delete(id);
    }

    /**
     * GET endpoint to retrieve tags associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tags")
    public List<Tag> getTagsForDeal(@PathVariable int id) {
        return dealService.getTagsByDealId(id);
    }

    /**
     * POST endpoint to add a tag to a deal.
     * @param id
     * @param tagId
     */
    @PostMapping("/{id}/tags/{tagId}")
    public void addTagToDeal(@PathVariable int id, @PathVariable int tagId) {
        dealService.addTag(id, tagId);
    }

    /**
     * DELETE endpoint to remove a tag from a deal.
     * @param id
     * @param tagId
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    public void removeTagFromDeal(@PathVariable int id, @PathVariable int tagId) {
        dealService.removeTag(id, tagId);
    }

    /**
     * GET endpoint to retrieve people associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/people")
    public List<Person> getPeopleForDeal(@PathVariable int id) {
        return dealService.getPeopleByDealId(id);
    }

    /**
     * POST endpoint to associate a person with a deal.
     * @param id
     * @param personId
     */
    @PostMapping("/{id}/people/{personId}")
    public void addPersonToDeal(@PathVariable int id, @PathVariable int personId) {
        dealService.addPerson(id, personId);
    }

    /**
     * DELETE endpoint to dissociate a person from a deal.
     * @param id
     * @param personId
     */
    @DeleteMapping("/{id}/people/{personId}")
    public void removePersonFromDeal(@PathVariable int id, @PathVariable int personId) {
        dealService.removePerson(id, personId);
    }
}
