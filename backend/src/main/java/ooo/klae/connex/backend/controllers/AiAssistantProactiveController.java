package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiBriefScheduleService;
import ooo.klae.connex.backend.ai.assistant.AiCommandCenterService;
import ooo.klae.connex.backend.ai.assistant.AiWatchService;
import ooo.klae.connex.backend.dto.AiBriefScheduleDto;
import ooo.klae.connex.backend.dto.AiBriefScheduleRequest;
import ooo.klae.connex.backend.dto.AiCommandCenterDto;
import ooo.klae.connex.backend.dto.AiWatchCreateRequest;
import ooo.klae.connex.backend.dto.AiWatchDto;
import ooo.klae.connex.backend.dto.AiWatchStatusRequest;

/**
 * The calling member's own proactive Ask Connex state: their brief schedule and their watches.
 *
 * <p>Every endpoint here is addressed by the resolved session identity alone. There is no member
 * path variable and no administrative variant, so no caller can read or change another member's
 * schedule or watches even with their identifiers, and the surface cannot become a view of who is
 * paying attention to what.
 */
@RestController
@RequestMapping("/api/ai/assistant")
@RequiredArgsConstructor
public class AiAssistantProactiveController {

    private final AiBriefScheduleService briefScheduleService;
    private final AiWatchService watchService;
    private final AiCommandCenterService commandCenterService;

    /** Returns the schedule, last delivered brief, and watches the command centre renders. */
    @GetMapping("/command-center")
    public AiCommandCenterDto commandCenter() {
        return commandCenterService.get();
    }

    /** Returns the calling member's brief schedule. */
    @GetMapping("/brief-schedule")
    public AiBriefScheduleDto briefSchedule() {
        return briefScheduleService.get();
    }

    /** Replaces the calling member's brief schedule in full. */
    @PutMapping("/brief-schedule")
    public AiBriefScheduleDto replaceBriefSchedule(
            @Valid @RequestBody AiBriefScheduleRequest request) {
        return briefScheduleService.replace(request);
    }

    /** Lists the calling member's watches. */
    @GetMapping("/watches")
    public List<AiWatchDto> watches() {
        return watchService.list();
    }

    /** Creates one typed watch from an already-previewed trigger. */
    @PostMapping("/watches")
    @ResponseStatus(HttpStatus.CREATED)
    public AiWatchDto createWatch(@Valid @RequestBody AiWatchCreateRequest request) {
        return watchService.create(request);
    }

    /** Pauses or resumes one of the calling member's watches. */
    @PatchMapping("/watches/{id}")
    public AiWatchDto setWatchStatus(
            @PathVariable int id, @Valid @RequestBody AiWatchStatusRequest request) {
        return watchService.setActive(id, request.active());
    }

    /** Deletes one of the calling member's watches. */
    @DeleteMapping("/watches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWatch(@PathVariable int id) {
        watchService.delete(id);
    }
}
