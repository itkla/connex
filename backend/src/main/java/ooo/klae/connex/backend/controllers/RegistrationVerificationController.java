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
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.RegistrationVerificationService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Registration email-verification endpoints. Resend is authenticated and CSRF-protected
 * (it re-sends a link to the caller's own account); validation and confirmation live under
 * {@code /api/auth/**} so a recipient can redeem the emailed link without a prior session —
 * the token from the account's address is the bearer credential.
 */
@RestController
@RequiredArgsConstructor
public class RegistrationVerificationController {

    private final RegistrationVerificationService registrationVerificationService;
    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    @PostMapping("/api/users/me/verify-email/resend")
    public Map<String, String> resend(HttpServletRequest httpRequest) {
        registrationVerificationService.issue(authService.getCurrentUser(), clientIpResolver.resolve(httpRequest));
        return Map.of("message", "If your email needs verification, a new link has been sent");
    }

    @PostMapping("/api/auth/verify-email/exchange")
    public void exchange(
            @Valid @RequestBody OneTimeLinkExchangeRequest dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        String tokenHash = registrationVerificationService.exchangeToken(dto.getToken());
        IssuedGrant grant = oneTimeLinkFlowService.issue(
            request, Purpose.REGISTRATION_VERIFICATION, tokenHash);
        oneTimeLinkFlowCookie.set(
            response, Purpose.REGISTRATION_VERIFICATION, grant.value(), grant.lifetime());
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/auth/confirm-email");
    }

    @GetMapping("/api/auth/verify-email/validate")
    public Map<String, Boolean> validate(
            @CookieValue(
                name = OneTimeLinkFlowCookie.REGISTRATION_VERIFICATION,
                required = false) String grant,
            HttpServletRequest request) {
        String tokenHash = oneTimeLinkFlowService.require(
            request, Purpose.REGISTRATION_VERIFICATION, grant);
        return Map.of("valid", registrationVerificationService.validateExchangedTokenHash(tokenHash));
    }

    @PostMapping("/api/auth/verify-email/confirm")
    public Map<String, String> confirm(
            @CookieValue(
                name = OneTimeLinkFlowCookie.REGISTRATION_VERIFICATION,
                required = false) String grant,
            HttpServletRequest request,
            HttpServletResponse response) {
        String tokenHash = oneTimeLinkFlowService.consume(
            request, Purpose.REGISTRATION_VERIFICATION, grant);
        try {
            registrationVerificationService.confirmByHash(tokenHash);
            return Map.of("message", "Your email address has been verified");
        } finally {
            oneTimeLinkFlowCookie.clear(response, Purpose.REGISTRATION_VERIFICATION);
        }
    }
}
