package ooo.klae.connex.backend.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;

/**
 * Pre-login endpoint the login screen reads to decide which consumer social-login buttons to
 * offer. Unauthenticated (under the permitAll {@code /api/auth/**}); reports only which providers
 * are enabled and configured, never any client credentials.
 */
@RestController
@RequestMapping("/api/auth/social-login")
@RequiredArgsConstructor
public class SocialLoginController {

    private final SocialLoginClientRegistrations socialLoginClientRegistrations;

    @GetMapping("/providers")
    public Map<String, Boolean> providers() {
        return Map.of(
                "google", socialLoginClientRegistrations.isGoogleEnabled(),
                "microsoft", socialLoginClientRegistrations.isMicrosoftEnabled());
    }
}
