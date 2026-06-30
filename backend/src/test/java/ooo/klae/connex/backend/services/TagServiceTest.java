package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class TagServiceTest extends AbstractServiceTest {

    @Autowired TagService tagService;

    @Test
    void getDealsByTagId_returnsOnlyDealsLinkedToTag() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal linked = newDeal(pipeline, stage, newCompany());
        Deal unlinked = newDeal(pipeline, stage, newCompany());
        Tag tag = newTag();
        dealMapper.addTag(workspace.getId(), linked.getId(), tag.getId());

        List<Deal> deals = tagService.getDealsByTagId(tag.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == linked.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == unlinked.getId()));
    }

    @Test
    void getDealsByTagId_throwsWhenTagMissing() {
        assertThrows(ResourceNotFoundException.class, () -> tagService.getDealsByTagId(-1));
    }

    @Test
    void getPersonsByTagId_returnsOnlyPeopleLinkedToTag() {
        Person linked = newPerson(newCompany());
        Person unlinked = newPerson(newCompany());
        Tag tag = newTag();
        personMapper.addTag(workspace.getId(), linked.getId(), tag.getId());

        List<Person> people = tagService.getPersonsByTagId(tag.getId());

        assertTrue(people.stream().anyMatch(x -> x.getId() == linked.getId()));
        assertTrue(people.stream().noneMatch(x -> x.getId() == unlinked.getId()));
    }

    @Test
    void getPersonsByTagId_throwsWhenTagMissing() {
        assertThrows(ResourceNotFoundException.class, () -> tagService.getPersonsByTagId(-1));
    }

    @Test
    void getCompaniesByTagId_returnsOnlyCompaniesLinkedToTag() {
        Company linked = newCompany();
        Company unlinked = newCompany();
        Tag tag = newTag();
        companyMapper.addTag(workspace.getId(), linked.getId(), tag.getId());

        List<Company> companies = tagService.getCompaniesByTagId(tag.getId());

        assertTrue(companies.stream().anyMatch(x -> x.getId() == linked.getId()));
        assertTrue(companies.stream().noneMatch(x -> x.getId() == unlinked.getId()));
    }

    @Test
    void getCompaniesByTagId_throwsWhenTagMissing() {
        assertThrows(ResourceNotFoundException.class, () -> tagService.getCompaniesByTagId(-1));
    }
}
