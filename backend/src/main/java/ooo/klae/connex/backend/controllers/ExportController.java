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
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.services.ExportService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.DealFilterNormalizer;
import ooo.klae.connex.backend.util.LikePattern;

/**
 * REST controller for CSV export of contacts, companies, and deals. Each endpoint returns a UTF-8
 * CSV (BOM-prefixed for spreadsheet compatibility) of the workspace's records, honoring the same
 * search, facet, and member-scope filters as the matching list endpoint so the exported set tracks
 * the visible filtered+scoped list rather than just the loaded page. Contacts under an APPI
 * processing restriction ({@code suspended_at}) are the one deliberate exception: they remain
 * visible and manageable in the browser but are never exported, since export is a data provision.
 * Delegates to {@code ExportService}.
 */
@RestController
@RequestMapping("/api/exports")
@RequiredArgsConstructor
public class ExportController {

    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private final ExportService exportService;
    private final MemberScopeResolver memberScopeResolver;
    private final WorkspaceService workspaceService;

    /**
     * Export contacts as CSV, honoring the contact list's search, facet, and member-scope filters.
     */
    @GetMapping("/persons")
    public ResponseEntity<byte[]> exportPersons(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> companies,
            @RequestParam(required = false) List<String> titles,
            @RequestParam(defaultValue = "false") boolean noCompany,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) List<Integer> memberIds) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        return csv("contacts.csv", exportService.exportPersons(query, companies, titles, noCompany, memberScope));
    }

    /**
     * Export companies as CSV, honoring the company list's search, facet, and member-scope filters.
     */
    @GetMapping("/companies")
    public ResponseEntity<byte[]> exportCompanies(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> industry,
            @RequestParam(defaultValue = "false") boolean noIndustry,
            @RequestParam(required = false) List<Integer> ids,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) List<Integer> memberIds) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        return csv("companies.csv", exportService.exportCompanies(query, industry, noIndustry, ids, memberScope));
    }

    /**
     * Export deals as CSV, honoring the deal list's search, facet, and member-scope filters.
     */
    @GetMapping("/deals")
    public ResponseEntity<byte[]> exportDeals(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) List<Integer> pipelineId,
            @RequestParam(required = false) List<Integer> stageId,
            @RequestParam(required = false) List<Integer> companyId,
            @RequestParam(defaultValue = "false") boolean noCompany,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> risk,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) List<Integer> memberIds) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        return csv("deals.csv", exportService.exportDeals(
            query, currency,
            DealFilterNormalizer.normalizeIds(pipelineId, "pipelineId"),
            DealFilterNormalizer.normalizeIds(stageId, "stageId"),
            DealFilterNormalizer.normalizeIds(companyId, "companyId"),
            noCompany,
            DealFilterNormalizer.normalizeStatuses(status),
            DealFilterNormalizer.normalizeValues(risk, DealFilterNormalizer.DEAL_RISKS, "risk"),
            memberScope));
    }

    private MemberScope resolveMemberScope(String scope, List<Integer> memberIds) {
        return memberScopeResolver.resolve(scope, memberIds, workspaceService.getCurrentUserId());
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
