package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.ai.introrationale.IntroRationaleService;
import ooo.klae.connex.backend.dto.IntroRationaleDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.IntroductionRequestDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.WarmPathAcceptRequestDto;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.dto.WarmPathRequestDto;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.WarmPathService;

/**
 * Reverse-introduction endpoints (issue #43): suggested pairs to introduce, recording an
 * introduction, dismissing a suggestion, and the lineage of introductions made. Also the warm
 * introduction paths for the user (issue #614): ranked "reach this contact via that bridge"
 * suggestions, with accept-into-a-task and dismissal.
 * Delegates to {@code IntroductionService} and {@code WarmPathService}.
 */
@RestController
@RequestMapping("/api/introductions")
@RequiredArgsConstructor
public class IntroductionController {
    private final IntroductionService introductionService;
    private final IntroRationaleService introRationaleService;
    private final WarmPathService warmPathService;

    /**
     * GET ranked reverse-introduction suggestions for the active workspace.
     * @param limit maximum suggestions to return
     */
    @GetMapping("/suggestions")
    public List<IntroSuggestionDto> getSuggestions(@RequestParam(defaultValue = "20") int limit) {
        return introductionService.getSuggestions(limit);
    }

    /** Returns an AI-generated introduction rationale, or a graceful unavailability response. */
    @PostMapping("/suggestions/rationale")
    public IntroRationaleDto rationale(@RequestParam int personA, @RequestParam int personB) {
        return introRationaleService.generate(personA, personB);
    }

    /**
     * GET the lineage of introductions the team has made, newest first.
     */
    @GetMapping
    public PageResponse<IntroductionDto> getLineage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return introductionService.getLineage(page, size);
    }

    /**
     * POST records an introduction the team made between two contacts.
     */
    @PostMapping
    public IntroductionDto recordIntroduction(@Valid @RequestBody IntroductionRequestDto request) {
        return introductionService.createIntroduction(
            request.getPersonAId(), request.getPersonBId(), request.getNote());
    }

    /**
     * POST dismisses a suggested pair so it stops being surfaced.
     */
    @PostMapping("/dismiss")
    public void dismissSuggestion(@Valid @RequestBody IntroductionRequestDto request) {
        introductionService.dismissSuggestion(request.getPersonAId(), request.getPersonBId());
    }

    /**
     * GET ranked warm introduction paths for the active workspace.
     * @param limit maximum target rows to return
     */
    @GetMapping("/paths")
    public List<WarmPathDto> getPaths(@RequestParam(defaultValue = "20") int limit) {
        return warmPathService.getPaths(limit);
    }

    /**
     * POST accepts a warm path: creates the follow-up task asking the bridge for the
     * introduction and retires the target from the feed.
     */
    @PostMapping("/paths/accept")
    public TaskDto acceptPath(@Valid @RequestBody WarmPathAcceptRequestDto request) {
        return TaskDto.from(warmPathService.acceptPath(
            request.getTargetPersonId(), request.getBridgePersonId(), request.getTaskDescription()));
    }

    /**
     * POST dismisses a warm path — one avenue when a bridge is given, otherwise every path to
     * the target.
     */
    @PostMapping("/paths/dismiss")
    public void dismissPath(@Valid @RequestBody WarmPathRequestDto request) {
        warmPathService.dismissPath(request.getTargetPersonId(), request.getBridgePersonId());
    }
}
