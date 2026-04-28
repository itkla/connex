package ooo.klae.connex.backend.services;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.UserMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

/**
 * Generates registration/authentication options and verifies responses from the client.
 * Called by {@code AuthController}. Reads/writes {@code User} credentials via {@code UserMapper}.
 */

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /**
     * Registers a new user with the provided registration data.
     * @param request
     * @return
     */
    public User register(RegisterDto request) {
        if (userMapper.getUserByUsername(request.getUsername()) != null) {
            throw new IllegalArgumentException("Username already taken: " + request.getUsername());
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userMapper.insert(user);
        return user;
    }

    /**
     * Logs in a user with the provided login data.
     * @param request
     * @param httpRequest
     * @param httpResponse
     * @return
     */
    public User login(LoginDto request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        return (User) authentication.getPrincipal();
    }

    /**
     * Retrieves the currently authenticated user based on the security context. Throws {@code ResourceNotFoundException} if no user is currently authenticated.
     * @return
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof User)) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        return (User) authentication.getPrincipal();
    }
}
