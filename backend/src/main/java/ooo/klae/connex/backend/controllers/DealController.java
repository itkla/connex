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
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.CloseDealRequest;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldValuesRequest;
import ooo.klae.connex.backend.dto.DealCollaboratorsDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.DealOwnerDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.services.DealService;

import java.util.List;

import jakarta.validation.Valid;
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
    public List<DealDto> getDeals(
        @RequestParam(required = false) Integer pipelineId,
        @RequestParam(required = false) Integer stageId,
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer tagId
    ) {
        List<Deal> deals;
        if (stageId != null)         deals = dealService.getDealsByStageId(stageId);
        else if (pipelineId != null) deals = dealService.getDealsByPipelineId(pipelineId);
        else if (companyId != null)  deals = dealService.getDealsByCompanyId(companyId);
        else if (personId != null)   deals = dealService.getDealsByPersonId(personId);
        else if (tagId != null)      deals = dealService.getDealsByTagId(tagId);
        else                         deals = dealService.getAllDeals();
        return deals.stream().map(DealDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single deal by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public DealDto getDealById(@PathVariable int id) {
        return DealDto.from(dealService.getDealById(id));
    }

    /**
     * POST endpoint to create a new deal.
     * @param deal
     * @return
     */
    @PostMapping
    public DealDto createDeal(@Valid @RequestBody DealDto dto) {
        return DealDto.from(dealService.create(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing deal.
     * @param id
     * @param deal
     * @return
     */
    @PutMapping("/{id}")
    public DealDto updateDeal(@PathVariable int id, @Valid @RequestBody DealDto dto) {
        return DealDto.from(dealService.update(id, dto.toBean()));
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
     * POST endpoint to close a deal.
     * @param id
     * @param req
     * @return
     */
    @PostMapping("/{id}/close")
    public DealDto closeDeal(@PathVariable int id, @Valid @RequestBody(required = false) CloseDealRequest req) {
        Boolean won = req != null ? req.getWon() : null;
        String reason = req != null ? req.getReason() : null;
        Double actualValue = req != null ? req.getActualValue() : null;
        return DealDto.from(dealService.close(id, won, reason, actualValue));
    }

    /**
     * POST endpoint to reopen a closed deal.
     * @param id
     * @return
     */
    @PostMapping("/{id}/reopen")
    public DealDto reopenDeal(@PathVariable int id) {
        return DealDto.from(dealService.reopen(id));
    }

    /**
     * GET endpoint to retrieve tags associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tags")
    public List<TagDto> getTagsForDeal(@PathVariable int id) {
        return dealService.getTagsByDealId(id).stream().map(TagDto::from).toList();
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
    public List<DealPerson> getPeopleForDeal(@PathVariable int id) {
        return dealService.getPeopleByDealId(id);
    }

    /**
     * POST endpoint to add a person to a deal.
     * @param id
     * @param personId
     * @param role
     */
    @PostMapping("/{id}/people/{personId}")
    public void addPersonToDeal(@PathVariable int id, @PathVariable int personId, @RequestParam(required = false) String role) {
        dealService.addPerson(id, personId, role);
    }

    /**
     * PUT endpoint to update the role of a person on a deal.
     * @param id
     * @param personId
     * @param role
     */
    @PutMapping("/{id}/people/{personId}")
    public void updatePersonRoleOnDeal(@PathVariable int id, @PathVariable int personId, @RequestParam(required = false) String role) {
        dealService.updatePersonRole(id, personId, role);
    }

    /**
     * DELETE endpoint to remove a person from a deal.
     * @param id
     * @param personId
     */
    @DeleteMapping("/{id}/people/{personId}")
    public void removePersonFromDeal(@PathVariable int id, @PathVariable int personId) {
        dealService.removePerson(id, personId);
    }

    /**
     * PUT endpoint to replace the tags associated with a deal.
     * @param id
     * @param tagIds
     * @return
     */
    @PutMapping("/{id}/tags")
    public List<TagDto> replaceTagsForDeal(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return dealService.replaceTags(id, tagIds).stream().map(TagDto::from).toList();
    }

    /**
     * PUT endpoint to replace the people associated with a deal.
     * @param id
     * @param personIds
     * @return List of people
     */
    @PutMapping("/{id}/people")
    public List<DealPerson> replacePeopleForDeal(@PathVariable int id, @RequestBody List<DealPerson> people) {
        return dealService.replacePeople(id, people);
    }

    /**
     * GET endpoint to retrieve activities associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/activities")
    public List<ActivityDto> getActivitiesForDeal(@PathVariable int id) {
        return dealService.getActivitiesByDealId(id).stream().map(ActivityDto::from).toList();
    }

    /**
     * GET endpoint to retrieve notes associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/notes")
    public List<NoteDto> getNotesForDeal(@PathVariable int id) {
        return dealService.getNotesByDealId(id).stream().map(NoteDto::from).toList();
    }

    /**
     * GET endpoint to retrieve tasks associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tasks")
    public List<TaskDto> getTasksForDeal(@PathVariable int id) {
        return dealService.getTasksByDealId(id).stream().map(TaskDto::from).toList();
    }

    @PutMapping("/{id}/owner")
    public DealDto updateOwner(@PathVariable int id, @Valid @RequestBody DealOwnerDto dto) {
        return DealDto.from(dealService.updateOwner(id, dto.getOwnerId()));
    }

    @GetMapping("/{id}/collaborators")
    public List<UserDto> getCollaborators(@PathVariable int id) {
        return dealService.getCollaborators(id).stream().map(UserDto::from).toList();
    }

    @PutMapping("/{id}/collaborators")
    public List<UserDto> replaceCollaborators(
        @PathVariable int id,
        @Valid @RequestBody DealCollaboratorsDto dto
    ) {
        return dealService.replaceCollaborators(id, dto.getUserIds()).stream().map(UserDto::from).toList();
    }

    /**
     * GET retrieves the custom-field values for a deal.
     */
    @GetMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> getCustomFieldsForDeal(@PathVariable int id) {
        return dealService.getCustomFields(id);
    }

    /**
     * PUT replaces the custom-field values for a deal.
     */
    @PutMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> updateCustomFieldsForDeal(@PathVariable int id,
            @RequestBody CustomFieldValuesRequest request) {
        return dealService.updateCustomFields(id, request.getValues());
    }
}
