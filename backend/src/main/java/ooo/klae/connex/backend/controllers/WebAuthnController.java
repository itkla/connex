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
import ooo.klae.connex.backend.dto.RenamePasskeyRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnJsonMapper;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import tools.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;

/**
 * WebAuthn / passkey ceremony endpoints under {@code /api/auth/webauthn}. Registration and
 * credential management require an authenticated session (passkeys are additive to password login);
 * the authentication ceremony is pre-login. Challenges are held in the {@code HttpSession} and
 * cleared after every verify. A successful assertion finishes through
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

    /**
     * Issues passkey registration options for the authenticated user and stashes them in the session.
     */
    @PostMapping(value = "/register/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> registerOptions(HttpServletRequest req, HttpServletResponse res) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        PublicKeyCredentialCreationOptions options = webAuthnService.createRegistrationOptions(auth);
        creationOptions.save(req, res, options);
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
        try {
            PublicKeyCredential<AuthenticatorAttestationResponse> credential = json.read(body, ATTESTATION_TYPE);
            CredentialRecord record = webAuthnService.finishRegistration(options, credential, label);
            return Map.of("credentialId", record.getCredentialId().toBase64UrlString());
        } catch (RequestBodyTooLargeException ex) {
            throw ex;
        } catch (BadRequestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BadRequestException("Passkey registration failed");
        } finally {
            creationOptions.save(req, res, null);
        }
    }

    /**
     * Issues discoverable-credential login options (usernameless) and stashes them in the session.
     */
    @PostMapping(value = "/authenticate/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> authenticateOptions(HttpServletRequest req, HttpServletResponse res) {
        String ip = clientIpResolver.resolve(req);
        if (loginRateLimiter.isBlocked(ip, null, System.currentTimeMillis())) {
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
        String ip = clientIpResolver.resolve(req);
        long now = System.currentTimeMillis();
        if (loginRateLimiter.isBlocked(ip, null, now)) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }
        PublicKeyCredentialRequestOptions options = requestOptions.load(req);
        if (options == null) {
            throw new BadCredentialsException("No passkey login in progress");
        }
        User user;
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion = json.read(body, ASSERTION_TYPE);
            user = webAuthnService.finishLogin(options, assertion);
        } catch (RequestBodyTooLargeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            loginRateLimiter.recordFailure(ip, null, now);
            throw new BadCredentialsException("Passkey authentication failed");
        } finally {
            requestOptions.save(req, res, null);
        }
        if (ssoConnectionService.isSsoEnforcedForUser(user.getId())) {
            throw new ForbiddenException("This account must sign in with SSO");
        }
        authService.establishAuthenticatedSession(user, req, res);
        return Map.of("message", "You are now logged in");
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
        webAuthnService.rename(authService.getCurrentUser().getId(), credentialId, request.getLabel());
        return Map.of("message", "Passkey renamed");
    }

    /**
     * Removes a passkey the caller owns.
     */
    @DeleteMapping("/credentials/{credentialId}")
    public Map<String, String> deleteCredential(@PathVariable("credentialId") String credentialId) {
        webAuthnService.delete(authService.getCurrentUser().getId(), credentialId);
        return Map.of("message", "Passkey removed");
    }
}
