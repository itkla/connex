package ooo.klae.connex.backend.controllers;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ForgotPasswordRequest;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.dto.ResetPasswordRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.PasswordResetService;

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

    /**
     * POST endpoint for user registration.
     * Does not establish a session — the client must call {@code /login} after.
     */
    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody RegisterDto request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.registerSelfService(request);
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
     * state-changing requests. Returns null when CSRF protection is disabled.
     */
    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    /**
     * Requests a password reset link for the given email. Always responds
     * identically whether or not an account exists, to avoid account enumeration.
     */
    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request.getEmail(), clientIp(httpRequest));
        return Map.of("message", "If an account exists for that email, a reset link has been sent");
    }

    /**
     * Reports whether a reset token is still valid, so the reset page can show a
     * rejection state before prompting for a new password.
     */
    @GetMapping("/reset-password/validate")
    public Map<String, Boolean> validateResetToken(@RequestParam("token") String token) {
        return Map.of("valid", passwordResetService.validateToken(token));
    }

    /**
     * Consumes a valid reset token and sets the account's new password.
     * The password policy is enforced by {@code ResetPasswordRequest}.
     */
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
        return Map.of("message", "Your password has been reset");
    }

    /**
     * Resolves the originating client IP, honouring a forwarding proxy header.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
