package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DeclineDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptanceDecisionDto;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.services.DocumentAcceptanceService;
import ooo.klae.connex.backend.services.DocumentAcceptanceService.Link;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Session-less recipient surface for viewing and deciding a frozen document. The emailed fragment
 * bearer is exchanged once for a purpose-bound browser grant; every other endpoint reads only that
 * grant cookie, so no request path or query ever names the bearer.
 */
@RestController
@RequestMapping("/api/document-acceptance")
@RequiredArgsConstructor
public class DocumentAcceptanceController {
    private final DocumentAcceptanceService acceptanceService;
    private final ClientIpResolver clientIpResolver;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    /** Exchanges the fragment bearer for a routed grant cookie and redirects to the bare page. */
    @PostMapping("/exchange")
    public void exchange(
            @Valid @RequestBody OneTimeLinkExchangeRequest dto,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        Link link = acceptanceService.exchange(
            dto.getToken(), clientIpResolver.resolve(servletRequest));
        IssuedGrant grant = oneTimeLinkFlowService.issueRouted(
            servletRequest, Purpose.DOCUMENT_ACCEPTANCE, link.tokenHash(), link.workspaceId());
        oneTimeLinkFlowCookie.set(
            response, Purpose.DOCUMENT_ACCEPTANCE, grant.value(), grant.lifetime());
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/document-acceptance");
    }

    /**
     * Returns the frozen document without recording anything, so an email scanner, link prefetcher
     * or URL-rewriting proxy that fetches the emailed link cannot forge recipient view evidence into
     * the completion certificate. The rendered recipient page reports the view through
     * {@link #markViewed}.
     */
    @GetMapping
    public DocumentAcceptancePreviewDto preview(
            @CookieValue(name = OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, required = false)
            String grant,
            HttpServletRequest servletRequest) {
        return acceptanceService.preview(
            acceptanceService.admitGrant(servletRequest, grant),
            clientIpResolver.resolve(servletRequest));
    }

    /** Idempotently records that the recipient opened the document. */
    @PostMapping("/viewed")
    public DocumentAcceptancePreviewDto markViewed(
            @CookieValue(name = OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, required = false)
            String grant,
            HttpServletRequest servletRequest) {
        return acceptanceService.markViewed(
            acceptanceService.admitGrant(servletRequest, grant),
            clientIpResolver.resolve(servletRequest));
    }

    @PostMapping("/accept")
    public DocumentAcceptanceDecisionDto accept(
            @Valid @RequestBody AcceptDocumentRequest request,
            @CookieValue(name = OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, required = false)
            String grant,
            HttpServletRequest servletRequest) {
        return acceptanceService.accept(
            acceptanceService.admitGrant(servletRequest, grant),
            request,
            clientIpResolver.resolve(servletRequest),
            servletRequest.getHeader("User-Agent"));
    }

    @PostMapping("/decline")
    public DocumentAcceptanceDecisionDto decline(
            @Valid @RequestBody DeclineDocumentRequest request,
            @CookieValue(name = OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, required = false)
            String grant,
            HttpServletRequest servletRequest) {
        return acceptanceService.decline(
            acceptanceService.admitGrant(servletRequest, grant),
            request,
            clientIpResolver.resolve(servletRequest),
            servletRequest.getHeader("User-Agent"));
    }
}
