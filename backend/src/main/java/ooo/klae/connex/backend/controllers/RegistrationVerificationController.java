package ooo.klae.connex.backend.controllers;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.RegistrationVerificationConfirmDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.RegistrationVerificationService;
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

    @PostMapping("/api/users/me/verify-email/resend")
    public Map<String, String> resend(HttpServletRequest httpRequest) {
        registrationVerificationService.issue(authService.getCurrentUser(), clientIpResolver.resolve(httpRequest));
        return Map.of("message", "If your email needs verification, a new link has been sent");
    }

    @GetMapping("/api/auth/verify-email/validate")
    public Map<String, Boolean> validate(@RequestParam("token") String token) {
        return Map.of("valid", registrationVerificationService.validateToken(token));
    }

    @PostMapping("/api/auth/verify-email/confirm")
    public Map<String, String> confirm(@Valid @RequestBody RegistrationVerificationConfirmDto dto) {
        registrationVerificationService.confirm(dto.getToken());
        return Map.of("message", "Your email address has been verified");
    }
}
