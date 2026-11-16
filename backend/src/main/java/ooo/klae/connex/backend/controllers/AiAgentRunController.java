package ooo.klae.connex.backend.controllers;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.mission.AiAgentProposalDecisionService;
import ooo.klae.connex.backend.ai.mission.AiAgentProposalReadService;
import ooo.klae.connex.backend.ai.mission.AiAgentRunService;
import ooo.klae.connex.backend.dto.AiAgentOutputDto;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionResponse;
import ooo.klae.connex.backend.dto.AiAgentRunControlRequest;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiAgentRunDto;
import ooo.klae.connex.backend.dto.AiAgentRunPage;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;
import ooo.klae.connex.backend.tenant.TenantJournalClientDriven;

/** Authenticated workspace mission endpoints; session cookies and CSRF use the ordinary API chain. */
@RestController
@RequestMapping("/api/ai/assistant")
@RequiredArgsConstructor
@TenantJournalAttributable
public class AiAgentRunController {
    private final AiAgentRunService service;
    private final AiAgentProposalReadService proposals;
    private final AiAgentProposalDecisionService decisions;

    @PostMapping("/sessions/{sessionId}/missions")
    public ResponseEntity<AiAgentRunDto> create(@PathVariable int sessionId,
            @Valid @RequestBody AiAgentRunCreateRequest request) {
        AiAgentRunDto result = service.create(sessionId, request);
        return ResponseEntity.accepted().location(URI.create("/api/ai/assistant/missions/" + result.id())).body(result);
    }

    @GetMapping("/missions")
    @TenantJournalClientDriven(retainFailures = false)
    public AiAgentRunPage list(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Integer sessionId, @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor) {
        return service.list(limit, sessionId, state, cursor);
    }

    @GetMapping("/missions/{id}")
    @TenantJournalClientDriven(retainFailures = false)
    public AiAgentRunDto get(@PathVariable int id) {
        return service.get(id);
    }

    @GetMapping("/missions/{id}/output")
    @TenantJournalClientDriven(retainFailures = false)
    public AiAgentOutputDto output(@PathVariable int id) {
        return service.output(id);
    }

    /** Direct item reads retain failures; the shipped client refreshes complete mission output. */
    @GetMapping("/missions/{id}/proposals/{actionSlot}")
    @TenantJournalClientDriven
    public AiAgentOutputDto.ProposalItem proposal(@PathVariable int id, @PathVariable String actionSlot) {
        return proposals.item(id, actionSlot);
    }

    @PostMapping("/missions/{id}/proposal-decisions")
    public AiAgentProposalDecisionResponse decide(@PathVariable int id,
            @Valid @RequestBody AiAgentProposalDecisionRequest request) {
        return decisions.decide(id, request);
    }

    @PostMapping("/missions/{id}/pause")
    public AiAgentRunDto pause(@PathVariable int id, @Valid @RequestBody AiAgentRunControlRequest request) {
        return service.control(id, request, "pause");
    }

    @PostMapping("/missions/{id}/resume")
    public AiAgentRunDto resume(@PathVariable int id, @Valid @RequestBody AiAgentRunControlRequest request) {
        return service.control(id, request, "resume");
    }

    @PostMapping("/missions/{id}/cancel")
    public AiAgentRunDto cancel(@PathVariable int id, @Valid @RequestBody AiAgentRunControlRequest request) {
        return service.control(id, request, "cancel");
    }
}
