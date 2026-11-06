package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.MyWorkDealKpisDto;
import ooo.klae.connex.backend.dto.MyWorkHistoryDto;
import ooo.klae.connex.backend.dto.MyWorkRelationshipsDto;
import ooo.klae.connex.backend.dto.MyWorkRisksDto;
import ooo.klae.connex.backend.dto.NoteActivityDayDto;
import ooo.klae.connex.backend.services.MyWorkReadService;

/** Supporting personal projections with no caller-selectable user or workspace scope. */
@RestController
@RequestMapping("/api/my-work")
@RequiredArgsConstructor
public class MyWorkReadController {
    private final MyWorkReadService readService;

    /** Bounded recent work using the current actor's existing history visibility. */
    @GetMapping("/history")
    public MyWorkHistoryDto history() {
        return readService.history();
    }

    /** Complete personal daily counts for the 84-day pulse. */
    @GetMapping("/pulse")
    public List<NoteActivityDayDto> pulse() {
        return readService.pulse();
    }

    /** Complete personal warmth counts with bounded constellation and cooling records. */
    @GetMapping("/relationships")
    public MyWorkRelationshipsDto relationships() {
        return readService.relationships();
    }

    /** Currency-partitioned personal KPIs; ordinary members cannot select another member. */
    @GetMapping("/deal-kpis")
    public MyWorkDealKpisDto dealKpis(@RequestParam(required = false) String currency) {
        return readService.dealKpis(currency);
    }

    /** Bounded personal risk ranking, with explicit capacity availability. */
    @GetMapping("/risks")
    public MyWorkRisksDto risks() {
        return readService.risks();
    }
}
