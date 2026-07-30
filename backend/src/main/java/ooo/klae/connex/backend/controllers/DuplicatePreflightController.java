package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.services.DuplicatePreflightService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Bounded duplicate checks for person and company intake.
 */
@RestController
@RequestMapping("/api/duplicate-preflight")
@RequiredArgsConstructor
public class DuplicatePreflightController {

    private final DuplicatePreflightService duplicatePreflightService;

    /**
     * Returns ranked visible person candidates without exposing invisible records.
     *
     * @param request candidate person fields
     * @return ranked candidates
     */
    @PostMapping("/persons")
    @RequirePermission(Permission.PERSON_CREATE)
    public DuplicatePreflightResponse persons(
            @Valid @RequestBody PersonDuplicatePreflightRequest request) {
        return duplicatePreflightService.preflightPerson(request);
    }

    /**
     * Returns ranked visible company candidates without exposing invisible records.
     *
     * @param request candidate company fields
     * @return ranked candidates
     */
    @PostMapping("/companies")
    @RequirePermission(Permission.COMPANY_CREATE)
    public DuplicatePreflightResponse companies(
            @Valid @RequestBody CompanyDuplicatePreflightRequest request) {
        return duplicatePreflightService.preflightCompany(request);
    }
}
