package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DeclineDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptanceDecisionDto;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.services.DocumentAcceptanceService;
import ooo.klae.connex.backend.util.ClientIpResolver;

/** Session-less bearer-link surface for viewing and deciding a frozen document. */
@RestController
@RequestMapping("/api/document-acceptance")
@RequiredArgsConstructor
public class DocumentAcceptanceController {
    private final DocumentAcceptanceService acceptanceService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Returns the frozen document without recording anything, so an email scanner, link prefetcher
     * or URL-rewriting proxy that fetches the emailed link cannot forge recipient view evidence into
     * the completion certificate. The rendered recipient page reports the view through
     * {@link #markViewed}.
     */
    @GetMapping("/{token}")
    public DocumentAcceptancePreviewDto preview(
            @PathVariable String token,
            HttpServletRequest servletRequest) {
        return acceptanceService.preview(token, clientIpResolver.resolve(servletRequest));
    }

    /** Idempotently records that the recipient opened the document. */
    @PostMapping("/{token}/viewed")
    public DocumentAcceptancePreviewDto markViewed(
            @PathVariable String token,
            HttpServletRequest servletRequest) {
        return acceptanceService.markViewed(token, clientIpResolver.resolve(servletRequest));
    }

    @PostMapping("/{token}/accept")
    public DocumentAcceptanceDecisionDto accept(
            @PathVariable String token,
            @Valid @RequestBody AcceptDocumentRequest request,
            HttpServletRequest servletRequest) {
        return acceptanceService.accept(
            token,
            request,
            clientIpResolver.resolve(servletRequest),
            servletRequest.getHeader("User-Agent"));
    }

    @PostMapping("/{token}/decline")
    public DocumentAcceptanceDecisionDto decline(
            @PathVariable String token,
            @Valid @RequestBody DeclineDocumentRequest request,
            HttpServletRequest servletRequest) {
        return acceptanceService.decline(
            token,
            request,
            clientIpResolver.resolve(servletRequest),
            servletRequest.getHeader("User-Agent"));
    }
}
