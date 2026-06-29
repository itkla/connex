package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.ExportService;

/**
 * REST controller for CSV export of contacts, companies, and deals. Each endpoint returns a UTF-8
 * CSV (BOM-prefixed for spreadsheet compatibility) of the workspace's records, honoring the same
 * filters as the matching list endpoint. Delegates to {@code ExportService}.
 */
@RestController
@RequestMapping("/api/exports")
@RequiredArgsConstructor
public class ExportController {

    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private final ExportService exportService;

    /**
     * Export contacts as CSV, honoring the contact list's search and facet filters.
     */
    @GetMapping("/persons")
    public ResponseEntity<byte[]> exportPersons(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> companies,
            @RequestParam(required = false) List<String> titles,
            @RequestParam(defaultValue = "false") boolean noCompany) {
        return csv("contacts.csv", exportService.exportPersons(q, companies, titles, noCompany));
    }

    /**
     * Export companies as CSV (optionally filtered by tag).
     */
    @GetMapping("/companies")
    public ResponseEntity<byte[]> exportCompanies(@RequestParam(required = false) Integer tagId) {
        return csv("companies.csv", exportService.exportCompanies(tagId));
    }

    /**
     * Export deals as CSV, honoring the deal list's filters.
     */
    @GetMapping("/deals")
    public ResponseEntity<byte[]> exportDeals(
            @RequestParam(required = false) Integer pipelineId,
            @RequestParam(required = false) Integer stageId,
            @RequestParam(required = false) Integer companyId,
            @RequestParam(required = false) Integer personId,
            @RequestParam(required = false) Integer tagId) {
        return csv("deals.csv", exportService.exportDeals(pipelineId, stageId, companyId, personId, tagId));
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
