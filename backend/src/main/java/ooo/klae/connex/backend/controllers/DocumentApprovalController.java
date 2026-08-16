package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.dto.ApprovalDecisionRequest;
import ooo.klae.connex.backend.dto.ApprovalDelegateDto;
import ooo.klae.connex.backend.dto.ApprovalDelegationRequest;
import ooo.klae.connex.backend.dto.ApprovalRequestBody;
import ooo.klae.connex.backend.dto.ApprovalStepApproverChangeRequest;
import ooo.klae.connex.backend.dto.ApprovalStepApproverDto;
import ooo.klae.connex.backend.dto.DocumentApprovalDto;
import ooo.klae.connex.backend.services.DocumentApprovalService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** REST controller for the approval lifecycle on generated deal documents. */
@RestController
@RequestMapping("/api/deals/{dealId}/documents/{documentId}/approval")
@RequiredArgsConstructor
public class DocumentApprovalController {
    private final DocumentApprovalService approvalService;

    @GetMapping
    public List<DocumentApprovalDto> getForDocument(@PathVariable int dealId, @PathVariable int documentId) {
        return approvalService.getForDocument(dealId, documentId);
    }

    @PostMapping
    public DocumentApprovalDto request(@PathVariable int dealId, @PathVariable int documentId,
            @Valid @RequestBody ApprovalRequestBody body) {
        return approvalService.requestApproval(dealId, documentId, body.getComment());
    }

    @PostMapping("/decision")
    public DocumentApprovalDto decide(@PathVariable int dealId, @PathVariable int documentId,
            @Valid @RequestBody ApprovalDecisionRequest body) {
        return approvalService.decide(dealId, documentId, body.getDecision(), body.getComment(),
            body.getStepId());
    }

    @PostMapping("/cancel")
    public DocumentApprovalDto cancel(@PathVariable int dealId, @PathVariable int documentId) {
        return approvalService.cancel(dealId, documentId);
    }

    @PostMapping("/steps/{stepId}/delegate")
    public DocumentApprovalDto delegate(@PathVariable int dealId, @PathVariable int documentId,
            @PathVariable int stepId, @Valid @RequestBody ApprovalDelegationRequest body) {
        return approvalService.createDelegation(dealId, documentId, stepId,
            body.getDelegateUserId(), body.getComment());
    }

    @GetMapping("/steps/{stepId}/delegate-candidates")
    @RequirePermission(Permission.DOCUMENT_APPROVE)
    public List<ApprovalDelegateDto> delegateCandidates(@PathVariable int dealId,
            @PathVariable int documentId, @PathVariable int stepId) {
        return approvalService.eligibleDelegates(dealId, documentId, stepId);
    }

    @PostMapping("/steps/{stepId}/approvers")
    public DocumentApprovalDto addApprovers(@PathVariable int dealId, @PathVariable int documentId,
            @PathVariable int stepId, @Valid @RequestBody ApprovalStepApproverChangeRequest body) {
        return approvalService.addStepApprovers(dealId, documentId, stepId,
            approversOf(body), body.getComment());
    }

    @PutMapping("/steps/{stepId}/approvers")
    public DocumentApprovalDto replaceApprovers(@PathVariable int dealId,
            @PathVariable int documentId, @PathVariable int stepId,
            @Valid @RequestBody ApprovalStepApproverChangeRequest body) {
        return approvalService.replaceStepApprovers(dealId, documentId, stepId,
            approversOf(body), body.getComment());
    }

    private List<ApprovalStepApprover> approversOf(ApprovalStepApproverChangeRequest body) {
        return body.getApprovers().stream().map(ApprovalStepApproverDto::toBean).toList();
    }
}
