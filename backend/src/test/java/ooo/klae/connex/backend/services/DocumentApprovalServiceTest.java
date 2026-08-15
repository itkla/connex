package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.dto.DocumentApprovalStepDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;

class DocumentApprovalServiceTest extends AbstractServiceTest {

    @Autowired DocumentApprovalService approvalService;
    @Autowired ApprovalPolicyService policyService;
    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;
    @Autowired DocumentApprovalMapper approvalMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private Deal jpyDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        return newDeal(pipeline, stage, company);
    }

    private DocumentTemplate template() {
        DocumentTemplate t = new DocumentTemplate();
        t.setName("Quote template " + unique());
        t.setType("quote");
        t.setLocale("en");
        t.setTitle("Quote for {{company.name}}");
        return templateService.create(t);
    }

    private void addLine(Deal deal, String unitPrice, String quantity) {
        Product p = new Product();
        p.setSku("sku_" + unique());
        p.setName("Product " + unique());
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setCurrency("JPY");
        p.setBillingFrequency("one_time");
        Product product = productService.create(p);
        DealLineItemRequest r = new DealLineItemRequest();
        r.setProductId(product.getId());
        r.setQuantity(new BigDecimal(quantity));
        lineItemService.create(deal.getId(), r);
    }

    private ApprovalPolicyStep step(int requiredCount, String name, User... approvers) {
        ApprovalPolicyStep step = new ApprovalPolicyStep();
        step.setName(name);
        step.setRequiredCount(requiredCount);
        step.setApprovers(approvers.length == 0 ? List.of(anyApprover())
            : java.util.Arrays.stream(approvers).map(this::namedApprover).toList());
        return step;
    }

    private ApprovalStepApprover anyApprover() {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind("any_approver");
        return approver;
    }

    private ApprovalStepApprover namedApprover(User user) {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind("user");
        approver.setUserId(user.getId());
        return approver;
    }

    private ApprovalPolicy chainPolicy(String mode, ApprovalPolicyStep... steps) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Chain policy " + unique());
        policy.setActive(true);
        policy.setMode(mode);
        policy.setSteps(List.of(steps));
        return policyService.create(policy);
    }

    private DocumentApprovalStepDto stepOf(DocumentApprovalDto approval, int order) {
        return approval.steps().stream().filter(s -> s.stepOrder() == order).findFirst().orElseThrow();
    }

    private ApprovalPolicy jpyTotalPolicy(String minTotal) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("JPY total policy " + unique());
        policy.setActive(true);
        policy.setCurrency("JPY");
        policy.setMinTotal(new BigDecimal(minTotal));
        return policyService.create(policy);
    }

    private DealDocumentDto generate(Deal deal) {
        return documentService.generate(deal.getId(), template().getId());
    }

    private User approver() {
        User approver = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), approver.getId(), "admin");
        return approver;
    }

    @Test
    void requestMovesDraftToPendingAndRecordsTriggeringPolicy() {
        ApprovalPolicy policy = jpyTotalPolicy("100");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto doc = generate(deal);
        assertTrue(doc.requiresApproval());

        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), "please review");
        assertEquals("pending", approval.status());
        assertEquals(policy.getId(), approval.policyId());
        assertEquals(currentUser.getId(), approval.requestedBy());
        assertEquals("please review", approval.requestComment());

        DealDocumentDto pending = documentService.getOne(deal.getId(), doc.id());
        assertEquals("pending_approval", pending.status());
        assertNotNull(pending.latestApproval());
    }

    @Test
    void differentApproverCanApprove() {
        jpyTotalPolicy("100");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto doc = generate(deal);

        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), doc.id(), "final"));

        approvalService.requestApproval(deal.getId(), doc.id(), null);
        authenticateAs(approver(), workspace.getId());
        DocumentApprovalDto decided = approvalService.decide(deal.getId(), doc.id(), "approved", "looks good", null);
        assertEquals("approved", decided.status());
        assertEquals("quorum", decided.outcomeReason());
        assertEquals("looks good", decided.decisionComment());
        assertNotNull(decided.decidedAt());

        assertEquals("approved", documentService.getOne(deal.getId(), doc.id()).status());
        authenticateAs(currentUser, workspace.getId());
        assertEquals("final", documentService.updateStatus(deal.getId(), doc.id(), "final").status());
    }

    @Test
    void requesterCannotApproveOwnDocument() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());
        assertEquals("pending", documentService.getOne(deal.getId(), doc.id()).latestApproval().status());
    }

    @Test
    void requesterCannotRejectOwnDocument() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "rejected", null, null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());
        assertEquals("pending", documentService.getOne(deal.getId(), doc.id()).latestApproval().status());
    }

    @Test
    void authorCannotApproveOwnDocumentRequestedByAnotherMember() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        User requester = newUser();
        authenticateAs(requester, workspace.getId());
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        authenticateAs(currentUser, workspace.getId());
        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());
        assertEquals("pending", documentService.getOne(deal.getId(), doc.id()).latestApproval().status());
    }

    @Test
    void authorCannotApproveAfterCancellingAndAnotherMemberRequests() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);
        approvalService.cancel(deal.getId(), doc.id());
        User requester = newUser();
        authenticateAs(requester, workspace.getId());
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        authenticateAs(currentUser, workspace.getId());
        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());
        assertEquals("pending", documentService.getOne(deal.getId(), doc.id()).latestApproval().status());
    }

    @Test
    void unknownRequesterFailsClosed() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        jdbcTemplate.update(
                "UPDATE document_approval SET requested_by = NULL WHERE workspace_id = ? AND id = ?",
                workspace.getId(), approval.id());
        authenticateAs(approver(), workspace.getId());

        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());
        assertEquals("pending", documentService.getOne(deal.getId(), doc.id()).latestApproval().status());
    }

    @Test
    void rejectionReturnsDocumentToDraft() {
        jpyTotalPolicy("100");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        authenticateAs(approver(), workspace.getId());
        DocumentApprovalDto rejected = approvalService.decide(deal.getId(), doc.id(), "rejected", "too steep", null);
        assertEquals("rejected", rejected.status());
        assertEquals("rejected", rejected.outcomeReason());

        DealDocumentDto reread = documentService.getOne(deal.getId(), doc.id());
        assertEquals("draft", reread.status());
        assertEquals("rejected", reread.latestApproval().status());
        assertEquals("too steep", reread.latestApproval().decisionComment());
        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), doc.id(), "final"));
    }

    @Test
    void clientCannotSetApprovalStatesDirectly() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), doc.id(), "pending_approval"));
        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), doc.id(), "approved"));
    }

    @Test
    void doubleRequestIsRejected() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertThrows(BadRequestException.class,
            () -> approvalService.requestApproval(deal.getId(), doc.id(), null));
    }

    @Test
    void onlyDraftDocumentsCanBeSentForApproval() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        documentService.updateStatus(deal.getId(), doc.id(), "final");

        assertThrows(BadRequestException.class,
            () -> approvalService.requestApproval(deal.getId(), doc.id(), null));
    }

    @Test
    void onlyTheRequesterCanCancel() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class,
            () -> approvalService.cancel(deal.getId(), doc.id()));

        authenticateAs(currentUser, workspace.getId());
        DocumentApprovalDto cancelled = approvalService.cancel(deal.getId(), doc.id());
        assertEquals("cancelled", cancelled.status());
        assertEquals("cancelled_by_requester", cancelled.outcomeReason());
        assertEquals("draft", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void onlyAdminOrOwnerCanCancelAnApprovalWithUnknownRequester() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        jdbcTemplate.update(
                "UPDATE document_approval SET requested_by = NULL WHERE workspace_id = ? AND id = ?",
                workspace.getId(), approval.id());
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class,
            () -> approvalService.cancel(deal.getId(), doc.id()));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());

        authenticateAs(currentUser, workspace.getId());
        DocumentApprovalDto cancelled = approvalService.cancel(deal.getId(), doc.id());
        assertEquals("cancelled", cancelled.status());
        assertEquals("cancelled_by_admin", cancelled.outcomeReason());
        assertEquals("draft", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void supersedeByRequesterCancelsPendingApproval() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        DealDocumentDto superseded = documentService.updateStatus(deal.getId(), doc.id(), "superseded");
        assertEquals("superseded", superseded.status());
        assertEquals("cancelled", superseded.latestApproval().status());
        assertEquals("superseded", superseded.latestApproval().outcomeReason());

        assertThrows(BadRequestException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
    }

    @Test
    void differentMemberCanSupersedeAndCancelAnotherMembersPendingApproval() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        User superseder = newUser();
        authenticateAs(superseder, workspace.getId());

        DealDocumentDto superseded = documentService.updateStatus(
            deal.getId(), doc.id(), "superseded");

        assertEquals("superseded", superseded.status());
        assertEquals("cancelled", superseded.latestApproval().status());
        assertEquals(currentUser.getId(), approval.requestedBy());
    }

    @Test
    void decisionRequiresApprovePermission() {
        jpyTotalPolicy("100");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
    }

    @Test
    void approvalIsScopedToItsDeal() {
        Deal deal = jpyDeal();
        Deal other = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertThrows(ResourceNotFoundException.class,
            () -> approvalService.getForDocument(other.getId(), doc.id()));
        assertThrows(ResourceNotFoundException.class,
            () -> approvalService.decide(other.getId(), doc.id(), "approved", null, null));
        assertThrows(ResourceNotFoundException.class,
            () -> approvalService.cancel(other.getId(), doc.id()));
    }

    @Test
    void regeneratedVersionRequiresItsOwnApproval() {
        jpyTotalPolicy("100");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto first = generate(deal);
        approvalService.requestApproval(deal.getId(), first.id(), null);
        authenticateAs(approver(), workspace.getId());
        approvalService.decide(deal.getId(), first.id(), "approved", null, null);

        authenticateAs(currentUser, workspace.getId());
        DealDocumentDto second = generate(deal);
        assertEquals("draft", second.status());
        assertTrue(second.requiresApproval());
        assertNull(second.latestApproval());
        assertThrows(BadRequestException.class,
            () -> documentService.updateStatus(deal.getId(), second.id(), "final"));
    }

    @Test
    void requestNotifiesApproversAndDecisionNotifiesRequester() {
        jpyTotalPolicy("100");
        User approver = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), approver.getId(), "admin");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), approver.getId(),
            "document.approval_request:" + approval.id() + ":"
                + approval.steps().getFirst().id() + ":" + approver.getId()));

        authenticateAs(approver, workspace.getId());
        approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), currentUser.getId(),
            "document.approval_decision:" + approval.id() + ":" + currentUser.getId()));
    }

    @Test
    void voluntaryRequestFreezesOneImplicitAnyApproverStep() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertEquals(1, approval.steps().size());
        DocumentApprovalStepDto only = approval.steps().getFirst();
        assertEquals("active", only.status());
        assertEquals(1, only.requiredCount());
        assertEquals("any_approver", only.approvers().getFirst().getApproverKind());
        assertEquals("sequential", approval.mode());
        assertEquals("strict", approval.separationOfDuties());
    }

    @Test
    void sequentialChainOpensOneStepAtATime() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential", step(1, "Manager", first), step(1, "Finance", second));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertEquals("active", stepOf(requested, 1).status());
        assertEquals("pending", stepOf(requested, 2).status());

        authenticateAs(second, workspace.getId());
        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));

        authenticateAs(first, workspace.getId());
        DocumentApprovalDto afterFirst = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("pending", afterFirst.status());
        assertEquals("approved", stepOf(afterFirst, 1).status());
        assertEquals("active", stepOf(afterFirst, 2).status());
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());

        authenticateAs(second, workspace.getId());
        DocumentApprovalDto afterSecond = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("approved", afterSecond.status());
        assertEquals("approved", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void parallelChainOpensEveryStepAndCompletesInAnyOrder() {
        User first = approver();
        User second = approver();
        chainPolicy("parallel", step(1, "Legal", first), step(1, "Finance", second));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertEquals("active", stepOf(requested, 1).status());
        assertEquals("active", stepOf(requested, 2).status());

        authenticateAs(second, workspace.getId());
        DocumentApprovalDto afterSecond = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("pending", afterSecond.status());
        assertEquals("approved", stepOf(afterSecond, 2).status());

        authenticateAs(first, workspace.getId());
        DocumentApprovalDto afterFirst = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("approved", afterFirst.status());
        assertEquals("approved", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void quorumNeedsDistinctApproversAndRefusesADoubleVote() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential", step(2, "Two of us", first, second));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        authenticateAs(first, workspace.getId());
        DocumentApprovalDto afterFirst = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("pending", afterFirst.status());
        assertEquals(1, stepOf(afterFirst, 1).approvedCount());
        assertTrue(afterFirst.satisfiable());
        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));

        authenticateAs(second, workspace.getId());
        DocumentApprovalDto afterSecond = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("approved", afterSecond.status());
        assertEquals(2, stepOf(afterSecond, 1).approvedCount());
    }

    @Test
    void rejectionCancelsTheRemainingChain() {
        User first = approver();
        User second = approver();
        chainPolicy("parallel", step(1, "Legal", first), step(1, "Finance", second));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        authenticateAs(first, workspace.getId());
        DocumentApprovalDto rejected = approvalService.decide(deal.getId(), doc.id(), "rejected", "no", null);

        assertEquals("rejected", rejected.status());
        assertEquals("rejected", stepOf(rejected, 1).status());
        assertEquals("cancelled", stepOf(rejected, 2).status());
        assertEquals("draft", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void aNamedStepRefusesAnUnassignedApprover() {
        User assigned = approver();
        User other = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        authenticateAs(other, workspace.getId());
        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());

        authenticateAs(assigned, workspace.getId());
        assertEquals("approved",
            approvalService.decide(deal.getId(), doc.id(), "approved", null, null).status());
    }

    @Test
    void stepIdTargetsAnExactStepAndRefusesAClosedOne() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential", step(1, "Manager", first), step(1, "Finance", second));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        int firstStepId = stepOf(requested, 1).id();
        int secondStepId = stepOf(requested, 2).id();

        authenticateAs(second, workspace.getId());
        assertThrows(BadRequestException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, secondStepId));

        authenticateAs(first, workspace.getId());
        DocumentApprovalDto afterFirst = approvalService.decide(
            deal.getId(), doc.id(), "approved", null, firstStepId);
        assertEquals("approved", stepOf(afterFirst, 1).status());
        assertThrows(BadRequestException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, firstStepId));
    }

    @Test
    void separationOfDutiesOffLetsTheRequesterApprove() {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Self serve " + unique());
        policy.setActive(true);
        policy.setSeparationOfDuties("off");
        policy.setSteps(List.of(step(1, "Anyone")));
        policyService.create(policy);
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertEquals("off", requested.separationOfDuties());
        assertEquals("approved",
            approvalService.decide(deal.getId(), doc.id(), "approved", null, null).status());
        assertEquals("approved", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void separationOfDutiesRequesterStillBlocksTheRequesterButAdmitsTheAuthor() {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Requester only " + unique());
        policy.setActive(true);
        policy.setSeparationOfDuties("requester");
        policy.setSteps(List.of(step(1, "Anyone")));
        policyService.create(policy);
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        User requester = approver();
        authenticateAs(requester, workspace.getId());
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null, null));

        authenticateAs(currentUser, workspace.getId());
        assertEquals("approved",
            approvalService.decide(deal.getId(), doc.id(), "approved", null, null).status());
    }

    @Test
    void retargetingThePolicyDoesNotRewriteAnInFlightChain() {
        User first = approver();
        ApprovalPolicy policy = chainPolicy("sequential", step(1, "Manager", first));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertEquals(1, requested.steps().size());

        ApprovalPolicy edited = new ApprovalPolicy();
        edited.setName(policy.getName());
        edited.setActive(true);
        edited.setMode("parallel");
        edited.setSeparationOfDuties(policy.getSeparationOfDuties());
        edited.setSteps(policy.getSteps());
        policyService.update(policy.getId(), edited, false);

        DocumentApprovalDto reread = approvalService.getForDocument(deal.getId(), doc.id()).getFirst();
        assertEquals(1, reread.steps().size());
        authenticateAs(first, workspace.getId());
        assertEquals("approved",
            approvalService.decide(deal.getId(), doc.id(), "approved", null, null).status());
    }

    @Test
    void namedApproverLosingPermissionIsProjectedUnsatisfiableWithoutMutation() {
        User assigned = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        approvalService.requestApproval(deal.getId(), document.id(), null);

        workspaceMapper.updateMemberRole(workspace.getId(), assigned.getId(), "member");
        DocumentApprovalDto reread = approvalService.getForDocument(
            deal.getId(), document.id()).getFirst();

        assertEquals("pending", reread.status());
        assertFalse(reread.satisfiable());
        assertNotNull(reread.blockedReason());
        assertFalse(reread.steps().getFirst().satisfiable());
        assertNotNull(reread.steps().getFirst().unsatisfiableReason());
        assertEquals("pending_approval", documentService.getOne(deal.getId(), document.id()).status());
    }

    @Test
    void anyApproverStepWithOnlyExcludedApproversIsProjectedUnsatisfiable() {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Requester excluded " + unique());
        policy.setActive(true);
        policy.setSeparationOfDuties("requester");
        policy.setSteps(List.of(step(1, "Anyone")));
        policyService.create(policy);
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);

        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);

        assertFalse(approval.satisfiable());
        assertFalse(approval.steps().getFirst().satisfiable());
        assertNotNull(approval.steps().getFirst().unsatisfiableReason());
        assertEquals("pending", approval.status());
    }

    @Test
    void terminateIfUnsatisfiableMarksBlockingStepCancelsRestAndIsIdempotent() {
        User assigned = approver();
        chainPolicy("parallel",
            step(1, "Manager", assigned),
            step(1, "Finance"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        workspaceMapper.updateMemberRole(workspace.getId(), assigned.getId(), "member");
        DocumentApproval approval = approvalMapper.getById(workspace.getId(), requested.id());

        assertTrue(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));
        assertFalse(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));

        DocumentApprovalDto terminated = approvalService.getForDocument(
            deal.getId(), document.id()).getFirst();
        assertEquals("unsatisfiable", terminated.status());
        assertEquals("unsatisfiable", terminated.outcomeReason());
        assertNotNull(terminated.outcomeDetail());
        assertFalse(terminated.satisfiable());
        assertEquals("unsatisfiable", stepOf(terminated, 1).status());
        assertEquals("cancelled", stepOf(terminated, 2).status());
        assertEquals("draft", documentService.getOne(deal.getId(), document.id()).status());
    }

    @Test
    void satisfiableStepIsNeverTerminated() {
        approver();
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Available " + unique());
        policy.setActive(true);
        policy.setSteps(List.of(step(1, "Anyone")));
        policyService.create(policy);
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        DocumentApproval approval = approvalMapper.getById(workspace.getId(), requested.id());

        assertFalse(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));

        DocumentApprovalDto reread = approvalService.getForDocument(
            deal.getId(), document.id()).getFirst();
        assertTrue(reread.satisfiable());
        assertEquals("pending", reread.status());
        assertEquals("pending_approval", documentService.getOne(deal.getId(), document.id()).status());
    }

    @Test
    void terminalStatusFlipBeforeReconciliationLockIsNotOverwritten() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        DocumentApproval approval = approvalMapper.getById(workspace.getId(), requested.id());
        jdbcTemplate.update(
            "UPDATE document_approval SET status = 'cancelled', outcome_reason = 'superseded', "
                + "decided_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), requested.id());

        assertFalse(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));

        DocumentApproval reread = approvalMapper.getById(workspace.getId(), requested.id());
        assertEquals("cancelled", reread.getStatus());
        assertEquals("superseded", reread.getOutcomeReason());
        assertEquals("pending_approval", documentService.getOne(deal.getId(), document.id()).status());
    }

    @Test
    void approvalChainFenceStillRejectsRootApprovalWithOpenStep() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);

        DataAccessException refused = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "UPDATE document_approval SET status = 'approved', outcome_reason = 'quorum', "
                + "decided_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), requested.id()));
        SQLException cause = assertInstanceOf(SQLException.class, refused.getCause());
        assertEquals("45000", cause.getSQLState());
        assertTrue(cause.getMessage().contains("chain steps are unapproved"));
    }

    @Test
    void newPendingApprovalMapperReadsRemainWorkspaceScoped() {
        ApprovalPolicy policy = jpyTotalPolicy("1");
        Deal deal = jpyDeal();
        addLine(deal, "10", "1");
        DealDocumentDto document = generate(deal);
        approvalService.requestApproval(deal.getId(), document.id(), null);
        int foreignWorkspaceId = workspace.getId() + 100_000;

        assertTrue(approvalMapper.findPendingByPolicyId(
            foreignWorkspaceId, policy.getId()).isEmpty());
        assertTrue(approvalMapper.findPendingForWorkspace(
            foreignWorkspaceId, 200).isEmpty());
        assertTrue(approvalMapper.findPendingForWorkspaceAfter(
            foreignWorkspaceId, 0, 200).isEmpty());
        assertEquals(0, approvalMapper.countPendingByPolicyId(
            foreignWorkspaceId, policy.getId()));
    }

    @Test
    void aStepNamingOnlyPeopleWhoCannotDecideIsRefusedAtRequestTime() {
        chainPolicy("sequential", step(1, "Self review", currentUser));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        assertThrows(BadRequestException.class,
            () -> approvalService.requestApproval(deal.getId(), doc.id(), null));
        assertEquals("draft", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void anApprovalFrozenByAnOlderBinaryStillDecides() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        jdbcTemplate.update("DELETE FROM document_approval_step WHERE workspace_id = ? AND approval_id = ?",
            workspace.getId(), approval.id());

        authenticateAs(approver(), workspace.getId());
        DocumentApprovalDto decided = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);

        assertEquals("approved", decided.status());
        assertEquals(1, decided.steps().size());
        assertEquals("approved", decided.steps().getFirst().status());
        assertEquals("approved", documentService.getOne(deal.getId(), doc.id()).status());
    }

    @Test
    void voluntaryRequestWithoutPolicyCarriesNoPolicyId() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        assertFalse(doc.requiresApproval());

        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertNull(approval.policyId());
        authenticateAs(approver(), workspace.getId());
        approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        authenticateAs(currentUser, workspace.getId());
        assertEquals("final", documentService.updateStatus(deal.getId(), doc.id(), "final").status());
    }
}
