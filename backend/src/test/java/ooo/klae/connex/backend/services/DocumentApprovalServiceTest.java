package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ApprovalDelegateDto;
import ooo.klae.connex.backend.dto.ApprovalInboxItemDto;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.dto.DocumentApprovalStepDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;

class DocumentApprovalServiceTest extends AbstractServiceTest {

    @Autowired DocumentApprovalService approvalService;
    @Autowired ApprovalPolicyService policyService;
    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;
    @MockitoSpyBean DocumentApprovalMapper approvalMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SqlSessionTemplate sqlSession;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean WorkspaceService workspaceService;
    @MockitoSpyBean DealDocumentMapper documentMapper;
    @MockitoSpyBean RuleTriggerPublisher ruleTriggers;

    /**
     * Drops the MyBatis session cache. The suite runs one transaction per test, so a write made with
     * {@link JdbcTemplate} would otherwise stay invisible to mapper statements already executed with
     * the same arguments. Production never sees this: each sweep and each request uses its own
     * session.
     */
    private void flushSession() {
        sqlSession.clearCache();
    }

    /**
     * Runs one call on its own savepoint, the way the reconciliation sweep and a real request both
     * get their own transaction. Without this a post-write guard's rollback would only be recorded
     * on the suite-wide transaction, leaving its refused write readable.
     */
    private void inNestedTransaction(Runnable work) {
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(status -> work.run());
    }

    private Deal jpyDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        return newDeal(pipeline, stage, company);
    }

    private DocumentTemplate template() {
        return template("quote");
    }

    private DocumentTemplate template(String type) {
        DocumentTemplate t = new DocumentTemplate();
        t.setName(type + " template " + unique());
        t.setType(type);
        t.setLocale("en");
        t.setTitle("Document for {{company.name}}");
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

    private Notification approvalRequestNotification(
            DocumentApprovalDto approval, int stepOrder, User recipient) {
        DocumentApprovalStepDto step = stepOf(approval, stepOrder);
        return notificationMapper.findByDedupe(
            workspace.getId(),
            recipient.getId(),
            "document.approval_request:" + approval.id() + ":" + step.id()
                + ":requested:" + recipient.getId());
    }

    private void assertApprovalRequestResolved(
            DocumentApprovalDto approval, int stepOrder, User recipient) {
        Notification notification = approvalRequestNotification(approval, stepOrder, recipient);
        assertNotNull(notification);
        assertNotNull(notification.getResolvedAt());
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

    private DealDocumentDto generate(Deal deal, String type) {
        return documentService.generate(deal.getId(), template(type).getId());
    }

    private ApprovalPolicy typedChainPolicy(String documentType, ApprovalPolicyStep... steps) {
        return typedChainPolicy(documentType, "sequential", steps);
    }

    private ApprovalPolicy typedChainPolicy(
            String documentType, String mode, ApprovalPolicyStep... steps) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Typed policy " + unique());
        policy.setActive(true);
        policy.setDocumentType(documentType);
        policy.setMode(mode);
        policy.setSteps(List.of(steps));
        return policyService.create(policy);
    }

    private User approver() {
        User approver = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), approver.getId(), "admin");
        return approver;
    }

    private void deactivateCurrentMembershipBeforeLockedMembershipRead() {
        int workspaceId = workspace.getId();
        int actorId = currentUser.getId();
        doAnswer(invocation -> {
            jdbcTemplate.update(
                "UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
                workspaceId, actorId);
            return invocation.callRealMethod();
        }).when(workspaceService).lockApprovalMutationMemberships(
            org.mockito.ArgumentMatchers.eq(workspaceId),
            org.mockito.ArgumentMatchers.eq(actorId),
            org.mockito.ArgumentMatchers.anySet());
    }

    private void deactivateCurrentMembershipBeforeLockedPermissionRead() {
        int workspaceId = workspace.getId();
        int actorId = currentUser.getId();
        doAnswer(invocation -> {
            jdbcTemplate.update(
                "UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
                workspaceId, actorId);
            return invocation.callRealMethod();
        }).when(workspaceService).lockedPermissionsFor(workspaceId, actorId);
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
    void requestFailsClosedWhenMembershipIsDeactivatedAfterEntryBeforeAuthorizationLock() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        deactivateCurrentMembershipBeforeLockedPermissionRead();

        assertThrows(ForbiddenException.class,
            () -> approvalService.requestApproval(deal.getId(), document.id(), null));

        assertEquals("draft", jdbcTemplate.queryForObject(
            "SELECT status FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), document.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_approval WHERE workspace_id = ? AND document_id = ?",
            Integer.class, workspace.getId(), document.id()));
    }

    @Test
    void appliedPolicySnapshotSurvivesPolicyDeletion() {
        ApprovalPolicy policy = jpyTotalPolicy("100");
        Deal deal = jpyDeal();
        addLine(deal, "150.00", "1");
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), doc.id(), null);

        policyService.delete(policy.getId());

        assertNull(approvalService.getForDocument(
            deal.getId(), doc.id()).getFirst().policyId());
        assertEquals(policy.getId(), jdbcTemplate.queryForObject(
            "SELECT policy_id_snapshot FROM document_approval WHERE workspace_id = ? AND id = ?",
            Integer.class,
            workspace.getId(),
            approval.id()));
        assertEquals("applied", jdbcTemplate.queryForObject(
            "SELECT policy_binding FROM document_approval WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            approval.id()));
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
    void cancelFailsClosedWhenMembershipIsDeactivatedAfterEntryBeforeAuthorizationLock() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        deactivateCurrentMembershipBeforeLockedMembershipRead();

        assertThrows(ForbiddenException.class,
            () -> approvalService.cancel(deal.getId(), document.id()));

        assertEquals("pending", approvalMapper.getById(
            workspace.getId(), approval.id()).getStatus());
        assertEquals("pending_approval", jdbcTemplate.queryForObject(
            "SELECT status FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), document.id()));
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
    void cancelAndSupersedeResolveEveryApprovalRequestRecipient() {
        User first = approver();
        User second = approver();
        chainPolicy("parallel", step(1, "Legal", first), step(1, "Finance", second));
        Deal cancelledDeal = jpyDeal();
        DealDocumentDto cancelledDocument = generate(cancelledDeal);
        DocumentApprovalDto cancelledApproval = approvalService.requestApproval(
            cancelledDeal.getId(), cancelledDocument.id(), null);

        approvalService.cancel(cancelledDeal.getId(), cancelledDocument.id());

        assertApprovalRequestResolved(cancelledApproval, 1, first);
        assertApprovalRequestResolved(cancelledApproval, 2, second);

        Deal supersededDeal = jpyDeal();
        DealDocumentDto supersededDocument = generate(supersededDeal);
        DocumentApprovalDto supersededApproval = approvalService.requestApproval(
            supersededDeal.getId(), supersededDocument.id(), null);

        documentService.updateStatus(
            supersededDeal.getId(), supersededDocument.id(), "superseded");

        assertApprovalRequestResolved(supersededApproval, 1, first);
        assertApprovalRequestResolved(supersededApproval, 2, second);
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
                + approval.steps().getFirst().id() + ":requested:" + approver.getId()));

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
        assertApprovalRequestResolved(requested, 1, first);
        assertNull(approvalRequestNotification(requested, 2, second).getResolvedAt());

        authenticateAs(second, workspace.getId());
        assertTrue(approvalService.inbox().stream().anyMatch(item ->
            item.approvalId() == requested.id()
                && item.stepId() == stepOf(requested, 2).id()));
        DocumentApprovalDto afterSecond = approvalService.decide(deal.getId(), doc.id(), "approved", null, null);
        assertEquals("approved", afterSecond.status());
        assertEquals("approved", documentService.getOne(deal.getId(), doc.id()).status());
        assertApprovalRequestResolved(requested, 2, second);
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
        policyService.update(policy.getId(), edited, false, null);

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
    void reconciliationLocksDiscoveredMembershipsBeforeDocumentAndRevalidatesPool() {
        User assigned = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        workspaceMapper.updateMemberRole(workspace.getId(), assigned.getId(), "member");
        DocumentApproval approval = approvalMapper.getById(workspace.getId(), requested.id());
        clearInvocations(workspaceService, documentMapper);

        assertTrue(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));

        InOrder order = inOrder(workspaceService, documentMapper);
        order.verify(workspaceService).getMembers(workspace.getId());
        order.verify(workspaceService)
            .lockApprovalMutationMemberships(
                org.mockito.ArgumentMatchers.eq(workspace.getId()),
                org.mockito.ArgumentMatchers.eq(currentUser.getId()),
                org.mockito.ArgumentMatchers.anySet());
        order.verify(documentMapper).lockById(workspace.getId(), document.id());
        order.verify(workspaceService).getMembers(workspace.getId());
    }

    @Test
    void grantCommittedBeforeTheAuthorizationRootIsReflectedInThePostLockPool() {
        User assigned = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        workspaceMapper.updateMemberRole(workspace.getId(), assigned.getId(), "member");
        doAnswer(invocation -> {
            workspaceMapper.updateMemberRole(workspace.getId(), assigned.getId(), "admin");
            return invocation.callRealMethod();
        }).when(workspaceService).lockApprovalMutationMemberships(
            org.mockito.ArgumentMatchers.eq(workspace.getId()),
            org.mockito.ArgumentMatchers.eq(currentUser.getId()),
            org.mockito.ArgumentMatchers.anySet());
        DocumentApproval approval = approvalMapper.getById(workspace.getId(), requested.id());

        assertFalse(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));

        DocumentApproval reread = approvalMapper.getById(workspace.getId(), requested.id());
        assertEquals("pending", reread.getStatus());
        assertEquals("pending_approval",
            documentService.getOne(deal.getId(), document.id()).status());
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
        flushSession();
        clearInvocations(workspaceService, approvalMapper, documentMapper);

        assertFalse(approvalService.terminateIfUnsatisfiable(workspace.getId(), approval));

        org.mockito.InOrder order = inOrder(workspaceService, documentMapper, approvalMapper);
        order.verify(workspaceService)
            .lockApprovalMutationMemberships(
                org.mockito.ArgumentMatchers.eq(workspace.getId()),
                org.mockito.ArgumentMatchers.eq(currentUser.getId()),
                org.mockito.ArgumentMatchers.anySet());
        order.verify(documentMapper).lockById(workspace.getId(), document.id());
        order.verify(approvalMapper).getByIdForUpdate(workspace.getId(), requested.id());
        verify(approvalMapper, times(0))
            .decide(anyInt(), anyInt(), any(), any(), any(), any(), any());
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
    void outcomeConstraintAcceptsLegacyTerminalNullAndRejectsPendingReason() {
        Deal firstDeal = jpyDeal();
        DealDocumentDto firstDocument = generate(firstDeal);
        DocumentApprovalDto terminal = approvalService.requestApproval(
            firstDeal.getId(), firstDocument.id(), null);

        assertEquals(1, jdbcTemplate.update(
            "UPDATE document_approval SET status = 'rejected', outcome_reason = NULL "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), terminal.id()));
        assertNull(approvalMapper.getById(workspace.getId(), terminal.id()).getOutcomeReason());

        Deal secondDeal = jpyDeal();
        DealDocumentDto secondDocument = generate(secondDeal);
        DocumentApprovalDto pending = approvalService.requestApproval(
            secondDeal.getId(), secondDocument.id(), null);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "UPDATE document_approval SET outcome_reason = 'rejected' "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), pending.id()));
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
        assertTrue(approvalMapper.findPendingIdsByPolicyId(
            foreignWorkspaceId, policy.getId()).isEmpty());
        assertTrue(approvalMapper.findPendingImpactSummaries(
            foreignWorkspaceId, policy.getId(), 20).isEmpty());
        assertTrue(approvalMapper.findPendingForWorkspace(
            foreignWorkspaceId, 200).isEmpty());
        assertTrue(approvalMapper.findPendingForWorkspaceAfter(
            foreignWorkspaceId, 0, 200).isEmpty());
        assertEquals(0, approvalMapper.countPendingByPolicyId(
            foreignWorkspaceId, policy.getId()));
        DocumentApprovalDto pending = approvalService.getForDocument(
            deal.getId(), document.id()).getFirst();
        assertTrue(approvalMapper.findActionableSteps(
            foreignWorkspaceId, currentUser.getId(),
            "2026-08-30 12:00:00", null, 200).isEmpty());
        assertTrue(approvalMapper.findExpiredActiveSteps(
            foreignWorkspaceId, pending.id()).isEmpty());
        assertTrue(approvalMapper.findReminderDueSteps(
            foreignWorkspaceId, pending.id()).isEmpty());
        assertTrue(approvalMapper.getAssignmentsByApprovalIds(
            foreignWorkspaceId, List.of(pending.id())).isEmpty());
        assertTrue(approvalMapper.getAssignmentsByApprovalIdsForUpdate(
            foreignWorkspaceId, List.of(pending.id())).isEmpty());
        assertTrue(approvalMapper.getStepsByApprovalIdsForUpdate(
            foreignWorkspaceId, List.of(pending.id())).isEmpty());
        assertTrue(approvalMapper.getDecisionsByApprovalIdsForUpdate(
            foreignWorkspaceId, List.of(pending.id())).isEmpty());
        assertTrue(approvalMapper.getByIds(
            foreignWorkspaceId, List.of(pending.id())).isEmpty());
        assertNull(approvalMapper.getByIdForUpdate(foreignWorkspaceId, pending.id()));
        assertNull(approvalMapper.findPendingForUpdate(foreignWorkspaceId, document.id()));
        assertTrue(documentMapper.getByIds(
            foreignWorkspaceId, List.of(document.id())).isEmpty());
        assertEquals(0, approvalMapper.maxReassignmentRound(
            foreignWorkspaceId, pending.steps().getFirst().id()));
        assertEquals(0, approvalMapper.escalateStep(
            foreignWorkspaceId, pending.steps().getFirst().id()));
        assertEquals(0, approvalMapper.advanceRemindedRound(
            foreignWorkspaceId, pending.steps().getFirst().id(), 1, 0));
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

    private ApprovalPolicyStep deadline(ApprovalPolicyStep step, Integer hours, String onExpiry) {
        step.setDueIntervalHours(hours);
        step.setOnExpiry(onExpiry);
        return step;
    }

    private void sweep(DocumentApprovalDto approval) {
        flushSession();
        approvalService.reconcileApproval(workspace.getId(),
            approvalMapper.getById(workspace.getId(), approval.id()));
    }

    private void backdateDue(int stepId, String activatedExpression, String dueExpression) {
        assertEquals(1, jdbcTemplate.update(
            "UPDATE document_approval_step SET activated_at = " + activatedExpression
                + ", due_at = " + dueExpression + " WHERE workspace_id = ? AND id = ?",
            workspace.getId(), stepId));
        flushSession();
    }

    private String stepColumn(int stepId, String column) {
        return jdbcTemplate.queryForObject(
            "SELECT CAST(" + column + " AS CHAR) FROM document_approval_step "
                + "WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), stepId);
    }

    private int assignmentCount(int stepId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_approval_step_assignment "
                + "WHERE workspace_id = ? AND step_id = ?",
            Integer.class, workspace.getId(), stepId);
    }

    private int decisionCount(int approvalId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_approval_decision "
                + "WHERE workspace_id = ? AND approval_id = ?",
            Integer.class, workspace.getId(), approvalId);
    }

    private int approverSnapshotCount(int stepId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_approval_step_approver "
                + "WHERE workspace_id = ? AND step_id = ?",
            Integer.class, workspace.getId(), stepId);
    }

    private void disableInApp(int userId, String type) {
        jdbcTemplate.update(
            "INSERT INTO notification_preference (user_id, type, channel, enabled) "
                + "VALUES (?, ?, 'in_app', FALSE)", userId, type);
        flushSession();
    }

    private List<ApprovalStepApprover> approverList(User... users) {
        return java.util.Arrays.stream(users).map(this::namedApprover).toList();
    }

    @Test
    void stepWithADueIntervalStampsActivatedAndDueWhenItOpens() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential",
            deadline(step(1, "Manager", first), 4, "expire"),
            deadline(step(1, "Finance", second), 2, "expire"));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertNotNull(stepOf(requested, 1).activatedAt());
        assertNotNull(stepOf(requested, 1).dueAt());
        assertNull(stepOf(requested, 2).activatedAt());
        assertNull(stepOf(requested, 2).dueAt());
        assertEquals(4, jdbcTemplate.queryForObject(
            "SELECT TIMESTAMPDIFF(HOUR, activated_at, due_at) FROM document_approval_step "
                + "WHERE workspace_id = ? AND id = ?",
            Integer.class, workspace.getId(), stepOf(requested, 1).id()));

        authenticateAs(first, workspace.getId());
        DocumentApprovalDto afterFirst = approvalService.decide(
            deal.getId(), doc.id(), "approved", null, null);

        assertNotNull(stepOf(afterFirst, 2).activatedAt());
        assertNotNull(stepOf(afterFirst, 2).dueAt());
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT TIMESTAMPDIFF(HOUR, activated_at, due_at) FROM document_approval_step "
                + "WHERE workspace_id = ? AND id = ?",
            Integer.class, workspace.getId(), stepOf(afterFirst, 2).id()));
    }

    @Test
    void aStepWithNoDueIntervalNeverGetsADueDate() {
        User assigned = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);

        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertNull(stepOf(requested, 1).dueAt());
        assertNull(stepOf(requested, 1).dueIntervalHours());
        assertEquals("expire", stepOf(requested, 1).onExpiry());
        assertTrue(approvalMapper.findExpiredActiveSteps(
            workspace.getId(), requested.id()).isEmpty());
        assertTrue(approvalMapper.findReminderDueSteps(
            workspace.getId(), requested.id()).isEmpty());
    }

    @Test
    void reconciliationReusesTheDocumentLockForUnsatisfiability() {
        User assigned = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        clearInvocations(documentMapper);

        sweep(requested);

        verify(documentMapper).lockById(workspace.getId(), document.id());
    }

    @Test
    void anExpiredStepTerminatesTheRequestAndReturnsTheDocumentToDraft() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential",
            deadline(step(1, "Manager", first), 4, "expire"),
            step(1, "Finance", second));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        backdateDue(stepOf(requested, 1).id(),
            "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");

        sweep(requested);

        DocumentApprovalDto terminated = approvalService.getForDocument(
            deal.getId(), document.id()).getFirst();
        assertEquals("expired", terminated.status());
        assertEquals("expired", terminated.outcomeReason());
        assertNotNull(terminated.outcomeDetail());
        assertEquals("expired", stepOf(terminated, 1).status());
        assertEquals("cancelled", stepOf(terminated, 2).status());
        assertEquals("draft", documentService.getOne(deal.getId(), document.id()).status());
    }

    @Test
    void aDecisionSubmittedAfterTheDeadlineExpiresBeforeItCanBeRecorded() {
        User assigned = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 4, "expire"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        backdateDue(stepOf(requested, 1).id(),
            "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");
        authenticateAs(assigned, workspace.getId());

        DocumentApprovalDto result = approvalService.decide(
            deal.getId(), document.id(), "approved", null, null);

        assertEquals("expired", result.status());
        assertEquals("expired", result.outcomeReason());
        assertEquals(0, decisionCount(requested.id()));
        assertEquals("draft", documentService.getOne(deal.getId(), document.id()).status());
    }

    @Test
    void aLateDecisionReconcilesEveryOverdueParallelEscalationFirst() {
        User first = approver();
        User second = approver();
        User newlyEligible = approver();
        chainPolicy("parallel",
            deadline(step(1, "Legal", first), 4, "escalate"),
            deadline(step(1, "Finance", second), 4, "escalate"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        for (DocumentApprovalStepDto step : requested.steps()) {
            backdateDue(step.id(), "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
                "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");
        }
        authenticateAs(first, workspace.getId());

        DocumentApprovalDto result = approvalService.decide(
            deal.getId(), document.id(), "approved", null, stepOf(requested, 1).id());

        assertEquals("pending", result.status());
        assertEquals(1, decisionCount(requested.id()));
        for (DocumentApprovalStepDto step : result.steps()) {
            assertNotNull(step.escalatedAt());
            assertNull(step.dueAt());
            assertEquals("escalation", step.assignments().getFirst().assignmentKind());
        }
        assertTrue(stepOf(result, 2).effectiveApproverIds().contains(newlyEligible.getId()));
    }

    @Test
    void expiryIsIdempotentUnderARepeatedSweep() {
        User first = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", first), 4, "expire"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        backdateDue(stepOf(requested, 1).id(),
            "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");
        sweep(requested);
        String decidedAt = jdbcTemplate.queryForObject(
            "SELECT CAST(decided_at AS CHAR) FROM document_approval WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), requested.id());

        sweep(requested);

        assertEquals("expired", approvalMapper.getById(
            workspace.getId(), requested.id()).getStatus());
        assertEquals(decidedAt, jdbcTemplate.queryForObject(
            "SELECT CAST(decided_at AS CHAR) FROM document_approval WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), requested.id()));
    }

    @Test
    void anEscalateStepWidensToTheApproverPoolExactlyOnce() {
        User assigned = approver();
        approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 4, "escalate"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        backdateDue(stepId, "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");

        sweep(requested);

        assertEquals(1, assignmentCount(stepId));
        assertEquals("escalation", jdbcTemplate.queryForObject(
            "SELECT assignment_kind FROM document_approval_step_assignment "
                + "WHERE workspace_id = ? AND step_id = ?",
            String.class, workspace.getId(), stepId));
        assertEquals("any_approver", jdbcTemplate.queryForObject(
            "SELECT approver_kind FROM document_approval_step_assignment "
                + "WHERE workspace_id = ? AND step_id = ?",
            String.class, workspace.getId(), stepId));
        assertNotNull(stepColumn(stepId, "escalated_at"));
        assertNull(stepColumn(stepId, "due_at"));
        assertEquals(1, approverSnapshotCount(stepId));

        DocumentApprovalDto widened = approvalService.getForDocument(
            deal.getId(), document.id()).getFirst();
        assertEquals("pending", widened.status());
        assertEquals("active", stepOf(widened, 1).status());
        assertTrue(stepOf(widened, 1).effectiveAnyApprover());

        sweep(requested);

        assertEquals(1, assignmentCount(stepId));
        assertEquals("pending", approvalMapper.getById(
            workspace.getId(), requested.id()).getStatus());
    }

    @Test
    void deadlineEscalationNotifiesOnlyApproversTheWideningAdded() {
        User assigned = approver();
        User addedFirst = approver();
        User addedSecond = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 4, "escalate"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        backdateDue(stepId, "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");

        sweep(requested);

        int assignmentId = approvalService.getForDocument(deal.getId(), document.id())
            .getFirst().steps().getFirst().assignments().getFirst().id();
        String key = "document.approval_request:" + requested.id() + ":" + stepId
            + ":escalated:" + assignmentId + ":";
        assertNull(notificationMapper.findByDedupe(
            workspace.getId(), assigned.getId(), key + assigned.getId()));
        assertNotNull(notificationMapper.findByDedupe(
            workspace.getId(), addedFirst.getId(), key + addedFirst.getId()));
        assertNotNull(notificationMapper.findByDedupe(
            workspace.getId(), addedSecond.getId(), key + addedSecond.getId()));
    }

    @Test
    void aFailedExpiryEscalationCasDoesNotAppendAnAssignment() {
        User assigned = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 4, "escalate"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        backdateDue(stepId, "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");
        doAnswer(invocation -> 0).when(approvalMapper).escalateStep(workspace.getId(), stepId);

        sweep(requested);

        assertEquals(0, assignmentCount(stepId));
        assertNull(stepColumn(stepId, "escalated_at"));
    }

    @Test
    void anEscalatedStepIsDecidableByAnyApproverWhoWasNotNamed() {
        User assigned = approver();
        User bystander = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 4, "escalate"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        backdateDue(stepOf(requested, 1).id(),
            "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");
        sweep(requested);

        authenticateAs(bystander, workspace.getId());
        DocumentApprovalDto decided = approvalService.decide(
            deal.getId(), document.id(), "approved", null, null);

        assertEquals("approved", decided.status());
    }

    @Test
    void expiryAndEscalationAreNeverBoth() {
        User assigned = approver();
        approver();
        typedChainPolicy("quote",
            deadline(step(1, "Escalating", assigned), 4, "escalate"));
        Deal escalatingDeal = jpyDeal();
        DealDocumentDto escalatingDocument = generate(escalatingDeal);
        DocumentApprovalDto escalating = approvalService.requestApproval(
            escalatingDeal.getId(), escalatingDocument.id(), null);
        backdateDue(stepOf(escalating, 1).id(),
            "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");

        sweep(escalating);

        assertEquals("pending", approvalMapper.getById(
            workspace.getId(), escalating.id()).getStatus());
        assertEquals("active", stepColumn(stepOf(escalating, 1).id(), "status"));

        typedChainPolicy("contract", deadline(step(1, "Expiring"), 4, "expire"));
        Deal expiringDeal = jpyDeal();
        DealDocumentDto expiringDocument = generate(expiringDeal, "contract");
        DocumentApprovalDto expiring = approvalService.requestApproval(
            expiringDeal.getId(), expiringDocument.id(), null);
        int expiringStepId = stepOf(expiring, 1).id();
        backdateDue(expiringStepId, "TIMESTAMPADD(HOUR, -8, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -4, CURRENT_TIMESTAMP)");

        sweep(expiring);

        flushSession();
        assertEquals("expired", approvalMapper.getById(
            workspace.getId(), expiring.id()).getStatus());
        assertEquals(0, assignmentCount(expiringStepId));
    }

    @Test
    void remindersFireOncePerRoundAndStopWhenTheDeadlinePasses() {
        User assigned = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 20, "expire"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        backdateDue(stepId, "TIMESTAMPADD(HOUR, -10, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, 10, CURRENT_TIMESTAMP)");
        sweep(requested);
        assertEquals("1", stepColumn(stepId, "reminded_round"));
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), assigned.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":1:"
                + assigned.getId()));

        sweep(requested);
        assertEquals("1", stepColumn(stepId, "reminded_round"));

        backdateDue(stepId, "TIMESTAMPADD(HOUR, -19, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP)");
        sweep(requested);
        assertEquals("2", stepColumn(stepId, "reminded_round"));
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), assigned.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":2:"
                + assigned.getId()));

        backdateDue(stepId, "TIMESTAMPADD(HOUR, -21, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP)");
        assertEquals(3, approvalMapper.findReminderDueSteps(
            workspace.getId(), requested.id()).getFirst().dueRound());

        sweep(requested);

        assertEquals("expired", approvalMapper.getById(
            workspace.getId(), requested.id()).getStatus());
        assertNull(notificationMapper.findByDedupe(workspace.getId(), assigned.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":3:"
                + assigned.getId()));
    }

    @Test
    void remindersReachOnlyApproversOfAnActiveStep() {
        User first = approver();
        User second = approver();
        User later = approver();
        chainPolicy("sequential",
            deadline(step(2, "Managers", first, second), 20, "expire"),
            deadline(step(1, "Finance", later), 20, "expire"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        authenticateAs(first, workspace.getId());
        approvalService.decide(deal.getId(), document.id(), "approved", null, stepId);
        authenticateAs(currentUser, workspace.getId());
        User revoked = approver();
        workspaceMapper.updateMemberRole(workspace.getId(), revoked.getId(), "member");
        backdateDue(stepId, "TIMESTAMPADD(HOUR, -10, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, 10, CURRENT_TIMESTAMP)");

        sweep(requested);

        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), second.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":1:"
                + second.getId()));
        assertNull(notificationMapper.findByDedupe(workspace.getId(), first.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":1:"
                + first.getId()));
        assertNull(notificationMapper.findByDedupe(workspace.getId(), later.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":1:"
                + later.getId()));
        assertNull(notificationMapper.findByDedupe(workspace.getId(), revoked.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":1:"
                + revoked.getId()));
        assertEquals("0", stepColumn(stepOf(requested, 2).id(), "reminded_round"));
    }

    @Test
    void reminderRespectsTheRecipientPreference() {
        User assigned = approver();
        chainPolicy("sequential", deadline(step(1, "Manager", assigned), 20, "expire"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        disableInApp(assigned.getId(), "document.approval_reminder");
        backdateDue(stepId, "TIMESTAMPADD(HOUR, -10, CURRENT_TIMESTAMP)",
            "TIMESTAMPADD(HOUR, 10, CURRENT_TIMESTAMP)");

        sweep(requested);

        assertNull(notificationMapper.findByDedupe(workspace.getId(), assigned.getId(),
            "document.approval_reminder:" + requested.id() + ":" + stepId + ":1:"
                + assigned.getId()));
        assertEquals("1", stepColumn(stepId, "reminded_round"));
    }

    @Test
    void delegationLetsTheDelegateDecideAndRefusesTheDelegator() {
        User bob = approver();
        User carol = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        authenticateAs(bob, workspace.getId());
        DocumentApprovalDto delegated = approvalService.createDelegation(
            deal.getId(), document.id(), stepId, carol.getId(), "over to you");

        assertEquals(1, stepOf(delegated, 1).assignments().size());
        assertEquals("delegation", stepOf(delegated, 1).assignments().getFirst().assignmentKind());
        assertEquals(carol.getId(), stepOf(delegated, 1).assignments().getFirst().userId());
        assertEquals(bob.getId(), stepOf(delegated, 1).assignments().getFirst().delegatedByUserId());
        assertEquals(carol.getDisplayName(),
            stepOf(delegated, 1).assignments().getFirst().userDisplayName());
        assertEquals(bob.getDisplayName(),
            stepOf(delegated, 1).assignments().getFirst().delegatedByDisplayName());
        assertEquals(bob.getDisplayName(),
            stepOf(delegated, 1).assignments().getFirst().createdByDisplayName());
        assertEquals(List.of(carol.getId()), stepOf(delegated, 1).effectiveApproverIds());
        assertEquals(1, approverSnapshotCount(stepId));
        assertEquals(bob.getId(), stepOf(delegated, 1).approvers().getFirst().getUserId());

        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), document.id(), "approved", null, null));

        authenticateAs(carol, workspace.getId());
        assertEquals("approved",
            approvalService.decide(deal.getId(), document.id(), "approved", null, null).status());
    }

    @Test
    void delegationRenotifiesAnApproverWhoReceivedTheOriginalRequest() {
        User bob = approver();
        User carol = approver();
        User dave = approver();
        chainPolicy("sequential", step(1, "Managers", bob, carol, dave));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), carol.getId(),
            "document.approval_request:" + requested.id() + ":" + stepId
                + ":requested:" + carol.getId()));
        authenticateAs(bob, workspace.getId());

        DocumentApprovalDto delegated = approvalService.createDelegation(
            deal.getId(), document.id(), stepId, carol.getId(), null);
        int assignmentId = stepOf(delegated, 1).assignments().getFirst().id();

        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), carol.getId(),
            "document.approval_request:" + requested.id() + ":" + stepId
                + ":delegated:" + assignmentId + ":" + carol.getId()));
        assertNull(notificationMapper.findByDedupe(workspace.getId(), dave.getId(),
            "document.approval_request:" + requested.id() + ":" + stepId
                + ":delegated:" + assignmentId + ":" + dave.getId()));
    }

    @Test
    void eligibleDelegatesAreProjectedFromTheAuthoritativeStepState() {
        User bob = approver();
        User decided = approver();
        User delegatedAway = approver();
        User eligible = approver();
        User lacksPermission = newUser();
        chainPolicy("sequential", step(2, "Managers", bob, decided, delegatedAway));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        authenticateAs(decided, workspace.getId());
        approvalService.decide(deal.getId(), document.id(), "approved", null, stepId);
        authenticateAs(delegatedAway, workspace.getId());
        approvalService.createDelegation(
            deal.getId(), document.id(), stepId, eligible.getId(), null);
        authenticateAs(bob, workspace.getId());

        List<ApprovalDelegateDto> delegates = approvalService.eligibleDelegates(
            deal.getId(), document.id(), stepId);

        assertEquals(List.of(eligible.getId()), delegates.stream()
            .map(ApprovalDelegateDto::id).toList());
        assertEquals(eligible.getDisplayName(), delegates.getFirst().displayName());
        assertFalse(delegates.stream().anyMatch(candidate ->
            candidate.id() == currentUser.getId()
                || candidate.id() == lacksPermission.getId()
                || candidate.id() == decided.getId()
                || candidate.id() == delegatedAway.getId()
                || candidate.id() == bob.getId()));
    }

    @Test
    void delegationToSomeoneSeparationOfDutiesExcludesIsRefused() {
        User bob = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        authenticateAs(bob, workspace.getId());
        assertThrows(ForbiddenException.class, () -> approvalService.createDelegation(
            deal.getId(), document.id(), stepId, currentUser.getId(), null));
        assertEquals(0, assignmentCount(stepId));
    }

    @Test
    void delegationToAMemberWithoutDocumentApproveIsRefused() {
        User bob = approver();
        User plain = newUser();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        authenticateAs(bob, workspace.getId());
        assertThrows(ForbiddenException.class, () -> approvalService.createDelegation(
            deal.getId(), document.id(), stepId, plain.getId(), null));
        assertEquals(0, assignmentCount(stepId));
    }

    @Test
    void delegatingTwiceOrToAMemberWhoAlreadyDelegatedIsRefused() {
        User bob = approver();
        User erin = approver();
        User carol = approver();
        User dave = approver();
        chainPolicy("sequential", step(1, "Managers", bob, erin));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        authenticateAs(bob, workspace.getId());
        approvalService.createDelegation(deal.getId(), document.id(), stepId, carol.getId(), null);

        assertThrows(ForbiddenException.class, () -> approvalService.createDelegation(
            deal.getId(), document.id(), stepId, dave.getId(), null));

        authenticateAs(erin, workspace.getId());
        assertThrows(BadRequestException.class, () -> approvalService.createDelegation(
            deal.getId(), document.id(), stepId, bob.getId(), null));
        assertThrows(BadRequestException.class, () -> approvalService.createDelegation(
            deal.getId(), document.id(), stepId, erin.getId(), null));
        assertEquals(1, assignmentCount(stepId));
    }

    @Test
    void delegationThatWouldBreakQuorumIsRefused() {
        User bob = approver();
        User carol = approver();
        chainPolicy("sequential", step(2, "Both of us", bob, carol));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        authenticateAs(bob, workspace.getId());
        assertThrows(BadRequestException.class, () -> inNestedTransaction(
            () -> approvalService.createDelegation(
                deal.getId(), document.id(), stepId, carol.getId(), null)));

        flushSession();
        assertEquals(0, assignmentCount(stepId));
    }

    @Test
    void delegationIsInertOnceTheDelegatorIsReassignedAway() {
        User bob = approver();
        User carol = approver();
        User dave = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        authenticateAs(bob, workspace.getId());
        approvalService.createDelegation(deal.getId(), document.id(), stepId, carol.getId(), null);

        authenticateAs(currentUser, workspace.getId());
        DocumentApprovalDto reassigned = approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(dave), null);

        assertEquals(List.of(dave.getId()), stepOf(reassigned, 1).effectiveApproverIds());
        authenticateAs(carol, workspace.getId());
        assertThrows(ForbiddenException.class, () -> approvalService.decide(
            deal.getId(), document.id(), "approved", null, stepId));
    }

    @Test
    void escalationWidensWithoutRewritingTheFrozenSnapshot() {
        User bob = approver();
        User carol = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        DocumentApprovalDto widened = approvalService.addStepApprovers(
            deal.getId(), document.id(), stepId, approverList(carol), "need cover");

        assertEquals(1, approverSnapshotCount(stepId));
        assertEquals(bob.getId(), stepOf(widened, 1).approvers().getFirst().getUserId());
        assertEquals(List.of(bob.getId(), carol.getId()),
            stepOf(widened, 1).effectiveApproverIds());
        assertEquals("escalation", stepOf(widened, 1).assignments().getFirst().assignmentKind());
        assertEquals(0, stepOf(widened, 1).assignments().getFirst().assignmentRound());

        authenticateAs(carol, workspace.getId());
        assertEquals("approved",
            approvalService.decide(deal.getId(), document.id(), "approved", null, null).status());
    }

    @Test
    void manualEscalationNotifiesOnlyTheNewlyAddedApprover() {
        User bob = approver();
        User unchanged = approver();
        User added = approver();
        chainPolicy("sequential", step(1, "Managers", bob, unchanged));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        DocumentApprovalDto widened = approvalService.addStepApprovers(
            deal.getId(), document.id(), stepId, approverList(added), null);

        int assignmentId = stepOf(widened, 1).assignments().getFirst().id();
        String key = "document.approval_request:" + requested.id() + ":" + stepId
            + ":escalated:" + assignmentId + ":";
        assertNotNull(notificationMapper.findByDedupe(
            workspace.getId(), added.getId(), key + added.getId()));
        assertNull(notificationMapper.findByDedupe(
            workspace.getId(), bob.getId(), key + bob.getId()));
        assertNull(notificationMapper.findByDedupe(
            workspace.getId(), unchanged.getId(), key + unchanged.getId()));
    }

    @Test
    void reassignmentReplacesTheSetAndOpensANewRound() {
        User bob = approver();
        User carol = approver();
        User dave = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        approvalService.addStepApprovers(deal.getId(), document.id(), stepId,
            approverList(dave), null);
        DocumentApprovalDto firstRound = approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(carol), null);
        assertEquals(List.of(carol.getId()), firstRound.steps().getFirst().effectiveApproverIds());

        DocumentApprovalDto secondRound = approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(dave), null);

        assertEquals(List.of(dave.getId()), secondRound.steps().getFirst().effectiveApproverIds());
        assertEquals(List.of(1, 2), secondRound.steps().getFirst().assignments().stream()
            .filter(assignment -> "reassignment".equals(assignment.assignmentKind()))
            .map(assignment -> assignment.assignmentRound())
            .toList());
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT MAX(assignment_round) FROM document_approval_step_assignment "
                + "WHERE workspace_id = ? AND step_id = ? AND assignment_kind = 'reassignment'",
            Integer.class, workspace.getId(), stepId));
        assertEquals(3, assignmentCount(stepId));
        authenticateAs(carol, workspace.getId());
        assertThrows(ForbiddenException.class, () -> approvalService.decide(
            deal.getId(), document.id(), "approved", null, stepId));
    }

    @Test
    void reassignmentNotifiesOnlyTheNewApproverSet() {
        User bob = approver();
        User carol = approver();
        User dave = approver();
        chainPolicy("sequential", step(1, "Managers", bob, carol));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        DocumentApprovalDto reassigned = approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(carol, dave), null);

        int assignmentId = stepOf(reassigned, 1).assignments().getFirst().id();
        String key = "document.approval_request:" + requested.id() + ":" + stepId
            + ":reassigned:" + assignmentId + ":";
        assertNotNull(notificationMapper.findByDedupe(
            workspace.getId(), dave.getId(), key + dave.getId()));
        assertNull(notificationMapper.findByDedupe(
            workspace.getId(), bob.getId(), key + bob.getId()));
        assertNull(notificationMapper.findByDedupe(
            workspace.getId(), carol.getId(), key + carol.getId()));
    }

    @Test
    void decisionHydratesAuthorizationWithCurrentReadsAfterTheDocumentLock() {
        User assigned = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        authenticateAs(assigned, workspace.getId());
        clearInvocations(approvalMapper, documentMapper);

        approvalService.decide(deal.getId(), document.id(), "approved", null, null);

        org.mockito.InOrder order = inOrder(documentMapper, approvalMapper);
        order.verify(documentMapper).lockById(workspace.getId(), document.id());
        order.verify(approvalMapper).findPendingForUpdate(workspace.getId(), document.id());
        order.verify(approvalMapper).getStepsByApprovalIdsForUpdate(
            workspace.getId(), List.of(requested.id()));
        order.verify(approvalMapper).getDecisionsByApprovalIdsForUpdate(
            workspace.getId(), List.of(requested.id()));
        order.verify(approvalMapper).getAssignmentsByApprovalIdsForUpdate(
            workspace.getId(), List.of(requested.id()));
    }

    @Test
    void reassignmentPreservesDecisionsAlreadyCollected() {
        User bob = approver();
        User carol = approver();
        User dave = approver();
        chainPolicy("sequential", step(2, "Two of us", bob, carol));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        authenticateAs(bob, workspace.getId());
        approvalService.decide(deal.getId(), document.id(), "approved", null, stepId);
        authenticateAs(currentUser, workspace.getId());

        DocumentApprovalDto reassigned = approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(carol, dave), null);

        assertEquals(1, stepOf(reassigned, 1).approvedCount());
        assertEquals(1, stepOf(reassigned, 1).decisions().size());
        assertEquals(List.of(carol.getId(), dave.getId()),
            stepOf(reassigned, 1).effectiveApproverIds());
        assertTrue(reassigned.satisfiable());

        authenticateAs(dave, workspace.getId());
        assertEquals("approved",
            approvalService.decide(deal.getId(), document.id(), "approved", null, null).status());
    }

    @Test
    void reassignmentThatCannotReachQuorumIsRefused() {
        User bob = approver();
        User carol = approver();
        chainPolicy("sequential", step(2, "Two of us", bob, carol));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();

        assertThrows(BadRequestException.class, () -> inNestedTransaction(
            () -> approvalService.replaceStepApprovers(
                deal.getId(), document.id(), stepId, approverList(carol), null)));

        flushSession();
        assertEquals(0, assignmentCount(stepId));
    }

    @Test
    void reassignmentMayRepairAnAlreadyUnsatisfiableStep() {
        User assigned = approver();
        User rescuer = approver();
        chainPolicy("sequential", step(1, "Manager", assigned));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        workspaceMapper.updateMemberRole(workspace.getId(), assigned.getId(), "member");
        assertFalse(approvalService.getForDocument(
            deal.getId(), document.id()).getFirst().satisfiable());

        DocumentApprovalDto repaired = approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(rescuer), null);

        assertTrue(repaired.satisfiable());
        assertEquals(List.of(rescuer.getId()), stepOf(repaired, 1).effectiveApproverIds());
    }

    @Test
    void escalationAndReassignmentRequireDocumentManage() {
        User bob = approver();
        User carol = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = stepOf(requested, 1).id();
        User approveOnly = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), approveOnly.getId(), "member");
        jdbcTemplate.update(
            "UPDATE workspace_member SET role = 'member' WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), approveOnly.getId());
        authenticateAs(approveOnly, workspace.getId());

        assertThrows(ForbiddenException.class, () -> approvalService.addStepApprovers(
            deal.getId(), document.id(), stepId, approverList(carol), null));
        assertThrows(ForbiddenException.class, () -> approvalService.replaceStepApprovers(
            deal.getId(), document.id(), stepId, approverList(carol), null));
        assertEquals(0, assignmentCount(stepId));
    }

    @Test
    void inboxListsOnlyStepsTheCallerCanActuallyDecide() {
        User caller = approver();
        User other = approver();
        User bystander = approver();
        typedChainPolicy("quote", step(1, "Anyone"));
        typedChainPolicy("contract", step(1, "Other", other));

        Deal openDeal = jpyDeal();
        DealDocumentDto openDocument = generate(openDeal);
        DocumentApprovalDto open = approvalService.requestApproval(
            openDeal.getId(), openDocument.id(), null);

        Deal namedDeal = jpyDeal();
        DealDocumentDto namedDocument = generate(namedDeal, "contract");
        DocumentApprovalDto named = approvalService.requestApproval(
            namedDeal.getId(), namedDocument.id(), null);

        Deal delegatedDeal = jpyDeal();
        DealDocumentDto delegatedDocument = generate(delegatedDeal, "contract");
        DocumentApprovalDto delegated = approvalService.requestApproval(
            delegatedDeal.getId(), delegatedDocument.id(), null);
        authenticateAs(other, workspace.getId());
        approvalService.createDelegation(delegatedDeal.getId(), delegatedDocument.id(),
            delegated.steps().getFirst().id(), caller.getId(), null);
        authenticateAs(currentUser, workspace.getId());

        Deal reassignedDeal = jpyDeal();
        DealDocumentDto reassignedDocument = generate(reassignedDeal, "contract");
        DocumentApprovalDto reassigned = approvalService.requestApproval(
            reassignedDeal.getId(), reassignedDocument.id(), null);
        int reassignedStepId = reassigned.steps().getFirst().id();
        authenticateAs(other, workspace.getId());
        approvalService.createDelegation(reassignedDeal.getId(), reassignedDocument.id(),
            reassignedStepId, caller.getId(), null);
        authenticateAs(currentUser, workspace.getId());
        approvalService.replaceStepApprovers(reassignedDeal.getId(), reassignedDocument.id(),
            reassignedStepId, approverList(bystander), null);

        Deal decidedDeal = jpyDeal();
        DealDocumentDto decidedDocument = generate(decidedDeal);
        DocumentApprovalDto partly = approvalService.requestApproval(
            decidedDeal.getId(), decidedDocument.id(), null);
        jdbcTemplate.update("UPDATE document_approval_step SET required_count = 2 "
            + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), partly.steps().getFirst().id());
        flushSession();
        authenticateAs(caller, workspace.getId());
        approvalService.decide(decidedDeal.getId(), decidedDocument.id(), "approved", null, null);

        Deal requesterDeal = jpyDeal();
        DealDocumentDto requesterDocument = generate(requesterDeal);
        DocumentApprovalDto ownRequest = approvalService.requestApproval(
            requesterDeal.getId(), requesterDocument.id(), null);

        List<Integer> visible = approvalService.inbox().stream()
            .map(ApprovalInboxItemDto::approvalId).toList();

        assertTrue(visible.contains(open.id()));
        assertTrue(visible.contains(delegated.id()));
        assertFalse(visible.contains(named.id()));
        assertFalse(visible.contains(reassigned.id()));
        assertEquals("pending",
            approvalMapper.getById(workspace.getId(), partly.id()).getStatus());
        assertFalse(visible.contains(partly.id()));
        assertFalse(visible.contains(ownRequest.id()));

        authenticateAs(other, workspace.getId());
        assertFalse(approvalService.inbox().stream()
            .map(ApprovalInboxItemDto::approvalId).toList().contains(delegated.id()));
    }

    @Test
    void staleCandidatePagesCannotStarveLaterInboxWork() {
        User caller = approver();
        User replacement = approver();
        List<ApprovalPolicyStep> steps = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            steps.add(step(1, "Stale " + index, caller));
        }
        typedChainPolicy("quote", "parallel", steps.toArray(ApprovalPolicyStep[]::new));
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        DocumentTemplate documentTemplate = template();
        for (int approvalIndex = 0; approvalIndex < 20; approvalIndex++) {
            Deal staleDeal = newDeal(pipeline, stage, company);
            DealDocumentDto staleDocument = documentService.generate(
                staleDeal.getId(), documentTemplate.getId());
            DocumentApprovalDto stale = approvalService.requestApproval(
                staleDeal.getId(), staleDocument.id(), null);
            for (DocumentApprovalStepDto staleStep : stale.steps()) {
                assertEquals(1, jdbcTemplate.update(
                    "INSERT INTO document_approval_step_assignment (workspace_id, approval_id, "
                        + "step_id, assignment_kind, assignment_round, approver_kind, user_id, "
                        + "created_by_user_id) VALUES (?, ?, ?, 'reassignment', 1, 'user', ?, ?)",
                    workspace.getId(), stale.id(), staleStep.id(), replacement.getId(),
                    currentUser.getId()));
            }
        }
        Deal actionableDeal = newDeal(pipeline, stage, company);
        DealDocumentDto actionableDocument = documentService.generate(
            actionableDeal.getId(), documentTemplate.getId());
        DocumentApprovalDto actionable = approvalService.requestApproval(
            actionableDeal.getId(), actionableDocument.id(), null);
        flushSession();
        authenticateAs(caller, workspace.getId());

        List<Integer> visible = approvalService.inbox().stream()
            .map(ApprovalInboxItemDto::approvalId).toList();

        assertTrue(visible.contains(actionable.id()));
        verify(approvalMapper, times(2)).findActionableSteps(
            org.mockito.ArgumentMatchers.eq(workspace.getId()),
            org.mockito.ArgumentMatchers.eq(caller.getId()),
            anyString(),
            nullable(ooo.klae.connex.backend.dto.ApprovalInboxCursor.class),
            org.mockito.ArgumentMatchers.eq(200));
    }

    @Test
    void inboxStoresNothingAndTakesNoLocks() {
        User caller = approver();
        chainPolicy("sequential", step(1, "Anyone"));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        authenticateAs(caller, workspace.getId());
        List<String> before = approvalDomainSnapshot();
        clearInvocations(workspaceService, approvalMapper, documentMapper);

        assertFalse(approvalService.inbox().isEmpty());

        assertEquals(before, approvalDomainSnapshot());
        verify(workspaceService, times(0)).lockedPermissionsFor(anyInt(), anyInt());
        verify(workspaceService, times(0))
            .lockApprovalMutationMemberships(anyInt(), anyInt(), any());
        verify(approvalMapper).getByIds(workspace.getId(), List.of(requested.id()));
        verify(documentMapper).getByIds(workspace.getId(), List.of(document.id()));
        verify(documentMapper, times(0)).getById(anyInt(), anyInt());
        verify(documentMapper, times(0)).lockById(anyInt(), anyInt());
    }

    @Test
    void boundedInboxKeepsParallelActionableStepsAsSeparateWorkItems() {
        User caller = approver();
        chainPolicy("parallel", step(1, "Legal", caller), step(1, "Finance", caller));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        authenticateAs(caller, workspace.getId());

        List<ApprovalInboxItemDto> all = approvalService.inbox(10).stream()
            .filter(item -> item.approvalId() == requested.id()).toList();

        assertEquals(2, all.size());
        assertEquals(List.of(1, 2), all.stream().map(ApprovalInboxItemDto::stepOrder).toList());
        assertEquals(1, approvalService.inbox(1).size());
        assertTrue(all.stream().allMatch(item -> item.currentVersion().length() == 64));
    }

    @Test
    void staleStepIdConflictsInsteadOfDecidingAnotherActionableStep() {
        User caller = approver();
        chainPolicy("parallel", step(1, "Legal", caller), step(1, "Finance", caller));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        authenticateAs(caller, workspace.getId());
        List<ApprovalInboxItemDto> items = approvalService.inbox(10).stream()
            .filter(item -> item.approvalId() == requested.id()).toList();

        assertThrows(ConflictException.class, () -> approvalService.decideWorkItem(
            requested.id(), items.get(1).stepId(), "approved", null,
            items.getFirst().currentVersion()));
        assertEquals("pending", approvalService.getForDocument(
            deal.getId(), document.id()).getFirst().status());
    }

    @Test
    void anotherApproversDecisionInvalidatesTheProjectedStepVersion() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential", step(2, "Two of us", first, second));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        authenticateAs(first, workspace.getId());
        ApprovalInboxItemDto stale = approvalService.inbox(10).stream()
            .filter(item -> item.approvalId() == requested.id()).findFirst().orElseThrow();
        authenticateAs(second, workspace.getId());
        ApprovalInboxItemDto current = approvalService.inbox(10).stream()
            .filter(item -> item.approvalId() == requested.id()).findFirst().orElseThrow();
        approvalService.decideWorkItem(
            requested.id(), current.stepId(), "approved", null, current.currentVersion());
        authenticateAs(first, workspace.getId());

        assertThrows(ConflictException.class, () -> approvalService.decideWorkItem(
            requested.id(), stale.stepId(), "approved", null, stale.currentVersion()));
    }

    private List<String> approvalDomainSnapshot() {
        List<String> rows = new java.util.ArrayList<>();
        rows.addAll(jdbcTemplate.queryForList(
            "SELECT id, status, outcome_reason FROM document_approval WHERE workspace_id = ? ORDER BY id",
            workspace.getId()).stream().map(String::valueOf).toList());
        rows.addAll(jdbcTemplate.queryForList(
            "SELECT id, status, reminded_round, escalated_at FROM document_approval_step "
                + "WHERE workspace_id = ? ORDER BY id", workspace.getId())
            .stream().map(String::valueOf).toList());
        rows.addAll(jdbcTemplate.queryForList(
            "SELECT id, assignment_kind, assignment_round FROM document_approval_step_assignment "
                + "WHERE workspace_id = ? ORDER BY id", workspace.getId())
            .stream().map(String::valueOf).toList());
        rows.addAll(jdbcTemplate.queryForList(
            "SELECT id, decision FROM document_approval_decision WHERE workspace_id = ? ORDER BY id",
            workspace.getId()).stream().map(String::valueOf).toList());
        return rows;
    }

    @Test
    void expiredOutcomeConstraintAcceptsTheNewVocabularyAndStillRejectsAPendingReason() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "UPDATE document_approval SET outcome_reason = 'expired' "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), approval.id()));

        assertEquals(1, jdbcTemplate.update(
            "UPDATE document_approval SET status = 'expired', outcome_reason = 'expired' "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), approval.id()));
        flushSession();
        assertEquals("expired",
            approvalMapper.getById(workspace.getId(), approval.id()).getOutcomeReason());
    }

    @Test
    void approvalChainFenceStillRejectsRootApprovalWithAnExpiredStep() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        assertEquals(1, jdbcTemplate.update(
            "UPDATE document_approval_step SET status = 'expired', decided_at = CURRENT_TIMESTAMP "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(), approval.steps().getFirst().id()));

        DataAccessException refused = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "UPDATE document_approval SET status = 'approved', outcome_reason = 'quorum', "
                + "decided_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), approval.id()));
        SQLException cause = assertInstanceOf(SQLException.class, refused.getCause());
        assertEquals("45000", cause.getSQLState());
    }

    @Test
    void escalatedStepCannotCarryADueDate() {
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = approval.steps().getFirst().id();
        assertEquals(1, jdbcTemplate.update(
            "UPDATE document_approval_step SET escalated_at = CURRENT_TIMESTAMP "
                + "WHERE workspace_id = ? AND id = ?", workspace.getId(), stepId));

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "UPDATE document_approval_step SET due_at = CURRENT_TIMESTAMP "
                + "WHERE workspace_id = ? AND id = ?", workspace.getId(), stepId));
    }

    @Test
    void assignmentDelegationConstraintsFailClosed() {
        User bob = approver();
        chainPolicy("sequential", step(1, "Manager", bob));
        Deal deal = jpyDeal();
        DealDocumentDto document = generate(deal);
        DocumentApprovalDto approval = approvalService.requestApproval(
            deal.getId(), document.id(), null);
        int stepId = approval.steps().getFirst().id();

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
                + "assignment_kind, assignment_round, approver_kind, user_id, delegated_by_user_id) "
                + "VALUES (?, ?, ?, 'delegation', 0, 'any_approver', NULL, ?)",
            workspace.getId(), approval.id(), stepId, bob.getId()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
                + "assignment_kind, assignment_round, approver_kind, user_id, delegated_by_user_id) "
                + "VALUES (?, ?, ?, 'delegation', 1, 'user', ?, ?)",
            workspace.getId(), approval.id(), stepId, currentUser.getId(), bob.getId()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "INSERT INTO document_approval_step_assignment (workspace_id, approval_id, step_id, "
                + "assignment_kind, assignment_round, approver_kind, user_id, delegated_by_user_id) "
                + "VALUES (?, ?, ?, 'escalation', 0, 'user', ?, ?)",
            workspace.getId(), approval.id(), stepId, currentUser.getId(), bob.getId()));
        assertEquals(0, assignmentCount(stepId));
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

    @Test
    void requestApprovalPublishesApprovalRequestedTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        clearInvocations(ruleTriggers);

        approvalService.requestApproval(deal.getId(), doc.id(), null);

        verify(ruleTriggers).publish(
            workspace.getId(), "document", doc.id(), "document.approval_requested");
    }

    @Test
    void finalApprovalPublishesApprovedTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);
        authenticateAs(approver(), workspace.getId());
        clearInvocations(ruleTriggers);

        approvalService.decide(deal.getId(), doc.id(), "approved", null, null);

        verify(ruleTriggers).publish(
            workspace.getId(), "document", doc.id(), "document.approved");
    }

    @Test
    void partialQuorumApprovalPublishesNoTrigger() {
        User first = approver();
        User second = approver();
        chainPolicy("sequential", step(2, "Two of us", first, second));
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);
        authenticateAs(first, workspace.getId());
        clearInvocations(ruleTriggers);

        assertEquals("pending",
            approvalService.decide(deal.getId(), doc.id(), "approved", null, null).status());

        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void rejectionPublishesRejectedTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);
        authenticateAs(approver(), workspace.getId());
        clearInvocations(ruleTriggers);

        approvalService.decide(deal.getId(), doc.id(), "rejected", null, null);

        verify(ruleTriggers).publish(
            workspace.getId(), "document", doc.id(), "document.rejected");
    }

    @Test
    void cancelPublishesNoTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);
        clearInvocations(ruleTriggers);

        assertEquals("cancelled", approvalService.cancel(deal.getId(), doc.id()).status());

        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void policyInvalidationPublishesNoTrigger() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        DocumentApprovalDto requested = approvalService.requestApproval(deal.getId(), doc.id(), null);
        DocumentApproval approval = approvalMapper.getById(workspace.getId(), requested.id());
        clearInvocations(ruleTriggers);

        DocumentApprovalService.ApprovalMutationLocks locks =
            approvalService.lockApprovalMutationRecipients(
                workspace.getId(), doc.id(), currentUser.getId());
        approvalService.invalidateForPolicyChange(
            workspace.getId(), approval, "tightened", locks);

        assertEquals("invalidated",
            approvalService.getForDocument(deal.getId(), doc.id()).getFirst().status());
        verify(ruleTriggers, never()).publish(anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    void triggerPublishesAfterDocumentStatusWrite() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        clearInvocations(ruleTriggers, documentMapper);

        approvalService.requestApproval(deal.getId(), doc.id(), null);

        InOrder order = inOrder(documentMapper, ruleTriggers);
        order.verify(documentMapper).updateStatus(workspace.getId(), doc.id(), "pending_approval");
        order.verify(ruleTriggers).publish(
            workspace.getId(), "document", doc.id(), "document.approval_requested");
    }
}
