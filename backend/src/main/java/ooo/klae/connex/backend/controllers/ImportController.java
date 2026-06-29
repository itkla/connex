package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.services.ImportService;

/**
 * REST controller for bulk CSV import of contacts, companies, and deals. Each entity exposes a
 * {@code /preview} dry-run (validate + deduplicate, no writes) and a commit endpoint. Request bodies
 * are validated and row-count capped; authorization and tenant scoping are enforced in
 * {@code ImportService}. Delegates to {@code ImportService}.
 */
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    /**
     * Dry-run a contact import, returning per-row outcomes and counts for the review step.
     */
    @PostMapping("/persons/preview")
    public ImportPreviewResult previewPersons(@Valid @RequestBody ImportRequest request) {
        return importService.previewPersons(request);
    }

    /**
     * Commit a contact import.
     */
    @PostMapping("/persons")
    public ImportResult importPersons(@Valid @RequestBody ImportRequest request) {
        return importService.commitPersons(request);
    }

    /**
     * Dry-run a company import, returning per-row outcomes and counts for the review step.
     */
    @PostMapping("/companies/preview")
    public ImportPreviewResult previewCompanies(@Valid @RequestBody ImportRequest request) {
        return importService.previewCompanies(request);
    }

    /**
     * Commit a company import.
     */
    @PostMapping("/companies")
    public ImportResult importCompanies(@Valid @RequestBody ImportRequest request) {
        return importService.commitCompanies(request);
    }

    /**
     * Dry-run a deal import, returning per-row outcomes and counts for the review step.
     */
    @PostMapping("/deals/preview")
    public ImportPreviewResult previewDeals(@Valid @RequestBody ImportRequest request) {
        return importService.previewDeals(request);
    }

    /**
     * Commit a deal import.
     */
    @PostMapping("/deals")
    public ImportResult importDeals(@Valid @RequestBody ImportRequest request) {
        return importService.commitDeals(request);
    }
}
