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
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.services.PersonService;

import java.util.List;

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
    public List<Person> getPersons(
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) Integer tagId,
        @RequestParam(required = false) Integer dealId
    ) {
        if (companyId != null) return personService.getPersonsByCompanyId(companyId);
        if (tagId != null)     return personService.getPersonsByTagId(tagId);
        if (dealId != null)    return personService.getPersonsByDealId(dealId);
        return personService.getAllPersons();
    }

    /**
     * GET endpoint to retrieve a single person by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Person getPersonById(@PathVariable int id) {
        return personService.getPersonById(id);
    }

    /**
     * POST endpoint to create a new person.
     * @param person
     * @return
     */
    @PostMapping
    public Person createPerson(@RequestBody Person person) {
        return personService.create(person);
    }

    /**
     * PUT endpoint to update an existing person.
     * @param id
     * @param person
     * @return
     */
    @PutMapping("/{id}")
    public Person updatePerson(@PathVariable int id, @RequestBody Person person) {
        return personService.update(id, person);
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
    public List<Tag> getTagsForPerson(@PathVariable int id) {
        return personService.getTagsByPersonId(id);
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
    public List<Tag> replaceTagsForPerson(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return personService.replaceTags(id, tagIds);
    }
}
