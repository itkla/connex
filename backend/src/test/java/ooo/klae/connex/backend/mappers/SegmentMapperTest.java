package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class SegmentMapperTest extends AbstractMapperTest {
    @Autowired private SegmentMapper segmentMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void ownerFieldConditionMatchesCurrentOwnerForCompanyAndPerson() {
        User owner = newUser();
        User other = newUser();
        Company mine = newCompany();
        Company theirs = newCompany();
        companyMapper.updateOwner(workspace.getId(), mine.getId(), owner.getId());
        companyMapper.updateOwner(workspace.getId(), theirs.getId(), other.getId());
        Person myPerson = newPerson(mine);
        Person theirPerson = newPerson(theirs);
        personMapper.updateOwner(workspace.getId(), myPerson.getId(), owner.getId());
        personMapper.updateOwner(workspace.getId(), theirPerson.getId(), other.getId());

        List<Integer> companyMatches = segmentMapper.companyIdsMatching(Map.of(
            "workspaceId", workspace.getId(), "field", "owner", "op", "is", "id", owner.getId()));
        assertTrue(companyMatches.contains(mine.getId()));
        assertFalse(companyMatches.contains(theirs.getId()));

        List<Integer> personMatches = segmentMapper.personIdsMatching(Map.of(
            "workspaceId", workspace.getId(), "field", "owner", "op", "is", "id", owner.getId()));
        assertTrue(personMatches.contains(myPerson.getId()));
        assertFalse(personMatches.contains(theirPerson.getId()));
    }

    @Test
    void suspendedPeopleAreExcludedFromUniversesMatchesAndPersonActivityPredicate() {
        Company company = newCompany();
        Person processable = newPerson(company);
        Person suspended = newPerson(company);
        User actor = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Suspended activity");
        activity.setPerson(suspended);
        activity.setDeal(deal);
        activity.setCreatedBy(actor);
        activity.setTimestamp("2999-01-01 00:00:00");
        activityMapper.insert(activity);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspended.getId(), true, false);

        assertTrue(segmentMapper.personIdsInWorkspace(workspace.getId()).contains(processable.getId()));
        assertFalse(segmentMapper.personIdsInWorkspace(workspace.getId()).contains(suspended.getId()));
        assertFalse(segmentMapper.personIdsMatching(Map.of(
            "workspaceId", workspace.getId(),
            "field", "name",
            "op", "equals",
            "value", suspended.getName())).contains(suspended.getId()));
        assertTrue(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspace.getId(), actor.getId(), List.of(processable.getId(), suspended.getId()), null)
            .contains(company.getId()));
        assertTrue(segmentMapper.companyIdsNoActivitySince(workspace.getId(), 365_000, null)
            .contains(company.getId()));
        assertTrue(segmentMapper.personLabels(
            workspace.getId(), List.of(suspended.getId())).stream()
            .anyMatch(label -> label.getId() == suspended.getId()));
    }

    @Test
    void sharedInProcessablePersonActivityStillCountsForOwnedDealCompany() {
        Company company = newCompany();
        Person target = newPerson(company);
        User actor = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Workspace sibling = new Workspace();
        sibling.setName("Workspace " + unique());
        sibling.setSlug("workspace-" + unique());
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        assertNotNull(orgId);
        sibling.setOrgId(orgId);
        workspaceMapper.insert(sibling);
        Company siblingCompany = new Company();
        siblingCompany.setName("Company " + unique());
        siblingCompany.setWorkspaceId(sibling.getId());
        companyMapper.insert(siblingCompany);
        Person shared = new Person();
        shared.setName("Person " + unique());
        shared.setWorkspaceId(sibling.getId());
        shared.setCompany(siblingCompany);
        personMapper.insert(shared);
        assertTrue(shareMapper.sharePerson(
            shared.getId(), sibling.getId(), workspace.getId(), actor.getId(), false) > 0);
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Shared contact activity");
        activity.setPerson(shared);
        activity.setDeal(deal);
        activity.setCreatedBy(actor);
        activity.setTimestamp("2999-01-01 00:00:00");
        activityMapper.insert(activity);
        assertFalse(segmentMapper.companyIdsNoActivitySince(
            workspace.getId(), 365_000, null).contains(company.getId()));
        assertFalse(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspace.getId(), actor.getId(), List.of(target.getId()), null)
            .contains(company.getId()));

        assertTrue(shareMapper.unsharePerson(shared.getId(), sibling.getId(), workspace.getId()) > 0);
        assertTrue(segmentMapper.companyIdsNoActivitySince(workspace.getId(), 365_000, null)
            .contains(company.getId()));
        assertTrue(segmentMapper.companyIdsForPersonsWithoutUserActivity(
            workspace.getId(), actor.getId(), List.of(target.getId()), null)
            .contains(company.getId()));
    }

    @Test
    void personExistenceOnlyConsidersActiveWorkspaceNotesAndAttachments() {
        User actor = newUser();
        Person person = newPerson(newCompany());
        Workspace sibling = newSiblingWorkspace();
        workspaceMapper.addMember(sibling.getId(), actor.getId(), "member");
        assertTrue(shareMapper.sharePerson(
            person.getId(), workspace.getId(), sibling.getId(), actor.getId(), false) > 0);
        noteMapper.insert(newNote(sibling.getId(), actor, person, null));
        attachmentMapper.insert(newAttachment(sibling.getId(), "person", person.getId(), actor));

        assertFalse(segmentMapper.personExistence(existenceParams("has_note")).contains(person.getId()));
        assertFalse(segmentMapper.personExistence(existenceParams("has_attachment")).contains(person.getId()));

        noteMapper.insert(newNote(workspace.getId(), actor, person, null));
        attachmentMapper.insert(newAttachment(workspace.getId(), "person", person.getId(), actor));

        assertTrue(segmentMapper.personExistence(existenceParams("has_note")).contains(person.getId()));
        assertTrue(segmentMapper.personExistence(existenceParams("has_attachment")).contains(person.getId()));
    }

    @Test
    void dealExistenceOnlyConsidersActiveWorkspaceNotesAndAttachments() {
        User actor = newUser();
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 0), newCompany());
        Workspace sibling = newSiblingWorkspace();
        noteMapper.insert(newNote(sibling.getId(), actor, null, deal));
        attachmentMapper.insert(newAttachment(sibling.getId(), "deal", deal.getId(), actor));

        assertFalse(segmentMapper.dealExistence(existenceParams("has_note")).contains(deal.getId()));
        assertFalse(segmentMapper.dealExistence(existenceParams("has_attachment")).contains(deal.getId()));

        noteMapper.insert(newNote(workspace.getId(), actor, null, deal));
        attachmentMapper.insert(newAttachment(workspace.getId(), "deal", deal.getId(), actor));

        assertTrue(segmentMapper.dealExistence(existenceParams("has_note")).contains(deal.getId()));
        assertTrue(segmentMapper.dealExistence(existenceParams("has_attachment")).contains(deal.getId()));
    }

    @Test
    void companyExistenceOnlyConsidersActiveWorkspaceAttachments() {
        User actor = newUser();
        Company company = newCompany();
        Workspace sibling = newSiblingWorkspace();
        workspaceMapper.addMember(sibling.getId(), actor.getId(), "member");
        assertTrue(shareMapper.shareCompany(
            company.getId(), workspace.getId(), sibling.getId(), actor.getId(), false) > 0);
        attachmentMapper.insert(newAttachment(sibling.getId(), "company", company.getId(), actor));

        assertFalse(segmentMapper.companyExistence(existenceParams("has_attachment")).contains(company.getId()));

        attachmentMapper.insert(newAttachment(workspace.getId(), "company", company.getId(), actor));

        assertTrue(segmentMapper.companyExistence(existenceParams("has_attachment")).contains(company.getId()));
    }

    @Test
    void personCompanyFieldOnlyMatchesCurrentlyVisibleCompanies() {
        User actor = newUser();
        Company ownedCompany = newCompany();
        Person ownedCompanyPerson = newPerson(ownedCompany);
        Workspace sibling = newSiblingWorkspace();
        Company sharedCompany = newCompany(sibling);
        workspaceMapper.addMember(sibling.getId(), actor.getId(), "member");
        assertTrue(shareMapper.shareCompany(
            sharedCompany.getId(), sibling.getId(), workspace.getId(), actor.getId(), false) > 0);
        Person sharedCompanyPerson = newPerson(sharedCompany);
        Person restrictedSharedCompanyPerson = newPerson(sharedCompany);
        personMapper.updateProcessingRestrictions(
            workspace.getId(), restrictedSharedCompanyPerson.getId(), true, false);

        assertTrue(personMatchesCompany(ownedCompany.getId()).contains(ownedCompanyPerson.getId()));
        assertTrue(personMatchesCompany(sharedCompany.getId()).contains(sharedCompanyPerson.getId()));
        assertFalse(personMatchesCompany(sharedCompany.getId()).contains(restrictedSharedCompanyPerson.getId()));
        assertTrue(personMatchesCompanyIncludingRestricted(sharedCompany.getId())
            .contains(sharedCompanyPerson.getId()));
        assertTrue(personMatchesCompanyIncludingRestricted(sharedCompany.getId())
            .contains(restrictedSharedCompanyPerson.getId()));
        assertTrue(personMatchesCompanies(List.of(ownedCompany.getId(), sharedCompany.getId()))
            .contains(sharedCompanyPerson.getId()));
        assertTrue(personMatchesCompaniesIncludingRestricted(
            List.of(ownedCompany.getId(), sharedCompany.getId()))
            .contains(restrictedSharedCompanyPerson.getId()));

        assertTrue(shareMapper.unshareCompany(
            sharedCompany.getId(), sibling.getId(), workspace.getId()) > 0);

        assertFalse(personMatchesCompany(sharedCompany.getId()).contains(sharedCompanyPerson.getId()));
        assertFalse(personMatchesCompanyIncludingRestricted(sharedCompany.getId())
            .contains(sharedCompanyPerson.getId()));
        assertFalse(personMatchesCompanies(List.of(ownedCompany.getId(), sharedCompany.getId()))
            .contains(sharedCompanyPerson.getId()));
        assertFalse(personMatchesCompaniesIncludingRestricted(
            List.of(ownedCompany.getId(), sharedCompany.getId()))
            .contains(restrictedSharedCompanyPerson.getId()));
        assertTrue(personMatchesCompanies(List.of(ownedCompany.getId(), sharedCompany.getId()))
            .contains(ownedCompanyPerson.getId()));
    }

    @Test
    void tagFieldConditionsIgnoreForeignWorkspaceAssociationsAcrossRecordTypes() {
        Company company = newCompany();
        Person person = newPerson(company);
        Person suspended = newPerson(company);
        personMapper.updateProcessingRestrictions(workspace.getId(), suspended.getId(), true, false);
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 0), company);
        Tag ownTag = newTag();
        companyMapper.addTag(workspace.getId(), company.getId(), ownTag.getId());
        personMapper.addTag(workspace.getId(), person.getId(), ownTag.getId());
        personMapper.addTag(workspace.getId(), suspended.getId(), ownTag.getId());
        dealMapper.addTag(workspace.getId(), deal.getId(), ownTag.getId());

        Workspace sibling = newSiblingWorkspace();
        Tag foreignTag = newTag(sibling);
        jdbcTemplate.update(
            "INSERT INTO company_tag (company_id, tag_id) VALUES (?, ?)", company.getId(), foreignTag.getId());
        jdbcTemplate.update(
            "INSERT INTO person_tag (person_id, tag_id) VALUES (?, ?)", person.getId(), foreignTag.getId());
        jdbcTemplate.update(
            "INSERT INTO person_tag (person_id, tag_id) VALUES (?, ?)", suspended.getId(), foreignTag.getId());
        jdbcTemplate.update(
            "INSERT INTO deal_tag (deal_id, tag_id) VALUES (?, ?)", deal.getId(), foreignTag.getId());

        assertTrue(segmentMapper.companyIdsMatching(tagParams(ownTag)).contains(company.getId()));
        assertTrue(segmentMapper.personIdsMatching(tagParams(ownTag)).contains(person.getId()));
        assertFalse(segmentMapper.personIdsMatching(tagParams(ownTag)).contains(suspended.getId()));
        assertTrue(segmentMapper.personIdsMatchingIncludingRestricted(tagParams(ownTag)).contains(person.getId()));
        assertTrue(segmentMapper.personIdsMatchingIncludingRestricted(tagParams(ownTag)).contains(suspended.getId()));
        assertTrue(segmentMapper.dealIdsMatching(tagParams(ownTag)).contains(deal.getId()));

        assertFalse(segmentMapper.companyIdsMatching(tagParams(foreignTag)).contains(company.getId()));
        assertFalse(segmentMapper.personIdsMatching(tagParams(foreignTag)).contains(person.getId()));
        assertFalse(segmentMapper.personIdsMatching(tagParams(foreignTag)).contains(suspended.getId()));
        assertFalse(segmentMapper.personIdsMatchingIncludingRestricted(tagParams(foreignTag)).contains(person.getId()));
        assertFalse(segmentMapper.personIdsMatchingIncludingRestricted(tagParams(foreignTag))
            .contains(suspended.getId()));
        assertFalse(segmentMapper.dealIdsMatching(tagParams(foreignTag)).contains(deal.getId()));
    }

    private Map<String, Object> existenceParams(String predicate) {
        return Map.of(
            "workspaceId", workspace.getId(),
            "predicate", predicate,
            "days", 30,
            "includeRestrictedPeople", false);
    }

    private List<Integer> personMatchesCompany(int companyId) {
        return segmentMapper.personIdsMatching(companyParams(companyId));
    }

    private List<Integer> personMatchesCompanyIncludingRestricted(int companyId) {
        return segmentMapper.personIdsMatchingIncludingRestricted(companyParams(companyId));
    }

    private List<Integer> personMatchesCompanies(List<Integer> companyIds) {
        return segmentMapper.personIdsMatching(companiesParams(companyIds));
    }

    private List<Integer> personMatchesCompaniesIncludingRestricted(List<Integer> companyIds) {
        return segmentMapper.personIdsMatchingIncludingRestricted(companiesParams(companyIds));
    }

    private Map<String, Object> companyParams(int companyId) {
        return Map.of(
            "workspaceId", workspace.getId(),
            "field", "company",
            "op", "is",
            "id", companyId);
    }

    private Map<String, Object> companiesParams(List<Integer> companyIds) {
        return Map.of(
            "workspaceId", workspace.getId(),
            "field", "company",
            "op", "in",
            "ids", companyIds);
    }

    private Map<String, Object> tagParams(Tag tag) {
        return Map.of(
            "workspaceId", workspace.getId(),
            "field", "tag",
            "op", "has",
            "id", tag.getId());
    }

    private Workspace newSiblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Workspace " + unique());
        sibling.setSlug("workspace-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }

    private Company newCompany(Workspace target) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }

    private Tag newTag(Workspace target) {
        Tag tag = new Tag();
        tag.setName("Tag " + unique());
        tag.setColor("#abcdef");
        tag.setWorkspaceId(target.getId());
        tagMapper.insert(tag);
        return tag;
    }

    private static Note newNote(int workspaceId, User author, Person person, Deal deal) {
        Note note = new Note();
        note.setWorkspaceId(workspaceId);
        note.setContent("Note " + unique());
        note.setVisibility("workspace");
        note.setAuthor(author);
        note.setPerson(person);
        note.setDeal(deal);
        return note;
    }

    private static Attachment newAttachment(
            int workspaceId, String entityType, int entityId, User uploadedBy) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType(entityType);
        attachment.setEntityId(entityId);
        attachment.setFileName("Attachment " + unique() + ".pdf");
        attachment.setUrl("https://files.example.com/" + unique());
        attachment.setContentType("application/pdf");
        attachment.setSize(1L);
        attachment.setUploadedBy(uploadedBy);
        return attachment;
    }
}
