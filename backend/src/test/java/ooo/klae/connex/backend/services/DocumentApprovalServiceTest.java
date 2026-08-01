package ooo.klae.connex.backend.services;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class DocumentApprovalServiceTest extends AbstractServiceTest {

    @Autowired DocumentApprovalService approvalService;
    @Autowired ApprovalPolicyService policyService;
    @Autowired DealDocumentService documentService;
    @Autowired DocumentTemplateService templateService;
    @Autowired DealLineItemService lineItemService;
    @Autowired ProductService productService;
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
        DocumentApprovalDto decided = approvalService.decide(deal.getId(), doc.id(), "approved", "looks good");
        assertEquals("approved", decided.status());
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
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null));
        assertEquals("pending_approval", documentService.getOne(deal.getId(), doc.id()).status());
        assertEquals("pending", documentService.getOne(deal.getId(), doc.id()).latestApproval().status());
    }

    @Test
    void requesterCannotRejectOwnDocument() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        approvalService.requestApproval(deal.getId(), doc.id(), null);

        assertThrows(ForbiddenException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "rejected", null));
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
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null));
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
        DocumentApprovalDto rejected = approvalService.decide(deal.getId(), doc.id(), "rejected", "too steep");
        assertEquals("rejected", rejected.status());

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

        assertThrows(BadRequestException.class,
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null));
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
            () -> approvalService.decide(deal.getId(), doc.id(), "approved", null));
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
            () -> approvalService.decide(other.getId(), doc.id(), "approved", null));
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
        approvalService.decide(deal.getId(), first.id(), "approved", null);

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
            "document.approval_request:" + approval.id() + ":" + approver.getId()));

        authenticateAs(approver, workspace.getId());
        approvalService.decide(deal.getId(), doc.id(), "approved", null);
        assertNotNull(notificationMapper.findByDedupe(workspace.getId(), currentUser.getId(),
            "document.approval_decision:" + approval.id() + ":" + currentUser.getId()));
    }

    @Test
    void voluntaryRequestWithoutPolicyCarriesNoPolicyId() {
        Deal deal = jpyDeal();
        DealDocumentDto doc = generate(deal);
        assertFalse(doc.requiresApproval());

        DocumentApprovalDto approval = approvalService.requestApproval(deal.getId(), doc.id(), null);
        assertNull(approval.policyId());
        authenticateAs(approver(), workspace.getId());
        approvalService.decide(deal.getId(), doc.id(), "approved", null);
        authenticateAs(currentUser, workspace.getId());
        assertEquals("final", documentService.updateStatus(deal.getId(), doc.id(), "final").status());
    }
}
