package ooo.klae.connex.backend.controllers;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.dto.EmailChangeRequestDto;
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.services.EmailChangeService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Verified account email-change endpoints. Initiation is authenticated and
 * CSRF-protected (it changes the caller's own account); validation and
 * confirmation live under {@code /api/auth/**} so a recipient can redeem the
 * emailed link without a prior session — the token from the new address is the
 * bearer credential.
 */
@RestController
@RequiredArgsConstructor
public class EmailChangeController {

    private final EmailChangeService emailChangeService;
    private final ClientIpResolver clientIpResolver;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    @PostMapping("/api/users/me/email-change")
    public Map<String, String> request(@Valid @RequestBody EmailChangeRequestDto dto,
            HttpServletRequest httpRequest) {
        emailChangeService.requestChange(dto.getNewEmail(), dto.getCurrentPassword(),
                clientIpResolver.resolve(httpRequest));
        return Map.of("message", "Check your new email address for a verification link");
    }

    @PostMapping("/api/auth/email-change/exchange")
    public void exchange(
            @Valid @RequestBody OneTimeLinkExchangeRequest dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        String tokenHash = emailChangeService.exchangeToken(dto.getToken());
        IssuedGrant grant = oneTimeLinkFlowService.issue(request, Purpose.EMAIL_CHANGE, tokenHash);
        oneTimeLinkFlowCookie.set(response, Purpose.EMAIL_CHANGE, grant.value(), grant.lifetime());
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/auth/verify-email");
    }

    @GetMapping("/api/auth/email-change/validate")
    public Map<String, Boolean> validate(
            @CookieValue(name = OneTimeLinkFlowCookie.EMAIL_CHANGE, required = false) String grant,
            HttpServletRequest request) {
        String tokenHash = oneTimeLinkFlowService.require(request, Purpose.EMAIL_CHANGE, grant);
        return Map.of("valid", emailChangeService.validateExchangedTokenHash(tokenHash));
    }

    @PostMapping("/api/auth/email-change/confirm")
    public Map<String, String> confirm(
            @CookieValue(name = OneTimeLinkFlowCookie.EMAIL_CHANGE, required = false) String grant,
            HttpServletRequest request,
            HttpServletResponse response) {
        String tokenHash = oneTimeLinkFlowService.consume(request, Purpose.EMAIL_CHANGE, grant);
        try {
            emailChangeService.confirmChangeByHash(tokenHash);
            return Map.of("message", "Your email address has been updated");
        } finally {
            oneTimeLinkFlowCookie.clear(response, Purpose.EMAIL_CHANGE);
        }
    }
}
