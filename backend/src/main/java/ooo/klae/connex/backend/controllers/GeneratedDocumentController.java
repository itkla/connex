package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.GeneratedDocumentSummaryDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.DealDocumentService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.DealFilterNormalizer;
import ooo.klae.connex.backend.util.PageBounds;

/**
 * Workspace-wide index of generated commercial documents.
 *
 * <p>Generated documents are authored and mutated under their parent deal
 * ({@code /api/deals/&#123;dealId&#125;/documents}); this read-only surface exists so they are findable
 * without already knowing the deal. It carries the same membership gate as the per-deal read and
 * returns bounded summaries, never the immutable content snapshot.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class GeneratedDocumentController {

    private final DealDocumentService documentService;
    private final MemberScopeResolver memberScopeResolver;
    private final WorkspaceService workspaceService;

    /**
     * Returns one bounded page of generated documents across every deal in the active workspace.
     *
     * @param page the one-based page number
     * @param size the page size, capped by {@link PageBounds#MAX_SIZE}
     * @param q an optional match over document title and parent deal name
     * @param status optional document statuses to include
     * @param type optional document types to include
     * @param dealId an optional single parent deal to restrict to
     * @param scope the parent deal's ownership scope
     * @param memberIds the selected member ids when {@code scope=members}
     * @return the page of document summaries and the total it was drawn from
     */
    @GetMapping
    public PageResponse<GeneratedDocumentSummaryDto> getDocumentsPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) Integer dealId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) List<Integer> memberIds) {
        PageBounds bounds = PageBounds.of(page, size);
        if (dealId != null && dealId < 1) {
            throw new BadRequestException("dealId must be a positive integer");
        }
        MemberScope memberScope = memberScopeResolver.resolve(
            scope, memberIds, workspaceService.getCurrentUserId());
        return documentService.getWorkspacePage(
            q,
            DealFilterNormalizer.normalizeValues(status, DealDocumentService.INDEX_STATUSES, "status"),
            DealFilterNormalizer.normalizeValues(type, DealDocumentService.INDEX_TYPES, "type"),
            dealId,
            memberScope,
            bounds.size(),
            bounds.offset());
    }
}
