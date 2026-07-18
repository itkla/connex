package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.DealDocument;
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
 */
@Service
@RequiredArgsConstructor
public class ApprovalPolicyService {
    private final ApprovalPolicyMapper policyMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS = Set.of(
        "name", "active", "documentType", "currency", "minTotal", "minDiscountPercent");

    public List<ApprovalPolicy> getAll() {
        return policyMapper.getAll(workspaceService.getCurrentWorkspaceId());
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public ApprovalPolicy create(ApprovalPolicy policy) {
        validate(policy);
        policy.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        policyMapper.insert(policy);
        ApprovalPolicy saved = require(policy.getWorkspaceId(), policy.getId());
        auditService.record("approval_policy.create", "approval_policy", saved.getId(), saved.getName(),
            "Created approval policy " + saved.getName(), auditService.diff(null, saved, AUDIT_FIELDS));
        return saved;
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public ApprovalPolicy update(int id, ApprovalPolicy policy) {
        validate(policy);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ApprovalPolicy before = require(workspaceId, id);
        policy.setId(id);
        policy.setWorkspaceId(workspaceId);
        policyMapper.update(policy);
        ApprovalPolicy after = require(workspaceId, id);
        auditService.record("approval_policy.update", "approval_policy", id, after.getName(),
            "Updated approval policy " + after.getName(), auditService.diff(before, after, AUDIT_FIELDS));
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
                && policy.getCurrency() != null && policy.getCurrency().equals(document.getCurrency())
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

    private void validate(ApprovalPolicy policy) {
        if (policy.getMinTotal() != null && (policy.getCurrency() == null || policy.getCurrency().isBlank())) {
            throw new BadRequestException("currency is required when minTotal is set");
        }
    }

    private ApprovalPolicy require(int workspaceId, int id) {
        ApprovalPolicy policy = policyMapper.getById(workspaceId, id);
        if (policy == null) throw new ResourceNotFoundException("Approval policy not found with id: " + id);
        return policy;
    }
}
