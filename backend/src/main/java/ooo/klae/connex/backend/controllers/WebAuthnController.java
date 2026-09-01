package ooo.klae.connex.backend.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyDto;
import ooo.klae.connex.backend.dto.PasskeyRegistrationOptionsRequest;
import ooo.klae.connex.backend.dto.PasskeyRegistrationRequirementsDto;
import ooo.klae.connex.backend.dto.RenamePasskeyRequest;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.dto.OneTimeLinkExchangeRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.LastPasskeyRemovalForbiddenException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.SsoEnforcedException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.MfaRecoveryService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import ooo.klae.connex.backend.webauthn.WebAuthnJsonMapper;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import tools.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;

/**
 * WebAuthn / passkey ceremony endpoints under {@code /api/auth/webauthn}. Registration and
 * credential management require an authenticated session; first enrollment is bound to the
 * account's existing password or a fresh passwordless federated login. The authentication ceremony
 * is pre-login. Challenges are held in the {@code HttpSession} and cleared after every verify.
 * A successful assertion finishes through
 * {@code AuthService.establishAuthenticatedSession}, the same ceremony as password login.
 */
@RestController
@RequestMapping("/api/auth/webauthn")
@RequiredArgsConstructor
public class WebAuthnController {

    private static final TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>> ATTESTATION_TYPE =
        new TypeReference<>() {};
    private static final TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>> ASSERTION_TYPE =
        new TypeReference<>() {};
    private final WebAuthnService webAuthnService;
    private final AuthService authService;
    private final WebAuthnJsonMapper json;
    private final PublicKeyCredentialCreationOptionsRepository creationOptions;
    private final PublicKeyCredentialRequestOptionsRepository requestOptions;
    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final SsoConnectionService ssoConnectionService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final MfaRecoveryService mfaRecoveryService;
    private final PasskeyBootstrapConfirmationService bootstrapConfirmationService;

    /**
     * Reports whether the current account must confirm its password for first-passkey enrollment.
     */
    @GetMapping("/register/requirements")
    public PasskeyRegistrationRequirementsDto registrationRequirements(HttpServletRequest req) {
        User user = authService.getCurrentUser();
        boolean currentPasswordRequired = !webAuthnService.hasPasskey(user.getId())
                && authService.hasPasswordCredential(user.getId());
        boolean emailConfirmationRequired = bootstrapConfirmationService.isRequiredFor(user.getId());
        boolean emailConfirmationSatisfied = emailConfirmationRequired
                && bootstrapConfirmationService.isSatisfiedFor(user, req);
        return new PasskeyRegistrationRequirementsDto(
                currentPasswordRequired, emailConfirmationRequired, emailConfirmationSatisfied);
    }

    /**
     * Mails a single-use enrollment confirmation to the account's own address. Required before a
     * privileged, password-backed account may enroll its first passkey, so a stolen password on
     * its own cannot install an attacker-controlled second factor.
     */
    @PostMapping("/register/confirmation")
    public Map<String, String> requestBootstrapConfirmation(HttpServletRequest req) {
        User user = authService.getCurrentUser();
        bootstrapConfirmationService.request(
                user, req, clientIpResolver.resolve(req));
        return Map.of("message", "Check your email for an enrollment confirmation link");
    }

    /**
     * Redeems the emailed confirmation and stamps the current session. The bearer arrives in the
     * link fragment, so it never reaches the server as part of a URL.
     */
    @PostMapping("/register/confirmation/exchange")
    public Map<String, String> exchangeBootstrapConfirmation(
            @Valid @RequestBody OneTimeLinkExchangeRequest request, HttpServletRequest req) {
        User user = authService.getCurrentUser();
        bootstrapConfirmationService.redeem(user, request.getToken(), req);
        return Map.of("message", "Enrollment confirmed; you can now add your passkey");
    }

    /**
     * Issues passkey registration options for the authenticated user and stashes them in the session.
     */
    @PostMapping(value = "/register/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> registerOptions(
            @Valid @RequestBody(required = false) PasskeyRegistrationOptionsRequest request,
            HttpServletRequest req, HttpServletResponse res) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = authService.getCurrentUser();
        boolean firstPasskeyBootstrap = authorizePasskeyRegistrationOptions(user, request, req);
        PublicKeyCredentialCreationOptions options = webAuthnService.createRegistrationOptions(auth);
        creationOptions.save(req, res, options);
        if (firstPasskeyBootstrap) {
            sessionSecurityService.markFirstPasskeyBootstrap(req, user.getId());
        }
        return ResponseEntity.ok(json.write(options));
    }

    /**
     * Verifies an attestation response against the pending registration options and stores the passkey.
     */
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> registerVerify(@RequestParam("label") String label,
            @RequestBody String body, HttpServletRequest req, HttpServletResponse res) {
        if (label == null || label.isBlank() || label.length() > 255) {
            throw new BadRequestException("A passkey label between 1 and 255 characters is required");
        }
        PublicKeyCredentialCreationOptions options = creationOptions.load(req);
        if (options == null) {
            throw new BadRequestException("No passkey registration in progress");
        }
        User user = authService.getCurrentUser();
        try {
            authorizePasskeyRegistrationVerify(user, req);
            PublicKeyCredential<AuthenticatorAttestationResponse> credential = json.read(body, ATTESTATION_TYPE);
            Integer expectedSessionEpoch = sessionSecurityService.sessionEpoch(req.getSession(false));
            boolean bootstrapConfirmationSatisfied =
                bootstrapConfirmationService.isSatisfiedFor(user, req);
            CredentialRecord record = webAuthnService.finishRegistration(
                    user.getId(), expectedSessionEpoch, bootstrapConfirmationSatisfied,
                    options, credential, label);
            sessionSecurityService.markStepUp(req, user.getId());
            return Map.of("credentialId", record.getCredentialId().toBase64UrlString());
        } catch (RequestBodyTooLargeException ex) {
            throw ex;
        } catch (BadRequestException ex) {
            throw ex;
        } catch (ForbiddenException ex) {
            throw ex;
        } catch (BadCredentialsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BadRequestException("Passkey registration failed");
        } finally {
            creationOptions.save(req, res, null);
            sessionSecurityService.clearFirstPasskeyBootstrap(req);
            sessionSecurityService.clearPasskeyBootstrapConfirmation(req);
        }
    }

    /**
     * Issues discoverable-credential login options (usernameless) and stashes them in the session.
     */
    @PostMapping(value = "/authenticate/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> authenticateOptions(HttpServletRequest req, HttpServletResponse res) {
        ResolvedClientIp clientIp = clientIpResolver.resolveWithProvenance(req);
        if (loginRateLimiter.isBlockedForClient(clientIp, null, System.currentTimeMillis())) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }
        PublicKeyCredentialRequestOptions options = webAuthnService.createLoginOptions();
        requestOptions.save(req, res, options);
        return ResponseEntity.ok(json.write(options));
    }

    /**
     * Verifies an assertion and, on success, establishes the session through the shared login ceremony.
     */
    @PostMapping(value = "/authenticate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> authenticateVerify(@RequestBody String body,
            HttpServletRequest req, HttpServletResponse res) {
        ResolvedClientIp clientIp = clientIpResolver.resolveWithProvenance(req);
        String ip = clientIp.address();
        long now = System.currentTimeMillis();
        if (loginRateLimiter.isBlockedForClient(clientIp, null, now)) {
            auditService.recordFailure("auth.login.passkey_throttled", "user", null, ip,
                    "Passkey login attempts throttled", null);
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }
        PublicKeyCredentialRequestOptions options = requestOptions.load(req);
        if (options == null) {
            auditService.recordFailure("auth.login.passkey", "user", null, ip,
                    "Passkey login missing challenge", null);
            throw new BadCredentialsException("No passkey login in progress");
        }
        User user;
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion = json.read(body, ASSERTION_TYPE);
            user = webAuthnService.finishLogin(options, assertion);
        } catch (RequestBodyTooLargeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            loginRateLimiter.recordFailureForClient(clientIp, null, now);
            auditService.recordFailure("auth.login.passkey", "user", null, ip,
                    "Failed passkey login attempt", ex.getMessage());
            throw new BadCredentialsException("Passkey authentication failed");
        } finally {
            requestOptions.save(req, res, null);
        }
        if (ssoConnectionService.isSsoEnforcedForUser(user.getId())) {
            auditService.recordFailure("auth.login.passkey_sso_enforced", "user", user.getId(),
                    user.getDisplayName(), "Passkey login refused; SSO enforced", null);
            throw new SsoEnforcedException();
        }
        auditService.recordStrictIndependentScoped(
                "auth.login.passkey",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                user.getDisplayName() + " logged in with passkey",
                null);
        User authenticatedUser = authService.establishAuthenticatedSession(user, req, res);
        sessionSecurityService.markStepUp(req, authenticatedUser.getId());
        return Map.of("message", "You are now logged in");
    }

    /**
     * Issues passkey assertion options for re-authenticating the current session.
     */
    @PostMapping(value = "/step-up/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> stepUpOptions(HttpServletRequest req, HttpServletResponse res) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        PublicKeyCredentialRequestOptions options = webAuthnService.createStepUpOptions(auth);
        requestOptions.save(req, res, options);
        return ResponseEntity.ok(json.write(options));
    }

    /**
     * Verifies a passkey assertion for the current user and refreshes the recent-auth stamp.
     */
    @PostMapping(value = "/step-up", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> stepUpVerify(@RequestBody String body, HttpServletRequest req,
            HttpServletResponse res) {
        User user = authService.getCurrentUser();
        PublicKeyCredentialRequestOptions options = requestOptions.load(req);
        if (options == null) {
            recordStepUpFailure(user, "missing_challenge");
            throw new BadCredentialsException("No passkey step-up in progress");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion = json.read(body, ASSERTION_TYPE);
            webAuthnService.finishStepUp(auth, options, assertion);
        } catch (RequestBodyTooLargeException ex) {
            recordStepUpFailure(user, "request_too_large");
            throw ex;
        } catch (RuntimeException ex) {
            recordStepUpFailure(user, "verification_failed");
            throw new BadCredentialsException("Passkey step-up failed");
        } finally {
            requestOptions.save(req, res, null);
        }
        auditService.recordStrictIndependentScoped(
                "auth.step_up.passkey",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "Passkey step-up completed",
                null);
        sessionSecurityService.markStepUp(req, user.getId());
        return Map.of("message", "Recent authentication refreshed");
    }

    /**
     * Lists the authenticated user's enrolled passkeys.
     */
    @GetMapping("/credentials")
    public List<PasskeyDto> listCredentials() {
        return webAuthnService.listForUser(authService.getCurrentUser().getId());
    }

    /**
     * Renames a passkey the caller owns.
     */
    @PatchMapping("/credentials/{credentialId}")
    public Map<String, String> renameCredential(@PathVariable("credentialId") String credentialId,
            @Valid @RequestBody RenamePasskeyRequest request) {
        User user = authService.getCurrentUser();
        int userId = user.getId();
        sessionSecurityService.requireRecentAuthentication(userId);
        webAuthnService.rename(userId, credentialId, request.getLabel());
        return Map.of("message", "Passkey renamed");
    }

    /**
     * Removes a passkey the caller owns.
     */
    @DeleteMapping("/credentials/{credentialId}")
    public Map<String, String> deleteCredential(@PathVariable("credentialId") String credentialId) {
        User user = authService.getCurrentUser();
        int userId = user.getId();
        sessionSecurityService.requireRecentAuthentication(userId);
        try {
            webAuthnService.delete(userId, credentialId);
        } catch (LastPasskeyRemovalForbiddenException exception) {
            auditService.recordFailureScoped(
                    "auth.passkey.delete_denied", "user", userId, null, null,
                    user.getDisplayName(), "Last passkey removal refused for privileged account",
                    "last_credential");
            throw exception;
        }
        return Map.of("message", "Passkey removed");
    }

    /**
     * Removes inaccessible passkeys after same-account proof and a short-lived operator recovery
     * authorization. The caller remains confined until a replacement passkey is enrolled.
     */
    @PostMapping("/recover")
    public Map<String, String> recoverCredentials(
            @Valid @RequestBody PasskeyRecoveryRequest request,
            HttpServletRequest httpRequest) {
        User user = authService.getCurrentUser();
        int epoch;
        try {
            epoch = mfaRecoveryService.recover(request, httpRequest);
        } catch (RuntimeException exception) {
            auditService.recordStrictFailureIndependentScoped(
                    "auth.mfa.recovery.denied",
                    "user",
                    user.getId(),
                    null,
                    null,
                    user.getDisplayName(),
                    "Passkey recovery denied",
                    "proof_rejected");
            throw exception;
        }
        sessionSecurityService.completeRecoveryStamp(httpRequest, epoch);
        bootstrapConfirmationService.invalidateForUser(user.getId());
        return Map.of("message", "Passkeys removed; enroll a replacement passkey to continue");
    }

    private void recordStepUpFailure(User user, String reason) {
        auditService.recordStrictFailureIndependentScoped(
                "auth.step_up.passkey",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "Failed passkey step-up attempt",
                reason);
    }

    private boolean authorizePasskeyRegistrationOptions(User user, PasskeyRegistrationOptionsRequest request,
            HttpServletRequest httpRequest) {
        sessionSecurityService.clearFirstPasskeyBootstrap(httpRequest);
        if (webAuthnService.hasPasskey(user.getId())) {
            sessionSecurityService.requireRecentAuthentication(user.getId());
            return false;
        }
        String password = request == null ? null : request.getCurrentPassword();
        authService.requireFirstPasskeyBootstrapAuthentication(user.getId(), password, httpRequest);
        requireBootstrapConfirmation(user, httpRequest);
        return true;
    }

    private void authorizePasskeyRegistrationVerify(User user, HttpServletRequest httpRequest) {
        if (webAuthnService.hasPasskey(user.getId())) {
            sessionSecurityService.requireRecentAuthentication(user.getId());
            return;
        }
        if (!sessionSecurityService.hasFreshFirstPasskeyBootstrap(httpRequest, user.getId())) {
            throw new BadRequestException("Current password confirmation required");
        }
        requireBootstrapConfirmation(user, httpRequest);
    }

    /**
     * Refuses a first-passkey enrollment that lacks the out-of-band confirmation this account
     * currently needs. Evaluated at both ceremony phases, and again under the account lock in
     * {@code WebAuthnService.finishRegistration}, so a promotion mid-ceremony cannot slip through.
     */
    private void requireBootstrapConfirmation(User user, HttpServletRequest httpRequest) {
        if (!bootstrapConfirmationService.isRequiredFor(user.getId())) {
            return;
        }
        if (bootstrapConfirmationService.isSatisfiedFor(user, httpRequest)) {
            return;
        }
        auditService.recordStrictFailureIndependentScoped(
                "auth.passkey.bootstrap_confirmation.required",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "First-passkey enrollment refused pending emailed confirmation",
                "bootstrap_confirmation_required");
        throw new ForbiddenException(
                "Confirm the emailed enrollment link before adding the first passkey");
    }
}
