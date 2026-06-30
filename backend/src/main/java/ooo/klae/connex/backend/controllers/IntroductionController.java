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

import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.IntroductionRequestDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.IntroductionService;

/**
 * Reverse-introduction endpoints (issue #43): suggested pairs to introduce, recording an
 * introduction, dismissing a suggestion, and the lineage of introductions made.
 * Delegates to {@code IntroductionService}.
 */
@RestController
@RequestMapping("/api/introductions")
@RequiredArgsConstructor
public class IntroductionController {
    private final IntroductionService introductionService;

    /**
     * GET ranked reverse-introduction suggestions for the active workspace.
     * @param limit maximum suggestions to return
     */
    @GetMapping("/suggestions")
    public List<IntroSuggestionDto> getSuggestions(@RequestParam(defaultValue = "20") int limit) {
        return introductionService.getSuggestions(limit);
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
}
