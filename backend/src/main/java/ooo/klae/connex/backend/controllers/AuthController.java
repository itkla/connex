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
    public User register(@RequestBody RegisterDto request) {
        return authService.register(request);
    }

    /**
     * POST endpoint for user login (assertion).
     * @param request
     * @param httpRequest
     * @param httpResponse
     * @return
     */
    @PostMapping("/login")
    public User login(@RequestBody LoginDto request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authService.login(request, httpRequest, httpResponse);
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
