package ooo.klae.connex.backend.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.SsoLinkConfirmRequest;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.services.SsoLinkService;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Pre-login SSO account-linking confirmation. When a verified IdP email collides with an existing
 * password account, the IdP success handler installs a purpose-bound HttpOnly flow and the linking
 * screen posts only the account password here to prove ownership. On success the identity is linked
 * and an authenticated session is established through the shared login ceremony. The password POST
 * remains CSRF-protected even though the endpoint is permit-all.
 */
@RestController
@RequestMapping("/api/auth/sso/link")
@RequiredArgsConstructor
public class SsoLinkController {

    private final SsoLinkService ssoLinkService;
    private final ClientIpResolver clientIpResolver;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    @GetMapping("/validate")
    public Map<String, Boolean> validate(
            @CookieValue(name = OneTimeLinkFlowCookie.SSO_LINK, required = false) String grant,
            HttpServletRequest request) {
        String tokenHash = oneTimeLinkFlowService.require(request, Purpose.SSO_LINK, grant);
        return Map.of("valid", ssoLinkService.validateChallengeHash(tokenHash));
    }

    @PostMapping("/confirm")
    public Map<String, String> confirm(@Valid @RequestBody SsoLinkConfirmRequest request,
            @CookieValue(name = OneTimeLinkFlowCookie.SSO_LINK, required = false) String grant,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        oneTimeLinkFlowService.consume(
            httpRequest,
            Purpose.SSO_LINK,
            grant,
            tokenHash -> ssoLinkService.confirmByHash(
                tokenHash,
                request.getPassword(),
                clientIpResolver.resolve(httpRequest),
                httpRequest,
                httpResponse));
        oneTimeLinkFlowCookie.clear(httpResponse, Purpose.SSO_LINK);
        return Map.of("message", "You are now signed in");
    }
}
