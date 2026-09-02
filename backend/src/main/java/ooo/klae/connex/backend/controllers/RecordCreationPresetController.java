package ooo.klae.connex.backend.controllers;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.RecordCreationPresetService;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

@RestController
@RequestMapping("/api/record-creation/presets")
@RequiredArgsConstructor
@Validated
@TenantJournalAttributable
public class RecordCreationPresetController {
    private final RecordCreationPresetService presetService;

    @GetMapping("/persons")
    public RecordCreationPresetCatalogDto persons(
            @RequestParam @NotNull RecordCreationEntryPoint entryPoint,
            @RequestParam(required = false) @Positive Integer relatedCompanyId) {
        return presetService.persons(entryPoint, relatedCompanyId);
    }

    @GetMapping("/companies")
    public RecordCreationPresetCatalogDto companies(
            @RequestParam @NotNull RecordCreationEntryPoint entryPoint) {
        return presetService.companies(entryPoint);
    }

    @GetMapping("/deals")
    public RecordCreationPresetCatalogDto deals(
            @RequestParam @NotNull RecordCreationEntryPoint entryPoint,
            @RequestParam(required = false) @Positive Integer relatedCompanyId) {
        return presetService.deals(entryPoint, relatedCompanyId);
    }
}
