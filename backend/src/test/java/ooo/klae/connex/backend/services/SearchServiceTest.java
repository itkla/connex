package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.util.LikePattern;

@Transactional(isolation = Isolation.READ_COMMITTED)
class SearchServiceTest extends AbstractServiceTest {

    @Autowired private SearchService searchService;
    @Autowired private NoteService noteService;
    @Autowired private TaskService taskService;
    @Autowired private ActivityService activityService;
    @Autowired private ProductMapper productMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private DocumentTemplateMapper documentTemplateMapper;
    @Autowired private DealDocumentMapper dealDocumentMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private RoleMapper roleMapper;
    @MockitoSpyBean private AttachmentMapper attachmentMapper;

    @Test
    void blankQueryReturnsEmptyResults() {
        SearchResultsDto results = searchService.search("   ");
        assertTrue(results.getCompanies().isEmpty());
        assertTrue(results.getProducts().isEmpty());
        assertTrue(results.getCampaigns().isEmpty());
        assertTrue(results.getReports().isEmpty());
        assertTrue(results.getDocumentTemplates().isEmpty());
        assertTrue(results.getDocuments().isEmpty());
        assertTrue(results.getWorkflows().isEmpty());
    }

    @Test
    void overlongQueryIsRejected() {
        String tooLong = "a".repeat(201);
        assertThrows(BadRequestException.class, () -> searchService.search(tooLong));
    }

    @Test
    void matchingCompanyIsFoundInTheActiveWorkspace() {
        Company company = newCompany();
        SearchResultsDto results = searchService.search(company.getName());
        assertTrue(results.getCompanies().stream().anyMatch(c -> c.getId() == company.getId()));
    }

    @Test
    void attachmentSearchReceivesCurrentUserId() {
        String query = "attachment-user-" + unique();

        searchService.search(query);

        verify(attachmentMapper).search(
            workspace.getId(), LikePattern.containing(query), currentUser.getId());
    }

    @Test
    void visibleNoteMetadataMatchesRemainSearchable() {
        Note note = new Note();
        note.setContent("metadata-only body " + unique());
        note.setTitle("metadata-only title " + unique());
        note.setVisibility("workspace");
        Note created = noteService.create(note);

        SearchResultsDto results = searchService.search(currentUser.getUsername());

        assertTrue(results.getNotes().stream().anyMatch(result -> result.getId() == created.getId()));
    }

    @Test
    void authorMetadataMatchesPreserveNoteRecencyOrder() {
        String marker = "author-order-" + unique();
        User firstAuthor = newUser();
        firstAuthor.setDisplayName(marker + " first");
        userMapper.update(firstAuthor);
        User secondAuthor = newUser();
        secondAuthor.setDisplayName(marker + " second");
        userMapper.update(secondAuthor);

        authenticateAs(firstAuthor, workspace.getId());
        Note firstNote = new Note();
        firstNote.setContent("first author-only body " + unique());
        firstNote.setVisibility("workspace");
        Note firstCreated = noteService.create(firstNote);

        authenticateAs(secondAuthor, workspace.getId());
        Note secondNote = new Note();
        secondNote.setContent("second author-only body " + unique());
        secondNote.setVisibility("workspace");
        Note secondCreated = noteService.create(secondNote);

        authenticateAs(currentUser, workspace.getId());
        SearchResultsDto results = searchService.search(marker);

        assertEquals(
            List.of(secondCreated.getId(), firstCreated.getId()),
            results.getNotes().stream().map(note -> note.getId()).toList());
    }

    @Test
    void visibleNoteMetadataMatchesSurviveRedactedContentMatches() {
        String query = currentUser.getUsername();
        Note privateTarget = new Note();
        privateTarget.setContent("private body");
        privateTarget.setTitle(query);
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        Note source = new Note();
        source.setContent("Visible source [" + query + "](note:" + target.getId() + ")");
        source.setVisibility("workspace");
        Note created = noteService.create(source);

        User other = newUser();
        authenticateAs(other, workspace.getId());

        SearchResultsDto results = searchService.search(query);

        assertTrue(results.getNotes().stream().anyMatch(result -> result.getId() == created.getId()));
    }

    /** A private target's frozen label cannot be used as a global-search oracle by another member. */
    @Test
    void privateNoteTargetLabelsAreNotSearchableByNonAuthor() {
        String label = "Secret Search " + unique();
        Note privateTarget = new Note();
        privateTarget.setContent("private body");
        privateTarget.setTitle(label);
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        Task task = new Task();
        task.setDescription("Review [" + label + "](note:" + target.getId() + ")");
        task.setAssignedTo(currentUser);
        taskService.create(task);

        Activity activity = new Activity();
        activity.setType("call");
        activity.setSubject("Source activity");
        activity.setNotes("Discussed [" + label + "](note:" + target.getId() + ")");
        activityService.create(activity);

        Note sourceNote = new Note();
        sourceNote.setContent("Source note [" + label + "](note:" + target.getId() + ")");
        sourceNote.setVisibility("workspace");
        noteService.create(sourceNote);

        User other = newUser();
        authenticateAs(other, workspace.getId());

        SearchResultsDto results = searchService.search(label);

        assertTrue(results.getNotes().isEmpty());
        assertTrue(results.getTasks().isEmpty());
        assertTrue(results.getActivities().isEmpty());
    }

    @Test
    void redactedTaskCandidatesDoNotHideLaterVisibleMatches() {
        String label = "Visible Search " + unique();
        Note privateTarget = new Note();
        privateTarget.setContent("private body");
        privateTarget.setTitle(label);
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        for (int index = 0; index < 25; index++) {
            Task hidden = new Task();
            hidden.setDescription("Hidden [" + label + "](note:" + target.getId() + ") " + index);
            hidden.setAssignedTo(currentUser);
            hidden.setDueDate(String.format("2024-01-%02d", index % 28 + 1));
            taskService.create(hidden);
        }

        Task visible = new Task();
        visible.setDescription("Visible task " + label);
        visible.setAssignedTo(currentUser);
        visible.setDueDate("2025-01-01");
        Task created = taskService.create(visible);

        User other = newUser();
        authenticateAs(other, workspace.getId());

        SearchResultsDto results = searchService.search(label);

        assertTrue(results.getTasks().stream().anyMatch(taskResult -> taskResult.getId() == created.getId()));
    }

    @Test
    void matchingProductIsFound() {
        String sentinel = sentinel();
        Product product = newProduct(sentinel);
        SearchResultsDto results = searchService.search(sentinel);
        assertEquals(1, results.getProducts().size());
        assertEquals(product.getId(), results.getProducts().getFirst().id());
    }

    @Test
    void matchingCampaignIsFound() {
        String sentinel = sentinel();
        Campaign campaign = newCampaign(sentinel);
        SearchResultsDto results = searchService.search(sentinel);
        assertEquals(1, results.getCampaigns().size());
        assertEquals(campaign.getId(), results.getCampaigns().getFirst().id());
    }

    @Test
    void matchingReportIsFound() {
        String sentinel = sentinel();
        ReportDefinition definition = newReportDefinition(sentinel);
        SearchResultsDto results = searchService.search(sentinel);
        assertEquals(1, results.getReports().size());
        assertEquals(definition.getId(), results.getReports().getFirst().id());
    }

    @Test
    void matchingDocumentTemplateIsFound() {
        String sentinel = sentinel();
        DocumentTemplate template = newDocumentTemplate(sentinel);
        SearchResultsDto results = searchService.search(sentinel);
        assertEquals(1, results.getDocumentTemplates().size());
        assertEquals(template.getId(), results.getDocumentTemplates().getFirst().id());
    }

    @Test
    void matchingGeneratedDocumentIsFoundAndNamesItsDeal() {
        String sentinel = sentinel();
        Deal deal = newDealFixture();
        DealDocument document = newDealDocument(deal, sentinel);
        SearchResultsDto results = searchService.search(sentinel);
        assertEquals(1, results.getDocuments().size());
        assertEquals(document.getId(), results.getDocuments().getFirst().id());
        assertEquals(deal.getId(), results.getDocuments().getFirst().dealId());
        assertEquals(deal.getName(), results.getDocuments().getFirst().dealName());
    }

    @Test
    void matchingWorkflowIsFound() {
        String sentinel = sentinel();
        Workflow workflow = newWorkflow(sentinel, false);
        SearchResultsDto results = searchService.search(sentinel);
        assertEquals(1, results.getWorkflows().size());
        assertEquals(workflow.getId(), results.getWorkflows().getFirst().id());
    }

    @Test
    void archivedWorkflowIsNotFound() {
        String sentinel = sentinel();
        newWorkflow(sentinel, true);
        assertTrue(searchService.search(sentinel).getWorkflows().isEmpty());
    }

    /**
     * The permission-gated groups must go empty — not fail the whole query — for a role that cannot
     * reach their own read endpoints. A built-in member holds {@code CAMPAIGN_VIEW} and
     * {@code REPORT_READ} but not {@code RULE_MANAGE}.
     */
    @Test
    void restrictedRoleSeesNoWorkflowHitsButStillSeesItsOwnGroups() {
        String sentinel = sentinel();
        newWorkflow(sentinel, false);
        newCampaign(sentinel);
        newProduct(sentinel);

        SearchResultsDto asOwner = searchService.search(sentinel);
        assertEquals(1, asOwner.getWorkflows().size());
        assertEquals(1, asOwner.getCampaigns().size());

        demoteCurrentUserTo("member");
        SearchResultsDto asMember = searchService.search(sentinel);
        assertTrue(asMember.getWorkflows().isEmpty());
        assertEquals(1, asMember.getCampaigns().size());
        assertEquals(1, asMember.getProducts().size());
    }

    /** A role holding neither campaign nor report read sees neither group. */
    @Test
    void roleWithoutCampaignAndReportReadSeesNeitherGroup() {
        String sentinel = sentinel();
        newCampaign(sentinel);
        newReportDefinition(sentinel);
        newProduct(sentinel);

        demoteCurrentUserToCustomRoleWithoutMarketingReads();
        SearchResultsDto results = searchService.search(sentinel);
        assertTrue(results.getCampaigns().isEmpty());
        assertTrue(results.getReports().isEmpty());
        assertEquals(1, results.getProducts().size());
    }

    private void demoteCurrentUserTo(String role) {
        workspaceMapper.updateMemberRole(workspace.getId(), currentUser.getId(), role);
        authenticateAs(currentUser, workspace.getId());
    }

    private void demoteCurrentUserToCustomRoleWithoutMarketingReads() {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("search_" + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of("PERSON_CREATE"));
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), role.getId());
        authenticateAs(currentUser, workspace.getId());
    }

    private static String sentinel() {
        return "SearchGroup" + unique();
    }

    private Product newProduct(String sentinel) {
        Product product = new Product();
        product.setWorkspaceId(workspace.getId());
        product.setName(sentinel + " Product");
        product.setSku("SKU-" + unique());
        product.setActive(true);
        product.setUnitPrice(new BigDecimal("10.00"));
        product.setCurrency("JPY");
        product.setBillingFrequency("one_time");
        productMapper.insert(product);
        return product;
    }

    private Campaign newCampaign(String sentinel) {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName(sentinel + " Campaign");
        campaign.setType("email");
        campaign.setStatus("draft");
        campaign.setCreatedById(currentUser.getId());
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private ReportDefinition newReportDefinition(String sentinel) {
        ReportDefinition definition = new ReportDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setName(sentinel + " Report");
        definition.setCadence("monthly");
        definition.setConfigJson("{}");
        definition.setCreatedBy(currentUser.getId());
        reportMapper.insertDefinition(definition);
        return definition;
    }

    private DocumentTemplate newDocumentTemplate(String sentinel) {
        DocumentTemplate template = new DocumentTemplate();
        template.setWorkspaceId(workspace.getId());
        template.setName(sentinel + " Template");
        template.setType("quote");
        template.setLocale("en");
        template.setActive(true);
        documentTemplateMapper.insert(template);
        return template;
    }

    private Deal newDealFixture() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        return newDeal(pipeline, stage, company);
    }

    private DealDocument newDealDocument(Deal deal, String sentinel) {
        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus("draft");
        document.setVersion(1);
        document.setTitle(sentinel + " Document");
        document.setContent("{}");
        document.setCurrency("JPY");
        document.setCreatedBy(currentUser.getId());
        dealDocumentMapper.insert(document);
        return document;
    }

    private Workflow newWorkflow(String sentinel, boolean archived) {
        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(workspace.getId());
        workflow.setName(sentinel + " Workflow");
        workflow.setDescription("fixture");
        workflow.setEnabled(false);
        workflow.setRuntimeOwner("legacy");
        workflow.setDraftRevision(0);
        workflow.setDraftRecordType("company");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftDefinitionJson("{\"schemaVersion\":1}");
        workflow.setDraftCanvasJson("{}");
        workflow.setCreatedById(currentUser.getId());
        if (archived) {
            workflow.setArchivedAt(LocalDateTime.now());
        }
        workflowMapper.insert(workflow);
        return workflow;
    }
}
