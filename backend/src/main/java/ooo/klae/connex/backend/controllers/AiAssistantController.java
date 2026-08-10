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
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDetailDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatSessionUpdateRequest;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.AiAssistantService;

/** Authenticated active-workspace endpoints for durable assistant chat sessions. */
@RestController
@RequestMapping("/api/ai/assistant/sessions")
@RequiredArgsConstructor
public class AiAssistantController {
    private final AiAssistantService assistantService;

    /** Returns a bounded page of caller-owned and shared-participant sessions. */
    @GetMapping
    public PageResponse<AiChatSessionDto> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return assistantService.page(page, size);
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
            @RequestParam(defaultValue = "50") int size) {
        return assistantService.get(id, page, size);
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
}
