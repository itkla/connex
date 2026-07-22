package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDefinitionRequest;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportGenerateRequest;
import ooo.klae.connex.backend.dto.ReportSnapshotDto;
import ooo.klae.connex.backend.dto.ReportSnapshotSummaryDto;
import ooo.klae.connex.backend.dto.ReportTemplateDto;
import ooo.klae.connex.backend.services.ReportService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Workspace-shared report definitions, generated documents, snapshots, and appendix exports.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final ReportService reportService;

    @GetMapping("/templates")
    @RequirePermission(Permission.REPORT_READ)
    public List<ReportTemplateDto> templates() {
        return reportService.templates();
    }

    @GetMapping
    @RequirePermission(Permission.REPORT_READ)
    public List<ReportDefinitionDto> list() {
        return reportService.list();
    }

    @GetMapping("/{id}")
    @RequirePermission(Permission.REPORT_READ)
    public ReportDefinitionDto get(@PathVariable int id) {
        return reportService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.REPORT_CREATE)
    public ReportDefinitionDto create(@Valid @RequestBody ReportDefinitionRequest request) {
        return reportService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permission.REPORT_UPDATE)
    public ReportDefinitionDto update(
            @PathVariable int id,
            @Valid @RequestBody ReportDefinitionRequest request) {
        return reportService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.REPORT_DELETE)
    public void delete(@PathVariable int id) {
        reportService.delete(id);
    }

    @PostMapping("/{id}/generate")
    @RequirePermission(Permission.REPORT_READ)
    public ReportDocumentDto generate(
            @PathVariable int id,
            @RequestParam(name = "narrative", defaultValue = "cached") String narrative,
            @Valid @RequestBody(required = false) ReportGenerateRequest request) {
        return reportService.generate(id, request, narrativeMode(narrative));
    }

    private static ReportService.NarrativeMode narrativeMode(String narrative) {
        return "full".equalsIgnoreCase(narrative)
                ? ReportService.NarrativeMode.FULL
                : ReportService.NarrativeMode.CACHED;
    }

    @PostMapping("/{id}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.REPORT_CREATE)
    public ReportSnapshotDto createSnapshot(
            @PathVariable int id,
            @Valid @RequestBody(required = false) ReportGenerateRequest request) {
        return reportService.createSnapshot(id, request);
    }

    @GetMapping("/{id}/snapshots")
    @RequirePermission(Permission.REPORT_READ)
    public List<ReportSnapshotSummaryDto> listSnapshots(@PathVariable int id) {
        return reportService.listSnapshots(id);
    }

    @GetMapping("/{id}/snapshots/{snapshotId}")
    @RequirePermission(Permission.REPORT_READ)
    public ReportSnapshotDto getSnapshot(@PathVariable int id, @PathVariable int snapshotId) {
        return reportService.getSnapshot(id, snapshotId);
    }

    @DeleteMapping("/{id}/snapshots/{snapshotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.REPORT_DELETE)
    public void deleteSnapshot(@PathVariable int id, @PathVariable int snapshotId) {
        reportService.deleteSnapshot(id, snapshotId);
    }

    @PostMapping("/{id}/export.csv")
    @RequirePermission(Permission.REPORT_READ)
    public ResponseEntity<byte[]> export(
            @PathVariable int id,
            @Valid @RequestBody(required = false) ReportGenerateRequest request) {
        return csv("report-" + id + ".csv", reportService.exportCsv(id, request));
    }

    @GetMapping("/{id}/snapshots/{snapshotId}/export.csv")
    @RequirePermission(Permission.REPORT_READ)
    public ResponseEntity<byte[]> exportSnapshot(@PathVariable int id, @PathVariable int snapshotId) {
        return csv("report-" + id + "-snapshot-" + snapshotId + ".csv",
                reportService.exportSnapshotCsv(id, snapshotId));
    }

    private static ResponseEntity<byte[]> csv(String filename, String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[UTF8_BOM.length + content.length];
        System.arraycopy(UTF8_BOM, 0, payload, 0, UTF8_BOM.length);
        System.arraycopy(content, 0, payload, UTF8_BOM.length, content.length);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(payload);
    }
}
