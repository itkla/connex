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
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.services.PersonService;

import java.util.List;

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
     * GET endpoint to retrieve a single person by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public PersonDto getPersonById(@PathVariable int id) {
        return PersonDto.from(personService.getPersonById(id));
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
}
