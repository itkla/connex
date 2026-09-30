package ooo.klae.connex.backend.support;

import java.util.UUID;

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
