package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredImage;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Person} (contact) operations.
 * Every read/write is scoped to the caller's active workspace; cross-workspace
 * ids resolve to "not found" (404). Delegates persistence to {@code PersonMapper}.
 */

@Service
@RequiredArgsConstructor
public class PersonService {
    private final PersonMapper personMapper;
    private final ShareMapper shareMapper;
    private final AiOutputCacheMapper aiOutputCacheMapper;
    private final CompanyMapper companyMapper;
    private final TagMapper tagMapper;
    private final DealMapper dealMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final AuthService authService;
    private final AuditService auditService;
    private final NotificationChangePublisher notificationChanges;
    private final WorkspaceService workspaceService;
    private final EmploymentService employmentService;
    private final CustomFieldValueService customFieldValueService;
    private final ReferenceService referenceService;
    private final RuleTriggerPublisher ruleTriggers;
    private final ManagedObjectService managedObjectService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("name", "email", "phone", "title", "imageUrl");

    private static final Set<String> EVALUATION_AUDIT_FIELDS = Set.of("riskExcluded", "introExcluded");

    private static final Set<String> RESTRICTION_AUDIT_FIELDS =
        Set.of("suspendedAt", "provisionCeasedAt");

    private static final int MAX_MATCHING_IDS = 1000;

    /**
     * Retrieves all {@code Person} records in the active workspace.
     */
    public List<Person> getAllPersons() {
        return personMapper.getAllPersons(workspaceService.getCurrentWorkspaceId());
    }

    public List<Person> getPersonsByCompanyId(int companyId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return personMapper.getPersonsByCompanyId(workspaceId, companyId, null);
    }

    public List<Person> getPersonsByTagId(int tagId) {
        return personMapper.getPersonsByTagId(workspaceService.getCurrentWorkspaceId(), tagId);
    }

    public List<Person> getPersonsByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return personMapper.getPersonsByDealId(workspaceId, dealId);
    }

    public List<Person> getPersonsPage(String query, String sort, String dir, List<String> companies,
            List<String> titles, boolean noCompany, MemberScope memberScope, int limit, int offset) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return personMapper.getPersonsPage(workspaceId, query, sort, dir,
            companies, titles, noCompany, memberScope, limit, offset);
    }

    public long countPersons(String query, List<String> companies, List<String> titles, boolean noCompany,
            MemberScope memberScope) {
        return personMapper.countPersons(
            workspaceService.getCurrentWorkspaceId(), query, companies, titles, noCompany, memberScope);
    }

    /**
     * Ids of every contact in the active workspace matching the given filter predicates — the same
     * predicates as {@code getPersonsPage}, but unpaginated. Backs "select all matching filter" so a
     * bulk action can target the whole filtered set, not just the loaded page.
     */
    public List<Integer> getMatchingPersonIds(String query, List<String> companies, List<String> titles,
            boolean noCompany) {
        if (!hasMatchingIdFilter(query, companies, titles, noCompany)) {
            throw new BadRequestException("At least one filter is required before selecting matching contact ids");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long total = personMapper.countPersons(
            workspaceId, query, companies, titles, noCompany, MemberScope.allTeam());
        if (total > MAX_MATCHING_IDS) {
            throw new BadRequestException("Too many matching contacts; narrow the filters before selecting all");
        }
        return personMapper.getPersonIdsFiltered(workspaceId, query, companies, titles, noCompany, MAX_MATCHING_IDS);
    }

    private static boolean hasMatchingIdFilter(String query, List<String> companies, List<String> titles,
            boolean noCompany) {
        return query != null
            || (companies != null && !companies.isEmpty())
            || (titles != null && !titles.isEmpty())
            || noCompany;
    }

    public List<String> distinctCompanies() {
        return personMapper.distinctCompanies(workspaceService.getCurrentWorkspaceId());
    }

    public List<String> distinctTitles() {
        return personMapper.distinctTitles(workspaceService.getCurrentWorkspaceId());
    }

    public boolean hasPersonWithoutCompany() {
        return personMapper.hasPersonWithoutCompany(workspaceService.getCurrentWorkspaceId());
    }

    public List<FacetCount> countsByOwner() {
        return personMapper.countsByOwner(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Retrieves a workspace-scoped {@code Person} by ID, throwing if absent.
     */
    public Person getPersonById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = personMapper.getPersonById(workspaceId, id);
        if (person == null) throw new ResourceNotFoundException("Person not found with id: " + id);
        Person hydrated = hydrateScopedRelationships(person, workspaceId);
        referenceService.hydrateTasks(workspaceId, List.of(hydrated.getTasks()));
        if (hydrated.getActivities() != null && hydrated.getActivities().length > 0) {
            referenceService.hydrateActivities(workspaceId, List.of(hydrated.getActivities()));
        }
        hydrated.setNotes(
            referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByPersonId(workspaceId, id, workspaceService.getCurrentUserId())).toArray(new Note[0]));
        return hydrated;
    }

    /**
     * Creates a new {@code Person} in the active workspace. The ID is auto-generated. When the
     * contact is created with a company, an opening employment-history row is recorded.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_CREATE)
    public Person create(Person person) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        validateCompanyVisible(workspaceId, person);
        person.setWorkspaceId(workspaceId);
        person.setOwnerId(authService.getCurrentUser().getId());
        person.setImageUrl(null);
        personMapper.insert(person);
        employmentService.recordInitial(workspaceId, person.getId(), companyIdOf(person), person.getTitle());
        auditService.record("person.create", "person", person.getId(), person.getName(),
            "Created person " + person.getName(),
            auditService.diff(null, person, AUDIT_FIELDS));
        ruleTriggers.publish(workspaceId, "person", person.getId(), "person.created");
        return person;
    }

    /**
     * Updates an existing {@code Person} in the active workspace. When the contact's company changes,
     * the employment history is updated: the current stint is closed and (if they moved to a new
     * company) a new current stint is opened.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person update(int id, Person person) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person before = requireOwnedPerson(workspaceId, id);
        validateCompanyVisible(workspaceId, person);
        person.setId(id);
        person.setWorkspaceId(workspaceId);
        person.setOwnerId(before.getOwnerId());
        person.setImageUrl(before.getImageUrl());
        personMapper.update(person);
        if (!Objects.equals(companyIdOf(before), companyIdOf(person))) {
            employmentService.recordTransition(workspaceId, id, companyIdOf(person), person.getTitle());
        }
        Person after = requireOwnedPerson(workspaceId, id);
        auditService.record("person.update", "person", id, after.getName(),
            "Updated person " + after.getName(),
            auditService.diff(before, after, AUDIT_FIELDS));
        ruleTriggers.publish(workspaceId, "person", id, "person.updated");
        return after;
    }

    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person updateOwner(int personId, Integer ownerId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person before = requireOwnedPerson(workspaceId, personId);
        if (ownerId != null) workspaceService.requireMember(workspaceId, ownerId);
        personMapper.updateOwner(workspaceId, personId, ownerId);
        auditService.record("person.updateOwner", "person", personId, before.getName(),
            "Updated owner on " + before.getName(),
            auditService.singleChange("ownerId", before.getOwnerId(), ownerId));
        notificationChanges.publish(workspaceId, "person", personId);
        if (!Objects.equals(before.getOwnerId(), ownerId)) {
            ruleTriggers.publish(workspaceId, "person", personId, "person.owner_changed");
        }
        return requireOwnedPerson(workspaceId, personId);
    }

    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person updateProfilePicture(int id, UploadSource source) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person before = requireOwnedPerson(workspaceId, id);
        StoredImage stored = managedObjectService.storePersonImage(workspaceId, id, source);
        int updated = personMapper.updateImageUrlIfCurrent(
            workspaceId, id, before.getImageUrl(), stored.url());
        if (updated != 1) {
            throw new ConflictException("Contact picture changed while the image was uploading; retry");
        }
        managedObjectService.deletePersonImageAfterCommit(
            before.getWorkspaceId(), id, before.getImageUrl());
        Person after = requireOwnedPerson(workspaceId, id);
        auditService.record("person.updateAvatar", "person", id, before.getName(),
            "Updated profile picture for " + before.getName(),
            auditService.singleChange("imageUrl", before.getImageUrl(), after.getImageUrl()));
        ruleTriggers.publish(workspaceId, "person", id, "person.updated");
        return after;
    }

    public ManagedContent getProfilePictureContent(int id, String token) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = requirePerson(workspaceId, id);
        return managedObjectService.openPersonImage(
            person.getWorkspaceId(), id, person.getImageUrl(), token);
    }

    /**
     * Sets the contact's engine-evaluation opt-outs (issue #358). {@code riskExcluded} removes the
     * contact from relationship-decay nudges and from contributing a stakeholder-cold factor to
     * deal risk; {@code introExcluded} removes them from introduction suggestions and
     * intro-opportunity nudges. A {@code null} flag is left unchanged. Warmth display and plain
     * date reminders are unaffected, and existing engine notifications about the contact resolve
     * on the next scheduled sweep.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person updateEvaluationExclusions(int id, Boolean riskExcluded, Boolean introExcluded) {
        if (riskExcluded == null && introExcluded == null) {
            throw new BadRequestException("At least one evaluation flag must be provided");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person before = requireOwnedPerson(workspaceId, id);
        personMapper.updateEvaluationExclusions(workspaceId, id, riskExcluded, introExcluded);
        Person after = requireOwnedPerson(workspaceId, id);
        auditService.record("person.updateEvaluation", "person", id, before.getName(),
            "Updated engine evaluation opt-outs for " + before.getName(),
            auditService.diff(before, after, EVALUATION_AUDIT_FIELDS));
        return after;
    }

    /**
     * Sets or clears the contact's processing and third-party-provision restrictions. Ceasing
     * third-party provision also revokes every standing cross-workspace share of the contact —
     * the restriction must stop provision already in flight, not just new grants.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public Person updateProcessingRestrictions(int id, boolean suspended, boolean provisionCeased) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person before = requireOwnedPerson(workspaceId, id);
        personMapper.updateProcessingRestrictions(workspaceId, id, suspended, provisionCeased);
        int revokedShares = provisionCeased ? shareMapper.revokePersonShares(id, workspaceId) : 0;
        int purgedAiOutputs = suspended || provisionCeased
            ? aiOutputCacheMapper.deleteForPerson(workspaceId, id)
            : 0;
        Person after = requireOwnedPerson(workspaceId, id);
        Map<String, Object> diff = auditService.diff(before, after, RESTRICTION_AUDIT_FIELDS);
        if (diff != null || revokedShares > 0 || purgedAiOutputs > 0) {
            Map<String, Object> changes = new LinkedHashMap<>();
            if (diff != null) {
                changes.putAll(diff);
            }
            if (revokedShares > 0) {
                changes.put("revokedShares", revokedShares);
            }
            if (purgedAiOutputs > 0) {
                changes.put("purgedAiOutputs", purgedAiOutputs);
            }
            auditService.record("person.restrictions", "person", id, before.getName(),
                "Updated processing restrictions for " + before.getName(), changes);
        }
        return after;
    }

    /** Resolved company id for a person, treating an absent or zero-id company as {@code null}. */
    private static Integer companyIdOf(Person person) {
        return (person.getCompany() == null || person.getCompany().getId() == 0)
            ? null : person.getCompany().getId();
    }

    private void validateCompanyVisible(int workspaceId, Person person) {
        Integer companyId = companyIdOf(person);
        if (companyId != null && !companyMapper.exists(workspaceId, companyId)) {
            throw new BadRequestException("Company not found with id: " + companyId);
        }
    }

    /**
     * Employment history for a workspace-scoped contact, current stint first. Throws if the contact
     * is not visible to the active workspace.
     */
    public List<PersonEmployment> getEmploymentHistory(int id) {
        requirePerson(workspaceService.getCurrentWorkspaceId(), id);
        return employmentService.getHistory(id);
    }

    /**
     * Deletes a {@code Person} in the active workspace.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (personMapper.lockById(workspaceId, id) == null) {
            throw new ResourceNotFoundException("Person not found with id: " + id);
        }
        Person before = requireOwnedPerson(workspaceId, id);
        managedObjectService.deletePersonImageAfterCommit(
            before.getWorkspaceId(), id, before.getImageUrl());
        customFieldValueService.deleteByEntity("person", id);
        personMapper.delete(workspaceId, id);
        auditService.record("person.delete", "person", id, before.getName(),
            "Deleted person " + before.getName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /**
     * Retrieves the tags associated with a person in the active workspace.
     */
    public List<Tag> getTagsByPersonId(int personId) {
        requirePerson(workspaceService.getCurrentWorkspaceId(), personId);
        return tagMapper.getTagsByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    /**
     * Adds a tag to a person in the active workspace.
     */
    @RequirePermission(Permission.PERSON_UPDATE)
    public void addTag(int personId, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = requireOwnedPerson(workspaceId, personId);
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        personMapper.addTag(workspaceId, personId, tagId);
        auditService.record("person.addTag", "person", personId, person.getName(),
            "Tagged " + person.getName() + " with " + tag.getName(),
            auditService.singleChange("tag", null, tag.getName()));
    }

    /**
     * Removes a tag from a person in the active workspace.
     */
    @RequirePermission(Permission.PERSON_UPDATE)
    public void removeTag(int personId, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = requireOwnedPerson(workspaceId, personId);
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        personMapper.removeTag(workspaceId, personId, tagId);
        String tagName = tag != null ? tag.getName() : "#" + tagId;
        auditService.record("person.removeTag", "person", personId, person.getName(),
            "Removed tag " + tagName + " from " + person.getName(),
            auditService.singleChange("tag", tagName, null));
    }

    /**
     * Replaces the tags associated with a person in the active workspace.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public List<Tag> replaceTags(int personId, List<Integer> tagIds) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = requireOwnedPerson(workspaceId, personId);
        List<String> before = tagMapper.getTagsByPersonId(workspaceId, personId).stream().map(Tag::getName).toList();
        personMapper.clearTags(workspaceId, personId);
        if (tagIds != null && !tagIds.isEmpty()) personMapper.insertTags(workspaceId, personId, tagIds);
        List<Tag> after = tagMapper.getTagsByPersonId(workspaceId, personId);
        auditService.record("person.replaceTags", "person", personId, person.getName(),
            "Updated tags on " + person.getName(),
            auditService.singleChange("tags", before, after.stream().map(Tag::getName).toList()));
        return after;
    }

    /**
     * Retrieves the deals associated with a person in the active workspace.
     */
    public List<Deal> getDealsByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return referenceService.hydrateDeals(
            workspaceId, dealMapper.getDealsByPersonId(workspaceId, personId));
    }

    /**
     * Retrieves the activities associated with a person in the active workspace.
     */
    public List<Activity> getActivitiesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesByPersonId(workspaceId, personId));
    }

    /**
     * Retrieves the notes associated with a person in the active workspace.
     */
    public List<Note> getNotesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return referenceService.hydrate(workspaceId, noteMapper.getVisibleNotesByPersonId(workspaceId, personId, workspaceService.getCurrentUserId()));
    }

    /**
     * Retrieves the tasks associated with a person in the active workspace.
     */
    public List<Task> getTasksByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return referenceService.hydrateTasks(workspaceId, taskMapper.getTasksByPersonId(workspaceId, personId));
    }

    /**
     * Custom-field values for a contact. Readable by any member who can see the contact.
     */
    public List<CustomFieldEntryDto> getCustomFields(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return customFieldValueService.getForEntity("person", personId);
    }

    /**
     * Replaces a contact's custom-field values.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public List<CustomFieldEntryDto> updateCustomFields(int personId, Map<Integer, Object> values) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return customFieldValueService.applyValues("person", personId, values);
    }

    /**
     * Sets or clears a single custom-field value on a contact.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public List<CustomFieldEntryDto> updateCustomField(int personId, int definitionId, Object value) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePerson(workspaceId, personId);
        return customFieldValueService.applyValue("person", personId, definitionId, value);
    }

    /**
     * Filled custom-field values for many contacts, keyed by contact id then definition id.
     */
    public Map<Integer, Map<Integer, Object>> getCustomFieldValues(List<Integer> personIds) {
        return customFieldValueService.getForEntities("person", personIds);
    }

    private Person requirePerson(int workspaceId, int personId) {
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null) throw new ResourceNotFoundException("Person not found with id: " + personId);
        return person;
    }

    private Person requireOwnedPerson(int workspaceId, int personId) {
        if (!personMapper.existsOwned(workspaceId, personId)) {
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
        return requirePerson(workspaceId, personId);
    }

    private Person hydrateScopedRelationships(Person person, int workspaceId) {
        person.setDeals(referenceService.hydrateDeals(
            workspaceId, dealMapper.getDealsByPersonId(workspaceId, person.getId())).toArray(Deal[]::new));
        person.setTags(tagMapper.getTagsByPersonId(workspaceId, person.getId()).toArray(Tag[]::new));
        person.setActivities(activityMapper.getActivitiesByPersonId(workspaceId, person.getId()).toArray(Activity[]::new));
        person.setTasks(taskMapper.getTasksByPersonId(workspaceId, person.getId()).toArray(Task[]::new));
        return person;
    }
}
