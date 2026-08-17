package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonFirstResponseState;
import ooo.klae.connex.backend.beans.PersonLeadSource;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.util.LikePattern;
import ooo.klae.connex.backend.util.PageBounds;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.dto.BulkDeleteRequest;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.BulkOwnerRequest;
import ooo.klae.connex.backend.dto.BulkTagRequest;
import ooo.klae.connex.backend.dto.ConnectionRequestDto;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldValueRequest;
import ooo.klae.connex.backend.dto.CustomFieldValuesRequest;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.IntroPathDto;
import ooo.klae.connex.backend.dto.JobMoveDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.dto.PersonEmploymentDto;
import ooo.klae.connex.backend.dto.PersonFacets;
import ooo.klae.connex.backend.dto.PersonDetailDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.PersonEvaluationDto;
import ooo.klae.connex.backend.dto.PersonLifecycleDto;
import ooo.klae.connex.backend.dto.PersonLifecycleHistoryDto;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.dto.PersonLifecycleWithdrawalRequest;
import ooo.klae.connex.backend.dto.PersonProvenanceRequest;
import ooo.klae.connex.backend.dto.PersonOwnerDto;
import ooo.klae.connex.backend.dto.PersonRestrictionsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.EmploymentService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.PersonLifecycleService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Person} (contact) CRUD operations.
 * Accepts and returns {@code PersonDto}. Delegates to {@code PersonService}.
 */

@RestController
@RequestMapping("/api/persons")
@RequiredArgsConstructor
@TenantJournalAttributable
public class PersonController {
    private final PersonService personService;
    private final PersonLifecycleService personLifecycleService;
    private final EmploymentService employmentService;
    private final ConnectionService connectionService;
    private final BulkOperationService bulkOperationService;
    private final WorkspaceService workspaceService;
    private final MemberScopeResolver memberScopeResolver;
    private static final String WARMTH_SORT = "warmth";

    /**
     * GET endpoint for the "recently moved" feed: contacts who recently changed companies.
     * @return
     */
    @GetMapping("/recent-moves")
    public List<JobMoveDto> getRecentMoves() {
        return employmentService.getRecentMoves();
    }

    /**
     * GET endpoint to retrieve people, with filtering by companyId, tagId, or dealId.
     * @param companyId
     * @param tagId
     * @param dealId
     * @return
     */
    @GetMapping
    public List<PersonDto> getPersons(
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) Integer tagId,
        @RequestParam(required = false) Integer dealId
    ) {
        List<Person> persons;
        if (companyId != null) persons = personService.getPersonsByCompanyId(companyId);
        else if (tagId != null) persons = personService.getPersonsByTagId(tagId);
        else if (dealId != null) persons = personService.getPersonsByDealId(dealId);
        else throw new BadRequestException("A filter is required; use /api/persons/page for workspace-wide lists");
        return persons.stream().map(PersonDto::from).toList();
    }

    /**
     * GET endpoint for a paginated, searchable, sortable slice of people.
     * Only the rows in scope are queried from the database.
     * @param page
     * @param size
     * @param q
     * @param sort
     * @param dir
     * @param companies
     * @param titles
     * @param noCompany
     * @return
     */
    @GetMapping("/page")
    public PageResponse<PersonDto> getPersonsPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String dir,
        @RequestParam(required = false) List<String> companies,
        @RequestParam(required = false) List<String> titles,
        @RequestParam(defaultValue = "false") boolean noCompany,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds,
        @RequestParam(required = false) List<PersonLifecycleStage> lifecycleStages,
        @RequestParam(defaultValue = "false") boolean noLifecycle,
        @RequestParam(required = false) List<PersonLeadSource> leadSources,
        @RequestParam(defaultValue = "false") boolean noLeadSource,
        @RequestParam(required = false) List<PersonFirstResponseState> firstResponseStates,
        @RequestParam(defaultValue = "false") boolean noFirstResponse,
        @RequestParam(defaultValue = "false") boolean archived
    ) {
        if (WARMTH_SORT.equalsIgnoreCase(sort)) {
            throw new BadRequestException("Warmth sorting requires a precomputed score index and is not available for paginated contacts");
        }
        PageBounds bounds = PageBounds.of(page, size);
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        List<PersonDto> items = personService.getPersonsPage(query, sort, dir, companies, titles, noCompany,
            memberScope, lifecycleStages, noLifecycle, leadSources, noLeadSource,
            firstResponseStates, noFirstResponse, archived,
            bounds.size(), bounds.offset())
            .stream().map(PersonDto::from).toList();
        return new PageResponse<>(items, personService.countPersons(
            query, companies, titles, noCompany, memberScope, lifecycleStages, noLifecycle,
            leadSources, noLeadSource, firstResponseStates, noFirstResponse, archived));
    }

    /**
     * GET endpoint returning the ids of every contact matching the given filter — the same filter
     * predicates as {@code /page} but unpaginated. Backs "select all matching filter" so a bulk
     * action can target the whole filtered set rather than just the loaded page.
     * @param q
     * @param companies
     * @param titles
     * @param noCompany
     * @return
     */
    @GetMapping("/ids")
    public List<Integer> getPersonIds(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) List<String> companies,
        @RequestParam(required = false) List<String> titles,
        @RequestParam(defaultValue = "false") boolean noCompany,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds,
        @RequestParam(required = false) List<PersonLifecycleStage> lifecycleStages,
        @RequestParam(defaultValue = "false") boolean noLifecycle,
        @RequestParam(required = false) List<PersonLeadSource> leadSources,
        @RequestParam(defaultValue = "false") boolean noLeadSource,
        @RequestParam(required = false) List<PersonFirstResponseState> firstResponseStates,
        @RequestParam(defaultValue = "false") boolean noFirstResponse,
        @RequestParam(defaultValue = "false") boolean archived
    ) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        if (!archived
            && query == null
            && (companies == null || companies.isEmpty())
            && (titles == null || titles.isEmpty())
            && !noCompany
            && (lifecycleStages == null || lifecycleStages.isEmpty())
            && !noLifecycle
            && (leadSources == null || leadSources.isEmpty())
            && !noLeadSource
            && (firstResponseStates == null || firstResponseStates.isEmpty())
            && !noFirstResponse
            && memberScope.mode() == MemberScope.Mode.ALL_TEAM) {
            throw new BadRequestException("At least one filter is required before selecting matching contact ids");
        }
        return personService.getMatchingPersonIds(
            query, companies, titles, noCompany, memberScope, lifecycleStages, noLifecycle,
            leadSources, noLeadSource, firstResponseStates, noFirstResponse, archived);
    }

    /**
     * GET endpoint for the distinct filter facets (companies, titles) used by the
     * records filter menu, computed across the whole table rather than one page.
     * @return
     */
    @GetMapping("/facets")
    public PersonFacets getPersonFacets() {
        return new PersonFacets(
            personService.distinctCompanies(),
            personService.distinctTitles(),
            personService.hasPersonWithoutCompany(),
            personService.countsByOwner(),
            personService.countArchivedPersons(),
            personService.countsByLifecycleStage(),
            personService.countsByLeadSource(),
            personService.countsByFirstResponseState()
        );
    }

    /**
     * GET endpoint to retrieve a single person by ID. Returns a {@link PersonDetailDto}
     * with fully hydrated tags, deals, notes, tasks, and activities so callers don't
     * need follow-up round-trips for the detail view.
     * @param id
     * @return
     */
    @GetMapping("/{id:\\d+}")
    public PersonDetailDto getPersonById(@PathVariable int id) {
        return PersonDetailDto.from(personService.getPersonById(id));
    }

    /**
     * Stores and assigns a private contact picture.
     */
    @PutMapping(value = "/{id}/profile-picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PersonDto updateProfilePicture(
            @PathVariable int id,
            @RequestPart("file") MultipartFile file) {
        return PersonDto.from(personService.updateProfilePicture(id, UploadSource.from(file)));
    }

    /**
     * Streams the currently assigned contact picture after tenant authorization.
     */
    @GetMapping("/{id}/profile-picture/{token:.+}")
    public ResponseEntity<StreamingResponseBody> getProfilePicture(
            @PathVariable int id,
            @PathVariable String token) {
        return ManagedContentResponse.inline(personService.getProfilePictureContent(id, token));
    }

    /**
     * POST endpoint to create a new person.
     * @param dto
     * @return
     */
    @PostMapping
    public PersonDto createPerson(@Valid @RequestBody PersonDto dto) {
        return PersonDto.from(personService.createReviewed(
            dto.toBean(), dto.getDuplicateReviewToken()));
    }

    /**
     * PUT endpoint to update an existing person.
     * @param id
     * @param dto
     * @return
     */
    @PutMapping("/{id}")
    public PersonDto updatePerson(@PathVariable int id, @Valid @RequestBody PersonDto dto) {
        return PersonDto.from(personService.update(id, dto.toBean()));
    }

    @PutMapping("/{id}/owner")
    public PersonDto updateOwner(@PathVariable int id, @Valid @RequestBody PersonOwnerDto dto) {
        return PersonDto.from(personService.updateOwner(id, dto.getOwnerId()));
    }

    /**
     * PUT endpoint to set the contact's engine-evaluation opt-outs (issue #358).
     * @param id
     * @param dto
     * @return the updated contact
     */
    @PutMapping("/{id}/evaluation")
    public PersonDto updateEvaluation(@PathVariable int id, @Valid @RequestBody PersonEvaluationDto dto) {
        return PersonDto.from(
            personService.updateEvaluationExclusions(id, dto.getRiskExcluded(), dto.getIntroExcluded()));
    }

    /**
     * PUT endpoint to set the contact's processing and third-party-provision restrictions.
     * @param id contact id
     * @param dto requested restriction state
     * @return the updated contact
     */
    @PutMapping("/{id}/restrictions")
    public PersonDto updateRestrictions(@PathVariable int id, @Valid @RequestBody PersonRestrictionsDto dto) {
        return PersonDto.from(personService.updateProcessingRestrictions(
            id, dto.getSuspended(), dto.getProvisionCeased()));
    }

    /**
     * PUT endpoint to replace the contact's source provenance (#559). A body with every field null
     * clears it, recording that the origin is unknown.
     * @param id contact id
     * @param request requested provenance
     * @return the updated contact
     */
    @PutMapping("/{id}/provenance")
    public PersonDto updateProvenance(
            @PathVariable int id, @Valid @RequestBody PersonProvenanceRequest request) {
        return PersonDto.from(personService.updateProvenance(
            id, request.getLeadSource(), request.getLeadSourceDetail(), request.getReferrerPersonId()));
    }

    /**
     * GET endpoint for the contact's current lead-lifecycle state and its permitted next moves.
     * @param id contact id
     * @return current lifecycle state
     */
    @GetMapping("/{id}/lifecycle")
    public PersonLifecycleDto getLifecycle(@PathVariable int id) {
        return personLifecycleService.getLifecycle(id);
    }

    /**
     * PUT endpoint to move a contact to a lead-lifecycle stage (#559). Requesting the stage the
     * contact already holds updates only the accompanying reason and note.
     * @param id contact id
     * @param request requested stage with its reason and note
     * @return the contact's lifecycle state after the move
     */
    @PutMapping("/{id}/lifecycle")
    public PersonLifecycleDto updateLifecycle(
            @PathVariable int id, @Valid @RequestBody PersonLifecycleRequest request) {
        return PersonLifecycleDto.from(personLifecycleService.updateLifecycle(id, request));
    }

    /**
     * POST endpoint to withdraw a contact from the lead lifecycle. Withdrawal is a deliberate,
     * separate operation so that a client which omits the stage field cannot erase it by accident,
     * and it carries its note in a body so the note never reaches a URL.
     * @param id contact id
     * @param request optional explanation recorded in the lifecycle history
     * @return the contact's lifecycle state after the withdrawal
     */
    @PostMapping("/{id}/lifecycle/withdrawal")
    public PersonLifecycleDto withdrawFromLifecycle(
            @PathVariable int id,
            @Valid @RequestBody(required = false) PersonLifecycleWithdrawalRequest request) {
        return PersonLifecycleDto.from(personLifecycleService.withdrawFromLifecycle(
            id, request == null ? null : request.getNote()));
    }

    /**
     * GET endpoint for the contact's append-only lead-lifecycle timeline, most recent first.
     * @param id contact id
     * @return transition history
     */
    @GetMapping("/{id}/lifecycle/history")
    public List<PersonLifecycleHistoryDto> getLifecycleHistory(@PathVariable int id) {
        return personLifecycleService.getHistory(id);
    }

    /**
     * POST endpoint to archive a contact, the reversible replacement for deletion (#854). There is
     * deliberately no delete endpoint: nothing in the product may destroy a contact record.
     * @param id contact id
     * @return the archived contact
     */
    @PostMapping("/{id}/archive")
    public PersonDto archivePerson(@PathVariable int id) {
        return PersonDto.from(personService.archive(id));
    }

    /**
     * POST endpoint to return an archived contact to the active working set.
     * @param id contact id
     * @return the restored contact
     */
    @PostMapping("/{id}/restore")
    public PersonDto restorePerson(@PathVariable int id) {
        return PersonDto.from(personService.restore(id));
    }

    /**
     * GET endpoint to retrieve tags associated with a person.
     * @param id
     * @return personService.getTagsByPersonId(id);
    */
    @GetMapping("/{id}/tags")
    public List<TagDto> getTagsForPerson(@PathVariable int id) {
        return personService.getTagsByPersonId(id).stream().map(TagDto::from).toList();
    }

    /**
     * POST endpoint to associate a tag with a person.
     * @param id
     * @param tagId
     */
    @PostMapping("/{id}/tags/{tagId}")
    public void addTagToPerson(@PathVariable int id, @PathVariable int tagId) {
        personService.addTag(id, tagId);
    }

    /**
     * DELETE endpoint to dissociate a tag from a person.
     * @param id
     * @param tagId
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    public void removeTagFromPerson(@PathVariable int id, @PathVariable int tagId) {
        personService.removeTag(id, tagId);
    }

    /**
     * PUT endpoint to replace the tags associated with a person.
     * @param id
     * @param tagIds
     * @return
     */
    @PutMapping("/{id}/tags")
    public List<TagDto> replaceTagsForPerson(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return personService.replaceTags(id, tagIds).stream().map(TagDto::from).toList();
    }

    /**
     * POST endpoint to add one tag to many contacts in a single request.
     * @param request the target contact ids and the tag to add
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/tags/add")
    public BulkOperationResult bulkAddTag(@Valid @RequestBody BulkTagRequest request) {
        return bulkOperationService.addTagToPersons(request.getIds(), request.getTagId());
    }

    /**
     * POST endpoint to remove one tag from many contacts in a single request.
     * @param request the target contact ids and the tag to remove
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/tags/remove")
    public BulkOperationResult bulkRemoveTag(@Valid @RequestBody BulkTagRequest request) {
        return bulkOperationService.removeTagFromPersons(request.getIds(), request.getTagId());
    }

    /**
     * POST endpoint to archive many contacts in a single request.
     * @param request the target contact ids
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/archive")
    public BulkOperationResult bulkArchive(@Valid @RequestBody BulkDeleteRequest request) {
        return bulkOperationService.archivePersons(request.getIds());
    }

    /**
     * POST endpoint to restore many archived contacts in a single request.
     * @param request the target contact ids
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/restore")
    public BulkOperationResult bulkRestore(@Valid @RequestBody BulkDeleteRequest request) {
        return bulkOperationService.restorePersons(request.getIds());
    }

    @PostMapping("/bulk/owner")
    public BulkOperationResult bulkAssignOwner(@Valid @RequestBody BulkOwnerRequest request) {
        return bulkOperationService.assignOwnerToPersons(request.getIds(), request.getOwnerId());
    }

    /**
     * GET endpoint to retrieve deals associated with a person.
     * @param id
     * @return
     */
    @GetMapping("/{id}/deals")
    public List<DealDto> getDealsForPerson(@PathVariable int id) {
        return personService.getDealsByPersonId(id).stream().map(DealDto::from).toList();
    }

    /**
     * GET endpoint to retrieve activities associated with a person.
     * @param id
     * @return
     */
    @GetMapping("/{id}/activities")
    public List<ActivityDto> getActivitiesForPerson(@PathVariable int id) {
        return personService.getActivitiesByPersonId(id).stream().map(ActivityDto::from).toList();
    }

    /**
     * GET endpoint to retrieve notes associated with a person.
     * @param id
     * @return
     */
    @GetMapping("/{id}/notes")
    public List<NoteDto> getNotesForPerson(@PathVariable int id) {
        return personService.getNotesByPersonId(id).stream().map(NoteDto::from).toList();
    }

    /**
     * GET endpoint to retrieve tasks associated with a person.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tasks")
    public List<TaskDto> getTasksForPerson(@PathVariable int id) {
        return personService.getTasksByPersonId(id).stream().map(TaskDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a contact's employment history, current stint first.
     * @param id
     * @return
     */
    @GetMapping("/{id}/employment")
    public List<PersonEmploymentDto> getEmploymentHistory(@PathVariable int id) {
        return personService.getEmploymentHistory(id).stream().map(PersonEmploymentDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a contact's connections in the warm-intro graph.
     * @param id
     * @return
     */
    @GetMapping("/{id}/connections")
    public List<PersonConnectionDto> getConnections(@PathVariable int id) {
        return connectionService.getConnections(id);
    }

    /**
     * POST endpoint to connect a contact to another contact (idempotent; re-adding edits the edge).
     * @param id
     * @param request
     */
    @PostMapping("/{id}/connections")
    public void addConnection(@PathVariable int id, @Valid @RequestBody ConnectionRequestDto request) {
        connectionService.addConnection(id, request.getTargetPersonId(), request.getType(),
            request.getStrength(), request.getNote());
    }

    /**
     * DELETE endpoint to remove a connection between two contacts.
     * @param id
     * @param targetId
     */
    @DeleteMapping("/{id}/connections/{targetId}")
    public void removeConnection(@PathVariable int id, @PathVariable int targetId) {
        connectionService.removeConnection(id, targetId);
    }

    /**
     * GET endpoint for the warm-introduction path to reach a contact.
     * @param id
     * @return
     */
    @GetMapping("/{id}/intro-path")
    public IntroPathDto getIntroPath(@PathVariable int id) {
        return connectionService.findIntroPath(id);
    }

    /**
     * GET retrieves the custom-field values for a contact.
     */
    @GetMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> getCustomFieldsForPerson(@PathVariable int id) {
        return personService.getCustomFields(id);
    }

    /**
     * PUT replaces the custom-field values for a contact.
     */
    @PutMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> updateCustomFieldsForPerson(@PathVariable int id,
            @Valid @RequestBody CustomFieldValuesRequest request) {
        return personService.updateCustomFields(id, request.getValues());
    }

    /**
     * PUT sets or clears a single custom-field value on a contact.
     */
    @PutMapping("/{id}/custom-fields/{definitionId}")
    public List<CustomFieldEntryDto> updateCustomFieldForPerson(@PathVariable int id,
            @PathVariable int definitionId, @Valid @RequestBody CustomFieldValueRequest request) {
        return personService.updateCustomField(id, definitionId, request.getValue());
    }

    /**
     * GET filled custom-field values for many contacts, keyed by contact id.
     */
    @GetMapping("/custom-field-values")
    public Map<Integer, Map<Integer, Object>> getCustomFieldValuesForPersons(@RequestParam List<Integer> ids) {
        return personService.getCustomFieldValues(ids);
    }

    private MemberScope resolveMemberScope(String scope, List<Integer> memberIds) {
        return memberScopeResolver.resolve(scope, memberIds, workspaceService.getCurrentUserId());
    }
}
