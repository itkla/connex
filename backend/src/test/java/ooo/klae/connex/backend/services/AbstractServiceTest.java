package ooo.klae.connex.backend.services;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractServiceTest {

    @Autowired protected UserMapper userMapper;
    @Autowired protected CompanyMapper companyMapper;
    @Autowired protected PipelineMapper pipelineMapper;
    @Autowired protected TagMapper tagMapper;
    @Autowired protected PersonMapper personMapper;
    @Autowired protected DealMapper dealMapper;
    @Autowired protected ActivityMapper activityMapper;
    @Autowired protected NoteMapper noteMapper;
    @Autowired protected TaskMapper taskMapper;

    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

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
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    protected Activity newActivity(User createdBy, Person person, Deal deal) {
        Activity activity = new Activity();
        activity.setType("call");
        activity.setSubject("subj_" + unique());
        activity.setNotes("notes_" + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(createdBy);
        activity.setTimestamp("2024-06-01 10:00:00");
        activityMapper.insert(activity);
        return activity;
    }

    protected Note newNote(User author, Person person, Deal deal) {
        Note note = new Note();
        note.setContent("note_" + unique());
        note.setAuthor(author);
        note.setPerson(person);
        note.setDeal(deal);
        noteMapper.insert(note);
        return note;
    }

    protected Task newTask(User assignedTo, Person person, Deal deal) {
        Task task = new Task();
        task.setDescription("task_" + unique());
        task.setCompleted(false);
        task.setDueDate("2024-12-31 17:00:00");
        task.setAssignedTo(assignedTo);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        return task;
    }
}
