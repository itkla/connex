package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.validation.annotation.Validated;
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

import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDuplicateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplatePreviewRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateReorderRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateResetRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateStateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateListDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUpdateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.services.RecordCreationTemplateService;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

@RestController
@RequestMapping("/api/record-creation/templates")
@RequiredArgsConstructor
@Validated
@TenantJournalAttributable
public class RecordCreationTemplateController {
    private final RecordCreationTemplateService templateService;

    @GetMapping
    public RecordCreationTemplateListDto list(
            @RequestParam RecordCreationRecordType recordType,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return templateService.list(recordType, includeArchived);
    }

    @GetMapping("/{templateId}")
    public RecordCreationTemplateDto get(@PathVariable String templateId) {
        return templateService.get(templateId);
    }

    @PostMapping("/preview")
    public ResolvedCreationTemplateDto preview(
            @Valid @RequestBody RecordCreationTemplatePreviewRequestDto request) {
        return templateService.preview(request);
    }

    @PostMapping("/impact")
    public RecordCreationImpactDto impact(
            @Valid @RequestBody RecordCreationImpactRequestDto request) {
        return templateService.impact(request);
    }

    @PostMapping
    public RecordCreationTemplateDto create(
            @Valid @RequestBody RecordCreationTemplateCreateRequestDto request) {
        return templateService.create(request);
    }

    @PutMapping("/{templateId}")
    public RecordCreationTemplateDto update(
            @PathVariable String templateId,
            @Valid @RequestBody RecordCreationTemplateUpdateRequestDto request) {
        return templateService.update(templateId, request);
    }

    @PostMapping("/{templateId}/duplicate")
    public RecordCreationTemplateDto duplicate(
            @PathVariable String templateId,
            @Valid @RequestBody RecordCreationTemplateDuplicateRequestDto request) {
        return templateService.duplicate(templateId, request);
    }

    @PutMapping("/order")
    public RecordCreationTemplateListDto reorder(
            @Valid @RequestBody RecordCreationTemplateReorderRequestDto request) {
        return templateService.reorder(request);
    }

    @PutMapping("/default")
    public RecordCreationPresetCatalogDto setDefault(
            @Valid @RequestBody RecordCreationTemplateDefaultRequestDto request) {
        return templateService.setDefault(request);
    }

    @PostMapping("/{templateId}/archive")
    public RecordCreationTemplateDto archive(
            @PathVariable String templateId,
            @Valid @RequestBody RecordCreationTemplateStateRequestDto request) {
        return templateService.archive(templateId, request);
    }

    @PostMapping("/{templateId}/restore")
    public RecordCreationTemplateDto restore(
            @PathVariable String templateId,
            @Valid @RequestBody RecordCreationTemplateStateRequestDto request) {
        return templateService.restore(templateId, request);
    }

    @PostMapping("/reset")
    public RecordCreationPresetCatalogDto reset(
            @Valid @RequestBody RecordCreationTemplateResetRequestDto request) {
        return templateService.reset(request);
    }
}
