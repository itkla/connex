package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class PersonMapperTest extends AbstractMapperTest {

    /**
     * Inserts a new person and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Person person = newPerson(newCompany());
        assertNotEquals(0, person.getId());
    }

    /**
     * Gets a person by ID and checks if the returned person is not null.
     */
    @Test
    void getPersonById_returnsInsertedRow() {
        Company company = newCompany();
        Person person = newPerson(company);

        Person found = personMapper.getPersonById(person.getId());

        assertNotNull(found);
        assertEquals(person.getName(), found.getName());
        assertEquals(person.getEmail(), found.getEmail());
        assertEquals(person.getPhone(), found.getPhone());
        assertEquals("Engineer", found.getTitle());
        assertNotNull(found.getCompany());
        assertEquals(company.getId(), found.getCompany().getId());
    }

    /**
     * Gets a person by ID and checks if the returned person is null when the ID is negative.
     */
    @Test
    void getPersonById_returnsNullWhenMissing() {
        assertNull(personMapper.getPersonById(-1));
    }

    /**
     * Gets all persons and checks if the returned list includes the inserted person.
     */
    @Test
    void getAllPersons_includesInsertedRow() {
        Person person = newPerson(newCompany());

        List<Person> all = personMapper.getAllPersons();

        assertTrue(all.stream().anyMatch(x -> x.getId() == person.getId()));
    }

    /**
     * Updates a person and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Person person = newPerson(newCompany());
        person.setName("Renamed Person");
        person.setTitle("Director");
        person.setCompany(null);

        personMapper.update(person);

        Person found = personMapper.getPersonById(person.getId());
        assertEquals("Renamed Person", found.getName());
        assertEquals("Director", found.getTitle());

        // PersonResult always materializes a Company association keyed on company_id
        assertTrue(found.getCompany() == null || found.getCompany().getId() == 0);
    }

    /**
     * Deletes a person and checks if the person is removed.
     */
    @Test
    void delete_removesRow() {
        Person person = newPerson(newCompany());

        personMapper.delete(person.getId());

        assertNull(personMapper.getPersonById(person.getId()));
    }

    /**
     * Gets persons by company ID and checks if the returned list includes the inserted person.
     */
    @Test
    void getPersonsByCompanyId_returnsOnlyMatchingPeople() {
        Company company1 = newCompany();
        Company company2 = newCompany();
        Person person1 = newPerson(company1);
        Person person2 = newPerson(company2);

        List<Person> in1 = personMapper.getPersonsByCompanyId(company1.getId());

        assertTrue(in1.stream().anyMatch(x -> x.getId() == person1.getId()));
        assertTrue(in1.stream().noneMatch(x -> x.getId() == person2.getId()));
    }

    /**
     * Adds a tag to a person and checks if the returned list includes the inserted person.
     */
    @Test
    void addTag_thenGetPersonsByTagId_returnsPerson() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();

        personMapper.addTag(person.getId(), tag.getId());

        List<Person> matched = personMapper.getPersonsByTagId(tag.getId());
        assertTrue(matched.stream().anyMatch(x -> x.getId() == person.getId()));
    }

    /**
     * Adds a tag to a person and checks if the tag is added only once.
     */
    @Test
    void addTag_isIdempotent() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();

        personMapper.addTag(person.getId(), tag.getId());
        personMapper.addTag(person.getId(), tag.getId());

        long matching = personMapper.getPersonsByTagId(tag.getId()).stream()
                .filter(x -> x.getId() == person.getId()).count();
        assertEquals(1, matching);
    }

    /**
     * Removes a tag from a person and checks if the tag is removed.
     */
    @Test
    void removeTag_dropsAssociation() {
        Person person = newPerson(newCompany());
        Tag tag = newTag();
        personMapper.addTag(person.getId(), tag.getId());

        personMapper.removeTag(person.getId(), tag.getId());

        assertTrue(personMapper.getPersonsByTagId(tag.getId()).stream()
                .noneMatch(x -> x.getId() == person.getId()));
    }

    /**
     * Gets persons by deal ID and checks if the returned list includes the inserted person.
     */
    @Test
    void getPersonsByDealId_returnsLinkedPeople() {
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.addPerson(deal.getId(), person.getId());

        List<Person> matched = personMapper.getPersonsByDealId(deal.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == person.getId()));
    }
}
