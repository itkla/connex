package ooo.klae.connex.backend.controllers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.MapReplayDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.MapReplayService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Read-only time-travel replay (#48) of the relationship map for the active workspace: a series of
 * weekly or monthly frames reconstructing graph membership, warmth, employment, and deal outcomes
 * over a date range. Workspace-scoped via {@link WorkspaceService}, mirroring the scoring endpoints.
 */
@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
public class MapReplayController {
    private final MapReplayService mapReplayService;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /** Maximum frames a single replay may produce; wider ranges must coarsen the granularity. */
    private static final int MAX_FRAMES = 120;

    /**
     * Replay frames for {@code [from, to]} (inclusive ISO {@code yyyy-MM-dd} dates) at weekly or
     * monthly granularity. A weekly range that would exceed {@link #MAX_FRAMES} is automatically
     * coarsened to monthly; a range too wide even monthly is rejected.
     */
    @GetMapping("/replay")
    public MapReplayDto replay(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "weekly") String granularity) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        if (fromDate.isAfter(toDate)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        if (toDate.isAfter(LocalDate.now(clock))) {
            throw new BadRequestException("'to' must not be in the future");
        }
        boolean weekly = switch (granularity.toLowerCase()) {
            case "weekly" -> true;
            case "monthly" -> false;
            default -> throw new BadRequestException("'granularity' must be 'weekly' or 'monthly'");
        };
        if (weekly && estimatedFrames(fromDate, toDate, true) > MAX_FRAMES) {
            weekly = false;
        }
        if (estimatedFrames(fromDate, toDate, weekly) > MAX_FRAMES) {
            throw new BadRequestException("Date range is too wide; narrow it or use a coarser granularity");
        }
        Period step = weekly ? Period.ofWeeks(1) : Period.ofMonths(1);
        return mapReplayService.buildReplay(workspaceService.getCurrentWorkspaceId(), fromDate, toDate, step);
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("'" + field + "' must be an ISO date (yyyy-MM-dd)");
        }
    }

    private static long estimatedFrames(LocalDate from, LocalDate to, boolean weekly) {
        return weekly ? ChronoUnit.DAYS.between(from, to) / 7 + 2 : ChronoUnit.MONTHS.between(from, to) + 2;
    }
}
