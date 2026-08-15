package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.ApprovalInboxItemDto;
import ooo.klae.connex.backend.services.DocumentApprovalService;

/**
 * REST controller for the workspace-scoped approval inbox. The deal-scoped approval controller is
 * the wrong host for this: the projection spans every deal in the workspace.
 */
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalInboxController {
    private final DocumentApprovalService approvalService;

    @GetMapping("/inbox")
    public List<ApprovalInboxItemDto> inbox() {
        return approvalService.inbox();
    }
}
