package ooo.klae.connex.backend.controllers;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
@Validated
public class DocumentAcceptanceController {
    private final DocumentAcceptanceService acceptanceService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/{token}")
    public DocumentAcceptancePreviewDto preview(
            @Pattern(regexp = "w\\d+-[a-f0-9]{64}") @PathVariable String token,
            HttpServletRequest servletRequest) {
        return acceptanceService.preview(token, clientIpResolver.resolve(servletRequest));
    }

    @PostMapping("/{token}/accept")
    public DocumentAcceptanceDecisionDto accept(
            @Pattern(regexp = "w\\d+-[a-f0-9]{64}") @PathVariable String token,
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
            @Pattern(regexp = "w\\d+-[a-f0-9]{64}") @PathVariable String token,
            @Valid @RequestBody DeclineDocumentRequest request,
            HttpServletRequest servletRequest) {
        return acceptanceService.decline(
            token,
            request,
            clientIpResolver.resolve(servletRequest),
            servletRequest.getHeader("User-Agent"));
    }
}
