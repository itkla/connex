package ooo.klae.connex.backend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
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
import ooo.klae.connex.backend.exceptions.SsoEnforcedException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.password.PasswordCredentialService;
import ooo.klae.connex.backend.password.PasswordScreeningFlow;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;

/**
 * Generates registration/authentication options and verifies responses from the client.
 * Called by {@code AuthController}. Reads/writes {@code User} credentials via {@code UserMapper}.
 */

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordCredentialService passwordCredentialService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final RegistrationVerificationService registrationVerificationService;
    private final SsoConnectionService ssoConnectionService;
    private final SessionSecurityService sessionSecurityService;
    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final WorkspaceCookie workspaceCookie;
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
    public User registerSelfService(RegisterDto request, String requestIp) {
        if (signupMode == null || !"open".equalsIgnoreCase(signupMode.trim())) {
            throw new ForbiddenException("Self-service registration is disabled on this instance");
        }
        // When verification is on, self-serve accounts start unverified and must prove control of
        // their address; when it is off they are verified by fiat, so turning the feature on later
        // never retroactively gates accounts created while it was off.
        boolean verificationEnabled = registrationVerificationService.isEnabled();
        User user = register(request, !verificationEnabled, PasswordScreeningFlow.SELF_REGISTRATION);
        if (verificationEnabled) {
            registrationVerificationService.issue(user, requestIp);
        }
        return user;
    }

    /**
     * Registers a new user with the provided registration data.
     * @param request the registration details
     * @param emailVerified whether the account starts email-verified — true for trusted callers
     *     (admin create), false for self-serve accounts that must prove control of their address
     * @return the created user
     */
    @Transactional
    public User register(RegisterDto request, boolean emailVerified) {
        return register(request, emailVerified, PasswordScreeningFlow.ADMIN_ACCOUNT_CREATION);
    }

    private User register(RegisterDto request, boolean emailVerified, PasswordScreeningFlow flow) {
        try {
            if (userMapper.getUserByUsername(request.getUsername()) != null
                    || userMapper.getUserByEmail(request.getEmail()) != null) {
                throw new DuplicateResourceException("Registration could not be completed");
            }

            User user = new User();
            user.setUsername(request.getUsername());
            user.setDisplayName(request.getDisplayName());
            user.setEmail(request.getEmail());
            user.setEmailVerified(emailVerified);
            user.setTimezone(TimezoneSupport.validateIana(request.getTimezone(), "UTC"));
            user.setPasswordHash(passwordCredentialService.encode(request.getPassword(), flow, null));
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
        user.setEmailVerified(true);
        user.setTimezone(TimezoneSupport.validateIana(request.getTimezone(), "UTC"));
        user.setPasswordHash(passwordCredentialService.encode(
                request.getPassword(), PasswordScreeningFlow.BOOTSTRAP_OWNER, null));
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
        ResolvedClientIp clientIp = clientIpResolver.resolveWithProvenance(httpRequest);
        long now = System.currentTimeMillis();
        if (loginRateLimiter.isBlockedForClient(clientIp, request.getUsername(), now)) {
            auditService.recordFailure("auth.login_throttled", "user", null, request.getUsername(),
                    "Login attempts throttled for " + request.getUsername(), null);
            throw new TooManyRequestsException("Too many login attempts. Please try again later.");
        }
        User candidate = request.getUsername() != null && request.getUsername().contains("@")
                ? userMapper.getUserByEmail(request.getUsername())
                : userMapper.getUserByUsername(request.getUsername());
        if (candidate != null && ssoConnectionService.isSsoEnforcedForUser(candidate.getId())) {
            loginRateLimiter.recordFailureForClient(clientIp, request.getUsername(), now);
            auditService.recordFailure("auth.login_sso_enforced", "user", candidate.getId(), request.getUsername(),
                    "Password login refused; SSO enforced for " + request.getUsername(), null);
            throw new SsoEnforcedException();
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (AuthenticationException e) {
            loginRateLimiter.recordFailureForClient(clientIp, request.getUsername(), now);
            auditService.recordFailure("auth.login", "user", null, request.getUsername(),
                    "Failed login attempt for " + request.getUsername(), e.getMessage());
            throw e;
        }
        loginRateLimiter.recordSuccess(request.getUsername());
        User user = (User) authentication.getPrincipal();
        User refreshedUser = establishAuthenticatedSession(user, httpRequest, httpResponse);
        auditService.record("auth.login", "user", refreshedUser.getId(), refreshedUser.getDisplayName(),
                refreshedUser.getDisplayName() + " logged in", null);
        return refreshedUser;
    }

    /**
     * Establishes an authenticated servlet session for an already-verified principal.
     * Rotates any existing session id (fixation defense), persists a fresh security context,
     * registers the session so a password reset can later enumerate and expire it, records the
     * login timestamp, and pins the account's default workspace. Every authentication method
     * (password login today, passkey/SSO login next) must route through this single ceremony so
     * they cannot diverge from the tenant-scoping and session-kill invariants it enforces.
     * @param user the verified principal
     * @param httpRequest the current request
     * @param httpResponse the current response
     * @return the principal reloaded after the login timestamp update
     */
    /**
     * The session epoch the presented credential was verified against.
     *
     * <p>Read from {@code user} — the principal the caller authenticated — and deliberately not from
     * the refreshed row below it. The refresh runs in its own transaction after the credential check,
     * so it can observe a revocation that committed in between; stamping that value would mark the
     * raced session as current and defeat the whole mechanism.
     *
     * <p>A null epoch means the principal came from a read that does not select the column, which is
     * a wiring mistake rather than a runtime condition. It fails loudly here instead of silently
     * stamping a session that could never be revoked.
     */
    private static int verifiedSessionEpoch(User user) {
        Integer epoch = user.getSessionEpoch();
        if (epoch == null) {
            throw new IllegalStateException(
                "Authenticated principal carries no session epoch; it was not loaded through the "
                    + "credential-bearing read");
        }
        return epoch;
    }

    /**
     * Re-stamps the calling session with the account's current epoch after a ceremony that advanced
     * it deliberately kept that session alive.
     *
     * <p>Must be called after the advancing transaction has committed. Session attributes are not
     * transactional and are written when the request completes, so re-stamping inside the
     * transaction would leave a session stamped ahead of the account if it then rolled back — which
     * the epoch check refuses permanently.
     *
     * @param userId the account whose epoch was advanced
     * @param httpRequest the request whose session is being kept
     */
    public void restampCurrentSessionEpoch(int userId, HttpServletRequest httpRequest) {
        Integer epoch = userMapper.currentSessionEpoch(userId);
        if (epoch != null) {
            sessionSecurityService.stampSessionEpoch(httpRequest, epoch);
        }
    }

    public User establishAuthenticatedSession(User user, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        userMapper.updateLastLoginAt(user.getId());
        User refreshedUser = userMapper.getUserById(user.getId());

        HttpSession existingSession = httpRequest.getSession(false);
        if (existingSession != null) {
            Object boundUserId = existingSession.getAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR);
            if (boundUserId instanceof Number number && number.intValue() != refreshedUser.getId()) {
                oneTimeLinkFlowService.replaceSessionPreservingFlows(httpRequest);
                SecurityContextHolder.clearContext();
            } else {
                httpRequest.changeSessionId();
            }
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
            refreshedUser,
            null,
            refreshedUser.getAuthorities()
        ));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        sessionSecurityService.markAuthenticated(httpRequest, refreshedUser.getId());
        sessionSecurityService.stampSessionEpoch(httpRequest, verifiedSessionEpoch(user));

        Integer activeWorkspaceId = workspaceService.defaultWorkspaceIdFor(refreshedUser.getId());
        if (activeWorkspaceId != null) {
            workspaceService.rememberActive(refreshedUser.getId(), activeWorkspaceId);
            workspaceCookie.set(httpResponse, activeWorkspaceId);
        } else {
            workspaceCookie.clear(httpResponse);
        }
        return refreshedUser;
    }

    /** Replaces upstream or existing principal state while retaining only active link flows. */
    public void prepareUnauthenticatedLinkFlow(
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        oneTimeLinkFlowService.replaceSessionPreservingFlows(httpRequest);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        sessionSecurityService.clearAuthenticationState(httpRequest);
        workspaceCookie.clear(httpResponse);
    }

    public void requireCurrentPassword(int userId, String password, String clientIp) {
        requireCurrentPassword(userId, password, new ResolvedClientIp(clientIp, false));
    }

    /**
     * Confirms the current password with provenance-aware per-client throttling.
     *
     * @param userId the account whose password is being confirmed
     * @param password the submitted current password
     * @param clientIp the resolved client address and trusted-proxy provenance
     */
    public void requireCurrentPassword(int userId, String password, ResolvedClientIp clientIp) {
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new BadCredentialsException("Incorrect password");
        }
        long now = System.currentTimeMillis();
        String username = user.getUsername();
        if (loginRateLimiter.isBlockedForClient(clientIp, username, now)) {
            throw new TooManyRequestsException("Too many login attempts. Please try again later.");
        }
        if (password == null || user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            loginRateLimiter.recordFailureForClient(clientIp, username, now);
            throw new BadCredentialsException("Incorrect password");
        }
        loginRateLimiter.recordSuccess(username);
    }

    /**
     * Authorizes first-passkey enrollment using the account's existing authentication method.
     * Password-backed accounts must confirm their password; passwordless accounts must have a
     * freshly established, same-user federated session.
     * @param userId the authenticated account
     * @param password the submitted current password, when the account has one
     * @param httpRequest the current request carrying the authenticated session
     */
    public void requireFirstPasskeyBootstrapAuthentication(int userId, String password,
            HttpServletRequest httpRequest) {
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new BadCredentialsException("Incorrect password");
        }
        if (user.getPassword() == null) {
            sessionSecurityService.requireFreshAuthenticatedSession(httpRequest, userId);
            return;
        }
        requireCurrentPassword(userId, password, clientIpResolver.resolveWithProvenance(httpRequest));
    }

    /**
     * Reports whether an account has a password credential that must be confirmed for bootstrap.
     * @param userId the authenticated account
     * @return true when the account has a password hash
     */
    public boolean hasPasswordCredential(int userId) {
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        return user.getPassword() != null;
    }

    /**
     * Retrieves the authenticated session principal without refreshing it from persistence.
     *
     * @return the authenticated user principal
     */
    public User getCurrentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User principal)) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        return principal;
    }

    /**
     * Retrieves the currently authenticated user based on the security context. Throws {@code ResourceNotFoundException} if no user is currently authenticated.
     * @return
     */
    public User getCurrentUser() {
        User principal = getCurrentPrincipal();

        // handles cases where the user updates their info but is not returned
        // reduntant if the user is not updated; just returns the same value
        User fresh = userMapper.getUserById(principal.getId());
        if (fresh == null) {
            throw new ResourceNotFoundException("Not authenticated");
        }
        return fresh;
    }
}
