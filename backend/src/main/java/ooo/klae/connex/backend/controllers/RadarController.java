package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RadarContextDto;
import ooo.klae.connex.backend.dto.RadarResponseDto;
import ooo.klae.connex.backend.dto.RadarSnoozeRequestDto;
import ooo.klae.connex.backend.dto.RadarTaskRequestDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.RadarService;

/** REST boundary for canonical deterministic relationship signals. */
@RestController
@RequestMapping("/api/radar")
@RequiredArgsConstructor
public class RadarController {
    private final RadarService radarService;

    /**
     * Returns the bounded ranked Radar feed with explicit family availability, optionally narrowed
     * to the signals raised against one record.
     */
    @GetMapping
    public RadarResponseDto get(
            @RequestParam(required = false) List<String> family,
            @RequestParam(required = false) List<String> state,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) Integer subjectId) {
        return radarService.get(family, state, q, subjectType, subjectId);
    }

    /** Returns Radar counts and detector status without item bodies. */
    @GetMapping("/summary")
    public RadarResponseDto summary() {
        return radarService.summary();
    }

    /** Follows one signal for the current user. */
    @PostMapping("/{id}/follow")
    public RadarResponseDto.Signal follow(
            @PathVariable long id,
            @RequestHeader("If-Match") String ifMatch) {
        return radarService.follow(id, version(ifMatch));
    }

    /** Snoozes one signal until a validated future instant. */
    @PostMapping("/{id}/snooze")
    public RadarResponseDto.Signal snooze(
            @PathVariable long id,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody RadarSnoozeRequestDto request) {
        return radarService.snooze(id, version(ifMatch), request.until());
    }

    /** Dismisses the current source fingerprint for the current user. */
    @PostMapping("/{id}/dismiss")
    public RadarResponseDto.Signal dismiss(
            @PathVariable long id,
            @RequestHeader("If-Match") String ifMatch) {
        return radarService.dismiss(id, version(ifMatch));
    }

    /** Creates and idempotently binds one canonical task. */
    @PostMapping("/{id}/tasks")
    public RadarResponseDto.Signal createTask(
            @PathVariable long id,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody RadarTaskRequestDto request) {
        return radarService.createTask(id, version(ifMatch), request);
    }

    /** Returns current authorized record context for navigation. */
    @GetMapping("/{id}/context")
    public RadarContextDto context(@PathVariable long id) {
        return radarService.context(id);
    }

    private static String version(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("\\d+:\\d+")) {
            throw new BadRequestException("If-Match must contain a current Radar version");
        }
        return value;
    }
}
