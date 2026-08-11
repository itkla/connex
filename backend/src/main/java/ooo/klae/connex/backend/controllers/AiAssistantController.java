package ooo.klae.connex.backend.controllers;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDetailDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatSessionUpdateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.dto.AiChatTurnDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.AiAssistantService;

/** Authenticated active-workspace endpoints for durable assistant chat sessions. */
@RestController
@RequestMapping("/api/ai/assistant/sessions")
@RequiredArgsConstructor
public class AiAssistantController {
    private static final String RETAINED_SCOPE = "retained";

    private final AiAssistantService assistantService;
    private final AiAssistantTurnService turnService;

    /** Returns a bounded page of caller-owned and shared-participant sessions. */
    @GetMapping
    public PageResponse<AiChatSessionDto> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String scope) {
        if (scope == null) {
            return assistantService.page(page, size);
        }
        if (RETAINED_SCOPE.equals(scope)) {
            return assistantService.pageRetained(page, size);
        }
        throw unsupportedScope();
    }

    /** Creates a private active session owned by the caller. */
    @PostMapping
    public ResponseEntity<AiChatSessionDto> create(
            @Valid @RequestBody AiChatSessionCreateRequest request) {
        AiChatSessionDto created = assistantService.create(request);
        URI location = URI.create("/api/ai/assistant/sessions/" + created.getId());
        return ResponseEntity.created(location).body(created);
    }

    /** Returns one accessible session and an ordered page of its messages. */
    @GetMapping("/{id:\\d+}")
    public AiChatSessionDetailDto get(
            @PathVariable int id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String scope) {
        if (scope == null) {
            return assistantService.get(id, page, size);
        }
        if (RETAINED_SCOPE.equals(scope)) {
            return assistantService.getRetained(id, page, size);
        }
        throw unsupportedScope();
    }

    /** Renames and/or archives an owned session. */
    @PatchMapping("/{id:\\d+}")
    public AiChatSessionDto update(
            @PathVariable int id,
            @Valid @RequestBody AiChatSessionUpdateRequest request) {
        return assistantService.update(id, request);
    }

    /** Idempotently soft-archives an owned session. */
    @DeleteMapping("/{id:\\d+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable int id) {
        assistantService.archive(id);
    }

    /** Appends a caller-authored message to an active accessible session. */
    @PostMapping("/{id:\\d+}/messages")
    public ResponseEntity<AiChatMessageDto> appendMessage(
            @PathVariable int id,
            @Valid @RequestBody AiChatMessageCreateRequest request) {
        AiChatMessageDto created = assistantService.appendMessage(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Starts one bounded asynchronous agent turn without changing the message-append contract. */
    @PostMapping("/{id:\\d+}/turns")
    public ResponseEntity<AiChatTurnAcceptedDto> startTurn(
            @PathVariable int id,
            @Valid @RequestBody AiChatTurnCreateRequest request) {
        return ResponseEntity.accepted().body(turnService.start(id, request));
    }

    /** Returns one durable turn state after current authorization and lazy expiry. */
    @GetMapping("/{sessionId:\\d+}/turns/{turnId:\\d+}")
    public AiChatTurnDto getTurn(
            @PathVariable int sessionId,
            @PathVariable int turnId) {
        return turnService.get(sessionId, turnId);
    }

    private static BadRequestException unsupportedScope() {
        return new BadRequestException("Assistant session scope must be retained when provided");
    }
}
