package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
@Import(AbstractServiceTest.ApprovalMutationTestConfiguration.class)
abstract class AbstractServiceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ApprovalMutationTestConfiguration {
        @Bean
        @Primary
        ApprovalMutationRetryService testApprovalMutationRetryService(
                PlatformTransactionManager transactionManager) {
            return new JoiningApprovalMutationRetryService(transactionManager);
        }
    }

    static final class JoiningApprovalMutationRetryService
            extends ApprovalMutationRetryService {
        private final PlatformTransactionManager transactionManager;

        JoiningApprovalMutationRetryService(PlatformTransactionManager transactionManager) {
            super(transactionManager);
            this.transactionManager = transactionManager;
        }

        @Override
        public <T> T execute(Supplier<T> mutation) {
            Objects.requireNonNull(mutation, "mutation");
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    TransactionTemplate template = new TransactionTemplate(transactionManager);
                    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    T result = template.execute(status -> mutation.get());
                    return Objects.requireNonNull(result, "Approval mutation returned no result");
                } catch (ApprovalRecipientSetChangedException exception) {
                    if (attempt == MAX_ATTEMPTS) {
                        throw new ConflictException("Approval changed; refresh and try again");
                    }
                }
            }
            throw new IllegalStateException("Approval mutation retry loop did not terminate");
        }
    }

    @Autowired protected UserMapper userMapper;
    @Autowired protected CompanyMapper companyMapper;
    @Autowired protected PipelineMapper pipelineMapper;
    @Autowired protected TagMapper tagMapper;
    @Autowired protected PersonMapper personMapper;
    @Autowired protected DealMapper dealMapper;
    @Autowired protected ActivityMapper activityMapper;
    @Autowired protected NoteMapper noteMapper;
    @Autowired protected TaskMapper taskMapper;
    @Autowired protected WorkspaceMapper workspaceMapper;
    @Autowired protected NotificationMapper notificationMapper;
    @Autowired protected TenantContext tenantContext;

    protected Workspace workspace;
    protected User currentUser;

    @BeforeEach
    void setUpWorkspaceAndAuthentication() {
        workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Test Workspace");
            workspace.setSlug("default");
            workspaceMapper.insert(workspace);
        }
        currentUser = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId());
    }

    protected void authenticateAs(User user, int workspaceId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
        int orgId = workspaceServiceOrgId(workspaceId);
        String role = workspaceMapper.getRole(workspaceId, user.getId());
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(workspaceId, orgId, user.getId(), role == null ? "member" : role, null);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        clearRequestContext();
    }

    protected void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private int workspaceServiceOrgId(int workspaceId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        return orgId == null ? workspaceId : orgId;
    }

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
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    protected User newPendingMember() {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addPendingMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    protected Notification newNotification(int workspaceId, int recipientId) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspaceId);
        notification.setRecipientId(recipientId);
        notification.setType("task.assigned");
        notification.setCategory("tasks");
        notification.setSeverity("info");
        notification.setTemplateVersion(1);
        notification.setTitle("Notification " + unique());
        notification.setDedupeKey("fixture-" + unique());
        notification.setTriggeredAt("2026-07-11 00:00:00");
        notificationMapper.upsert(notification);
        return notification;
    }

    protected Company newCompany() {
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

    protected Pipeline newPipeline() {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(workspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    protected Stage newStage(Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setWorkspaceId(workspace.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    protected Tag newTag() {
        Tag tag = new Tag();
        tag.setName("tag_" + unique());
        tag.setColor("#abcdef");
        tag.setWorkspaceId(workspace.getId());
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
        person.setWorkspaceId(workspace.getId());
        personMapper.insert(person);
        return person;
    }

    protected Deal newDeal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setOwnerId(currentUser == null ? null : currentUser.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        dealMapper.insert(deal);
        return deal;
    }

    protected Activity newActivity(User createdBy, Person person, Deal deal) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
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
        note.setWorkspaceId(workspace.getId());
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
        task.setWorkspaceId(workspace.getId());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate("2024-12-31");
        task.setAssignedTo(assignedTo);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        return task;
    }
}
