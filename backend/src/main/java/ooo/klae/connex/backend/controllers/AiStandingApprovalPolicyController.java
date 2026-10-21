package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyDto;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.services.AiStandingApprovalPolicyService;
import ooo.klae.connex.backend.services.AuthService;

/** Organization administrator ceiling and kill switch; available with all execution flags off. */
@RestController
@RequestMapping("/api/ai/standing-approval-policy")
@RequiredArgsConstructor
public class AiStandingApprovalPolicyController {
    private final AiStandingApprovalPolicyService policy;
    private final AuthService auth;

    /** Reads the organization ceiling through the authenticated workspace context. */
    @GetMapping
    public AiStandingApprovalPolicyDto get(@RequestParam("workspaceId") int workspaceId) {
        return policy.read(workspaceId, auth.getCurrentUser().getId());
    }

    /** Replaces the expected version, including an acknowledged organization kill. */
    @PutMapping
    public AiStandingApprovalPolicyDto put(@RequestParam("workspaceId") int workspaceId,
            @Valid @RequestBody AiStandingApprovalPolicyRequest request) {
        return policy.replace(workspaceId, auth.getCurrentUser().getId(), request);
    }
}
