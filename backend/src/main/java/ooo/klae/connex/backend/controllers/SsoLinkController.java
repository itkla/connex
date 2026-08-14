package ooo.klae.connex.backend.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.SsoLinkConfirmRequest;
import ooo.klae.connex.backend.services.SsoLinkService;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Pre-login SSO account-linking confirmation. When a verified IdP email collides with
 * an existing password account, the linking screen posts the challenge token and the
 * account's password here to prove ownership; on success the identity is linked and a
 * session is established through the shared login ceremony. Under {@code /api/auth/**}
 * (permitAll and CSRF-exempt, like the other pre-login auth handshakes).
 */
@RestController
@RequestMapping("/api/auth/sso/link")
@RequiredArgsConstructor
public class SsoLinkController {

    private final SsoLinkService ssoLinkService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/confirm")
    public Map<String, String> confirm(@Valid @RequestBody SsoLinkConfirmRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ssoLinkService.confirm(request.getToken(), request.getPassword(),
                clientIpResolver.resolveWithProvenance(httpRequest), httpRequest, httpResponse);
        return Map.of("message", "You are now signed in");
    }
}
