package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

/** Cross-organization complement to the per-mapper sibling-workspace record isolation tests. */
class CoreRecordTenantIsolationTest extends AbstractMapperTest {

    @Autowired private ActivityMapper activityMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private OrganizationMapper organizationMapper;

    @Test
    void companyReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        Company company = newCompany();

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(companyMapper.getCompanyById(unauthorized.getId(), company.getId()));
            assertEquals(0, companyMapper.archive(unauthorized.getId(), company.getId()));
        }

        assertNotNull(companyMapper.getCompanyById(workspace.getId(), company.getId()));
    }

    @Test
    void contactReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        Person person = newPerson(newCompany());

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(personMapper.getPersonById(unauthorized.getId(), person.getId()));
            assertEquals(0, personMapper.archive(unauthorized.getId(), person.getId()));
        }

        assertNotNull(personMapper.getPersonById(workspace.getId(), person.getId()));
    }

    @Test
    void dealReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(dealMapper.getDealById(unauthorized.getId(), deal.getId()));
            assertEquals(0, dealMapper.delete(unauthorized.getId(), deal.getId()));
        }

        assertNotNull(dealMapper.getDealById(workspace.getId(), deal.getId()));
    }

    @Test
    void activityReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        User actor = newUser();
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Tenant matrix " + unique());
        activity.setCreatedBy(actor);
        activity.setTimestamp("2026-08-08 10:00:00");
        activityMapper.insert(activity);

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(activityMapper.getActivityById(unauthorized.getId(), activity.getId()));
            assertEquals(0, activityMapper.delete(unauthorized.getId(), activity.getId()));
        }

        assertNotNull(activityMapper.getActivityById(workspace.getId(), activity.getId()));
    }

    @Test
    void taskReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        User actor = newUser();
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Tenant matrix " + unique());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setPosition(0);
        task.setAssignedTo(actor);
        taskMapper.insert(task);

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(taskMapper.getTaskById(unauthorized.getId(), task.getId()));
            assertEquals(0, taskMapper.complete(
                    unauthorized.getId(), task.getId(), actor.getId(), 0));
        }

        Task unchanged = taskMapper.getTaskById(workspace.getId(), task.getId());
        assertNotNull(unchanged);
        assertFalse(unchanged.isCompleted());
    }

    @Test
    void noteReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        User actor = newUser();
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("Tenant matrix " + unique());
        note.setAuthor(actor);
        noteMapper.insert(note);

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(noteMapper.getNoteById(unauthorized.getId(), note.getId()));
            assertEquals(0, noteMapper.delete(unauthorized.getId(), note.getId()));
        }

        assertNotNull(noteMapper.getNoteById(workspace.getId(), note.getId()));
    }

    private List<Workspace> unauthorizedWorkspaces() {
        Workspace sibling = newWorkspaceInOrg(workspace.getOrgId());
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        return List.of(sibling, foreign);
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        Workspace created = new Workspace();
        created.setName("Record matrix " + unique());
        created.setSlug("record-matrix-" + unique());
        created.setOrgId(orgId);
        workspaceMapper.insert(created);
        return created;
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Record matrix " + unique());
        organization.setSlug("record-matrix-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }
}
