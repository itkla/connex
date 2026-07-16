package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.CustomFieldValue;
import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ActivityDto;

class DataSubjectRequestMapperTest extends AbstractMapperTest {
    @Autowired private DataSubjectRequestMapper dataSubjectRequestMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private PersonEmploymentMapper personEmploymentMapper;
    @Autowired private PersonEdgeMapper personEdgeMapper;
    @Autowired private IntroductionMapper introductionMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired private CustomFieldValueMapper customFieldValueMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long nextChainIndex = 1;

    @Test
    void crudRoundTripAndListStayOrgScoped() {
        Organization mine = newOrg();
        Organization other = newOrg();
        DataSubjectRequest mineRequest = newRequest(mine.getId(), "disclosure", "received");
        DataSubjectRequest otherRequest = newRequest(other.getId(), "correction", "in_progress");

        DataSubjectRequest loaded = dataSubjectRequestMapper.findById(mine.getId(), mineRequest.getId());
        assertEquals("disclosure", loaded.getRequestType());
        assertEquals("Requester", loaded.getRequesterName());
        assertNull(dataSubjectRequestMapper.findById(mine.getId(), otherRequest.getId()));
        assertEquals(List.of(mineRequest.getId()), dataSubjectRequestMapper.findByOrg(
            mine.getId(), null, 50, 0).stream().map(DataSubjectRequest::getId).toList());
        assertTrue(dataSubjectRequestMapper.findByOrg(mine.getId(), "closed", 50, 0).isEmpty());

        mineRequest.setStatus("closed");
        mineRequest.setResolution("Complete");
        assertEquals(1, dataSubjectRequestMapper.update(mineRequest));
        assertEquals("closed", dataSubjectRequestMapper.findById(mine.getId(), mineRequest.getId()).getStatus());

        mineRequest.setOrgId(other.getId());
        mineRequest.setStatus("refused");
        assertEquals(0, dataSubjectRequestMapper.update(mineRequest));
        assertNotEquals("refused", dataSubjectRequestMapper.findById(mine.getId(), mineRequest.getId()).getStatus());
    }

    @Test
    void disclosureQueriesIncludeSameOrgOverlaysAndExcludeOtherSubjectsAndForeignOrgs() {
        Organization org = newOrg();
        Organization foreignOrg = newOrg();
        Workspace ownerWorkspace = newWorkspace(org.getId());
        Workspace overlayWorkspace = newWorkspace(org.getId());
        Workspace foreignWorkspace = newWorkspace(foreignOrg.getId());
        User actor = newUser();
        Company subjectCompany = newCompany(ownerWorkspace.getId(), "Subject Company");
        Person subject = newPerson(ownerWorkspace.getId(), subjectCompany, "Subject Person");
        Person counterpart = newPerson(ownerWorkspace.getId(), subjectCompany, "Counterpart Person");
        Person other = newPerson(ownerWorkspace.getId(), subjectCompany, "Other Person");
        Person third = newPerson(ownerWorkspace.getId(), subjectCompany, "Third Person");

        assertTrue(dataSubjectRequestMapper.subjectPersonInOrg(
            org.getId(), ownerWorkspace.getId(), subject.getId()));
        assertFalse(dataSubjectRequestMapper.subjectPersonInOrg(
            foreignOrg.getId(), ownerWorkspace.getId(), subject.getId()));

        Tag subjectTag = newTag(ownerWorkspace.getId(), "Subject Tag");
        Tag otherTag = newTag(ownerWorkspace.getId(), "Other Tag");
        personMapper.addTag(ownerWorkspace.getId(), subject.getId(), subjectTag.getId());
        personMapper.addTag(ownerWorkspace.getId(), other.getId(), otherTag.getId());

        CustomFieldDefinition definition = newCustomField(overlayWorkspace.getId());
        newCustomValue(overlayWorkspace.getId(), definition.getId(), subject.getId(), "special value");
        newCustomValue(overlayWorkspace.getId(), definition.getId(), other.getId(), "other value");

        Activity subjectActivity = newActivity(overlayWorkspace.getId(), subject, actor, "Subject Activity");
        newActivity(overlayWorkspace.getId(), other, actor, "Other Activity");
        newActivity(foreignWorkspace.getId(), subject, actor, "Foreign Activity");
        Note subjectNote = newNote(overlayWorkspace.getId(), subject, actor, "Subject Note");
        newNote(overlayWorkspace.getId(), other, actor, "Other Note");
        Task subjectTask = newTask(overlayWorkspace.getId(), subject, actor, "Subject Task");
        newTask(overlayWorkspace.getId(), other, actor, "Other Task");
        PersonEmployment subjectEmployment = newEmployment(
            overlayWorkspace.getId(), subject, subjectCompany, "Subject Employment");
        newEmployment(overlayWorkspace.getId(), other, subjectCompany, "Other Employment");
        Attachment subjectAttachment = newAttachment(overlayWorkspace.getId(), subject, actor, "subject.pdf");
        newAttachment(overlayWorkspace.getId(), other, actor, "other.pdf");
        newAttachment(foreignWorkspace.getId(), subject, actor, "foreign.pdf");

        newEdge(overlayWorkspace.getId(), subject, counterpart, "subject edge");
        newEdge(overlayWorkspace.getId(), other, third, "other edge");

        shareMapper.sharePerson(subject.getId(), ownerWorkspace.getId(), overlayWorkspace.getId(), actor.getId(), false);
        shareMapper.sharePerson(other.getId(), ownerWorkspace.getId(), overlayWorkspace.getId(), actor.getId(), false);
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit) VALUES (?, ?, ?, ?)",
            subject.getId(), foreignWorkspace.getId(), actor.getId(), false);
        Deal subjectDeal = newDeal(overlayWorkspace.getId(), actor, "Subject Deal");
        Deal otherDeal = newDeal(overlayWorkspace.getId(), actor, "Other Deal");
        dealMapper.addPerson(overlayWorkspace.getId(), subjectDeal.getId(), subject.getId(), "decision_maker");
        dealMapper.addPerson(overlayWorkspace.getId(), otherDeal.getId(), other.getId(), "champion");

        newIntroduction(overlayWorkspace.getId(), actor, subject, counterpart, "subject introduction");
        newIntroduction(overlayWorkspace.getId(), actor, other, third, "other introduction");

        insertAudit(org.getId(), overlayWorkspace.getId(), subject.getId(), "person.subject");
        insertAudit(org.getId(), overlayWorkspace.getId(), other.getId(), "person.other");
        insertAudit(foreignOrg.getId(), foreignWorkspace.getId(), subject.getId(), "person.foreign");

        assertEquals(subject.getId(), dataSubjectRequestMapper.findDisclosurePerson(
            org.getId(), ownerWorkspace.getId(), subject.getId()).getId());
        assertEquals("Subject Company", dataSubjectRequestMapper.findDisclosurePerson(
            org.getId(), ownerWorkspace.getId(), subject.getId()).getCompanyName());
        assertEquals(List.of(subjectTag.getId()), dataSubjectRequestMapper.findDisclosureTags(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getId()).toList());
        assertEquals("special_care", dataSubjectRequestMapper.findDisclosureCustomFields(
            org.getId(), ownerWorkspace.getId(), subject.getId()).getFirst().getDataClassification());
        assertEquals(List.of(subjectActivity.getId()), dataSubjectRequestMapper.findDisclosureActivities(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(ActivityDto::getId).toList());
        assertEquals(List.of(subjectNote.getId()), dataSubjectRequestMapper.findDisclosureNotes(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getId()).toList());
        assertEquals(List.of(subjectTask.getId()), dataSubjectRequestMapper.findDisclosureTasks(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getId()).toList());
        assertEquals(List.of(subjectEmployment.getId()), dataSubjectRequestMapper.findDisclosureEmployment(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getId()).toList());
        assertEquals(List.of(subjectAttachment.getId()), dataSubjectRequestMapper.findDisclosureAttachments(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getId()).toList());
        assertEquals("subject.pdf", dataSubjectRequestMapper.findDisclosureAttachments(
            org.getId(), ownerWorkspace.getId(), subject.getId()).getFirst().getFileName());
        assertEquals(List.of("subject edge"), dataSubjectRequestMapper.findDisclosureEdges(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getNote()).toList());
        assertEquals(counterpart.getName(), dataSubjectRequestMapper.findDisclosureEdges(
            org.getId(), ownerWorkspace.getId(), subject.getId()).getFirst().getCounterpartPersonName());
        assertEquals(List.of(subjectDeal.getId()), dataSubjectRequestMapper.findDisclosureDeals(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getDealId()).toList());
        assertEquals(List.of("subject introduction"), dataSubjectRequestMapper.findDisclosureIntroductions(
            org.getId(), ownerWorkspace.getId(), subject.getId()).stream().map(row -> row.getNote()).toList());
        assertEquals(List.of(overlayWorkspace.getId()),
            dataSubjectRequestMapper.findDisclosureProvisions(
                org.getId(), ownerWorkspace.getId(), subject.getId()).stream()
                .map(row -> row.getTargetWorkspaceId()).sorted().toList());
        assertEquals(List.of("person.subject"), dataSubjectRequestMapper.findDisclosureAudit(
            org.getId(), ownerWorkspace.getId(), subject.getId(), 1_000).stream()
            .map(row -> row.getAction()).toList());
        assertEquals(1, dataSubjectRequestMapper.countDisclosureAudit(
            org.getId(), ownerWorkspace.getId(), subject.getId()));
    }

    private Organization newOrg() {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        return org;
    }

    private Workspace newWorkspace(int orgId) {
        Workspace created = new Workspace();
        created.setOrgId(orgId);
        created.setName("Workspace " + unique());
        created.setSlug("workspace-" + unique());
        workspaceMapper.insert(created);
        return created;
    }

    private Company newCompany(int workspaceId, String name) {
        Company company = new Company();
        company.setWorkspaceId(workspaceId);
        company.setName(name);
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(int workspaceId, Company company, String name) {
        Person person = new Person();
        person.setWorkspaceId(workspaceId);
        person.setCompany(company);
        person.setName(name);
        person.setEmail(unique() + "@example.com");
        personMapper.insert(person);
        return person;
    }

    private Tag newTag(int workspaceId, String name) {
        Tag tag = new Tag();
        tag.setWorkspaceId(workspaceId);
        tag.setName(name + " " + unique());
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        return tag;
    }

    private CustomFieldDefinition newCustomField(int workspaceId) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspaceId);
        definition.setEntityType("person");
        definition.setFieldKey("special_" + unique());
        definition.setLabel("Special Care");
        definition.setFieldType("text");
        definition.setDataClassification("special_care");
        customFieldDefinitionMapper.insert(definition);
        return definition;
    }

    private CustomFieldValue newCustomValue(int workspaceId, int definitionId, int personId, String value) {
        CustomFieldValue customValue = new CustomFieldValue();
        customValue.setWorkspaceId(workspaceId);
        customValue.setDefinitionId(definitionId);
        customValue.setEntityType("person");
        customValue.setEntityId(personId);
        customValue.setValueText(value);
        customFieldValueMapper.upsert(customValue);
        return customValue;
    }

    private Activity newActivity(int workspaceId, Person person, User actor, String subject) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspaceId);
        activity.setType("call");
        activity.setSubject(subject);
        activity.setPerson(person);
        activity.setCreatedBy(actor);
        activity.setTimestamp("2026-01-02 03:04:05");
        activityMapper.insert(activity);
        return activity;
    }

    private Note newNote(int workspaceId, Person person, User actor, String content) {
        Note note = new Note();
        note.setWorkspaceId(workspaceId);
        note.setContent(content);
        note.setVisibility("private");
        note.setPerson(person);
        note.setAuthor(actor);
        noteMapper.insert(note);
        return note;
    }

    private Task newTask(int workspaceId, Person person, User actor, String description) {
        Task task = new Task();
        task.setWorkspaceId(workspaceId);
        task.setDescription(description);
        task.setStatus("todo");
        task.setPosition(0);
        task.setDueDate("2026-12-31");
        task.setAssignedTo(actor);
        task.setPerson(person);
        taskMapper.insert(task);
        return task;
    }

    private Attachment newAttachment(int workspaceId, Person person, User actor, String fileName) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType("person");
        attachment.setEntityId(person.getId());
        attachment.setFileName(fileName);
        attachment.setUrl("https://files.example.com/" + unique() + "/" + fileName);
        attachment.setContentType("application/pdf");
        attachment.setSize(1024L);
        attachment.setUploadedBy(actor);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private PersonEmployment newEmployment(int workspaceId, Person person, Company company, String title) {
        PersonEmployment employment = new PersonEmployment();
        employment.setWorkspaceId(workspaceId);
        employment.setPersonId(person.getId());
        employment.setCompanyId(company.getId());
        employment.setCompanyName(company.getName());
        employment.setTitle(title);
        employment.setStartedAt("2025-01-01 00:00:00");
        personEmploymentMapper.insert(employment);
        return employment;
    }

    private PersonEdge newEdge(int workspaceId, Person first, Person second, String note) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspaceId);
        edge.setSourcePersonId(Math.min(first.getId(), second.getId()));
        edge.setTargetPersonId(Math.max(first.getId(), second.getId()));
        edge.setType("knows");
        edge.setStrength(2);
        edge.setNote(note);
        personEdgeMapper.upsert(edge);
        return edge;
    }

    private Deal newDeal(int workspaceId, User actor, String name) {
        Company company = newCompany(workspaceId, name + " Company");
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspaceId);
        pipeline.setName(name + " Pipeline");
        pipelineMapper.insertPipeline(pipeline);
        Stage stage = new Stage();
        stage.setWorkspaceId(workspaceId);
        stage.setName(name + " Stage");
        stage.setPipeline(pipeline);
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        Deal deal = new Deal();
        deal.setWorkspaceId(workspaceId);
        deal.setOwnerId(actor.getId());
        deal.setName(name);
        deal.setValue(1000);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private Introduction newIntroduction(int workspaceId, User actor, Person first, Person second, String note) {
        Introduction introduction = new Introduction();
        introduction.setWorkspaceId(workspaceId);
        introduction.setIntroducerUserId(actor.getId());
        introduction.setPersonAId(Math.min(first.getId(), second.getId()));
        introduction.setPersonBId(Math.max(first.getId(), second.getId()));
        introduction.setNote(note);
        introduction.setIntroducedAt("2026-01-02 03:04:05");
        introductionMapper.recordMade(introduction);
        return introduction;
    }

    private DataSubjectRequest newRequest(int orgId, String requestType, String status) {
        DataSubjectRequest request = new DataSubjectRequest();
        request.setOrgId(orgId);
        request.setRequestType(requestType);
        request.setStatus(status);
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setReceivedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        dataSubjectRequestMapper.insert(request);
        return request;
    }

    private void insertAudit(int orgId, int workspaceId, int personId, String action) {
        AuditLog entry = new AuditLog();
        entry.setOrgId(orgId);
        entry.setWorkspaceId(workspaceId);
        entry.setAction(action);
        entry.setEntityType("person");
        entry.setEntityId(personId);
        entry.setActorLabel("Actor");
        entry.setOutcome("success");
        entry.setSummary("summary");
        entry.setChainScopeType("workspace");
        entry.setChainScopeId(workspaceId);
        entry.setChainIndex(nextChainIndex);
        entry.setPrevHash(hash(nextChainIndex - 1));
        entry.setRowHash(hash(nextChainIndex));
        nextChainIndex++;
        auditLogMapper.insert(entry);
    }

    private static String hash(long index) {
        return String.format("%064d", index);
    }
}
