package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.DisqualificationReasonDto;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.services.DisqualificationReasonService;

/** Workspace disqualification vocabulary; reads are open and writes require workspace settings. */
@RestController
@RequestMapping("/api/disqualification-reasons")
@RequiredArgsConstructor
public class DisqualificationReasonController {
    private final DisqualificationReasonService reasonService;

    /** Returns the resolved workspace vocabulary in display order. */
    @GetMapping
    public List<DisqualificationReasonDto> getReasons(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return includeArchived ? reasonService.getAll() : reasonService.getActive();
    }

    /** Adds one custom reason. */
    @PostMapping
    public DisqualificationReasonDto create(
            @Valid @RequestBody DisqualificationReasonRequest request) {
        return reasonService.create(request);
    }

    /** Replaces one reason's editable fields while preserving its code. */
    @PutMapping("/{id}")
    public DisqualificationReasonDto update(
            @PathVariable int id, @Valid @RequestBody DisqualificationReasonRequest request) {
        return reasonService.update(id, request);
    }

    /** Archives one reason without removing its historical label. */
    @DeleteMapping("/{id}")
    public void archive(@PathVariable int id) {
        reasonService.archive(id);
    }

    /** Restores one archived reason. */
    @PostMapping("/{id}/restore")
    public void restore(@PathVariable int id) {
        reasonService.restore(id);
    }
}
