package ooo.klae.connex.backend.controllers;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.mission.AiAgentDsrPurgeService;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeDto;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.services.AuthService;

/** Current-admin, CSRF-protected initiation and metadata progress for conservative DSR cleanup. */
@RestController
@RequestMapping("/api/orgs/{orgId}/data-subject-requests/{requestId}")
@RequiredArgsConstructor
public class AiAgentDsrPurgeController {
    private final AiAgentDsrPurgeService purgeService;
    private final AuthService authService;

    /** Accepts one immutable erasure operation; workflow status edits have no erasure side effect. */
    @PostMapping("/mission-holdings-purge")
    public ResponseEntity<AiAgentHoldingsPurgeDto> initiate(@PathVariable int orgId, @PathVariable long requestId,
            @Valid @RequestBody AiAgentHoldingsPurgeRequest request) {
        AiAgentHoldingsPurgeDto result = purgeService.initiate(orgId, requestId, authService.getCurrentUser().getId(), request);
        return ResponseEntity.status("complete".equals(result.state()) ? 200 : 202).body(result);
    }

    /** Reads only current operation progress after fresh organization-administrator authorization. */
    @GetMapping("/mission-holdings-purges/{operationId}")
    public AiAgentHoldingsPurgeDto get(@PathVariable int orgId, @PathVariable long requestId,
            @PathVariable String operationId) {
        return purgeService.get(orgId, requestId, authService.getCurrentUser().getId(), operationId);
    }
}
