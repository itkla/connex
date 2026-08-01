package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Runs the approval lifecycle on generated deal documents: request, decide, cancel. The document
 * row is locked for the duration of each mutation so a document can never hold two pending
 * approvals or finalize concurrently with a decision. Approval states on the document
 * ({@code pending_approval}, {@code approved}) are only ever written here — the status endpoint
 * refuses them — which keeps the policy gate non-bypassable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentApprovalService {
    private final DocumentApprovalMapper approvalMapper;
    private final DealDocumentMapper documentMapper;
    private final DealMapper dealMapper;
    private final UserMapper userMapper;
    private final ApprovalPolicyService policyService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final NotificationPreferenceService notificationPreferenceService;
    private final NotificationDelivery notificationDelivery;
    private final ObjectMapper objectMapper;

    private static final String REQUEST_TYPE = "document.approval_request";
    private static final String DECISION_TYPE = "document.approval_decision";
    private static final String IN_APP = "in_app";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Approval history for one document, newest first. */
    public List<DocumentApprovalDto> getForDocument(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        requireDocument(workspaceId, dealId, documentId);
        return approvalMapper.getByDocumentId(workspaceId, documentId).stream()
            .map(DocumentApprovalDto::from).toList();
    }

    /** Sends a draft document for approval and notifies the workspace's approvers. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DocumentApprovalDto requestApproval(int dealId, int documentId, String comment) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        if (!"draft".equals(document.getStatus())) {
            throw new BadRequestException("Only draft documents can be sent for approval");
        }
        if (approvalMapper.findPending(workspaceId, documentId) != null) {
            throw new BadRequestException("An approval is already pending for this document");
        }
        ApprovalPolicy policy = policyService.firstMatch(
            policyService.activePolicies(workspaceId), document, parseContent(document));
        User actor = userMapper.getUserById(workspaceService.getCurrentUserId());

        DocumentApproval approval = new DocumentApproval();
        approval.setWorkspaceId(workspaceId);
        approval.setDealId(dealId);
        approval.setDocumentId(documentId);
        approval.setPolicyId(policy == null ? null : policy.getId());
        approval.setStatus("pending");
        approval.setRequestedBy(actor == null ? null : actor.getId());
        approval.setRequestComment(blankToNull(comment));
        approvalMapper.insert(approval);
        documentMapper.updateStatus(workspaceId, documentId, "pending_approval");

        auditService.record("document_approval.request", "deal", dealId, deal.getName(),
            "Requested approval for " + document.getType() + " v" + document.getVersion()
                + (policy == null ? "" : " under policy " + policy.getName()), null);
        notifyRequested(workspaceId, deal, document, approval, actor);
        return DocumentApprovalDto.from(requireApproval(workspaceId, approval.getId()));
    }

    /** Decides the pending approval on a document; approving unlocks finalization. */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_APPROVE)
    public DocumentApprovalDto decide(int dealId, int documentId, String decision, String comment) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPending(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        User actor = userMapper.getUserById(workspaceService.getCurrentUserId());
        if (actor == null || approval.getRequestedBy() == null || approval.getRequestedBy() == actor.getId()) {
            throw new ForbiddenException("An approval cannot be decided by its requester");
        }
        boolean approved = "approved".equals(decision);
        approvalMapper.decide(workspaceId, approval.getId(), decision,
            actor == null ? null : actor.getId(), blankToNull(comment));
        documentMapper.updateStatus(workspaceId, documentId, approved ? "approved" : "draft");

        auditService.record("document_approval.decide", "deal", dealId, deal.getName(),
            (approved ? "Approved " : "Rejected ") + document.getType() + " v" + document.getVersion(),
            auditService.singleChange("status", "pending", decision));
        notifyDecided(workspaceId, document, approval, decision, actor);
        return DocumentApprovalDto.from(requireApproval(workspaceId, approval.getId()));
    }

    /**
     * Cancels a pending approval because its document is being superseded. Called by
     * {@code DealDocumentService.updateStatus} under the document row lock — the superseder needs
     * {@link Permission#DEAL_UPDATE} but not requester identity, so the requester is notified that
     * their request was withdrawn on their behalf.
     */
    void cancelPendingOnSupersede(int workspaceId, Deal deal, DealDocument document) {
        DocumentApproval pending = approvalMapper.findPending(workspaceId, document.getId());
        if (pending == null) {
            return;
        }
        User actor = userMapper.getUserById(workspaceService.getCurrentUserId());
        approvalMapper.decide(workspaceId, pending.getId(), "cancelled",
            actor == null ? null : actor.getId(), null);
        auditService.record("document_approval.cancel", "deal", deal.getId(), deal.getName(),
            "Cancelled approval request for " + document.getType() + " v" + document.getVersion()
                + " by superseding the document", null);
        notifyDecided(workspaceId, document, pending, "cancelled", actor);
    }

    /** Withdraws the requester's own pending approval request, returning the document to draft. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DocumentApprovalDto cancel(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPending(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        int currentUserId = workspaceService.getCurrentUserId();
        if (approval.getRequestedBy() == null || approval.getRequestedBy() != currentUserId) {
            throw new ForbiddenException("Only the requester can cancel an approval request");
        }
        approvalMapper.decide(workspaceId, approval.getId(), "cancelled", currentUserId, null);
        documentMapper.updateStatus(workspaceId, documentId, "draft");
        auditService.record("document_approval.cancel", "deal", dealId, deal.getName(),
            "Cancelled approval request for " + document.getType() + " v" + document.getVersion(), null);
        return DocumentApprovalDto.from(requireApproval(workspaceId, approval.getId()));
    }

    private void notifyRequested(int workspaceId, Deal deal, DealDocument document,
            DocumentApproval approval, User actor) {
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        for (User member : workspaceService.getMembers(workspaceId)) {
            if (actor != null && member.getId() == actor.getId()) {
                continue;
            }
            if (!workspaceService.permissionsFor(workspaceId, member.getId()).contains(Permission.DOCUMENT_APPROVE)) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(member.getId(), REQUEST_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = baseNotification(workspaceId, document, actor, triggeredAt);
                notification.setRecipientId(member.getId());
                notification.setType(REQUEST_TYPE);
                notification.setSeverity("info");
                notification.setTitle("Approval requested");
                notification.setBody((actor == null ? "Someone" : actor.getDisplayName())
                    + " requested approval for " + titleOf(document));
                notification.setContextType("deal");
                notification.setContextId(deal.getId());
                notification.setDedupeKey(REQUEST_TYPE + ":" + approval.getId() + ":" + member.getId());
                notification.setData(json(Map.of(
                    "dealId", deal.getId(),
                    "documentId", document.getId(),
                    "documentTitle", titleOf(document),
                    "version", document.getVersion())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException e) {
                log.warn("Failed to deliver approval-request notification for document {} to recipient {}: {}",
                    document.getId(), member.getId(), e.toString());
            }
        }
    }

    private void notifyDecided(int workspaceId, DealDocument document, DocumentApproval approval,
            String decision, User actor) {
        Integer recipientId = approval.getRequestedBy();
        if (recipientId == null || (actor != null && recipientId == actor.getId())) {
            return;
        }
        boolean stillMember = workspaceService.getMembers(workspaceId).stream()
            .anyMatch(member -> member.getId() == recipientId);
        if (!stillMember || !notificationPreferenceService.isEnabled(recipientId, DECISION_TYPE, IN_APP)) {
            return;
        }
        try {
            boolean approved = "approved".equals(decision);
            boolean cancelled = "cancelled".equals(decision);
            Notification notification = baseNotification(workspaceId, document, actor,
                LocalDateTime.now(ZoneOffset.UTC).format(TS));
            notification.setRecipientId(recipientId);
            notification.setType(DECISION_TYPE);
            notification.setSeverity(approved || cancelled ? "info" : "warning");
            notification.setTitle(approved ? "Document approved"
                : cancelled ? "Approval request cancelled" : "Document rejected");
            notification.setBody(cancelled
                ? "The approval request for " + titleOf(document) + " was withdrawn because the document was superseded"
                : (actor == null ? "An approver" : actor.getDisplayName())
                    + (approved ? " approved " : " rejected ") + titleOf(document));
            notification.setContextType("deal");
            notification.setContextId(document.getDealId());
            notification.setDedupeKey(DECISION_TYPE + ":" + approval.getId() + ":" + recipientId);
            notification.setData(json(Map.of(
                "dealId", document.getDealId(),
                "documentId", document.getId(),
                "documentTitle", titleOf(document),
                "version", document.getVersion(),
                "decision", decision)));
            notificationDelivery.deliver(notification);
        } catch (RuntimeException e) {
            log.warn("Failed to deliver approval-decision notification for document {} to recipient {}: {}",
                document.getId(), recipientId, e.toString());
        }
    }

    private Notification baseNotification(int workspaceId, DealDocument document, User actor, String triggeredAt) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspaceId);
        notification.setCategory("deal");
        notification.setTemplateVersion(1);
        if (actor != null) {
            notification.setActorId(actor.getId());
            notification.setActorLabel(actor.getDisplayName());
        }
        notification.setSourceType("deal_document");
        notification.setSourceId(document.getId());
        notification.setSourceLabel(titleOf(document));
        notification.setActionUrl("/records/deals/" + document.getDealId());
        notification.setTriggeredAt(triggeredAt);
        return notification;
    }

    private String titleOf(DealDocument document) {
        return document.getTitle() == null || document.getTitle().isBlank()
            ? document.getType() + " v" + document.getVersion() : document.getTitle();
    }

    private String json(Map<String, Object> data) {
        return objectMapper.writeValueAsString(data);
    }

    private DocumentContent parseContent(DealDocument document) {
        return document.getContent() == null ? null
            : objectMapper.readValue(document.getContent(), DocumentContent.class);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Deal requireDeal(int workspaceId, int dealId) {
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        return deal;
    }

    private DealDocument requireDocument(int workspaceId, int dealId, int documentId) {
        DealDocument document = documentMapper.getById(workspaceId, documentId);
        if (document == null || document.getDealId() != dealId) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        return document;
    }

    private DealDocument lockDocument(int workspaceId, int dealId, int documentId) {
        DealDocument document = documentMapper.lockById(workspaceId, documentId);
        if (document == null || document.getDealId() != dealId) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        return document;
    }

    private DocumentApproval requireApproval(int workspaceId, int id) {
        DocumentApproval approval = approvalMapper.getById(workspaceId, id);
        if (approval == null) throw new ResourceNotFoundException("Approval not found with id: " + id);
        return approval;
    }
}
