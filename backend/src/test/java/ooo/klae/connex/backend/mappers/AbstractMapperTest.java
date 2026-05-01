package ooo.klae.connex.backend.mappers;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractMapperTest {

    @Autowired protected UserMapper userMapper;
    @Autowired protected CompanyMapper companyMapper;
    @Autowired protected PipelineMapper pipelineMapper;
    @Autowired protected TagMapper tagMapper;
    @Autowired protected PersonMapper personMapper;
    @Autowired protected DealMapper dealMapper;

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /* BELOW THIS LINE ARE HELPER METHODS FOR CREATING TEST DATA. Not to be confused with mapper methods. Don't get it mixed up chief. */
    protected User newUser() {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("hash_" + s);
        userMapper.insert(user);
        return user;
    }

    protected Company newCompany() {
        String s = unique();
        Company company = new Company();
        company.setName("Company " + s);
        company.setWebsite("https://" + s + ".example.com");
        company.setIndustry("Tech");
        company.setPhone("+81-90-1234-5678");
        company.setAddress("1-1-1 Shinjuku, Tokyo, Japan");
        companyMapper.insert(company);
        return company;
    }

    protected Pipeline newPipeline() {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    protected Stage newStage(Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    protected Tag newTag() {
        Tag tag = new Tag();
        tag.setName("tag_" + unique());
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        return tag;
    }

    protected Person newPerson(Company company) {
        String s = unique();
        Person person = new Person();
        person.setName("Person " + s);
        person.setEmail(s + ".person@example.com");
        person.setPhone("+81-90-2345-6789");
        person.setTitle("Engineer");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    protected Deal newDeal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipeline(pipeline);
        deal.setStage(stage);
        deal.setCompany(company);
        dealMapper.insert(deal);
        return deal;
    }
}
