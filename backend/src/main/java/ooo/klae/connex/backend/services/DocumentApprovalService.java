package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ApprovalDelegateDto;
import ooo.klae.connex.backend.dto.ApprovalInboxItemDto;
import ooo.klae.connex.backend.dto.ApprovalInboxRow;
import ooo.klae.connex.backend.dto.ApprovalReminderRow;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.dto.DocumentApprovalStepDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.services.ApprovalStepEligibility.EffectiveApprovers;
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
 *
 * <p>The frozen snapshot stays immutable even when the people change. Delegation, escalation, and
 * reassignment are appended as {@code document_approval_step_assignment} facts and replayed over the
 * snapshot by {@link ApprovalStepEligibility}, which is the single owner of "who may decide this
 * step now" for authorization, satisfiability, notification fan-out, and the approval inbox. A step
 * frozen with a due interval also carries a deadline; the scheduled sweep calls
 * {@link #reconcileApproval} to expire it, escalate it once, or remind its remaining approvers.
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
    private final ApprovalStepEligibility eligibility = new ApprovalStepEligibility();

    private static final String REQUEST_TYPE = "document.approval_request";
    private static final String DECISION_TYPE = "document.approval_decision";
    private static final String TERMINATED_TYPE = "document.approval_terminated";
    private static final String REMINDER_TYPE = "document.approval_reminder";
    private static final String IN_APP = "in_app";
    private static final String ANY_APPROVER = "any_approver";
    private static final String NAMED_APPROVER = "user";
    private static final String ACTIVE = "active";
    private static final String PENDING = "pending";
    private static final String APPROVED = "approved";
    private static final String REJECTED = "rejected";
    private static final String CANCELLED = "cancelled";
    private static final String INVALIDATED = "invalidated";
    private static final String UNSATISFIABLE = "unsatisfiable";
    private static final String EXPIRED = "expired";
    private static final String EXPIRE = "expire";
    private static final String ESCALATE = "escalate";
    private static final String DELEGATION = "delegation";
    private static final String ESCALATION = "escalation";
    private static final String REASSIGNMENT = "reassignment";
    private static final int MAX_REMINDER_ROUNDS = 3;
    private static final int MAX_STEP_APPROVERS = 20;
    private static final int CANDIDATE_LIMIT = 200;
    private static final int INBOX_LIMIT = 50;
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
     * Attaches each approval's frozen chain, appended approver facts, and decision history in three
     * extra queries. Callers that render approvals alongside documents use this so neither the chain
     * nor its assignments load per row.
     */
    List<DocumentApproval> withChain(int workspaceId, List<DocumentApproval> approvals) {
        if (approvals.isEmpty()) {
            return approvals;
        }
        List<Integer> ids = approvals.stream().map(DocumentApproval::getId).toList();
        return attachChain(approvals,
            approvalMapper.getStepsByApprovalIds(workspaceId, ids),
            approvalMapper.getDecisionsByApprovalIds(workspaceId, ids),
            approvalMapper.getAssignmentsByApprovalIds(workspaceId, ids));
    }

    /** Hydrates an approval chain with current reads while its document row lock is held. */
    List<DocumentApproval> withChainForUpdate(int workspaceId, List<DocumentApproval> approvals) {
        if (approvals.isEmpty()) {
            return approvals;
        }
        List<Integer> ids = approvals.stream().map(DocumentApproval::getId).toList();
        return attachChain(approvals,
            approvalMapper.getStepsByApprovalIdsForUpdate(workspaceId, ids),
            approvalMapper.getDecisionsByApprovalIdsForUpdate(workspaceId, ids),
            approvalMapper.getAssignmentsByApprovalIdsForUpdate(workspaceId, ids));
    }

    private List<DocumentApproval> attachChain(List<DocumentApproval> approvals,
            List<DocumentApprovalStep> steps, List<DocumentApprovalDecision> decisions,
            List<DocumentApprovalStepAssignment> assignments) {
        Map<Integer, List<DocumentApprovalDecision>> decisionsByStep = decisions.stream()
                .collect(Collectors.groupingBy(DocumentApprovalDecision::getStepId));
        Map<Integer, List<DocumentApprovalStepAssignment>> assignmentsByStep = assignments.stream()
                .collect(Collectors.groupingBy(DocumentApprovalStepAssignment::getStepId));
        steps.forEach(step -> {
            step.setDecisions(decisionsByStep.getOrDefault(step.getId(), List.of()));
            step.setAssignments(assignmentsByStep.getOrDefault(step.getId(), List.of()));
        });
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
        if (approvalMapper.findPendingForUpdate(workspaceId, documentId) != null) {
            throw new BadRequestException("An approval is already pending for this document");
        }
        ApprovalPolicy policy = policyService.firstMatch(policies, document, parseContent(document));
        User actor = userMapper.getUserById(actorId);

        DocumentApproval approval = new DocumentApproval();
        approval.setWorkspaceId(workspaceId);
        approval.setDealId(dealId);
        approval.setDocumentId(documentId);
        approval.setPolicyId(policy == null ? null : policy.getId());
        approval.setPolicyIdSnapshot(policy == null ? null : policy.getId());
        approval.setPolicyBinding(policy == null ? "none" : "applied");
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
        notifyStepApprovers(workspaceId, deal, document, approval, opened, actor, pool, null, null);
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
     * Locks the workspace authorization root before resolving the pool reused by one reconciliation
     * sweep. The caller's transaction must retain this root through every document mutation.
     */
    ApproverPool reconciliationApproverPool(int workspaceId) {
        requireActiveTransaction();
        workspaceService.lockApprovalReconciliationAuthorizationRoot(workspaceId);
        return approverPool(workspaceId);
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
    record ApproverPool(List<User> members, Set<Integer> approvers) {
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
        DocumentApproval approval = approvalMapper.findPendingForUpdate(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        List<DocumentApprovalStep> steps = chainOfForUpdate(workspaceId, approval);
        ApproverPool pool = approverPool(workspaceId);
        while (reconcileExpiry(workspaceId, document, approval, pool)) {
            approval = requireApprovalForUpdate(workspaceId, approval.getId());
            if (!PENDING.equals(approval.getStatus())) {
                return toDto(approval, document, pool);
            }
            steps = approval.getSteps();
        }
        requireSeparationOfDuties(approval, document, actor);
        DocumentApprovalStep step = resolveStep(approval, document, steps, stepId, actor, pool);

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
            ? advanceAfterApproval(workspaceId, deal, document, approval, steps, step, actor,
                comment, pool)
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
        return toDto(requireApprovalForUpdate(workspaceId, approval.getId()), document, pool);
    }

    /**
     * Marks the step passed once it holds its quorum, then opens the next sequential step or
     * completes the request when every step has passed. Returns the request's resulting status, so
     * an approval that only advances one step is never audited as approving the document.
     */
    private String advanceAfterApproval(int workspaceId, Deal deal, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> steps, DocumentApprovalStep step,
            User actor, String comment, ApproverPool pool) {
        long approvalCount = step.getDecisions().stream()
            .filter(decision -> APPROVED.equals(decision.getDecision()))
            .count() + 1;
        if (approvalCount < step.getRequiredCount()) {
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
                pool, null, null);
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
    private DocumentApprovalStep resolveStep(DocumentApproval approval, DealDocument document,
            List<DocumentApprovalStep> steps, Integer stepId, User actor, ApproverPool pool) {
        if (stepId == null) {
            return steps.stream()
                .filter(step -> ACTIVE.equals(step.getStatus()))
                .filter(step -> isApprover(approval, document, step, actor, pool)
                    && !hasDecided(step, actor.getId()))
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
        if (!isApprover(approval, document, step, actor, pool)) {
            throw new ForbiddenException("You are not an approver for that step");
        }
        if (hasDecided(step, actor.getId())) {
            throw new BadRequestException("You have already decided that step");
        }
        return step;
    }

    private boolean isApprover(DocumentApproval approval, DealDocument document,
            DocumentApprovalStep step, User actor, ApproverPool pool) {
        return eligibility.resolve(step, pool, separationExclusions(approval, document))
            .eligible().contains(actor.getId());
    }

    private boolean hasDecided(DocumentApprovalStep step, int userId) {
        return step.getDecisions().stream().anyMatch(decision -> decision.getDecidedBy() == userId);
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
            opened.add(insertStep(workspaceId, approval, 1, null, 1, ACTIVE,
                List.of(anyApprover()), null, EXPIRE));
            return opened;
        }
        int order = 1;
        for (ApprovalPolicyStep source : template) {
            String status = parallel || order == 1 ? ACTIVE : PENDING;
            DocumentApprovalStep step = insertStep(workspaceId, approval, order, source.getName(),
                source.getRequiredCount(), status, source.getApprovers(),
                source.getDueIntervalHours(), source.getOnExpiry());
            if (ACTIVE.equals(status)) {
                opened.add(step);
            }
            order++;
        }
        return opened;
    }

    private DocumentApprovalStep insertStep(int workspaceId, DocumentApproval approval, int order,
            String name, int requiredCount, String status, List<ApprovalStepApprover> approvers,
            Integer dueIntervalHours, String onExpiry) {
        DocumentApprovalStep step = new DocumentApprovalStep();
        step.setWorkspaceId(workspaceId);
        step.setApprovalId(approval.getId());
        step.setStepOrder(order);
        step.setName(name);
        step.setRequiredCount(requiredCount);
        step.setStatus(status);
        step.setDueIntervalHours(dueIntervalHours);
        step.setOnExpiry(onExpiry == null || onExpiry.isBlank() ? EXPIRE : onExpiry);
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

    private List<DocumentApprovalStep> chainOfForUpdate(int workspaceId, DocumentApproval approval) {
        List<DocumentApprovalStep> steps = withChainForUpdate(workspaceId, List.of(approval))
            .getFirst().getSteps();
        if (!steps.isEmpty()) {
            return steps;
        }
        return List.of(insertStep(workspaceId, approval, 1, null, 1, ACTIVE,
            List.of(anyApprover()), null, EXPIRE));
    }

    /**
     * Cancels a pending approval because its document is being superseded. Called by
     * {@code DealDocumentService.updateStatus} under the document row lock — the superseder needs
     * {@link Permission#DEAL_UPDATE} but not requester identity, so the requester is notified that
     * their request was withdrawn on their behalf.
     */
    void cancelPendingOnSupersede(int workspaceId, Deal deal, DealDocument document) {
        DocumentApproval pending = approvalMapper.findPendingForUpdate(workspaceId, document.getId());
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
        DocumentApproval approval = approvalMapper.findPendingForUpdate(workspaceId, documentId);
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
        DocumentApproval current = approvalMapper.getByIdForUpdate(workspaceId, approval.getId());
        if (current == null || current.getDocumentId() != approval.getDocumentId()
                || !PENDING.equals(current.getStatus())) {
            return;
        }
        current = withChainForUpdate(workspaceId, List.of(current)).getFirst();
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
        return terminateIfUnsatisfiable(
            workspaceId, approval, reconciliationApproverPool(workspaceId));
    }

    /** Terminates one pending request using the post-authorization-lock workspace pool. */
    boolean terminateIfUnsatisfiable(int workspaceId, DocumentApproval approval, ApproverPool pool) {
        requireActiveTransaction();
        DealDocument document = lockDocument(
            workspaceId, approval.getDealId(), approval.getDocumentId());
        DocumentApproval current = approvalMapper.getByIdForUpdate(workspaceId, approval.getId());
        if (current == null || current.getDocumentId() != approval.getDocumentId()
                || !PENDING.equals(current.getStatus())) {
            return false;
        }
        current = withChainForUpdate(workspaceId, List.of(current)).getFirst();
        return terminateIfUnsatisfiable(workspaceId, document, current, pool);
    }

    /** Terminates one already-hydrated request while its document lock is held by the caller. */
    boolean terminateIfUnsatisfiable(int workspaceId, DealDocument document,
            DocumentApproval current, ApproverPool pool) {
        requireActiveTransaction();
        ApprovalProjection projection = projectAvailability(
            current, document, pool);
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
            UNSATISFIABLE, detail, false, pool);
        return true;
    }

    /**
     * Hands the caller's seat on one active step to another member who may approve documents.
     *
     * <p>Nothing about the frozen chain is rewritten: the delegation is appended as a fact, and the
     * resolver removes the delegator and admits the delegate when it replays the step. A delegate
     * who could not have decided the request anyway — because separation of duties excludes them,
     * because they already decided the step, or because they lack the permission — is refused, and a
     * delegation that would leave the step short of its quorum rolls the whole transaction back.
     */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_APPROVE)
    public DocumentApprovalDto createDelegation(int dealId, int documentId, int stepId,
            int delegateUserId, String comment) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        if (delegateUserId == actorId) {
            throw new BadRequestException("You cannot delegate to yourself");
        }
        workspaceService.lockAndRequirePermissions(workspaceId, Map.of(
            actorId, EnumSet.of(Permission.DOCUMENT_APPROVE),
            delegateUserId, EnumSet.of(Permission.DOCUMENT_APPROVE)));
        User actor = userMapper.getUserById(actorId);
        User delegate = userMapper.getUserById(delegateUserId);
        if (actor == null || delegate == null) {
            throw new ForbiddenException("Only a workspace member can be given an approval step");
        }
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPendingForUpdate(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        String attributionFailure = attributionFailure(approval, document);
        if (attributionFailure != null) {
            throw new ForbiddenException(attributionFailure);
        }
        DocumentApprovalStep step = requireActiveStep(chainOfForUpdate(workspaceId, approval), stepId);
        ApproverPool pool = approverPool(workspaceId);
        Set<Integer> exclusions = separationExclusions(approval, document);
        if (!eligibility.resolve(step, pool, exclusions).eligible().contains(actorId)) {
            throw new ForbiddenException("You are not an approver for that step");
        }
        if (hasDecided(step, actorId)) {
            throw new BadRequestException("You have already decided that step");
        }
        if (exclusions.contains(delegateUserId)) {
            throw new ForbiddenException("The delegate cannot decide this approval either");
        }
        if (hasDelegated(step, actorId)) {
            throw new BadRequestException("You have already delegated this step");
        }
        if (hasDelegated(step, delegateUserId)) {
            throw new BadRequestException("That member has already delegated this step");
        }
        if (hasDecided(step, delegateUserId)) {
            throw new BadRequestException("That member has already decided this step");
        }
        DocumentApprovalStepAssignment assignment = new DocumentApprovalStepAssignment();
        assignment.setWorkspaceId(workspaceId);
        assignment.setApprovalId(approval.getId());
        assignment.setStepId(step.getId());
        assignment.setAssignmentKind(DELEGATION);
        assignment.setAssignmentRound(0);
        assignment.setApproverKind(NAMED_APPROVER);
        assignment.setUserId(delegateUserId);
        assignment.setDelegatedByUserId(actorId);
        assignment.setCreatedByUserId(actorId);
        assignment.setComment(blankToNull(comment));
        approvalMapper.insertAssignment(assignment);

        DocumentApproval reloaded = requireApprovalForUpdate(workspaceId, approval.getId());
        requireStepStaysSatisfiable(reloaded, document, pool, step.getId(),
            "Delegating this step would leave too few approvers to reach its quorum");
        auditService.record("document_approval.delegate", "deal", deal.getId(), deal.getName(),
            "Delegated step " + step.getStepOrder() + " of " + document.getType()
                + " v" + document.getVersion() + " to " + delegate.getDisplayName(), null);
        Set<Integer> recipients = newlyAffectedRecipients(
            approval, step, reloaded, document, pool, delegateUserId);
        notifyStepApprovers(workspaceId, deal, document, reloaded,
            stepsOf(reloaded, step.getId()), actor, pool, "delegated", assignment.getId(), recipients);
        return toDto(reloaded, document, pool);
    }

    /** Members the current approver may delegate one active step to right now. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.DOCUMENT_APPROVE)
    public List<ApprovalDelegateDto> eligibleDelegates(int dealId, int documentId, int stepId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        requireDeal(workspaceId, dealId);
        DealDocument document = requireDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPending(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        approval = withChain(workspaceId, List.of(approval)).getFirst();
        DocumentApprovalStep step = requireActiveStep(approval.getSteps(), stepId);
        ApproverPool pool = approverPool(workspaceId);
        Set<Integer> exclusions = separationExclusions(approval, document);
        EffectiveApprovers effective = eligibility.resolve(step, pool, exclusions);
        if (attributionFailure(approval, document) != null
                || !effective.undecided().contains(actorId)) {
            throw new ForbiddenException("You are not an approver for that step");
        }
        return pool.members().stream()
            .filter(member -> member.getId() != actorId)
            .filter(member -> pool.approvers().contains(member.getId()))
            .filter(member -> !exclusions.contains(member.getId()))
            .filter(member -> !hasDecided(step, member.getId()))
            .filter(member -> !hasDelegated(step, member.getId()))
            .filter(member -> delegationKeepsSatisfiable(
                effective, actorId, member.getId()))
            .map(ApprovalDelegateDto::from)
            .toList();
    }

    /**
     * Widens one active step by appending approvers to its current set, without rewriting the frozen
     * snapshot. Used to unblock a step whose named approvers can no longer reach its quorum.
     */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public DocumentApprovalDto addStepApprovers(int dealId, int documentId, int stepId,
            List<ApprovalStepApprover> approvers, String comment) {
        return changeStepApprovers(dealId, documentId, stepId, approvers, comment, ESCALATION);
    }

    /**
     * Replaces the approvers of one active step, opening a new reassignment round. Rows of earlier
     * rounds, including escalations, stop resolving; decisions already collected are untouched.
     */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public DocumentApprovalDto replaceStepApprovers(int dealId, int documentId, int stepId,
            List<ApprovalStepApprover> approvers, String comment) {
        return changeStepApprovers(dealId, documentId, stepId, approvers, comment, REASSIGNMENT);
    }

    private DocumentApprovalDto changeStepApprovers(int dealId, int documentId, int stepId,
            List<ApprovalStepApprover> approvers, String comment, String assignmentKind) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        List<ApprovalStepApprover> requested = validateRequestedApprovers(approvers);
        workspaceService.lockAndRequirePermissions(workspaceId, requiredPermissions(actorId, requested));
        User actor = userMapper.getUserById(actorId);
        if (actor == null) {
            throw new ForbiddenException("Only a workspace member can change an approval step");
        }
        Deal deal = requireDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentApproval approval = approvalMapper.findPendingForUpdate(workspaceId, documentId);
        if (approval == null || !"pending_approval".equals(document.getStatus())) {
            throw new BadRequestException("No pending approval on this document");
        }
        DocumentApprovalStep step = requireActiveStep(chainOfForUpdate(workspaceId, approval), stepId);
        DocumentApproval before = approval;
        int currentRound = approvalMapper.maxReassignmentRound(workspaceId, step.getId());
        int round = REASSIGNMENT.equals(assignmentKind) ? currentRound + 1 : currentRound;
        Integer appendedAssignmentId = null;
        for (ApprovalStepApprover approver : requested) {
            if (alreadyAssigned(step, assignmentKind, round, approver)) {
                continue;
            }
            DocumentApprovalStepAssignment assignment = new DocumentApprovalStepAssignment();
            assignment.setWorkspaceId(workspaceId);
            assignment.setApprovalId(approval.getId());
            assignment.setStepId(step.getId());
            assignment.setAssignmentKind(assignmentKind);
            assignment.setAssignmentRound(round);
            assignment.setApproverKind(approver.getApproverKind());
            assignment.setUserId(approver.getUserId());
            assignment.setCreatedByUserId(actorId);
            assignment.setComment(blankToNull(comment));
            approvalMapper.insertAssignment(assignment);
            if (appendedAssignmentId == null) {
                appendedAssignmentId = assignment.getId();
            }
        }

        ApproverPool pool = approverPool(workspaceId);
        DocumentApproval reloaded = requireApprovalForUpdate(workspaceId, approval.getId());
        requireStepStaysSatisfiable(reloaded, document, pool, step.getId(),
            "The new approver set cannot reach this step's quorum");
        boolean escalation = ESCALATION.equals(assignmentKind);
        auditService.record(
            escalation ? "document_approval.escalate" : "document_approval.reassign",
            "deal", deal.getId(), deal.getName(),
            (escalation ? "Widened step " : "Reassigned step ") + step.getStepOrder() + " of "
                + document.getType() + " v" + document.getVersion() + " to "
                + approverSummary(requested), null);
        if (appendedAssignmentId != null) {
            Set<Integer> recipients = newlyAffectedRecipients(
                before, step, reloaded, document, pool, null);
            notifyStepApprovers(workspaceId, deal, document, reloaded,
                stepsOf(reloaded, step.getId()), actor, pool,
                escalation ? "escalated" : "reassigned", appendedAssignmentId, recipients);
        }
        return toDto(reloaded, document, pool);
    }

    /**
     * Approval steps the caller can still decide across the whole workspace, nearest deadline first.
     *
     * <p>This is a pure projection over {@code document_approval}, {@code document_approval_step},
     * and {@code document_approval_step_assignment}: it takes no locks, writes nothing, and persists
     * no state. The mapper deliberately over-selects bounded pages, so every candidate is
     * re-resolved in Java before it is disclosed and stale early pages cannot starve later work.
     *
     * <p>Disclosure note: an approver sees the deal name and document title of a request routed to
     * them even when owner-scope record visibility would otherwise hide that deal. That is the same
     * disclosure the existing {@code document.approval_request} notification already makes, and it is
     * intentional — an approver must know what they are approving.
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.DOCUMENT_APPROVE)
    public List<ApprovalInboxItemDto> inbox() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int callerId = workspaceService.getCurrentUserId();
        ApproverPool pool = approverPool(workspaceId);
        List<ApprovalInboxItemDto> items = new ArrayList<>();
        int offset = 0;
        while (items.size() < INBOX_LIMIT) {
            List<ApprovalInboxRow> candidates = approvalMapper.findActionableSteps(
                workspaceId, callerId, offset, CANDIDATE_LIMIT);
            if (candidates.isEmpty()) {
                break;
            }
            appendInboxItems(workspaceId, callerId, candidates, pool, items);
            if (candidates.size() < CANDIDATE_LIMIT) {
                break;
            }
            offset += candidates.size();
        }
        return items;
    }

    private void appendInboxItems(int workspaceId, int callerId,
            List<ApprovalInboxRow> candidates, ApproverPool pool,
            List<ApprovalInboxItemDto> items) {
        List<Integer> approvalIds = candidates.stream()
            .map(ApprovalInboxRow::approvalId)
            .distinct()
            .toList();
        Map<Integer, DocumentApproval> approvalsById = new LinkedHashMap<>();
        withChain(workspaceId, approvalMapper.getByIds(workspaceId, approvalIds))
            .forEach(approval -> approvalsById.put(approval.getId(), approval));
        List<Integer> documentIds = candidates.stream()
            .map(ApprovalInboxRow::documentId)
            .distinct()
            .toList();
        Map<Integer, DealDocument> documentsById = documentMapper
            .getByIds(workspaceId, documentIds).stream()
            .collect(Collectors.toMap(DealDocument::getId, document -> document,
                (first, second) -> first, LinkedHashMap::new));
        for (ApprovalInboxRow row : candidates) {
            if (items.size() >= INBOX_LIMIT) {
                break;
            }
            DocumentApproval approval = approvalsById.get(row.approvalId());
            if (approval == null) {
                continue;
            }
            DealDocument document = documentsById.get(row.documentId());
            if (document == null || attributionFailure(approval, document) != null) {
                continue;
            }
            DocumentApprovalStep step = approval.getSteps().stream()
                .filter(candidate -> candidate.getId() == row.stepId())
                .findFirst()
                .orElse(null);
            if (step == null || !eligibility
                    .resolve(step, pool, separationExclusions(approval, document))
                    .undecided().contains(callerId)) {
                continue;
            }
            items.add(new ApprovalInboxItemDto(row.approvalId(), row.dealId(), row.dealName(),
                row.documentId(), row.documentTitle(), row.documentType(), row.version(),
                row.stepId(), row.stepOrder(), row.stepName(), row.requiredCount(), row.dueAt(),
                row.escalatedAt() != null, row.requestedBy(), row.requestedAt()));
        }
    }

    /**
     * Reconciles one pending request against the wall clock and the current approver pool: expiry
     * and expiry-escalation first, then unsatisfiability, then reminders.
     *
     * <p>Expiry runs before unsatisfiability because a step can be both overdue and short of
     * approvers, and the policy-declared deadline outcome must win — an {@code escalate} step becomes
     * satisfiable again precisely by passing its deadline.
     *
     * <p>The caller must already hold a transaction, for the reason given on
     * {@link #invalidateForPolicyChange}.
     */
    void reconcileApproval(int workspaceId, DocumentApproval approval, ApproverPool pool) {
        requireActiveTransaction();
        DealDocument document = lockDocument(
            workspaceId, approval.getDealId(), approval.getDocumentId());
        DocumentApproval current = approvalMapper.getByIdForUpdate(workspaceId, approval.getId());
        if (current == null || current.getDocumentId() != approval.getDocumentId()
                || !PENDING.equals(current.getStatus())) {
            return;
        }
        current = withChainForUpdate(workspaceId, List.of(current)).getFirst();
        if (reconcileExpiry(workspaceId, document, current, pool)) {
            return;
        }
        if (terminateIfUnsatisfiable(workspaceId, document, current, pool)) {
            return;
        }
        remindOpenSteps(workspaceId, document, current, pool);
    }

    /**
     * Applies the deadline outcome of the lowest-order overdue step, if any. Returns whether this
     * sweep changed the request; the widened state after an escalation is deliberately left for the
     * next sweep to evaluate, so satisfiability and reminders never read a stale chain.
     */
    private boolean reconcileExpiry(int workspaceId, DealDocument document,
            DocumentApproval current, ApproverPool pool) {
        List<Integer> expired = approvalMapper.findExpiredActiveSteps(workspaceId, current.getId());
        if (expired.isEmpty()) {
            return false;
        }
        int stepId = expired.getFirst();
        DocumentApprovalStep step = current.getSteps().stream()
            .filter(candidate -> candidate.getId() == stepId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Expired approval step is missing"));
        if (ESCALATE.equals(step.getOnExpiry()) && step.getEscalatedAt() == null) {
            return escalateExpiredStep(workspaceId, document, current, step, pool);
        }
        return expireApproval(workspaceId, document, current, step, pool);
    }

    private boolean escalateExpiredStep(int workspaceId, DealDocument document,
            DocumentApproval current, DocumentApprovalStep step, ApproverPool pool) {
        if (approvalMapper.escalateStep(workspaceId, step.getId()) != 1) {
            return true;
        }
        DocumentApprovalStepAssignment assignment = new DocumentApprovalStepAssignment();
        assignment.setWorkspaceId(workspaceId);
        assignment.setApprovalId(current.getId());
        assignment.setStepId(step.getId());
        assignment.setAssignmentKind(ESCALATION);
        assignment.setAssignmentRound(
            approvalMapper.maxReassignmentRound(workspaceId, step.getId()));
        assignment.setApproverKind(ANY_APPROVER);
        approvalMapper.insertAssignment(assignment);
        Deal deal = requireDeal(workspaceId, current.getDealId());
        auditService.record("document_approval.escalate", "deal", deal.getId(), deal.getName(),
            "Widened step " + step.getStepOrder() + " of " + document.getType()
                + " v" + document.getVersion() + " to every approver after its deadline passed",
            null);
        DocumentApproval reloaded = requireApprovalForUpdate(workspaceId, current.getId());
        Set<Integer> recipients = newlyAffectedRecipients(
            current, step, reloaded, document, pool, null);
        notifyStepApprovers(workspaceId, deal, document, reloaded,
            stepsOf(reloaded, step.getId()), null, pool, "escalated", assignment.getId(), recipients);
        return true;
    }

    private boolean expireApproval(int workspaceId, DealDocument document,
            DocumentApproval current, DocumentApprovalStep step, ApproverPool pool) {
        List<DocumentApprovalStep> activeSteps = current.getSteps().stream()
            .filter(candidate -> ACTIVE.equals(candidate.getStatus())).toList();
        String detail = expiryDetail(step);
        if (approvalMapper.decide(workspaceId, current.getId(), EXPIRED, null, null,
                EXPIRED, detail) == 0) {
            return true;
        }
        if (approvalMapper.updateStepStatus(workspaceId, step.getId(), EXPIRED, ACTIVE) != 1) {
            throw new IllegalStateException("Expiring approval step changed during termination");
        }
        approvalMapper.cancelOpenSteps(workspaceId, current.getId());
        documentMapper.updateStatus(workspaceId, document.getId(), "draft");
        Deal deal = requireDeal(workspaceId, current.getDealId());
        auditService.record("document_approval.expire", "deal", deal.getId(), deal.getName(),
            "Terminated approval request for " + document.getType() + " v" + document.getVersion()
                + " because step " + step.getStepOrder() + " passed its deadline",
            auditService.singleChange("status", PENDING, EXPIRED));
        notifyTerminated(workspaceId, document, current, activeSteps, null,
            EXPIRED, detail, true, pool);
        return true;
    }

    /**
     * Emits at most one reminder per step per round. The round is derived from the elapsed fraction
     * of the step's deadline and claimed durably by a compare-and-set on {@code reminded_round}, so
     * a repeated or concurrent sweep cannot re-send one.
     */
    private void remindOpenSteps(int workspaceId, DealDocument document,
            DocumentApproval current, ApproverPool pool) {
        Set<Integer> exclusions = separationExclusions(current, document);
        for (ApprovalReminderRow row : approvalMapper.findReminderDueSteps(
                workspaceId, current.getId())) {
            if (row.dueRound() <= row.remindedRound()) {
                continue;
            }
            if (approvalMapper.advanceRemindedRound(workspaceId, row.stepId(), row.dueRound(),
                    row.remindedRound()) != 1) {
                continue;
            }
            DocumentApprovalStep step = current.getSteps().stream()
                .filter(candidate -> candidate.getId() == row.stepId())
                .findFirst()
                .orElse(null);
            if (step == null) {
                continue;
            }
            notifyReminder(workspaceId, document, current, step, row.dueRound(),
                eligibility.resolve(step, pool, exclusions).undecided(), pool);
        }
    }

    private DocumentApprovalStep requireActiveStep(List<DocumentApprovalStep> steps, int stepId) {
        DocumentApprovalStep step = steps.stream()
            .filter(candidate -> candidate.getId() == stepId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException(
                "Approval step not found with id: " + stepId));
        if (!ACTIVE.equals(step.getStatus())) {
            throw new BadRequestException("That approval step is not awaiting a decision");
        }
        return step;
    }

    private List<DocumentApprovalStep> stepsOf(DocumentApproval approval, int stepId) {
        return approval.getSteps().stream()
            .filter(candidate -> candidate.getId() == stepId)
            .toList();
    }

    private boolean hasDelegated(DocumentApprovalStep step, int userId) {
        return step.getAssignments().stream()
            .anyMatch(assignment -> DELEGATION.equals(assignment.getAssignmentKind())
                && assignment.getDelegatedByUserId() != null
                && assignment.getDelegatedByUserId() == userId);
    }

    private boolean alreadyAssigned(DocumentApprovalStep step, String assignmentKind, int round,
            ApprovalStepApprover approver) {
        return step.getAssignments().stream()
            .anyMatch(assignment -> assignmentKind.equals(assignment.getAssignmentKind())
                && assignment.getAssignmentRound() == round
                && assignment.getApproverKind().equals(approver.getApproverKind())
                && Objects.equals(assignment.getUserId(), approver.getUserId()));
    }

    /**
     * Refuses an approver change whose resulting step could no longer reach its quorum. The guard
     * reads the post-write state only, so an admin may deliberately repair a step that was already
     * unsatisfiable before the change.
     */
    private void requireStepStaysSatisfiable(DocumentApproval approval, DealDocument document,
            ApproverPool pool, int stepId, String message) {
        ApprovalAvailability availability = projectAvailability(approval, document, pool)
            .steps().get(stepId);
        if (availability != null && !availability.satisfiable()) {
            throw new BadRequestException(message);
        }
    }

    private List<ApprovalStepApprover> validateRequestedApprovers(
            List<ApprovalStepApprover> approvers) {
        List<ApprovalStepApprover> requested = approvers == null ? List.of() : approvers;
        if (requested.isEmpty()) {
            throw new BadRequestException("At least one approver is required");
        }
        if (requested.size() > MAX_STEP_APPROVERS) {
            throw new BadRequestException(
                "A step may not have more than " + MAX_STEP_APPROVERS + " approvers");
        }
        boolean anyApprover = requested.stream()
            .anyMatch(approver -> ANY_APPROVER.equals(approver.getApproverKind()));
        if (anyApprover && requested.size() > 1) {
            throw new BadRequestException(
                "A step set to any approver cannot also name individual approvers");
        }
        Set<Integer> named = new HashSet<>();
        for (ApprovalStepApprover approver : requested) {
            if (anyApprover) {
                approver.setUserId(null);
                continue;
            }
            if (!NAMED_APPROVER.equals(approver.getApproverKind())) {
                throw new BadRequestException("approverKind must be user or any_approver");
            }
            if (approver.getUserId() == null) {
                throw new BadRequestException("userId is required for a named approver");
            }
            if (!named.add(approver.getUserId())) {
                throw new BadRequestException("The same approver is listed twice on one step");
            }
        }
        return requested;
    }

    private Map<Integer, Set<Permission>> requiredPermissions(int actorId,
            List<ApprovalStepApprover> approvers) {
        Map<Integer, Set<Permission>> required = new LinkedHashMap<>();
        required.put(actorId, EnumSet.of(Permission.DOCUMENT_MANAGE));
        for (ApprovalStepApprover approver : approvers) {
            if (approver.getUserId() == null) {
                continue;
            }
            required.computeIfAbsent(approver.getUserId(),
                    userId -> EnumSet.noneOf(Permission.class))
                .add(Permission.DOCUMENT_APPROVE);
        }
        return required;
    }

    private String approverSummary(List<ApprovalStepApprover> approvers) {
        return approvers.stream()
            .map(approver -> ANY_APPROVER.equals(approver.getApproverKind())
                ? ANY_APPROVER : String.valueOf(approver.getUserId()))
            .collect(Collectors.joining(","));
    }

    private String expiryDetail(DocumentApprovalStep step) {
        String label = step.getName() == null || step.getName().isBlank()
            ? "Step " + step.getStepOrder()
            : "Step " + step.getStepOrder() + " (" + step.getName() + ")";
        return label + ": No decision by " + step.getDueAt();
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
        Map<Integer, String> memberLabels = pool.members().stream()
            .collect(Collectors.toMap(User::getId, this::displayLabel));
        List<DocumentApprovalStepDto> steps = approval.getSteps().stream()
            .map(step -> {
                ApprovalAvailability availability = projection.steps().get(step.getId());
                EffectiveApprovers effective = projection.effective().get(step.getId());
                return DocumentApprovalStepDto.from(step, availability.satisfiable(),
                    availability.reason(), effective.anyApprover(),
                    List.copyOf(effective.undecided()), memberLabels);
            })
            .toList();
        return DocumentApprovalDto.from(approval, projection.overall().satisfiable(),
            projection.overall().reason(), steps);
    }

    private ApprovalProjection projectAvailability(DocumentApproval approval,
            DealDocument document, ApproverPool pool) {
        Set<Integer> exclusions = separationExclusions(approval, document);
        Map<Integer, ApprovalAvailability> steps = new LinkedHashMap<>();
        Map<Integer, EffectiveApprovers> effective = new LinkedHashMap<>();
        ApprovalAvailability firstBlocking = null;
        for (DocumentApprovalStep step : approval.getSteps()) {
            EffectiveApprovers resolved = eligibility.resolve(step, pool, exclusions);
            effective.put(step.getId(), resolved);
            ApprovalAvailability availability = availabilityForStep(
                approval, document, step, resolved);
            steps.put(step.getId(), availability);
            if (!availability.satisfiable() && firstBlocking == null) {
                firstBlocking = availability;
            }
        }
        ApprovalAvailability overall = firstBlocking == null
            ? new ApprovalAvailability(true, null, null) : firstBlocking;
        return new ApprovalProjection(overall, steps, effective);
    }

    private ApprovalAvailability availabilityForStep(DocumentApproval approval,
            DealDocument document, DocumentApprovalStep step, EffectiveApprovers effective) {
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
        int undecided = effective.undecided().size();
        int remainingNeeded = effective.remainingNeeded();
        if (undecided >= remainingNeeded) {
            return new ApprovalAvailability(true, null, null);
        }
        String reason = "Only " + undecided + " eligible undecided approver"
            + (undecided == 1 ? " remains" : "s remain") + " for " + remainingNeeded
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
     * Notifies the members who can still decide the given steps. The recipient set is the resolver's
     * undecided set, so a named approver who lost {@link Permission#DOCUMENT_APPROVE}, delegated
     * their seat away, was reassigned out, or already approved stops hearing about the step.
     *
     * <p>{@code reason} distinguishes a re-notification caused by a delegation, escalation, or
     * reassignment from the original request; it is {@code null} on the original request. Appended
     * facts also carry their generated assignment ID so repeated events remain independently
     * deliverable.
     */
    private void notifyStepApprovers(int workspaceId, Deal deal, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> steps, User actor,
            ApproverPool pool, String reason, Integer assignmentId) {
        notifyStepApprovers(workspaceId, deal, document, approval, steps, actor,
            pool, reason, assignmentId, null);
    }

    private void notifyStepApprovers(int workspaceId, Deal deal, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> steps, User actor,
            ApproverPool pool, String reason, Integer assignmentId,
            Set<Integer> recipientIds) {
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        String eventKey = reason == null ? "requested" : reason + ":" + assignmentId;
        for (DocumentApprovalStep step : steps) {
            for (User member : recipientsFor(approval, document, step, pool)) {
                if (recipientIds != null && !recipientIds.contains(member.getId())) {
                    continue;
                }
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
                    notification.setBody(requestBody(document, actor, reason));
                    notification.setContextType("deal");
                    notification.setContextId(deal.getId());
                    notification.setDedupeKey(REQUEST_TYPE + ":" + approval.getId() + ":"
                        + step.getId() + ":" + eventKey + ":" + member.getId());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("dealId", deal.getId());
                    data.put("documentId", document.getId());
                    data.put("documentTitle", titleOf(document));
                    data.put("version", document.getVersion());
                    data.put("stepId", step.getId());
                    data.put("stepOrder", step.getStepOrder());
                    if (reason != null) {
                        data.put("reason", reason);
                    }
                    notification.setData(json(data));
                    notificationDelivery.deliver(notification);
                } catch (RuntimeException e) {
                    log.warn("Failed to deliver approval-request notification for document {} to recipient {}: {}",
                        document.getId(), member.getId(), e.toString());
                }
            }
        }
    }

    private String requestBody(DealDocument document, User actor, String reason) {
        String who = actor == null ? "Someone" : actor.getDisplayName();
        if (reason == null) {
            return who + " requested approval for " + titleOf(document);
        }
        return switch (reason) {
            case "delegated" -> who + " delegated their approval of " + titleOf(document) + " to you";
            case "escalated" -> "You were added to the approval of " + titleOf(document);
            default -> "You were assigned the approval of " + titleOf(document);
        };
    }

    private Set<User> recipientsFor(DocumentApproval approval, DealDocument document,
            DocumentApprovalStep step, ApproverPool pool) {
        Set<Integer> undecided = eligibility
            .resolve(step, pool, separationExclusions(approval, document))
            .undecided();
        return pool.members().stream()
            .filter(member -> undecided.contains(member.getId()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Integer> newlyAffectedRecipients(DocumentApproval beforeApproval,
            DocumentApprovalStep beforeStep, DocumentApproval afterApproval,
            DealDocument document, ApproverPool pool, Integer explicitDelegateId) {
        Set<Integer> before = new LinkedHashSet<>(eligibility.resolve(
            beforeStep, pool, separationExclusions(beforeApproval, document)).undecided());
        DocumentApprovalStep afterStep = afterApproval.getSteps().stream()
            .filter(candidate -> candidate.getId() == beforeStep.getId())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Changed approval step is missing"));
        Set<Integer> after = new LinkedHashSet<>(eligibility.resolve(
            afterStep, pool, separationExclusions(afterApproval, document)).undecided());
        after.removeAll(before);
        if (explicitDelegateId != null) {
            after.add(explicitDelegateId);
        }
        return after;
    }

    private boolean delegationKeepsSatisfiable(EffectiveApprovers effective,
            int delegatorId, int delegateId) {
        Set<Integer> after = new LinkedHashSet<>(effective.undecided());
        after.remove(delegatorId);
        after.add(delegateId);
        return after.size() >= effective.remainingNeeded();
    }

    private String displayLabel(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return Integer.toString(user.getId());
    }

    /**
     * Reminds the members who can still decide an open step that its deadline is approaching or has
     * passed. The round is part of the dedupe key, so the durable notification uniqueness index is a
     * second independent guard behind the {@code reminded_round} compare-and-set.
     */
    private void notifyReminder(int workspaceId, DealDocument document, DocumentApproval approval,
            DocumentApprovalStep step, int round, Set<Integer> undecided, ApproverPool pool) {
        boolean overdue = round >= MAX_REMINDER_ROUNDS;
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TS);
        for (User member : pool.members()) {
            if (!undecided.contains(member.getId())) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(member.getId(), REMINDER_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = baseNotification(workspaceId, document, null, triggeredAt);
                notification.setRecipientId(member.getId());
                notification.setType(REMINDER_TYPE);
                notification.setSeverity(overdue ? "warning" : "info");
                notification.setTitle("Approval reminder");
                notification.setBody(titleOf(document) + (overdue
                    ? " is overdue for your approval" : " is still awaiting your approval"));
                notification.setContextType("deal");
                notification.setContextId(document.getDealId());
                notification.setDedupeKey(REMINDER_TYPE + ":" + approval.getId() + ":"
                    + step.getId() + ":" + round + ":" + member.getId());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("dealId", document.getDealId());
                data.put("documentId", document.getId());
                data.put("documentTitle", titleOf(document));
                data.put("version", document.getVersion());
                data.put("stepId", step.getId());
                data.put("stepOrder", step.getStepOrder());
                data.put("dueAt", step.getDueAt());
                data.put("round", round);
                notification.setData(json(data));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException e) {
                log.warn("Failed to deliver approval-reminder notification for document {} to recipient {}: {}",
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
        notifyTerminated(workspaceId, document, approval, activeSteps, actor,
            outcomeReason, detail, includeActiveApprovers, approverPool(workspaceId));
    }

    private void notifyTerminated(int workspaceId, DealDocument document,
            DocumentApproval approval, List<DocumentApprovalStep> activeSteps, User actor,
            String outcomeReason, String detail, boolean includeActiveApprovers,
            ApproverPool pool) {
        Set<User> recipients = new LinkedHashSet<>();
        if (approval.getRequestedBy() != null) {
            pool.members().stream()
                .filter(member -> member.getId() == approval.getRequestedBy())
                .findFirst()
                .ifPresent(recipients::add);
        }
        if (includeActiveApprovers) {
            activeSteps.forEach(step ->
                recipients.addAll(recipientsFor(approval, document, step, pool)));
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

    private DocumentApproval requireApprovalForUpdate(int workspaceId, int id) {
        DocumentApproval approval = approvalMapper.getByIdForUpdate(workspaceId, id);
        if (approval == null) throw new ResourceNotFoundException("Approval not found with id: " + id);
        return withChainForUpdate(workspaceId, List.of(approval)).getFirst();
    }

    private record ApprovalProjection(
            ApprovalAvailability overall,
            Map<Integer, ApprovalAvailability> steps,
            Map<Integer, EffectiveApprovers> effective) {
    }
}

/** Current ability of one frozen approval step to reach its remaining quorum. */
record ApprovalAvailability(boolean satisfiable, String reason, Integer blockingStepId) {
}
