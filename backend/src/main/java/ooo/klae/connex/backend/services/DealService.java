package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.Note;
// import ooo.klae.connex.backend.beans.Person; // Not used in this service anymore
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for logging and retrieving {@code Deal} records.
 * Handles mapping between {@code DealDto} and {@code Deal} bean.
 * Delegates persistence to {@code DealMapper}.
 */

@Service
@RequiredArgsConstructor
public class DealService {
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
    private final TagMapper tagMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;

    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Keeps {@code closedAt} in sync with the deal's stage: a deal sitting on a terminal
     * @param deal
     * @return null
     */
    private void reconcileClosedAt(Deal deal) {
        Integer stageId = deal.getStageId();
        boolean terminal = stageId != null && dealMapper.isStageTerminal(stageId);
        if (terminal) {
            String closedAt = deal.getClosedAt();
            if (closedAt == null || closedAt.isBlank()) {
                deal.setClosedAt(LocalDateTime.now().format(MYSQL_DATETIME));
            }
        } else {
            deal.setClosedAt(null);
        }
    }

    /**
     * Retrieves all {@code Deal} records.
     * @return
     */
    public List<Deal> getAllDeals() {
        return dealMapper.getAllDeals();
    }

    /**
     * Retrieves all {@code Deal} records by pipeline ID.
     * @param pipelineId
     * @return
     */
    public List<Deal> getDealsByPipelineId(int pipelineId) {
        return dealMapper.getDealsByPipelineId(pipelineId);
    }

    /**
     * Retrieves all {@code Deal} records by stage ID.
     * @param stageId
     * @return
     */
    public List<Deal> getDealsByStageId(int stageId) {
        return dealMapper.getDealsByStageId(stageId);
    }

    /**
     * Retrieves all {@code Deal} records by company ID.
     * @param companyId
     * @return
     */
    public List<Deal> getDealsByCompanyId(int companyId) {
        return dealMapper.getDealsByCompanyId(companyId);
    }

    /**
     * Retrieves all {@code Deal} records by person ID.
     * @param personId
     * @return
     */
    public List<Deal> getDealsByPersonId(int personId) {
        return dealMapper.getDealsByPersonId(personId);
    }

    /**
     * Retrieves all {@code Deal} records by tag ID.
     * @param tagId
     * @return
     */
    public List<Deal> getDealsByTagId(int tagId) {
        return dealMapper.getDealsByTagId(tagId);
    }

    /**
     * Retrieves a {@code Deal} by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     * @return
     */
    public Deal getDealById(int id) {
        Deal deal = dealMapper.getDealById(id);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        return deal;
    }

    /**
     * Creates a new {@code Deal} record.
     * @param deal
     * @return
     */
    public Deal create(Deal deal) {
        reconcileClosedAt(deal);
        dealMapper.insert(deal);
        return deal;
    }

    /**
     * Updates an existing {@code Deal} record.
     * @param id
     * @param deal
     * @return
     */
    public Deal update(int id, Deal deal) {
        if (dealMapper.getDealById(id) == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        deal.setId(id);
        reconcileClosedAt(deal);
        dealMapper.update(deal);
        return deal;
    }

    /**
     * Deletes a {@code Deal} record by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     */
    public void delete(int id) {
        if (dealMapper.getDealById(id) == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        dealMapper.delete(id);
    }

    /**
     * Retrieves the tags associated with a deal.
     * @param dealId
     * @return
     */
    public List<Tag> getTagsByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return tagMapper.getTagsByDealId(dealId);
    }

    /**
     * Adds a tag to a deal.
     * @param dealId
     * @param tagId
     */
    public void addTag(int dealId, int tagId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        if (tagMapper.getTagById(tagId) == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        dealMapper.addTag(dealId, tagId);
    }

    /**
     * Removes a tag from a deal.
     * @param dealId
     * @param tagId
     */
    public void removeTag(int dealId, int tagId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.removeTag(dealId, tagId);
    }

    /**
     * Retrieves the people associated with a deal.
     * @param dealId
     * @return
     */
    public List<DealPerson> getPeopleByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return dealMapper.getDealPeopleByDealId(dealId);
    }

    /**
     * Adds a person to a deal.
     * @param dealId
     * @param personId
     * @param role
     */
    public void addPerson(int dealId, int personId, String role) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        if (personMapper.getPersonById(personId) == null) throw new ResourceNotFoundException("Person not found with id: " + personId);
        dealMapper.addPerson(dealId, personId, role);
    }

    /**
     * Updates the role of a person in a deal.
     * @param dealId
     * @param personId
     * @param role
     */
    public void updatePersonRole(int dealId, int personId, String role) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        if (dealMapper.updatePersonRole(dealId, personId, role) == 0)
            throw new ResourceNotFoundException("Person " + personId + " is not associated with deal " + dealId);
    }

    /**
     * Removes a person from a deal.
     * @param dealId
     * @param personId
     */
    public void removePerson(int dealId, int personId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.removePerson(dealId, personId);
    }

    /**
     * Replaces the tags associated with a deal.
     * @param dealId
     * @param tagIds
     * @return
     */
    @Transactional
    public List<Tag> replaceTags(int dealId, List<Integer> tagIds) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.clearTags(dealId);
        if (tagIds != null && !tagIds.isEmpty()) dealMapper.insertTags(dealId, tagIds);
        return tagMapper.getTagsByDealId(dealId);
    }

    /**
     * Replaces the people associated with a deal.
     * @param dealId
     * @param people
     * @return
     */
    @Transactional
    public List<DealPerson> replacePeople(int dealId, List<DealPerson> people) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        dealMapper.clearPeople(dealId);
        if (people != null && !people.isEmpty()) dealMapper.insertPeople(dealId, people);
        return dealMapper.getDealPeopleByDealId(dealId);
    }

    /**
     * Retrieves the activities associated with a deal.
     * @param dealId
     * @return
     */
    public List<Activity> getActivitiesByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return activityMapper.getActivitiesByDealId(dealId);
    }

    /**
     * Retrieves the notes associated with a deal.
     * @param dealId
     * @return
     */
    public List<Note> getNotesByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return noteMapper.getNotesByDealId(dealId);
    }

    /**
     * Retrieves the tasks associated with a deal.
     * @param dealId
     * @return
     */
    public List<Task> getTasksByDealId(int dealId) {
        if (dealMapper.getDealById(dealId) == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return taskMapper.getTasksByDealId(dealId);
    }
}
