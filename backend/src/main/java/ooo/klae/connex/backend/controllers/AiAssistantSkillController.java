package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiSkillDirectoryService;
import ooo.klae.connex.backend.dto.AiAssistantSkillDto;

/**
 * The declared assistant capabilities the current member can run on the current surface.
 *
 * <p>Contextual entry points are built from this directory, so what a page offers follows the
 * server's own catalog and the caller's permissions instead of a list maintained in the client.
 */
@RestController
@RequestMapping("/api/ai/assistant/skills")
@RequiredArgsConstructor
public class AiAssistantSkillController {
    private final AiSkillDirectoryService skillDirectoryService;

    /** Lists runnable skills, optionally filtered to one declared context kind. */
    @GetMapping
    public List<AiAssistantSkillDto> list(@RequestParam(required = false) String context) {
        return skillDirectoryService.list(context);
    }
}
