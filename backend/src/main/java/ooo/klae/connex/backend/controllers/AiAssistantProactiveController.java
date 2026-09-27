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
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;
import ooo.klae.connex.backend.tenant.TenantJournalClientDriven;

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
@TenantJournalAttributable
public class AiAssistantProactiveController {

    private final AiBriefScheduleService briefScheduleService;
    private final AiWatchService watchService;
    private final AiCommandCenterService commandCenterService;

    /**
     * Returns the schedule, last delivered brief, and watches the command centre renders.
     *
     * <p>A client-scheduled read: it is fetched once per mount and per reload token rather than on
     * a member action, and it answers no operator question its failures do not.
     */
    @TenantJournalClientDriven
    @GetMapping("/command-center")
    public AiCommandCenterDto commandCenter() {
        return commandCenterService.get();
    }

    /**
     * Returns the calling member's brief schedule.
     *
     * <p>No shipped client reads this route — {@code getAiCommandCenter} already serves the
     * schedule inline — so there is no cadence to cite. It is marked anyway, so the structural rule
     * that every assistant read is client-driven keeps no exception a future author has to reason
     * about. Its failures are retained, because nothing re-drives it; the member action that
     * changes a schedule is the {@code PUT}, which stays fully journaled.
     */
    @TenantJournalClientDriven
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

    /**
     * Lists the calling member's watches.
     *
     * <p>No shipped client reads this route — {@code getAiCommandCenter} already serves the watches
     * inline — so there is no cadence to cite. It is marked anyway, so the structural rule that
     * every assistant read is client-driven keeps no exception a future author has to reason about.
     * Its failures are retained, because nothing re-drives it; the member actions that change a
     * watch are the {@code POST}, {@code PATCH} and {@code DELETE}, which stay fully journaled.
     */
    @TenantJournalClientDriven
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
