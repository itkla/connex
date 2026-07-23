package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.services.ScoringService;

class TaskScoringShareIsolationMapperTest extends AbstractMapperTest {

    @Autowired private DataSource dataSource;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private ScoringService scoringService;
    @Autowired private TaskMapper taskMapper;

    @Test
    void companyScoringAcceptsSameOrgPersonShareAndRejectsCrossOrgShare() {
        User assignee = newUser();
        Company sameOrgCompany = newCompanyIn(workspace);
        Company crossOrgCompany = newCompanyIn(workspace);
        Company dealCompany = newCompanyIn(workspace);
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person sameOrgPerson = newPersonIn(sibling, sameOrgCompany);
        Person crossOrgPerson = newPersonIn(foreign, crossOrgCompany);
        insertPersonShare(sameOrgPerson.getId(), assignee.getId());
        insertPersonShare(crossOrgPerson.getId(), assignee.getId());
        taskMapper.insert(newTask(sameOrgPerson, null, assignee));
        taskMapper.insert(newTask(crossOrgPerson, null, assignee));

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, dealCompany);
        taskMapper.insert(newTask(null, deal, assignee));

        List<RelationshipTemperatureDto> scores = scoringService.scoreCompanies(
            workspace.getId(), Set.of(
                sameOrgCompany.getId(), crossOrgCompany.getId(), dealCompany.getId()));
        Map<Integer, RelationshipTemperatureDto> scoresByCompany = scores.stream()
            .collect(Collectors.toMap(RelationshipTemperatureDto::getId, score -> score));

        assertEquals(3, scores.size());
        assertEquals(1, scoresByCompany.get(sameOrgCompany.getId()).getTouchCount());
        assertEquals(0, scoresByCompany.get(crossOrgCompany.getId()).getTouchCount());
        assertEquals(1, scoresByCompany.get(dealCompany.getId()).getTouchCount());
    }

    private void insertPersonShare(int personId, int grantedBy) {
        new JdbcTemplate(dataSource).update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit) VALUES (?, ?, ?, ?)",
            personId, workspace.getId(), grantedBy, false);
    }

    private Organization newOrganization() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Org " + suffix);
        organization.setSlug("org-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        String suffix = unique();
        Workspace target = new Workspace();
        target.setName("Workspace " + suffix);
        target.setSlug("ws-" + suffix);
        target.setOrgId(orgId);
        workspaceMapper.insert(target);
        return target;
    }

    private int orgIdOf(Workspace target) {
        Integer orgId = workspaceMapper.getOrgId(target.getId());
        assertTrue(orgId != null, "test workspace must belong to an organization");
        return orgId;
    }

    private Company newCompanyIn(Workspace target) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person newPersonIn(Workspace target, Company company) {
        Person person = new Person();
        person.setName("Person " + unique());
        person.setWorkspaceId(target.getId());
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private Task newTask(Person person, Deal deal, User assignee) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Task " + unique());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate("2026-07-31");
        task.setAssignedTo(assignee);
        task.setPerson(person);
        task.setDeal(deal);
        return task;
    }
}
