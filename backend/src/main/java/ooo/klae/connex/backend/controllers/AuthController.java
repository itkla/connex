package ooo.klae.connex.backend.controllers;

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
     * @param request
     * @return
     */
    @PostMapping("/register")
    public User register(@Valid @RequestBody RegisterDto request) {
        return authService.register(request);
    }

    /**
     * POST endpoint for user login (assertion).
     * Authenticates the user and establishes a session. Profile data is
     * available via {@code GET /api/auth/me} once the session cookie is set.
     */
    @PostMapping("/login")
    public Map<String, String> login(@Valid @RequestBody LoginDto request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.login(request, httpRequest, httpResponse);
        return Map.of("message", "Login successful");
    }

    /**
     * GET endpoint to retrieve the currently authenticated user's profile.
     * @return
     */
    @GetMapping("/me")
    public User me() {
        return authService.getCurrentUser();
    }
}
