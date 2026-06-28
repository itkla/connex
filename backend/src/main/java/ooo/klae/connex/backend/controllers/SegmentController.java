package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.SegmentEvaluateRequest;
import ooo.klae.connex.backend.dto.SegmentResultDto;
import ooo.klae.connex.backend.services.SegmentService;

/**
 * Evaluates graph-aware smart segments. A read-only operation: it returns the ids of records the
 * caller can already see, scoped to the active workspace and the current user, so it carries no
 * {@code @RequirePermission} (the workspace + user scoping is the boundary).
 */
@RestController
@RequestMapping("/api/segments")
@RequiredArgsConstructor
public class SegmentController {

    private final SegmentService segmentService;

    @PostMapping("/evaluate")
    public SegmentResultDto evaluate(@Valid @RequestBody SegmentEvaluateRequest request) {
        return new SegmentResultDto(segmentService.evaluate(request.getRecordType(), request.getSegments()));
    }
}
