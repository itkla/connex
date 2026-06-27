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

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.util.LikePattern;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.ConnectionRequestDto;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldValueRequest;
import ooo.klae.connex.backend.dto.CustomFieldValuesRequest;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.IntroPathDto;
import ooo.klae.connex.backend.dto.JobMoveDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.dto.PersonEmploymentDto;
import ooo.klae.connex.backend.dto.PersonFacets;
import ooo.klae.connex.backend.dto.PersonDetailDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.EmploymentService;
import ooo.klae.connex.backend.services.PersonService;

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
public class PersonController {
    private final PersonService personService;
    private final EmploymentService employmentService;
    private final ConnectionService connectionService;

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
        else persons = personService.getAllPersons();
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
     * @throws IllegalArgumentException if the page or size is less than 1.
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
        @RequestParam(defaultValue = "false") boolean noCompany
    ) {
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 1);
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        int offset = Math.max(0, (page - 1) * size);
        List<PersonDto> items = personService.getPersonsPage(query, sort, dir, companies, titles, noCompany, size, offset)
            .stream().map(PersonDto::from).toList();
        return new PageResponse<>(items, personService.countPersons(query, companies, titles, noCompany));
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
            personService.hasPersonWithoutCompany()
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
     * POST endpoint to create a new person.
     * @param dto
     * @return
     */
    @PostMapping
    public PersonDto createPerson(@Valid @RequestBody PersonDto dto) {
        return PersonDto.from(personService.create(dto.toBean()));
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

    /**
     * DELETE endpoint to delete a person by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deletePerson(@PathVariable int id) {
        personService.delete(id);
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
}
