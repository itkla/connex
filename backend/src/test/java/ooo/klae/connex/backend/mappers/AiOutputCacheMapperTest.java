package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class AiOutputCacheMapperTest extends AbstractMapperTest {

    @Autowired AiOutputCacheMapper aiOutputCacheMapper;
    @Autowired ActivityMapper activityMapper;
    @Autowired NoteMapper noteMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired PersonEdgeMapper personEdgeMapper;
    @Autowired OrganizationMapper organizationMapper;

    @Test
    void upsert_assignsGeneratedId() {
        AiOutputCache row = save(workspace, "deal.brief", 29, 0, "hash-1", "{\"sections\":[]}", 0);
        assertNotEquals(0, row.getId());
    }

    @Test
    void getBySubject_returnsStoredRow() {
        save(workspace, "deal.brief", 29, 0, "hash-1", "{\"sections\":[{\"title\":\"A\",\"body\":\"B\"}]}", 2);

        AiOutputCache found = aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0);

        assertNotNull(found);
        assertEquals("hash-1", found.getContentHash());
        assertEquals(2, found.getWarnings());
        assertEquals("2026-07-09T18:30:00Z", found.getGeneratedAt());
        assertTrue(found.getPayload().contains("\"title\""));
    }

    @Test
    void getBySubject_nullWhenAbsent() {
        assertNull(aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 999, 0));
    }

    @Test
    void upsert_replacesRowOnSameSubjectKey() {
        save(workspace, "deal.brief", 29, 0, "hash-old", "{\"v\":\"a\"}", 0);
        int firstId = aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0).getId();

        save(workspace, "deal.brief", 29, 0, "hash-new", "{\"v\":\"b\"}", 1);
        AiOutputCache after = aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0);

        assertEquals(firstId, after.getId());
        assertEquals("hash-new", after.getContentHash());
        assertEquals(1, after.getWarnings());
        assertTrue(after.getPayload().contains("\"b\""));
    }

    @Test
    void deleteBySubjectAndContentHash_removesOnlyObservedVersion() {
        save(workspace, "deal.brief", 29, 0, "hash-current", "{}", 0);

        assertEquals(0, aiOutputCacheMapper.deleteBySubjectAndContentHash(
                workspace.getId(), "deal.brief", 29, 0, "hash-stale"));
        assertNotNull(aiOutputCacheMapper.getBySubject(
                workspace.getId(), "deal.brief", 29, 0));
        assertEquals(1, aiOutputCacheMapper.deleteBySubjectAndContentHash(
                workspace.getId(), "deal.brief", 29, 0, "hash-current"));
        assertNull(aiOutputCacheMapper.getBySubject(
                workspace.getId(), "deal.brief", 29, 0));
    }

    @Test
    void secondSubjectDistinguishesRows() {
        save(workspace, "intro.rationale", 29, 0, "hash-a", "{\"rationale\":\"a\"}", 0);
        save(workspace, "intro.rationale", 29, 41, "hash-b", "{\"rationale\":\"b\"}", 0);

        assertTrue(aiOutputCacheMapper.getBySubject(workspace.getId(), "intro.rationale", 29, 0)
                .getPayload().contains("\"a\""));
        assertTrue(aiOutputCacheMapper.getBySubject(workspace.getId(), "intro.rationale", 29, 41)
                .getPayload().contains("\"b\""));
    }

    @Test
    void outputs_areIsolatedByWorkspace() {
        Workspace other = newWorkspace();
        save(workspace, "deal.brief", 29, 0, "hash-here", "{\"where\":\"here\"}", 0);
        save(other, "deal.brief", 29, 0, "hash-there", "{\"where\":\"there\"}", 0);

        assertTrue(aiOutputCacheMapper.getBySubject(workspace.getId(), "deal.brief", 29, 0)
                .getPayload().contains("here"));
        assertTrue(aiOutputCacheMapper.getBySubject(other.getId(), "deal.brief", 29, 0)
                .getPayload().contains("there"));
    }

    @Test
    void deleteForPersonPurgesBriefsForCurrentStructuredDealLinksOnly() {
        Company company = newCompany();
        Person subject = newPerson(company);
        User actor = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal activityDeal = newDeal(pipeline, stage, company);
        Deal noteDeal = newDeal(pipeline, stage, company);
        Deal taskDeal = newDeal(pipeline, stage, company);
        Deal stakeholderDeal = newDeal(pipeline, stage, company);
        Deal unrelatedDeal = newDeal(pipeline, stage, company);
        Workspace sameOrg = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace foreignOrg = newWorkspaceInOrg(newOrganization().getId());
        activityMapper.insert(activity(subject, activityDeal, actor));
        noteMapper.insert(note(subject, noteDeal, actor));
        taskMapper.insert(task(subject, taskDeal, actor));
        dealMapper.addPerson(workspace.getId(), stakeholderDeal.getId(), subject.getId(), null);
        for (Deal deal : new Deal[] {activityDeal, noteDeal, taskDeal, stakeholderDeal}) {
            save(workspace, "deal.brief:en", deal.getId(), 0, "brief-" + deal.getId(), "{}", 0);
            save(workspace, "deal.risk_rationale:en", deal.getId(), 0,
                    "risk-" + deal.getId(), "{}", 0);
        }
        save(workspace, "deal.brief:en", unrelatedDeal.getId(), 0,
                "brief-unrelated", "{}", 0);
        save(workspace, "report.narrative:v4:en", 4242, 0, "report-owner", "{}", 0);
        save(sameOrg, "report.narrative:v4:ja", 4243, 0, "report-sibling", "{}", 0);
        save(foreignOrg, "report.narrative:v4:en", 4244, 0, "report-foreign", "{}", 0);

        assertEquals(7, aiOutputCacheMapper.deleteForPerson(workspace.getId(), subject.getId()));

        assertNull(cached("deal.brief:en", activityDeal));
        assertNull(cached("deal.brief:en", noteDeal));
        assertNull(cached("deal.brief:en", taskDeal));
        assertNull(cached("deal.brief:en", stakeholderDeal));
        assertNull(cached("deal.risk_rationale:en", stakeholderDeal));
        assertNotNull(cached("deal.risk_rationale:en", activityDeal));
        assertNotNull(cached("deal.risk_rationale:en", noteDeal));
        assertNotNull(cached("deal.risk_rationale:en", taskDeal));
        assertNotNull(cached("deal.brief:en", unrelatedDeal));
        assertNull(aiOutputCacheMapper.getBySubject(
                workspace.getId(), "report.narrative:v4:en", 4242, 0));
        assertNull(aiOutputCacheMapper.getBySubject(
                sameOrg.getId(), "report.narrative:v4:ja", 4243, 0));
        assertNotNull(aiOutputCacheMapper.getBySubject(
                foreignOrg.getId(), "report.narrative:v4:en", 4244, 0));
    }

    @Test
    void deleteForPersonPurgesConnectionPersonDealOutputsWithinOrganizationOnly() {
        Company company = newCompany();
        Person stakeholder = newPerson(company);
        Person connected = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), stakeholder.getId(), null);
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(stakeholder.getId(), connected.getId()));
        edge.setTargetPersonId(Math.max(stakeholder.getId(), connected.getId()));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);
        Workspace foreignOrg = newWorkspaceInOrg(newOrganization().getId());
        save(workspace, "deal.brief:en", deal.getId(), 0, "brief-owner", "{}", 0);
        save(workspace, "deal.risk_rationale:en", deal.getId(), 0, "risk-owner", "{}", 0);
        save(foreignOrg, "deal.brief:en", deal.getId(), 0, "brief-foreign", "{}", 0);
        save(foreignOrg, "deal.risk_rationale:en", deal.getId(), 0, "risk-foreign", "{}", 0);

        assertEquals(2, aiOutputCacheMapper.deleteForPerson(workspace.getId(), connected.getId()));

        assertNull(aiOutputCacheMapper.getBySubject(
                workspace.getId(), "deal.brief:en", deal.getId(), 0));
        assertNull(aiOutputCacheMapper.getBySubject(
                workspace.getId(), "deal.risk_rationale:en", deal.getId(), 0));
        assertNotNull(aiOutputCacheMapper.getBySubject(
                foreignOrg.getId(), "deal.brief:en", deal.getId(), 0));
        assertNotNull(aiOutputCacheMapper.getBySubject(
                foreignOrg.getId(), "deal.risk_rationale:en", deal.getId(), 0));
    }

    private AiOutputCache save(
            Workspace ws, String feature, int subjectAId, int subjectBId, String hash, String payload, int warnings) {
        AiOutputCache row = new AiOutputCache();
        row.setWorkspaceId(ws.getId());
        row.setFeature(feature);
        row.setSubjectAId(subjectAId);
        row.setSubjectBId(subjectBId);
        row.setContentHash(hash);
        row.setPayload(payload);
        row.setWarnings(warnings);
        row.setGeneratedAt("2026-07-09T18:30:00Z");
        aiOutputCacheMapper.upsert(row);
        return row;
    }

    private AiOutputCache cached(String feature, Deal deal) {
        return aiOutputCacheMapper.getBySubject(workspace.getId(), feature, deal.getId(), 0);
    }

    private Activity activity(Person person, Deal deal, User actor) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Activity " + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(actor);
        activity.setTimestamp("2026-07-22 09:00:00");
        return activity;
    }

    private Note note(Person person, Deal deal, User actor) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Note " + unique());
        note.setAuthor(actor);
        note.setPerson(person);
        note.setDeal(deal);
        return note;
    }

    private Task task(Person person, Deal deal, User actor) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Task " + unique());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setPosition(0);
        task.setAssignedTo(actor);
        task.setPerson(person);
        task.setDeal(deal);
        return task;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Organization newOrganization() {
        String value = unique();
        Organization organization = new Organization();
        organization.setName("Org " + value);
        organization.setSlug("org-" + value);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        ws.setOrgId(orgId);
        workspaceMapper.insert(ws);
        return ws;
    }

    private int orgIdOf(Workspace ws) {
        Integer orgId = workspaceMapper.getOrgId(ws.getId());
        assertNotNull(orgId);
        return Objects.requireNonNull(orgId);
    }
}
