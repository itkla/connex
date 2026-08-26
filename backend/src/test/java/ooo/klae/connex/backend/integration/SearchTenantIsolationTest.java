package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Full HTTP search isolation for sibling workspaces and foreign organizations. */
@SpringBootTest
@Transactional
class SearchTenantIsolationTest {

    private static final String PASSWORD = "Search-Tenant-Pw1!";
    private static final List<String> RESULT_COLLECTIONS = List.of(
            "companies", "people", "deals", "pipelines", "tags",
            "activities", "notes", "tasks", "users", "attachments",
            "products", "campaigns", "reports", "documentTemplates", "documents", "workflows");

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CompanyMapper companyMapper;
    @Autowired private DealDocumentMapper dealDocumentMapper;
    @Autowired private DealMapper dealMapper;
    @Autowired private DocumentTemplateMapper documentTemplateMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PipelineMapper pipelineMapper;
    @Autowired private ProductMapper productMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void searchExcludesOwnerDataInSiblingAndForeignOrganizationWorkspaces() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        Organization ownerOrganization = newOrganization();
        Workspace ownerWorkspace = newWorkspace(ownerOrganization);
        Workspace siblingWorkspace = newWorkspace(ownerOrganization);
        Workspace foreignWorkspace = newWorkspace(newOrganization());
        User actor = newMember(ownerWorkspace);
        workspaceMapper.addMember(siblingWorkspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(foreignWorkspace.getId(), actor.getId(), "member");
        String sentinel = "SearchMatrix" + unique();
        Company company = newCompany(ownerWorkspace, sentinel);
        Person person = newPerson(ownerWorkspace, company, sentinel);
        Pipeline pipeline = newPipeline(ownerWorkspace, sentinel);
        Stage stage = newStage(ownerWorkspace, pipeline);
        Deal deal = newDeal(ownerWorkspace, company, pipeline, stage, sentinel);
        Tag tag = newTag(ownerWorkspace, sentinel);
        Activity activity = newActivity(ownerWorkspace, actor, sentinel);
        Note note = newNote(ownerWorkspace, actor, sentinel);
        Task task = newTask(ownerWorkspace, actor, sentinel);
        User searchableMember = newSearchableMember(ownerWorkspace, sentinel);
        Attachment attachment = newAttachment(ownerWorkspace, company, actor, sentinel);
        Product product = newProduct(ownerWorkspace, sentinel);
        Campaign campaign = newCampaign(ownerWorkspace, sentinel);
        ReportDefinition report = newReportDefinition(ownerWorkspace, sentinel);
        DocumentTemplate template = newDocumentTemplate(ownerWorkspace, sentinel);
        DealDocument document = newDealDocument(ownerWorkspace, deal, sentinel);
        Workflow workflow = newWorkflow(ownerWorkspace, sentinel);
        workspaceMapper.updateMemberRole(ownerWorkspace.getId(), actor.getId(), "owner");
        workspaceMapper.updateMemberRole(siblingWorkspace.getId(), actor.getId(), "owner");
        workspaceMapper.updateMemberRole(foreignWorkspace.getId(), actor.getId(), "owner");
        MockHttpSession session = login(actor.getUsername());

        mockMvc.perform(get("/api/search")
                        .queryParam("query", sentinel)
                        .header("X-Workspace-Id", ownerWorkspace.getId())
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].id").value(company.getId()))
                .andExpect(jsonPath("$.people[0].id").value(person.getId()))
                .andExpect(jsonPath("$.deals[0].id").value(deal.getId()))
                .andExpect(jsonPath("$.pipelines[0].id").value(pipeline.getId()))
                .andExpect(jsonPath("$.tags[0].id").value(tag.getId()))
                .andExpect(jsonPath("$.activities[0].id").value(activity.getId()))
                .andExpect(jsonPath("$.notes[0].id").value(note.getId()))
                .andExpect(jsonPath("$.tasks[0].id").value(task.getId()))
                .andExpect(jsonPath("$.users[0].id").value(searchableMember.getId()))
                .andExpect(jsonPath("$.attachments[0].id").value(attachment.getId()))
                .andExpect(jsonPath("$.products[0].id").value(product.getId()))
                .andExpect(jsonPath("$.campaigns[0].id").value(campaign.getId()))
                .andExpect(jsonPath("$.reports[0].id").value(report.getId()))
                .andExpect(jsonPath("$.documentTemplates[0].id").value(template.getId()))
                .andExpect(jsonPath("$.documents[0].id").value(document.getId()))
                .andExpect(jsonPath("$.workflows[0].id").value(workflow.getId()));

        for (Workspace unauthorized : List.of(siblingWorkspace, foreignWorkspace)) {
            var result = mockMvc.perform(get("/api/search")
                            .queryParam("query", sentinel)
                            .header("X-Workspace-Id", unauthorized.getId())
                            .session(session))
                    .andExpect(status().isOk());
            for (String collection : RESULT_COLLECTIONS) {
                result.andExpect(jsonPath("$." + collection + ".length()").value(0));
            }
        }
    }

    private Product newProduct(Workspace workspace, String sentinel) {
        Product product = new Product();
        product.setWorkspaceId(workspace.getId());
        product.setName(sentinel + " Product");
        product.setSku("SKU-" + unique());
        product.setActive(true);
        product.setUnitPrice(BigDecimal.ONE);
        product.setCurrency("USD");
        product.setBillingFrequency("one_time");
        productMapper.insert(product);
        return product;
    }

    private Campaign newCampaign(Workspace workspace, String sentinel) {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName(sentinel + " Campaign");
        campaign.setType("email");
        campaign.setStatus("draft");
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private ReportDefinition newReportDefinition(Workspace workspace, String sentinel) {
        ReportDefinition definition = new ReportDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setName(sentinel + " Report");
        definition.setCadence("monthly");
        definition.setConfigJson("{}");
        reportMapper.insertDefinition(definition);
        return definition;
    }

    private DocumentTemplate newDocumentTemplate(Workspace workspace, String sentinel) {
        DocumentTemplate template = new DocumentTemplate();
        template.setWorkspaceId(workspace.getId());
        template.setName(sentinel + " Template");
        template.setType("quote");
        template.setLocale("en");
        template.setActive(true);
        documentTemplateMapper.insert(template);
        return template;
    }

    private DealDocument newDealDocument(Workspace workspace, Deal deal, String sentinel) {
        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus("draft");
        document.setVersion(1);
        document.setTitle(sentinel + " Document");
        document.setContent("{}");
        document.setCurrency("USD");
        dealDocumentMapper.insert(document);
        return document;
    }

    private Workflow newWorkflow(Workspace workspace, String sentinel) {
        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(workspace.getId());
        workflow.setName(sentinel + " Workflow");
        workflow.setEnabled(false);
        workflow.setRuntimeOwner("legacy");
        workflow.setDraftRevision(0);
        workflow.setDraftRecordType("company");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftDefinitionJson("{\"schemaVersion\":1}");
        workflow.setDraftCanvasJson("{}");
        workflowMapper.insert(workflow);
        return workflow;
    }

    private Company newCompany(Workspace workspace, String sentinel) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(sentinel + " Company");
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(Workspace workspace, Company company, String sentinel) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(sentinel + " Person");
        person.setEmail(unique() + "@example.com");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private Pipeline newPipeline(Workspace workspace, String sentinel) {
        Pipeline pipeline = new Pipeline();
        pipeline.setWorkspaceId(workspace.getId());
        pipeline.setName(sentinel + " Pipeline");
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStage(Workspace workspace, Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setWorkspaceId(workspace.getId());
        stage.setPipeline(pipeline);
        stage.setName("Search matrix stage " + unique());
        stage.setPosition(0);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Deal newDeal(
            Workspace workspace,
            Company company,
            Pipeline pipeline,
            Stage stage,
            String sentinel) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName(sentinel + " Deal");
        deal.setValue(BigDecimal.ONE);
        deal.setCurrency("USD");
        deal.setCompanyId(company.getId());
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private Tag newTag(Workspace workspace, String sentinel) {
        Tag tag = new Tag();
        tag.setWorkspaceId(workspace.getId());
        tag.setName(sentinel + " Tag");
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        return tag;
    }

    private Activity newActivity(Workspace workspace, User actor, String sentinel) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject(sentinel + " Activity");
        activity.setCreatedBy(actor);
        activity.setTimestamp("2026-08-08 10:00:00");
        activityMapper.insert(activity);
        return activity;
    }

    private Note newNote(Workspace workspace, User actor, String sentinel) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent(sentinel + " Note");
        note.setAuthor(actor);
        noteMapper.insert(note);
        return note;
    }

    private Task newTask(Workspace workspace, User actor, String sentinel) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription(sentinel + " Task");
        task.setCompleted(false);
        task.setStatus("todo");
        task.setPosition(0);
        task.setAssignedTo(actor);
        taskMapper.insert(task);
        return task;
    }

    private User newSearchableMember(Workspace workspace, String sentinel) {
        String suffix = unique();
        User user = new User();
        user.setUsername("search_target_" + suffix);
        user.setDisplayName(sentinel + " User");
        user.setEmail(suffix + ".target@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private Attachment newAttachment(
            Workspace workspace,
            Company company,
            User actor,
            String sentinel) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspace.getId());
        attachment.setEntityType("company");
        attachment.setEntityId(company.getId());
        attachment.setFileName(sentinel + " Attachment.pdf");
        attachment.setUrl("https://example.com/" + unique() + ".pdf");
        attachment.setContentType("application/pdf");
        attachment.setSize(1L);
        attachment.setUploadedBy(actor);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Search matrix " + unique());
        organization.setSlug("search-matrix-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(Organization organization) {
        Workspace workspace = new Workspace();
        workspace.setName("Search matrix " + unique());
        workspace.setSlug("search-matrix-" + unique());
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        return workspace;
    }

    private User newMember(Workspace workspace) {
        String suffix = unique();
        User user = new User();
        user.setUsername("search_matrix_" + suffix);
        user.setDisplayName("Search matrix actor " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private MockHttpSession login(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "login did not establish a search matrix session");
        return session;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
