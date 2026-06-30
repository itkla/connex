package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

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
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @Value("${connex.signup.mode:open}")
    private String signupMode;

    /**
     * Public self-service registration entry point: enforces the instance signup mode, then
     * delegates to {@link #register}. A non-{@code open} mode (reserved for locked-down on-prem)
     * refuses anonymous self-service signup, while the permission-gated admin create path
     * ({@code UserController.createUser}) calls {@link #register} directly and is unaffected.
     */
    @Transactional
    public User registerSelfService(RegisterDto request) {
        if (signupMode == null || !"open".equalsIgnoreCase(signupMode.trim())) {
            throw new ForbiddenException("Self-service registration is disabled on this instance");
        }
        return register(request);
    }

    /**
     * Registers a new user with the provided registration data.
     * @param request
     * @return
     */
    @Transactional
    public User register(RegisterDto request) {
        try {
            if (userMapper.getUserByUsername(request.getUsername()) != null
                    || userMapper.getUserByEmail(request.getEmail()) != null) {
                throw new DuplicateResourceException("Registration could not be completed");
            }

            User user = new User();
            user.setUsername(request.getUsername());
            user.setDisplayName(request.getDisplayName());
            user.setEmail(request.getEmail());
            user.setTimezone(TimezoneSupport.validate(request.getTimezone(), "UTC"));
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            userMapper.insert(user);
            // New users get their own owned workspace unless the instance restricts creation
            // (invite-only mode), in which case they onboard by accepting an invite.
            if (workspaceService.isSelfServiceCreationAllowed()) {
                workspaceService.createWorkspace(user.getDisplayName() + "'s Workspace", user.getId());
            }
            auditService.record("auth.register", "user", user.getId(), user.getDisplayName(), "User registered", null);
            return user;
        } catch (Exception e) {
            auditService.recordFailure("auth.register", "user", null, request.getUsername(),
                    "Failed to register user " + request.getUsername(), e.getMessage());
            throw e;
        }
    }

    /**
     * Provisions the initial owner account and its workspace during instance bootstrap
     * (see {@code BootstrapRunner}), bypassing the self-service-creation flag because the
     * bootstrap actor is the trusted operator rather than a self-service user. Not exposed over HTTP.
     */
    @Transactional
    public User provisionBootstrapOwner(RegisterDto request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setTimezone(TimezoneSupport.validate(request.getTimezone(), "UTC"));
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userMapper.insert(user);
        workspaceService.createWorkspaceForBootstrap(user.getDisplayName() + "'s Workspace", user.getId());
        auditService.record("auth.bootstrap", "user", user.getId(), user.getDisplayName(),
                "Bootstrap owner provisioned", null);
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
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (AuthenticationException e) {
            auditService.recordFailure("auth.login", "user", null, request.getUsername(),
                    "Failed login attempt for " + request.getUsername(), e.getMessage());
            throw e;
        }
        User user = (User) authentication.getPrincipal();
        userMapper.updateLastLoginAt(user.getId());
        User refreshedUser = userMapper.getUserById(user.getId());

        // Rotate any pre-existing session id on login to defend against session fixation.
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
            refreshedUser,
            authentication.getCredentials(),
            authentication.getAuthorities()
        ));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // Pin the active workspace for the new session so SSR and the first requests are scoped.
        Integer activeWorkspaceId = workspaceService.defaultWorkspaceIdFor(refreshedUser.getId());
        if (activeWorkspaceId != null) {
            workspaceService.rememberActive(refreshedUser.getId(), activeWorkspaceId);
            WorkspaceCookie.set(httpResponse, activeWorkspaceId);
        }

        auditService.record("auth.login", "user", refreshedUser.getId(), refreshedUser.getDisplayName(),
                refreshedUser.getDisplayName() + " logged in", null);
        return refreshedUser;
    }

    /**
     * Retrieves the currently authenticated user based on the security context. Throws {@code ResourceNotFoundException} if no user is currently authenticated.
     * @return
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User principal)) {
            throw new ResourceNotFoundException("Not authenticated");
        }

        // handles cases where the user updates their info but is not returned
        // reduntant if the user is not updated; just returns the same value
        User fresh = userMapper.getUserById(principal.getId());
        if (fresh == null) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        return fresh;
    }
}
