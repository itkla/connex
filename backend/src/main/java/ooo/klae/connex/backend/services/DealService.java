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
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

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
    private final AuditService auditService;

    private static final DateTimeFormatter MYSQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> AUDIT_FIELDS =
        Set.of("name", "value", "actualValue", "currency", "pipelineId", "stageId",
               "companyId", "expectedCloseDate", "closedAt");

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
        auditService.record("deal.create", "deal", deal.getId(), deal.getName(),
            "Created deal " + deal.getName(),
            auditService.diff(null, deal, AUDIT_FIELDS));
        return deal;
    }

    /**
     * Updates an existing {@code Deal} record.
     * @param id
     * @param deal
     * @return
     */
    public Deal update(int id, Deal deal) {
        Deal before = dealMapper.getDealById(id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        deal.setId(id);
        reconcileClosedAt(deal);
        dealMapper.update(deal);
        auditService.record("deal.update", "deal", id, deal.getName(),
            "Updated deal " + deal.getName(),
            auditService.diff(before, deal, AUDIT_FIELDS));
        return deal;
    }

    /**
     * Deletes a {@code Deal} record by ID, throwing a {@code ResourceNotFoundException} if not found.
     * @param id
     */
    public void delete(int id) {
        Deal before = dealMapper.getDealById(id);
        if (before == null) throw new ResourceNotFoundException("Deal not found with id: " + id);
        dealMapper.delete(id);
        auditService.record("deal.delete", "deal", id, before.getName(),
            "Deleted deal " + before.getName(),
            auditService.diff(before, null, AUDIT_FIELDS));
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
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Tag tag = tagMapper.getTagById(tagId);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        dealMapper.addTag(dealId, tagId);
        auditService.record("deal.addTag", "deal", dealId, deal.getName(),
            "Tagged " + deal.getName() + " with " + tag.getName(),
            auditService.singleChange("tag", null, tag.getName()));
    }

    /**
     * Removes a tag from a deal.
     * @param dealId
     * @param tagId
     */
    public void removeTag(int dealId, int tagId) {
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Tag tag = tagMapper.getTagById(tagId);
        dealMapper.removeTag(dealId, tagId);
        String tagName = tag != null ? tag.getName() : "#" + tagId;
        auditService.record("deal.removeTag", "deal", dealId, deal.getName(),
            "Removed tag " + tagName + " from " + deal.getName(),
            auditService.singleChange("tag", tagName, null));
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
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Person person = personMapper.getPersonById(personId);
        if (person == null) throw new ResourceNotFoundException("Person not found with id: " + personId);
        dealMapper.addPerson(dealId, personId, role);
        String label = contactLabel(person.getName(), role);
        auditService.record("deal.addPerson", "deal", dealId, deal.getName(),
            "Linked " + label + " to " + deal.getName(),
            auditService.singleChange("contact", null, label));
    }

    /**
     * Updates the role of a person in a deal.
     * @param dealId
     * @param personId
     * @param role
     */
    public void updatePersonRole(int dealId, int personId, String role) {
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        DealPerson existing = dealMapper.getDealPeopleByDealId(dealId).stream()
            .filter(dp -> dp.getPerson() != null && dp.getPerson().getId() == personId)
            .findFirst().orElse(null);
        if (dealMapper.updatePersonRole(dealId, personId, role) == 0)
            throw new ResourceNotFoundException("Person " + personId + " is not associated with deal " + dealId);
        String name = existing != null && existing.getPerson() != null ? existing.getPerson().getName() : "#" + personId;
        String oldRole = existing != null ? existing.getRole() : null;
        auditService.record("deal.updatePersonRole", "deal", dealId, deal.getName(),
            "Changed " + name + "'s role on " + deal.getName(),
            auditService.singleChange("role", oldRole, role));
    }

    /**
     * Removes a person from a deal.
     * @param dealId
     * @param personId
     */
    public void removePerson(int dealId, int personId) {
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        Person person = personMapper.getPersonById(personId);
        dealMapper.removePerson(dealId, personId);
        String name = person != null ? person.getName() : "#" + personId;
        auditService.record("deal.removePerson", "deal", dealId, deal.getName(),
            "Unlinked " + name + " from " + deal.getName(),
            auditService.singleChange("contact", name, null));
    }

    /**
     * Replaces the tags associated with a deal.
     * @param dealId
     * @param tagIds
     * @return
     */
    @Transactional
    public List<Tag> replaceTags(int dealId, List<Integer> tagIds) {
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        List<String> before = tagMapper.getTagsByDealId(dealId).stream().map(Tag::getName).toList();
        dealMapper.clearTags(dealId);
        if (tagIds != null && !tagIds.isEmpty()) dealMapper.insertTags(dealId, tagIds);
        List<Tag> after = tagMapper.getTagsByDealId(dealId);
        auditService.record("deal.replaceTags", "deal", dealId, deal.getName(),
            "Updated tags on " + deal.getName(),
            auditService.singleChange("tags", before, after.stream().map(Tag::getName).toList()));
        return after;
    }

    /**
     * Replaces the people associated with a deal.
     * @param dealId
     * @param people
     * @return
     */
    @Transactional
    public List<DealPerson> replacePeople(int dealId, List<DealPerson> people) {
        Deal deal = dealMapper.getDealById(dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        List<String> before = dealMapper.getDealPeopleByDealId(dealId).stream().map(DealService::personLabel).toList();
        dealMapper.clearPeople(dealId);
        if (people != null && !people.isEmpty()) dealMapper.insertPeople(dealId, people);
        List<DealPerson> after = dealMapper.getDealPeopleByDealId(dealId);
        auditService.record("deal.replacePeople", "deal", dealId, deal.getName(),
            "Updated contacts on " + deal.getName(),
            auditService.singleChange("contacts", before, after.stream().map(DealService::personLabel).toList()));
        return after;
    }

    /**
     * Renders a deal-person association as "Name (Role)" (or just the name when no role is set) for audit summaries and change payloads.
     * @param name
     * @param role
     * @return
     */
    private static String contactLabel(String name, String role) {
        String safeName = name == null ? "?" : name;
        return (role == null || role.isBlank()) ? safeName : safeName + " (" + role + ")";
    }

    private static String personLabel(DealPerson dp) {
        String name = dp.getPerson() != null ? dp.getPerson().getName() : "?";
        return contactLabel(name, dp.getRole());
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
