package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;

/** Workspace- and actor-scoped status reads for bounded asynchronous AI generation. */
@RestController
@RequestMapping("/api/ai/generations")
@RequiredArgsConstructor
public class AiGenerationController {
    private final AiGenerationService aiGenerationService;

    /** Returns the current state of one opaque generation handle without re-running feature work. */
    @GetMapping("/{handle}")
    public AiGenerationStatusDto status(
            @PathVariable String handle,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return aiGenerationService.status(handle);
    }
}
