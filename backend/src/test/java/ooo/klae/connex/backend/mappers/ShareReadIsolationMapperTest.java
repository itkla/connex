package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;

/**
 * Read-path org-ceiling proof (#316): the owned-or-shared visibility predicates
 * grant a shared-in record only when the owner and grantee workspaces share an
 * organization. Share rows are inserted directly (bypassing {@code ShareService}
 * and the write-path SQL ceiling) to model a legacy or out-of-band cross-org row
 * — the read path must refuse it on its own, symmetrically with the write path.
 */
class ShareReadIsolationMapperTest extends AbstractMapperTest {

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void crossOrgCompanyShare_grantsNoReadVisibility() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Company company = newCompanyIn(foreign);
        insertShare("company_share", "company_id", company.getId(), workspace.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()),
            "a cross-org share row must not make the record readable");
        assertFalse(companyMapper.exists(workspace.getId(), company.getId()));
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream()
            .noneMatch(c -> c.getId() == company.getId()));
        assertTrue(companyMapper.search(workspace.getId(), "%").stream()
            .noneMatch(c -> c.getId() == company.getId()));
    }

    @Test
    void sameOrgCompanyShare_grantsReadVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompanyIn(sibling);
        insertShare("company_share", "company_id", company.getId(), workspace.getId());

        assertTrue(companyMapper.exists(workspace.getId(), company.getId()),
            "a same-org share row remains readable (positive control)");
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream()
            .anyMatch(c -> c.getId() == company.getId()));
    }

    @Test
    void crossOrgPersonShare_grantsNoReadVisibility() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person person = newPersonIn(foreign);
        insertShare("person_share", "person_id", person.getId(), workspace.getId());

        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()));
        assertFalse(personMapper.exists(workspace.getId(), person.getId()));
    }

    @Test
    void crossOrgPipelineShare_grantsNoReadVisibility() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Pipeline pipeline = newPipelineIn(foreign);
        Stage stage = newStageIn(foreign, pipeline);
        insertShare("pipeline_share", "pipeline_id", pipeline.getId(), workspace.getId());

        assertFalse(pipelineMapper.pipelineExists(workspace.getId(), pipeline.getId()));
        assertTrue(pipelineMapper.getAllPipelines(workspace.getId()).stream()
            .noneMatch(p -> p.getId() == pipeline.getId()));
        assertTrue(pipelineMapper.getAllStages(workspace.getId()).stream()
            .noneMatch(candidate -> candidate.getId() == stage.getId()));
    }

    @Test
    void sameOrgPipelineShareGrantsBatchStageVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Pipeline pipeline = newPipelineIn(sibling);
        Stage stage = newStageIn(sibling, pipeline);
        insertShare("pipeline_share", "pipeline_id", pipeline.getId(), workspace.getId());

        assertTrue(pipelineMapper.getAllStages(workspace.getId()).stream()
            .anyMatch(candidate -> candidate.getId() == stage.getId()));
    }

    private void insertShare(String table, String fkColumn, int entityId, int granteeWorkspaceId) {
        jdbc().update("INSERT INTO " + table + " (" + fkColumn + ", workspace_id, granted_by, can_edit) "
            + "VALUES (?, ?, ?, ?)", entityId, granteeWorkspaceId, newUser().getId(), false);
    }

    private Organization newOrganization() {
        String s = unique();
        Organization organization = new Organization();
        organization.setName("Org " + s);
        organization.setSlug("org-" + s);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        String s = unique();
        Workspace ws = new Workspace();
        ws.setName("Workspace " + s);
        ws.setSlug("ws-" + s);
        ws.setOrgId(orgId);
        workspaceMapper.insert(ws);
        return ws;
    }

    private int orgIdOf(Workspace ws) {
        Integer orgId = workspaceMapper.getOrgId(ws.getId());
        assertTrue(orgId != null, "test workspace must belong to an organization");
        return orgId;
    }

    private Company newCompanyIn(Workspace ws) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person newPersonIn(Workspace ws) {
        Person person = new Person();
        person.setName("Person " + unique());
        person.setWorkspaceId(ws.getId());
        personMapper.insert(person);
        return person;
    }

    private Pipeline newPipelineIn(Workspace ws) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(ws.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setWorkspaceId(ws.getId());
        stage.setPipeline(pipeline);
        pipelineMapper.insertStage(stage);
        return stage;
    }
}
