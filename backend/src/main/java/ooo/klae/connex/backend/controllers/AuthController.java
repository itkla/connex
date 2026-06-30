package ooo.klae.connex.backend.controllers;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.services.AuthService;

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
}
