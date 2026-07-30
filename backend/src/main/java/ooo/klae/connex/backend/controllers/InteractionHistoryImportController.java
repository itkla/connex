package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.HistoryImportPreviewResult;
import ooo.klae.connex.backend.dto.HistoryImportRequest;
import ooo.klae.connex.backend.dto.HistoryImportResult;
import ooo.klae.connex.backend.services.InteractionHistoryImportService;

/**
 * HTTP boundary for activity, note, and task interaction-history CSV imports.
 */
@RestController
@RequestMapping("/api/imports/history")
@RequiredArgsConstructor
public class InteractionHistoryImportController {

    private final InteractionHistoryImportService importService;

    /** Previews an activity-history import. */
    @PostMapping("/activities/preview")
    public HistoryImportPreviewResult previewActivities(
            @Valid @RequestBody HistoryImportRequest request) {
        return importService.previewActivities(request);
    }

    /** Commits an activity-history import. */
    @PostMapping("/activities")
    public HistoryImportResult importActivities(
            @Valid @RequestBody HistoryImportRequest request) {
        return importService.commitActivities(request);
    }

    /** Previews a note-history import. */
    @PostMapping("/notes/preview")
    public HistoryImportPreviewResult previewNotes(
            @Valid @RequestBody HistoryImportRequest request) {
        return importService.previewNotes(request);
    }

    /** Commits a note-history import. */
    @PostMapping("/notes")
    public HistoryImportResult importNotes(
            @Valid @RequestBody HistoryImportRequest request) {
        return importService.commitNotes(request);
    }

    /** Previews a task-history import. */
    @PostMapping("/tasks/preview")
    public HistoryImportPreviewResult previewTasks(
            @Valid @RequestBody HistoryImportRequest request) {
        return importService.previewTasks(request);
    }

    /** Commits a task-history import. */
    @PostMapping("/tasks")
    public HistoryImportResult importTasks(
            @Valid @RequestBody HistoryImportRequest request) {
        return importService.commitTasks(request);
    }
}
