package ooo.klae.connex.backend.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Shared entity builders without lifecycle, authentication, or transaction ownership.
 * Callers supply their current workspace on each invocation.
 */
public final class TestEntityFixtures {

    private TestEntityFixtures() {
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Models an already initialized workspace for tests that insert pre-existing data directly.
     * Provisioning tests must use the production service and must not call this helper. */
    public static void seedExistingWorkspacePrivacy(JdbcTemplate jdbc, int workspaceId) {
        jdbc.update("INSERT IGNORE INTO ai_assistant_privacy_fence (organization_id,generation,bootstrap_operation_id)"
                + " SELECT org_id,1,UUID() FROM workspace WHERE id=?", workspaceId);
        jdbc.update("INSERT IGNORE INTO ai_assistant_privacy_operation"
                + " (organization_id,operation_key,content_scope,cutoff_generation,invalidated_generation,reason,operation_fingerprint)"
                + " SELECT f.organization_id,f.bootstrap_operation_id,'organization_assistant_holdings_v2',0,1,'bootstrap',"
                + " UNHEX(SHA2(CONCAT('assistant-bootstrap-v1|',f.organization_id,'|',f.bootstrap_operation_id),256))"
                + " FROM ai_assistant_privacy_fence f JOIN workspace w ON w.org_id=f.organization_id WHERE w.id=?", workspaceId);
        jdbc.update("INSERT IGNORE INTO ai_assistant_privacy_operation_workspace (organization_id,operation_key,workspace_id)"
                + " SELECT w.org_id,f.bootstrap_operation_id,w.id FROM workspace w"
                + " JOIN ai_assistant_privacy_fence f ON f.organization_id=w.org_id WHERE w.id=?", workspaceId);
        jdbc.update("INSERT INTO ai_agent_privacy_barrier (workspace_id) VALUES (?)"
                + " ON DUPLICATE KEY UPDATE workspace_id = workspace_id", workspaceId);
    }

    /** Removes privacy maintenance state after an ordinary-chat fixture's content has been deleted. */
    public static void deleteChatPrivacy(JdbcTemplate jdbc, int workspaceId) {
        jdbc.update("UPDATE ai_agent_privacy_barrier SET latest_operation_key = NULL WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM ai_agent_privacy_object WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM ai_agent_privacy_work WHERE workspace_id = ?", workspaceId);
        jdbc.update("DELETE FROM ai_agent_privacy_barrier WHERE workspace_id = ?", workspaceId);
    }

    /** Inserts a fresh user in the caller's workspace and transaction. */
    public static User newUser(UserMapper userMapper, WorkspaceMapper workspaceMapper, Workspace workspace) {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    /** Inserts a fresh company in the caller's workspace and transaction. */
    public static Company newCompany(CompanyMapper companyMapper, Workspace workspace) {
        String s = unique();
        Company company = new Company();
        company.setName("Company " + s);
        company.setWebsite("https://" + s + ".example.com");
        company.setIndustry("Tech");
        company.setPhone("+81-90-1234-5678");
        company.setAddress("1-1-1 Shinjuku, Tokyo, Japan");
        company.setWorkspaceId(workspace.getId());
        companyMapper.insert(company);
        return company;
    }

    /** Inserts a fresh pipeline in the caller's workspace and transaction. */
    public static Pipeline newPipeline(PipelineMapper pipelineMapper, Workspace workspace) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(workspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    /** Inserts a fresh stage in the caller's workspace and transaction. */
    public static Stage newStage(PipelineMapper pipelineMapper, Workspace workspace, Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setWorkspaceId(workspace.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    /** Inserts a fresh tag in the caller's workspace and transaction. */
    public static Tag newTag(TagMapper tagMapper, Workspace workspace) {
        Tag tag = new Tag();
        tag.setName("tag_" + unique());
        tag.setColor("#abcdef");
        tag.setWorkspaceId(workspace.getId());
        tagMapper.insert(tag);
        return tag;
    }

    /** Inserts a fresh person in the caller's workspace and transaction. */
    public static Person newPerson(PersonMapper personMapper, Workspace workspace, Company company) {
        String s = unique();
        Person person = new Person();
        person.setName("Person " + s);
        person.setEmail(s + ".person@example.com");
        person.setPhone("+81-90-2345-6789");
        person.setTitle("Engineer");
        person.setCompany(company);
        person.setWorkspaceId(workspace.getId());
        personMapper.insert(person);
        return person;
    }
}
