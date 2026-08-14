package ooo.klae.connex.backend.controllers;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.dto.ForgotPasswordRequest;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.dto.ResetPasswordRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.PasswordResetService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for authentication endpoints.
 * Delegates to {@code AuthService}. No session tokens are issued here.
 * Spring Security manages the security context after a successful assertion.
 */

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final ClientIpResolver clientIpResolver;
    private final SessionSecurityService sessionSecurityService;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final OneTimeLinkFlowCookie oneTimeLinkFlowCookie;

    /**
     * POST endpoint for user registration.
     * Does not establish a session — the client must call {@code /login} after.
     */
    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody RegisterDto request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.registerSelfService(request, clientIpResolver.resolve(httpRequest));
        authService.login(new LoginDto(request.getUsername(), request.getPassword()), httpRequest, httpResponse);
        return Map.of("message", "You are now registered and logged in");
    }

    /**
     * POST endpoint for user login (assertion).
     * Authenticates the user and establishes a session. Profile data is
     * available via {@code GET /api/auth/me} once the session cookie is set.
     */
    @PostMapping("/login")
    public Map<String, String> login(@Valid @RequestBody LoginDto request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.login(request, httpRequest, httpResponse);
        return Map.of("message", "You are now logged in");
    }

    /**
     * GET endpoint to retrieve the currently authenticated user's profile.
     * @return
     */
    @GetMapping("/me")
    public User me() {
        return authService.getCurrentUser();
    }

    /**
     * Exposes the CSRF token so the SPA can echo it in the configured header on
     * state-changing requests, together with an opaque authenticated-session generation.
     */
    @GetMapping("/csrf")
    public CsrfBootstrapDto csrf(CsrfToken token, HttpServletRequest request) {
        return CsrfBootstrapDto.of(token, sessionSecurityService.requestIdentity(request));
    }

    /**
     * Requests a password reset link for the given email. Always responds
     * identically whether or not an account exists, to avoid account enumeration.
     */
    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request.getEmail(), clientIpResolver.resolve(httpRequest));
        return Map.of("message", "If an account exists for that email, a reset link has been sent");
    }

    /** Exchanges a raw reset bearer for a short-lived, purpose-bound browser session. */
    @PostMapping("/reset-password/exchange")
    public void exchangeResetToken(
            @Valid @RequestBody OneTimeLinkExchangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String tokenHash = passwordResetService.exchangeToken(request.getToken());
        IssuedGrant grant = oneTimeLinkFlowService.issue(
            httpRequest, Purpose.PASSWORD_RESET, tokenHash);
        oneTimeLinkFlowCookie.set(response, Purpose.PASSWORD_RESET, grant.value(), grant.lifetime());
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", "/auth/reset-password");
    }

    /** Reports whether the token-free reset flow is still redeemable. */
    @GetMapping("/reset-password/validate")
    public Map<String, Boolean> validateResetToken(
            @CookieValue(name = OneTimeLinkFlowCookie.PASSWORD_RESET, required = false) String grant,
            HttpServletRequest request) {
        String tokenHash = oneTimeLinkFlowService.require(request, Purpose.PASSWORD_RESET, grant);
        return Map.of("valid", passwordResetService.validateExchangedTokenHash(tokenHash));
    }

    /**
     * Consumes a valid reset token and sets the account's new password.
     * The password policy is enforced by {@code ResetPasswordRequest}.
     */
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            @CookieValue(name = OneTimeLinkFlowCookie.PASSWORD_RESET, required = false) String grant,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String tokenHash = oneTimeLinkFlowService.consume(
            httpRequest, Purpose.PASSWORD_RESET, grant);
        try {
            passwordResetService.resetPasswordByHash(tokenHash, request.getNewPassword());
            return Map.of("message", "Your password has been reset");
        } finally {
            oneTimeLinkFlowCookie.clear(response, Purpose.PASSWORD_RESET);
        }
    }
}
