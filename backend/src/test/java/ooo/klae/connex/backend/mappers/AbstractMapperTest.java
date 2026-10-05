package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.BeforeEach;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.support.TestEntityFixtures;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractMapperTest {

    @Autowired protected UserMapper userMapper;
    @Autowired protected CompanyMapper companyMapper;
    @Autowired protected PipelineMapper pipelineMapper;
    @Autowired protected TagMapper tagMapper;
    @Autowired protected PersonMapper personMapper;
    @Autowired protected DealMapper dealMapper;
    @Autowired protected WorkspaceMapper workspaceMapper;

    protected Workspace workspace;

    @BeforeEach
    void setUpWorkspace() {
        workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Test Workspace");
            workspace.setSlug("default");
            workspaceMapper.insert(workspace);
        }
    }

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected User newUser() {
        return TestEntityFixtures.newUser(userMapper, workspaceMapper, workspace);
    }

    protected Company newCompany() {
        return TestEntityFixtures.newCompany(companyMapper, workspace);
    }

    protected Pipeline newPipeline() {
        return TestEntityFixtures.newPipeline(pipelineMapper, workspace);
    }

    protected Stage newStage(Pipeline pipeline, int position) {
        return TestEntityFixtures.newStage(pipelineMapper, workspace, pipeline, position);
    }

    protected Tag newTag() {
        return TestEntityFixtures.newTag(tagMapper, workspace);
    }

    protected Person newPerson(Company company) {
        return TestEntityFixtures.newPerson(personMapper, workspace, company);
    }

    protected Deal newDeal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }
}
