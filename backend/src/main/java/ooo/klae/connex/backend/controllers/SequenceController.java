package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.sequence.SequenceDto;
import ooo.klae.connex.backend.dto.sequence.SequenceMergeFieldDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewDto;
import ooo.klae.connex.backend.dto.sequence.SequencePreviewRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceVersionDto;
import ooo.klae.connex.backend.services.SequencePreviewService;
import ooo.klae.connex.backend.services.SequenceService;
import ooo.klae.connex.backend.services.SequenceVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

/** Workspace-scoped sequence template, version, and preview endpoints. */
@RestController
@RequestMapping("/api/sequences")
@RequiredArgsConstructor
@Validated
@TenantJournalAttributable
@ConditionalOnProperty(prefix = "connex.sequences", name = "enabled", havingValue = "true")
public class SequenceController {
    private final SequenceService sequenceService;
    private final SequenceVersionService versionService;
    private final SequencePreviewService previewService;

    /** Lists visible sequence templates. */
    @GetMapping
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public List<SequenceDto> list() {
        return sequenceService.list();
    }

    /** Creates a sequence template and its ordered draft. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public SequenceDto create(@Valid @RequestBody SequenceRequest request) {
        return sequenceService.create(request);
    }

    /** Returns one visible sequence template. */
    @GetMapping("/{id}")
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public SequenceDto get(@Positive @PathVariable int id) {
        return sequenceService.get(id);
    }

    /** Replaces a sequence template and its ordered draft. */
    @PutMapping("/{id}")
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public SequenceDto update(
            @Positive @PathVariable int id,
            @Valid @RequestBody SequenceRequest request) {
        return sequenceService.update(id, request);
    }

    /** Archives a sequence while retaining published history. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public void archive(@Positive @PathVariable int id) {
        sequenceService.archive(id);
    }

    /** Publishes the current draft as the next immutable version. */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.SEQUENCE_MANAGE)
    public SequenceVersionDto publish(@Positive @PathVariable int id) {
        return versionService.publish(id);
    }

    /** Lists published immutable versions. */
    @GetMapping("/{id}/versions")
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public List<SequenceVersionDto> listVersions(@Positive @PathVariable int id) {
        return versionService.list(id);
    }

    /** Returns one published immutable version. */
    @GetMapping("/{id}/versions/{version}")
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public SequenceVersionDto getVersion(
            @Positive @PathVariable int id,
            @Positive @PathVariable int version) {
        return versionService.get(id, version);
    }

    /** Renders a published version without persisting or sending content. */
    @PostMapping("/{id}/versions/{version}/preview")
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public SequencePreviewDto preview(
            @Positive @PathVariable int id,
            @Positive @PathVariable int version,
            @Valid @RequestBody SequencePreviewRequest request) {
        return previewService.preview(id, version, request);
    }

    /** Returns the fixed allowlisted merge-field catalog. */
    @GetMapping("/merge-fields")
    @RequirePermission(Permission.SEQUENCE_VIEW)
    public List<SequenceMergeFieldDto> mergeFields() {
        return previewService.mergeFields();
    }
}
