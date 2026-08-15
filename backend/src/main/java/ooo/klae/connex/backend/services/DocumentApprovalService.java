package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentApprovalDecision;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.dto.DocumentApprovalStepDto;
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
 * approvals, collect two racing decisions on one step, or finalize concurrently with a decision.
 * Approval states on the document ({@code pending_approval}, {@code approved}) are only ever
 * written here — the status endpoint refuses them — which keeps the policy gate non-bypassable.
 *
 * <p>Requesting approval freezes the matching policy's chain onto the request, so later policy
 * edits never rewrite an in-flight approval. A {@code sequential} chain opens one step at a time;
 * a {@code parallel} chain opens all of them. A step passes once it holds {@code requiredCount}
 * distinct approvals, and a single rejection terminates the whole request.
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
    private static final String TERMINATED_TYPE = "document.approval_terminated";
    private static final String IN_APP = "in_app";
    private static final String ANY_APPROVER = "any_approver";
    private static final String ACTIVE = "active";
    private static final String PENDING = "pending";
    private static final String APPROVED = "approved";
    private static final String REJECTED = "rejected";
    private static final String CANCELLED = "cancelled";
    private static final String INVALIDATED = "invalidated";
    private static final String UNSATISFIABLE = "unsatisfiable";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Approval history for one document, newest first. */
    public List<DocumentApprovalDto> getForDocument(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        DealDocument document = requireDocument(workspaceId, dealId, documentId);
        ApproverPool pool = approverPool(workspaceId);
        return withChain(workspaceId, approvalMapper.getByDocumentId(workspaceId, documentId)).stream()
            .map(approval -> toDto(approval, document, pool)).toList();
    }

    /**
     * Attaches each approval's frozen chain and decision history in two extra queries. Callers that
     * render approvals alongside documents use this so the chain never loads per row.
     */
    List<DocumentApproval> withChain(int workspaceId, List<DocumentApproval> approvals) {
        if (approvals.isEmpty()) {
            return approvals;
        }
        List<Integer> ids = approvals.stream().map(DocumentApproval::getId).toList();
        Map<Integer, List<DocumentApprovalDecision>> decisionsByStep =
            approvalMapper.getDecisionsByApprovalIds(workspaceId, ids).stream()
                .collect(Collectors.groupingBy(DocumentApprovalDecision::getStepId));
        List<DocumentApprovalStep> steps = approvalMapper.getStepsByApprovalIds(workspaceId, ids);
        steps.forEach(step -> step.setDecisions(decisionsByStep.getOrDefault(step.getId(), List.of())));
        Map<Integer, List<DocumentApprovalStep>> stepsByApproval = steps.stream()
            .collect(Collectors.groupingBy(DocumentApprovalStep::getApprovalId));
        approvals.forEach(approval ->
            approval.setSteps(stepsByApproval.getOrDefault(approval.getId(), List.of())));
        return approvals;
    }

    /** Sends a draft document for approval and notifies the workspace's approvers. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DocumentApprovalDto requestApproval(int dealId, int documentId, String comment) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        if (!workspaceService.lockedPermissionsFor(workspaceId, actorId)
                .contains(Permission.DEAL_UPDATE)) {
            throw new ForbiddenException("You cannot update deals in this workspace");
        }
        Deal deal = requireDeal(workspaceId, dealId);
        requireDocument(workspaceId, dealId, documentId);
        List<ApprovalPolicy> policies = policyService.activePoliciesForRequest(workspaceId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        if (!"draft".equals(document.getStatus())) {
            throw new BadRequestException("Only draft documents can be sent for approval");
        }
        if (approvalMapper.findPending(workspaceId, documentId) != null) {
            throw new BadRequestException("An approval is already pending for this document");
        }
        ApprovalPolicy policy = policyService.firstMatch(policies, document, parseContent(document));
        User actor = userMapper.getUserById(actorId);

        DocumentApproval approval = new DocumentApproval();
        approval.setWorkspaceId(workspaceId);
        approval.setDealId(dealId);
        approval.setDocumentId(documentId);
        approval.setPolicyId(policy == null ? null : policy.getId());
        approval.setStatus(PENDING);
        approval.setMode(policy == null || policy.getMode() == null ? "sequential" : policy.getMode());
        approval.setSeparationOfDuties(policy == null || policy.getSeparationOfDuties() == null
            ? "strict" : policy.getSeparationOfDuties());
        approval.setRequestedBy(actor == null ? null : actor.getId());
        approval.setRequestComment(blankToNull(comment));

        ApproverPool pool = approverPool(workspaceId);
        requireChainIsSatisfiable(policy, approval, document, pool);
        approvalMapper.insert(approval);
        List<DocumentApprovalStep> opened = freezeChain(workspaceId, approval, policy);
        documentMapper.updateStatus(workspaceId, documentId, "pending_approval");

        auditService.record("document_approval.request", "deal", dealId, deal.getName(),
            "Requested approval for " + document.getType() + " v" + document.getVersion()
                + (policy == null ? "" : " under policy " + policy.getName()), null);
        notifyStepApprovers(workspaceId, deal, document, approval, opened, actor, pool);
        return toDto(requireApproval(workspaceId, approval.getId()), document, pool);
    }

    /**
     * Every workspace member paired with whether they may approve documents today, resolved once per
     * request so neither chain validation nor the notification fan-out re-reads permissions per step.
     */
    private ApproverPool approverPool(int workspaceId) {
        List<User> members = workspaceService.getMembers(workspaceId);
        Set<Integer> approvers = members.stream()
            .map(User::getId)
            .filter(memberId -> workspaceService.permissionsFor(workspaceId, memberId)
                .contains(Permission.DOCUMENT_APPROVE))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ApproverPool(members, approvers);
    }

    /**
     * Refuses a request that this particular actor could never get past. Separation of duties
     * removes the requester and the document's author from the pool for this request only, so a step
     * whose remaining named approvers can no longer reach its quorum is a dead end the requester can
     * see and fix now. A step open to any approver is deliberately left alone: its pool legitimately
     * grows as people join the workspace.
     */
    private void requireChainIsSatisfiable(ApprovalPolicy policy, DocumentApproval approval,
            DealDocument document, ApproverPool pool) {
        if ("off".equals(approval.getSeparationOfDuties()) || policy == null) {
            return;
        }
        Set<Integer> blocked = new LinkedHashSet<>();
        if (approval.getRequestedBy() != null) {
            blocked.add(approval.getRequestedBy());
        }
        if ("strict".equals(approval.getSeparationOfDuties()) && document.getCreatedBy() != null) {
            blocked.add(document.getCreatedBy());
        }
        for (ApprovalPolicyStep step : policy.getSteps()) {
            List<Integer> named = step.getApprovers().stream()
                .filter(approver -> !ANY_APPROVER.equals(approver.getApproverKind()))
                .map(ApprovalStepApprover::getUserId)
                .filter(userId -> userId != null)
                .toList();
            long remaining = named.stream().filter(userId -> !blocked.contains(userId)).count();
            if (!named.isEmpty() && remaining < step.getRequiredCount()) {
                throw new BadRequestException("Step \""
                    + (step.getName() == null || step.getName().isBlank() ? "unnamed" : step.getName())
                    + "\" has too few named approvers who can decide this document");
            }
        }
        if (pool.approvers().isEmpty()) {
            throw new BadRequestException("Nobody in this workspace can approve documents yet");
        }
    }

    /** The workspace's members and the subset of them that may approve documents. */
    private record ApproverPool(List<User> members, Set<Integer> approvers) {
    }

    /**
     * Records the caller's decision on one step of the pending approval. Approving advances or
     * completes the chain — completion unlocks finalization — while rejecting terminates the whole
     * request and returns the document to draft.
     */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_APPROVE)
    public DocumentApprovalDto decide(int dealId, int documentId, String decision, String comment,
            Integer stepId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Deal deal = requireDeal(workspaceId, dealId);
        User actor = userMapper.getUserById(workspaceService.getCurrentUserId());
        if (actor == null) {
            throw new ForbiddenException("Only a workspace member can decide an approval");
        }
        if (!workspaceService.lockedPermissionsFor(workspaceId, actor.getId())
                .contains(Permission.DOCUMENT_APPROVE)) {
            throw new ForbiddenException("You cannot approve documents in this workspace");
        }
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPending(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        requireSeparationOfDuties(approval, document, actor);
        List<DocumentApprovalStep> steps = chainOf(workspaceId, approval);
        DocumentApprovalStep step = resolveStep(steps, stepId, actor);

        DocumentApprovalDecision recorded = new DocumentApprovalDecision();
        recorded.setWorkspaceId(workspaceId);
        recorded.setApprovalId(approval.getId());
        recorded.setStepId(step.getId());
        recorded.setDecision(decision);
        recorded.setDecidedBy(actor.getId());
        recorded.setComment(blankToNull(comment));
        approvalMapper.insertDecision(recorded);

        boolean approved = APPROVED.equals(decision);
        String outcome = approved
            ? advanceAfterApproval(workspaceId, deal, document, approval, steps, step, actor, comment)
            : PENDING;
        if (!approved) {
            approvalMapper.updateStepStatus(workspaceId, step.getId(), REJECTED, ACTIVE);
            approvalMapper.cancelOpenSteps(workspaceId, approval.getId());
            approvalMapper.decide(workspaceId, approval.getId(), REJECTED, actor.getId(),
                blankToNull(comment), REJECTED, null);
            documentMapper.updateStatus(workspaceId, documentId, "draft");
            notifyDecided(workspaceId, document, approval, REJECTED, actor);
            outcome = REJECTED;
        }

        String subject = document.getType() + " v" + document.getVersion() + " at step " + step.getStepOrder();
        auditService.record("document_approval.decide", "deal", dealId, deal.getName(),
            PENDING.equals(outcome)
                ? "Recorded an approval for " + subject + ", which is still awaiting other approvers"
                : (approved ? "Approved " : "Rejected ") + subject,
            PENDING.equals(outcome) ? null : auditService.singleChange("status", PENDING, outcome));
        return toDto(workspaceId, requireApproval(workspaceId, approval.getId()), document);
    }

    /**
     * Marks the step passed once it holds its quorum, then opens the next sequential step or
     * completes the request when every step has passed. Returns the request's resulting status, so
     * an approval that only advances one step is never audited as approving the document.
     */
    private String advanceAfterApproval(int workspaceId, Deal deal, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> steps, DocumentApprovalStep step,
            User actor, String comment) {
        if (approvalMapper.countStepApprovals(workspaceId, step.getId()) < step.getRequiredCount()) {
            return PENDING;
        }
        approvalMapper.updateStepStatus(workspaceId, step.getId(), APPROVED, ACTIVE);
        step.setStatus(APPROVED);
        DocumentApprovalStep next = "parallel".equals(approval.getMode()) ? null
            : steps.stream().filter(candidate -> PENDING.equals(candidate.getStatus()))
                .min(Comparator.comparingInt(DocumentApprovalStep::getStepOrder)).orElse(null);
        if (next != null) {
            approvalMapper.updateStepStatus(workspaceId, next.getId(), ACTIVE, PENDING);
            next.setStatus(ACTIVE);
            notifyStepApprovers(workspaceId, deal, document, approval, List.of(next), actor,
                approverPool(workspaceId));
            return PENDING;
        }
        boolean complete = steps.stream().allMatch(candidate -> APPROVED.equals(candidate.getStatus()));
        if (!complete) {
            return PENDING;
        }
        approvalMapper.decide(workspaceId, approval.getId(), APPROVED, actor.getId(),
            blankToNull(comment), "quorum", null);
        documentMapper.updateStatus(workspaceId, document.getId(), APPROVED);
        notifyDecided(workspaceId, document, approval, APPROVED, actor);
        return APPROVED;
    }

    /**
     * Enforces the separation-of-duties rule frozen onto the request. {@code strict} fails closed
     * when the requester or author is unknown, because an unattributable request must not be
     * decidable by someone who might be its author.
     */
    private void requireSeparationOfDuties(DocumentApproval approval, DealDocument document, User actor) {
        String rule = approval.getSeparationOfDuties();
        if ("off".equals(rule)) {
            return;
        }
        boolean strict = !"requester".equals(rule);
        if (approval.getRequestedBy() == null || approval.getRequestedBy() == actor.getId()) {
            throw new ForbiddenException("You cannot decide an approval you requested");
        }
        if (strict && (document.getCreatedBy() == null || document.getCreatedBy() == actor.getId())) {
            throw new ForbiddenException("You cannot decide an approval for a document you authored");
        }
    }

    /**
     * The step this decision applies to: the requested one when {@code stepId} is given, otherwise
     * the caller's lowest-order step that is still awaiting their decision.
     */
    private DocumentApprovalStep resolveStep(List<DocumentApprovalStep> steps, Integer stepId, User actor) {
        if (stepId == null) {
            return steps.stream()
                .filter(step -> ACTIVE.equals(step.getStatus()))
                .filter(step -> isApprover(step, actor) && !hasDecided(step, actor))
                .min(Comparator.comparingInt(DocumentApprovalStep::getStepOrder))
                .orElseThrow(() -> new ForbiddenException(
                    "You are not an approver for any step awaiting a decision"));
        }
        DocumentApprovalStep step = steps.stream().filter(candidate -> candidate.getId() == stepId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Approval step not found with id: " + stepId));
        if (!ACTIVE.equals(step.getStatus())) {
            throw new BadRequestException("That approval step is not awaiting a decision");
        }
        if (!isApprover(step, actor)) {
            throw new ForbiddenException("You are not an approver for that step");
        }
        if (hasDecided(step, actor)) {
            throw new BadRequestException("You have already decided that step");
        }
        return step;
    }

    private boolean isApprover(DocumentApprovalStep step, User actor) {
        return step.getApprovers().stream().anyMatch(approver ->
            ANY_APPROVER.equals(approver.getApproverKind())
                || (approver.getUserId() != null && approver.getUserId() == actor.getId()));
    }

    private boolean hasDecided(DocumentApprovalStep step, User actor) {
        return step.getDecisions().stream().anyMatch(decision -> decision.getDecidedBy() == actor.getId());
    }

    /**
     * Copies the matching policy's chain onto the request. A policy with no explicit steps, and a
     * voluntary request with no matching policy, both freeze one step needing a single approval
     * from any member who can approve documents. Returns the steps that opened immediately.
     */
    private List<DocumentApprovalStep> freezeChain(int workspaceId, DocumentApproval approval,
            ApprovalPolicy policy) {
        List<ApprovalPolicyStep> template = policy == null ? List.of() : policy.getSteps();
        boolean parallel = "parallel".equals(approval.getMode());
        List<DocumentApprovalStep> opened = new ArrayList<>();
        if (template.isEmpty()) {
            opened.add(insertStep(workspaceId, approval, 1, null, 1, ACTIVE, List.of(anyApprover())));
            return opened;
        }
        int order = 1;
        for (ApprovalPolicyStep source : template) {
            String status = parallel || order == 1 ? ACTIVE : PENDING;
            DocumentApprovalStep step = insertStep(workspaceId, approval, order, source.getName(),
                source.getRequiredCount(), status, source.getApprovers());
            if (ACTIVE.equals(status)) {
                opened.add(step);
            }
            order++;
        }
        return opened;
    }

    private DocumentApprovalStep insertStep(int workspaceId, DocumentApproval approval, int order,
            String name, int requiredCount, String status, List<ApprovalStepApprover> approvers) {
        DocumentApprovalStep step = new DocumentApprovalStep();
        step.setWorkspaceId(workspaceId);
        step.setApprovalId(approval.getId());
        step.setStepOrder(order);
        step.setName(name);
        step.setRequiredCount(requiredCount);
        step.setStatus(status);
        approvalMapper.insertStep(step);
        List<ApprovalStepApprover> frozen = new ArrayList<>();
        for (ApprovalStepApprover source : approvers) {
            ApprovalStepApprover approver = new ApprovalStepApprover();
            approver.setWorkspaceId(workspaceId);
            approver.setStepId(step.getId());
            approver.setApproverKind(source.getApproverKind());
            approver.setUserId(ANY_APPROVER.equals(source.getApproverKind()) ? null : source.getUserId());
            approvalMapper.insertStepApprover(approver);
            frozen.add(approver);
        }
        step.setApprovers(frozen);
        return step;
    }

    private ApprovalStepApprover anyApprover() {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind(ANY_APPROVER);
        return approver;
    }

    /**
     * The frozen chain of a pending approval. A request created by a binary older than the chain
     * runtime carries no steps; rather than refusing every decision on it, freeze the one implicit
     * step it always meant — a single approval from anyone who can approve documents.
     */
    private List<DocumentApprovalStep> chainOf(int workspaceId, DocumentApproval approval) {
        List<DocumentApprovalStep> steps = withChain(workspaceId, List.of(approval)).getFirst().getSteps();
        if (!steps.isEmpty()) {
            return steps;
        }
        return List.of(insertStep(workspaceId, approval, 1, null, 1, ACTIVE, List.of(anyApprover())));
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
        approvalMapper.cancelOpenSteps(workspaceId, pending.getId());
        approvalMapper.decide(workspaceId, pending.getId(), CANCELLED,
            actor == null ? null : actor.getId(), null, "superseded", null);
        auditService.record("document_approval.cancel", "deal", deal.getId(), deal.getName(),
            "Cancelled approval request for " + document.getType() + " v" + document.getVersion()
                + " by superseding the document", null);
        notifyDecided(workspaceId, document, pending, CANCELLED, actor);
    }

    /** Withdraws the requester's own pending approval request, returning the document to draft. */
    @Transactional
    @RequirePermission(Permission.DEAL_UPDATE)
    public DocumentApprovalDto cancel(int dealId, int documentId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        if (!workspaceService.lockedPermissionsFor(workspaceId, currentUserId)
                .contains(Permission.DEAL_UPDATE)) {
            throw new ForbiddenException("You cannot update deals in this workspace");
        }
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPending(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        if (approval.getRequestedBy() == null) {
            workspaceService.requireRole(WorkspaceService.Role.ADMIN);
        } else if (approval.getRequestedBy() != currentUserId) {
            throw new ForbiddenException("Only the requester can cancel an approval request");
        }
        approvalMapper.cancelOpenSteps(workspaceId, approval.getId());
        String outcomeReason = approval.getRequestedBy() == null
            ? "cancelled_by_admin" : "cancelled_by_requester";
        approvalMapper.decide(workspaceId, approval.getId(), CANCELLED, currentUserId, null,
            outcomeReason, null);
        documentMapper.updateStatus(workspaceId, documentId, "draft");
        auditService.record("document_approval.cancel", "deal", dealId, deal.getName(),
            "Cancelled approval request for " + document.getType() + " v" + document.getVersion(), null);
        return toDto(workspaceId, requireApproval(workspaceId, approval.getId()), document);
    }

    /**
     * Terminates one still-pending request after a confirmed tightening policy edit.
     *
     * <p>The caller must already hold a transaction. This method is package-private so it stays off
     * the RBAC-guarded surface, and Spring's proxy-based transaction management only advises public
     * methods, so an annotation here would be silently ignored and the document row lock would not
     * survive the write.
     */
    void invalidateForPolicyChange(int workspaceId, DocumentApproval approval, String detail) {
        requireActiveTransaction();
        DealDocument document = lockDocument(
            workspaceId, approval.getDealId(), approval.getDocumentId());
        DocumentApproval current = approvalMapper.getById(workspaceId, approval.getId());
        if (current == null || current.getDocumentId() != approval.getDocumentId()
                || !PENDING.equals(current.getStatus())) {
            return;
        }
        current = withChain(workspaceId, List.of(current)).getFirst();
        int changed = approvalMapper.decide(workspaceId, current.getId(), INVALIDATED,
            null, null, "policy_invalidated", detail);
        if (changed == 0) {
            return;
        }
        approvalMapper.cancelOpenSteps(workspaceId, current.getId());
        documentMapper.updateStatus(workspaceId, document.getId(), "draft");
        Deal deal = requireDeal(workspaceId, current.getDealId());
        auditService.record("document_approval.invalidate", "deal", deal.getId(), deal.getName(),
            "Invalidated approval request for " + document.getType() + " v" + document.getVersion()
                + " after its policy was tightened",
            auditService.singleChange("status", PENDING, INVALIDATED));
        User actor = userMapper.getUserById(workspaceService.getCurrentUserId());
        List<DocumentApprovalStep> activeSteps = current.getSteps().stream()
            .filter(step -> ACTIVE.equals(step.getStatus())).toList();
        notifyTerminated(workspaceId, document, current, activeSteps, actor,
            "policy_invalidated", detail, true);
    }

    /**
     * Terminates one pending request when its frozen chain can no longer reach quorum.
     *
     * <p>The caller must already hold a transaction, for the reason given on
     * {@link #invalidateForPolicyChange}.
     */
    boolean terminateIfUnsatisfiable(int workspaceId, DocumentApproval approval) {
        requireActiveTransaction();
        DealDocument document = lockDocument(
            workspaceId, approval.getDealId(), approval.getDocumentId());
        DocumentApproval current = approvalMapper.getById(workspaceId, approval.getId());
        if (current == null || current.getDocumentId() != approval.getDocumentId()
                || !PENDING.equals(current.getStatus())) {
            return false;
        }
        current = withChain(workspaceId, List.of(current)).getFirst();
        ApprovalProjection projection = projectAvailability(
            current, document, approverPool(workspaceId));
        if (projection.overall().satisfiable()) {
            return false;
        }
        int changed = approvalMapper.decide(workspaceId, current.getId(), UNSATISFIABLE,
            null, null, UNSATISFIABLE, outcomeDetail(current, projection.overall()));
        if (changed == 0) {
            return false;
        }
        DocumentApprovalStep blockingStep = current.getSteps().stream()
            .filter(step -> step.getId() == projection.overall().blockingStepId())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Blocking approval step is missing"));
        if (approvalMapper.updateStepStatus(workspaceId, blockingStep.getId(), UNSATISFIABLE,
                blockingStep.getStatus()) != 1) {
            throw new IllegalStateException("Blocking approval step changed during termination");
        }
        approvalMapper.cancelOpenSteps(workspaceId, current.getId());
        documentMapper.updateStatus(workspaceId, document.getId(), "draft");
        Deal deal = requireDeal(workspaceId, current.getDealId());
        String detail = outcomeDetail(current, projection.overall());
        auditService.record("document_approval.unsatisfiable", "deal", deal.getId(), deal.getName(),
            "Terminated approval request for " + document.getType() + " v" + document.getVersion()
                + " because step " + blockingStep.getStepOrder() + " could no longer reach quorum",
            auditService.singleChange("status", PENDING, UNSATISFIABLE));
        User actor = userMapper.getUserById(workspaceService.getCurrentUserId());
        notifyTerminated(workspaceId, document, current, List.of(), actor,
            UNSATISFIABLE, detail, false);
        return true;
    }

    /** Projects one approval into its client DTO using current membership and permissions. */
    DocumentApprovalDto toDto(int workspaceId, DocumentApproval approval, DealDocument document) {
        return toDto(approval, document, approverPool(workspaceId));
    }

    /** Projects the newest approval per document while resolving the current approver pool once. */
    Map<Integer, DocumentApprovalDto> latestDtosByDocument(int workspaceId,
            List<DocumentApproval> approvals, Map<Integer, DealDocument> documentsById) {
        ApproverPool pool = approverPool(workspaceId);
        Map<Integer, DocumentApprovalDto> result = new LinkedHashMap<>();
        for (DocumentApproval approval : withChain(workspaceId, approvals)) {
            DealDocument document = documentsById.get(approval.getDocumentId());
            if (document != null) {
                result.putIfAbsent(approval.getDocumentId(), toDto(approval, document, pool));
            }
        }
        return result;
    }

    private DocumentApprovalDto toDto(DocumentApproval approval, DealDocument document,
            ApproverPool pool) {
        ApprovalProjection projection = projectAvailability(approval, document, pool);
        List<DocumentApprovalStepDto> steps = approval.getSteps().stream()
            .map(step -> {
                ApprovalAvailability availability = projection.steps().get(step.getId());
                return DocumentApprovalStepDto.from(step, availability.satisfiable(),
                    availability.reason());
            })
            .toList();
        return DocumentApprovalDto.from(approval, projection.overall().satisfiable(),
            projection.overall().reason(), steps);
    }

    private ApprovalProjection projectAvailability(DocumentApproval approval,
            DealDocument document, ApproverPool pool) {
        Map<Integer, ApprovalAvailability> steps = new LinkedHashMap<>();
        ApprovalAvailability firstBlocking = null;
        for (DocumentApprovalStep step : approval.getSteps()) {
            ApprovalAvailability availability = availabilityForStep(
                approval, document, step, pool);
            steps.put(step.getId(), availability);
            if (!availability.satisfiable() && firstBlocking == null) {
                firstBlocking = availability;
            }
        }
        ApprovalAvailability overall = firstBlocking == null
            ? new ApprovalAvailability(true, null, null) : firstBlocking;
        return new ApprovalProjection(overall, steps);
    }

    private ApprovalAvailability availabilityForStep(DocumentApproval approval,
            DealDocument document, DocumentApprovalStep step, ApproverPool pool) {
        if (UNSATISFIABLE.equals(step.getStatus())) {
            return new ApprovalAvailability(false, approval.getOutcomeDetail(), step.getId());
        }
        if (!PENDING.equals(approval.getStatus())
                || (!PENDING.equals(step.getStatus()) && !ACTIVE.equals(step.getStatus()))) {
            return new ApprovalAvailability(true, null, null);
        }
        String attributionFailure = attributionFailure(approval, document);
        if (attributionFailure != null) {
            return new ApprovalAvailability(false, attributionFailure, step.getId());
        }
        Set<Integer> eligible = new LinkedHashSet<>();
        boolean anyApprover = step.getApprovers().stream()
            .anyMatch(approver -> ANY_APPROVER.equals(approver.getApproverKind()));
        if (anyApprover) {
            eligible.addAll(pool.approvers());
        } else {
            step.getApprovers().stream()
                .map(ApprovalStepApprover::getUserId)
                .filter(userId -> userId != null && pool.approvers().contains(userId))
                .forEach(eligible::add);
        }
        eligible.removeAll(separationExclusions(approval, document));
        Set<Integer> approvedBy = step.getDecisions().stream()
            .filter(decision -> APPROVED.equals(decision.getDecision()))
            .map(DocumentApprovalDecision::getDecidedBy)
            .collect(Collectors.toSet());
        eligible.removeAll(approvedBy);
        int remainingNeeded = Math.max(0, step.getRequiredCount() - approvedBy.size());
        if (eligible.size() >= remainingNeeded) {
            return new ApprovalAvailability(true, null, null);
        }
        String reason = "Only " + eligible.size() + " eligible undecided approver"
            + (eligible.size() == 1 ? " remains" : "s remain") + " for " + remainingNeeded
            + " outstanding approval" + (remainingNeeded == 1 ? "" : "s");
        return new ApprovalAvailability(false, reason, step.getId());
    }

    private String attributionFailure(DocumentApproval approval, DealDocument document) {
        if ("off".equals(approval.getSeparationOfDuties())) {
            return null;
        }
        if (approval.getRequestedBy() == null) {
            return "Requester attribution is unavailable under separation of duties";
        }
        if ("strict".equals(approval.getSeparationOfDuties()) && document.getCreatedBy() == null) {
            return "Document author attribution is unavailable under strict separation of duties";
        }
        return null;
    }

    private Set<Integer> separationExclusions(DocumentApproval approval, DealDocument document) {
        Set<Integer> excluded = new LinkedHashSet<>();
        if ("off".equals(approval.getSeparationOfDuties())) {
            return excluded;
        }
        if (approval.getRequestedBy() != null) {
            excluded.add(approval.getRequestedBy());
        }
        if ("strict".equals(approval.getSeparationOfDuties()) && document.getCreatedBy() != null) {
            excluded.add(document.getCreatedBy());
        }
        return excluded;
    }

    private String outcomeDetail(DocumentApproval approval, ApprovalAvailability availability) {
        DocumentApprovalStep step = approval.getSteps().stream()
            .filter(candidate -> candidate.getId() == availability.blockingStepId())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Blocking approval step is missing"));
        String label = step.getName() == null || step.getName().isBlank()
            ? "Step " + step.getStepOrder()
            : "Step " + step.getStepOrder() + " (" + step.getName() + ")";
        return label + ": " + availability.reason();
    }

    /**
     * Notifies the approvers of the steps that just opened. A step assigned to named approvers
     * reaches only those members; a step open to any approver reaches every member who can approve.
     * Members who lost {@link Permission#DOCUMENT_APPROVE} since the chain was frozen are skipped.
     */
    private void notifyStepApprovers(int workspaceId, Deal deal, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> steps, User actor, ApproverPool pool) {
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        for (DocumentApprovalStep step : steps) {
            for (User member : recipientsFor(pool, step)) {
                if (actor != null && member.getId() == actor.getId()) {
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
                    notification.setDedupeKey(REQUEST_TYPE + ":" + approval.getId() + ":"
                        + step.getId() + ":" + member.getId());
                    notification.setData(json(Map.of(
                        "dealId", deal.getId(),
                        "documentId", document.getId(),
                        "documentTitle", titleOf(document),
                        "version", document.getVersion(),
                        "stepId", step.getId(),
                        "stepOrder", step.getStepOrder())));
                    notificationDelivery.deliver(notification);
                } catch (RuntimeException e) {
                    log.warn("Failed to deliver approval-request notification for document {} to recipient {}: {}",
                        document.getId(), member.getId(), e.toString());
                }
            }
        }
    }

    private Set<User> recipientsFor(ApproverPool pool, DocumentApprovalStep step) {
        boolean anyApprover = step.getApprovers().stream()
            .anyMatch(approver -> ANY_APPROVER.equals(approver.getApproverKind()));
        Set<Integer> named = step.getApprovers().stream()
            .map(ApprovalStepApprover::getUserId)
            .filter(userId -> userId != null)
            .collect(Collectors.toSet());
        return pool.members().stream()
            .filter(member -> anyApprover || named.contains(member.getId()))
            .filter(member -> pool.approvers().contains(member.getId()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
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
            boolean approved = APPROVED.equals(decision);
            boolean cancelled = CANCELLED.equals(decision);
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

    private void notifyTerminated(int workspaceId, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> activeSteps, User actor,
            String outcomeReason, String detail, boolean includeActiveApprovers) {
        ApproverPool pool = approverPool(workspaceId);
        Set<User> recipients = new LinkedHashSet<>();
        if (approval.getRequestedBy() != null) {
            pool.members().stream()
                .filter(member -> member.getId() == approval.getRequestedBy())
                .findFirst()
                .ifPresent(recipients::add);
        }
        if (includeActiveApprovers) {
            activeSteps.forEach(step -> recipients.addAll(recipientsFor(pool, step)));
        }
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        for (User recipient : recipients) {
            if (!notificationPreferenceService.isEnabled(
                    recipient.getId(), TERMINATED_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = baseNotification(workspaceId, document, actor, triggeredAt);
                notification.setRecipientId(recipient.getId());
                notification.setType(TERMINATED_TYPE);
                notification.setSeverity("warning");
                notification.setTitle("Approval request ended");
                notification.setBody("The approval request for " + titleOf(document) + " ended: " + detail);
                notification.setContextType("deal");
                notification.setContextId(document.getDealId());
                notification.setDedupeKey(TERMINATED_TYPE + ":" + approval.getId() + ":"
                    + recipient.getId());
                notification.setData(json(Map.of(
                    "dealId", document.getDealId(),
                    "documentId", document.getId(),
                    "documentTitle", titleOf(document),
                    "version", document.getVersion(),
                    "outcomeReason", outcomeReason)));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException e) {
                log.warn("Failed to deliver approval-termination notification for document {} to recipient {}: {}",
                    document.getId(), recipient.getId(), e.getClass().getSimpleName());
            }
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

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Approval termination requires the caller to hold a transaction");
        }
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
        return withChain(workspaceId, List.of(approval)).getFirst();
    }

    private record ApprovalProjection(
            ApprovalAvailability overall,
            Map<Integer, ApprovalAvailability> steps) {
    }
}

/** Current ability of one frozen approval step to reach its remaining quorum. */
record ApprovalAvailability(boolean satisfiable, String reason, Integer blockingStepId) {
}
