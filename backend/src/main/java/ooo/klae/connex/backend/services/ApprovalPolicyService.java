package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealLineItemDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Manages workspace-scoped document approval policies and evaluates them against generated
 * documents. Reads are workspace-scoped; mutations require {@link Permission#DOCUMENT_MANAGE}.
 * Evaluation is deterministic over the document's frozen snapshot: a policy matches when its type
 * filter passes and, if any threshold is configured, at least one threshold is met. A policy with
 * no thresholds matches every document of its type. {@code minTotal} only compares within the
 * policy's currency, so multi-currency workspaces declare one policy per quoted currency.
 *
 * <p>A policy also owns its approver chain. Steps are replaced wholesale on update, and step order
 * follows the submitted list. Chain validation is fail-fast: a step whose quorum can never be met
 * by its declared approvers is refused at save time rather than deadlocking a document later.
 */
@Service
@RequiredArgsConstructor
public class ApprovalPolicyService {
    private final ApprovalPolicyMapper policyMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS = Set.of(
        "name", "active", "documentType", "currency", "minTotal", "minDiscountPercent",
        "mode", "separationOfDuties");
    private static final String ANY_APPROVER = "any_approver";

    public List<ApprovalPolicy> getAll() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return withSteps(workspaceId, policyMapper.getAll(workspaceId));
    }

    @Transactional
    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public ApprovalPolicy create(ApprovalPolicy policy) {
        normalize(policy);
        validate(policy);
        policy.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        policyMapper.insert(policy);
        insertSteps(policy.getWorkspaceId(), policy.getId(), policy.getSteps());
        ApprovalPolicy saved = require(policy.getWorkspaceId(), policy.getId());
        auditService.record("approval_policy.create", "approval_policy", saved.getId(), saved.getName(),
            "Created approval policy " + saved.getName() + chainSummary(saved),
            auditService.diff(null, saved, AUDIT_FIELDS));
        return saved;
    }

    @Transactional
    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public ApprovalPolicy update(int id, ApprovalPolicy policy) {
        normalize(policy);
        validate(policy);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ApprovalPolicy before = require(workspaceId, id);
        policy.setId(id);
        policy.setWorkspaceId(workspaceId);
        policyMapper.update(policy);
        policyMapper.deleteStepsByPolicyId(workspaceId, id);
        insertSteps(workspaceId, id, policy.getSteps());
        ApprovalPolicy after = require(workspaceId, id);
        auditService.record("approval_policy.update", "approval_policy", id, after.getName(),
            "Updated approval policy " + after.getName() + chainSummary(after),
            auditService.diff(before, after, AUDIT_FIELDS));
        return after;
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ApprovalPolicy before = require(workspaceId, id);
        policyMapper.delete(workspaceId, id);
        auditService.record("approval_policy.delete", "approval_policy", id, before.getName(),
            "Deleted approval policy " + before.getName(), auditService.diff(before, null, AUDIT_FIELDS));
    }

    /** Active policies for a workspace, fetched once so callers can evaluate many documents. */
    public List<ApprovalPolicy> activePolicies(int workspaceId) {
        return policyMapper.getActive(workspaceId);
    }

    /**
     * The first of the given active policies matching the document's frozen snapshot, or
     * {@code null} when none matches and the document may be finalized without approval.
     */
    public ApprovalPolicy firstMatch(List<ApprovalPolicy> policies, DealDocument document, DocumentContent content) {
        BigDecimal grandTotal = content == null || content.totals() == null ? null : content.totals().grandTotal();
        BigDecimal discountPercent = discountPercent(content);
        return policies.stream()
            .filter(policy -> matches(policy, document, grandTotal, discountPercent))
            .findFirst()
            .orElse(null);
    }

    private boolean matches(ApprovalPolicy policy, DealDocument document, BigDecimal grandTotal,
            BigDecimal discountPercent) {
        if (policy.getDocumentType() != null && !policy.getDocumentType().equals(document.getType())) {
            return false;
        }
        if (policy.getMinTotal() == null && policy.getMinDiscountPercent() == null) {
            return true;
        }
        if (policy.getMinTotal() != null && grandTotal != null
                && currenciesMatch(policy.getCurrency(), document.getCurrency())
                && grandTotal.compareTo(policy.getMinTotal()) >= 0) {
            return true;
        }
        return policy.getMinDiscountPercent() != null && discountPercent != null
            && discountPercent.compareTo(policy.getMinDiscountPercent()) >= 0;
    }

    /**
     * Effective discount over the frozen line items: {@code (list - discounted) / list * 100}
     * where list is the undiscounted {@code unitPrice * quantity} sum and discounted is the
     * pre-tax subtotal sum. Returns {@code null} when the snapshot has no priced lines.
     */
    private BigDecimal discountPercent(DocumentContent content) {
        if (content == null || content.lineItems() == null || content.lineItems().isEmpty()) {
            return null;
        }
        BigDecimal list = BigDecimal.ZERO;
        BigDecimal discounted = BigDecimal.ZERO;
        for (DealLineItemDto line : content.lineItems()) {
            if (line.getUnitPrice() == null || line.getQuantity() == null) {
                continue;
            }
            list = list.add(line.getUnitPrice().multiply(line.getQuantity()));
            discounted = discounted.add(line.getLineSubtotal() == null ? BigDecimal.ZERO : line.getLineSubtotal());
        }
        if (list.signum() <= 0) {
            return null;
        }
        BigDecimal percent = list.subtract(discounted)
            .multiply(BigDecimal.valueOf(100))
            .divide(list, 3, RoundingMode.HALF_UP);
        return percent.max(BigDecimal.ZERO);
    }

    /**
     * Normalizes the currency to a trimmed uppercase code so a policy saved as {@code "jpy "} can
     * never silently fail to match {@code "JPY"} documents — a mismatch would fail open.
     */
    private void normalize(ApprovalPolicy policy) {
        if (policy.getCurrency() != null) {
            String currency = policy.getCurrency().trim().toUpperCase();
            policy.setCurrency(currency.isEmpty() ? null : currency);
        }
        if (policy.getMode() == null || policy.getMode().isBlank()) {
            policy.setMode("sequential");
        }
        if (policy.getSeparationOfDuties() == null || policy.getSeparationOfDuties().isBlank()) {
            policy.setSeparationOfDuties("strict");
        }
        policy.getSteps().forEach(step -> {
            if (step.getRequiredCount() < 1) {
                step.setRequiredCount(1);
            }
        });
    }

    private boolean currenciesMatch(String policyCurrency, String documentCurrency) {
        return policyCurrency != null && documentCurrency != null
            && policyCurrency.trim().equalsIgnoreCase(documentCurrency.trim());
    }

    private void validate(ApprovalPolicy policy) {
        if (policy.getMinTotal() != null && (policy.getCurrency() == null || policy.getCurrency().isBlank())) {
            throw new BadRequestException("currency is required when minTotal is set");
        }
        validateChain(policy);
    }

    /**
     * Refuses a chain that could never complete: an unknown approver, an approver who cannot hold
     * {@link Permission#DOCUMENT_APPROVE}, a step mixing named approvers with {@code any_approver},
     * or a quorum larger than the number of members who could satisfy it today.
     */
    private void validateChain(ApprovalPolicy policy) {
        List<ApprovalPolicyStep> steps = policy.getSteps();
        if (steps.isEmpty()) {
            return;
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Set<Integer> eligible = workspaceService.getMembers(workspaceId).stream()
            .map(User::getId)
            .filter(memberId -> workspaceService.permissionsFor(workspaceId, memberId)
                .contains(Permission.DOCUMENT_APPROVE))
            .collect(Collectors.toSet());
        for (ApprovalPolicyStep step : steps) {
            List<ApprovalStepApprover> approvers = step.getApprovers();
            boolean anyApprover = approvers.stream()
                .anyMatch(approver -> ANY_APPROVER.equals(approver.getApproverKind()));
            if (anyApprover && approvers.size() > 1) {
                throw new BadRequestException(
                    "A step set to any approver cannot also name individual approvers");
            }
            Set<Integer> named = new HashSet<>();
            for (ApprovalStepApprover approver : approvers) {
                if (anyApprover) {
                    continue;
                }
                Integer userId = approver.getUserId();
                if (userId == null) {
                    throw new BadRequestException("userId is required for a named approver");
                }
                if (!named.add(userId)) {
                    throw new BadRequestException("The same approver is listed twice on one step");
                }
                if (!eligible.contains(userId)) {
                    throw new BadRequestException(
                        "Approver " + userId + " is not a workspace member who can approve documents");
                }
            }
            int available = anyApprover ? eligible.size() : named.size();
            if (step.getRequiredCount() > available) {
                throw new BadRequestException("Step \"" + stepLabel(step)
                    + "\" requires more approvals than it has approvers who can give them");
            }
        }
    }

    private String stepLabel(ApprovalPolicyStep step) {
        return step.getName() == null || step.getName().isBlank() ? "unnamed" : step.getName();
    }

    private String chainSummary(ApprovalPolicy policy) {
        return policy.getSteps().isEmpty() ? ""
            : " with a " + policy.getMode() + " chain of " + policy.getSteps().size() + " step(s)";
    }

    private void insertSteps(int workspaceId, int policyId, List<ApprovalPolicyStep> steps) {
        int order = 1;
        for (ApprovalPolicyStep step : steps) {
            step.setWorkspaceId(workspaceId);
            step.setPolicyId(policyId);
            step.setStepOrder(order++);
            policyMapper.insertStep(step);
            boolean anyApprover = step.getApprovers().stream()
                .anyMatch(approver -> ANY_APPROVER.equals(approver.getApproverKind()));
            for (ApprovalStepApprover approver : step.getApprovers()) {
                approver.setWorkspaceId(workspaceId);
                approver.setStepId(step.getId());
                if (anyApprover) {
                    approver.setUserId(null);
                }
                policyMapper.insertStepApprover(approver);
            }
        }
    }

    /** Attaches each policy's ordered chain in one extra query. */
    private List<ApprovalPolicy> withSteps(int workspaceId, List<ApprovalPolicy> policies) {
        if (policies.isEmpty()) {
            return policies;
        }
        Map<Integer, List<ApprovalPolicyStep>> byPolicy = policyMapper.getStepsByPolicyIds(
                workspaceId, policies.stream().map(ApprovalPolicy::getId).toList()).stream()
            .collect(Collectors.groupingBy(ApprovalPolicyStep::getPolicyId));
        policies.forEach(policy -> policy.setSteps(byPolicy.getOrDefault(policy.getId(), List.of())));
        return policies;
    }

    /** The ordered chain declared by one policy, empty when the policy has no explicit steps. */
    List<ApprovalPolicyStep> stepsFor(int workspaceId, int policyId) {
        return policyMapper.getStepsByPolicyIds(workspaceId, List.of(policyId));
    }

    /**
     * One consistent read of a policy and its chain, or {@code null} when the policy no longer
     * exists. Header and steps come from a single statement so an approval can never freeze one
     * policy revision's mode against another revision's steps.
     */
    ApprovalPolicy snapshot(int workspaceId, int policyId) {
        return policyMapper.getWithStepsById(workspaceId, policyId);
    }

    private ApprovalPolicy require(int workspaceId, int id) {
        ApprovalPolicy policy = policyMapper.getById(workspaceId, id);
        if (policy == null) throw new ResourceNotFoundException("Approval policy not found with id: " + id);
        policy.setSteps(stepsFor(workspaceId, id));
        return policy;
    }
}
