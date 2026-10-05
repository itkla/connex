package ooo.klae.connex.backend.webauthn;

import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.LastPasskeyRemovalForbiddenException;
import ooo.klae.connex.backend.exceptions.PasskeyEnrollmentRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.session.SessionEpochRestampGrant;
import ooo.klae.connex.backend.session.StepUpProof;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the WebAuthn ceremonies over Spring Security's {@code WebAuthnRelyingPartyOperations}
 * (which performs attestation/assertion verification) and owns the durable handle&harr;{@code app_user}
 * link plus credential-ownership enforcement. Passkeys are additive: enrollment requires an
 * authenticated session and proof through the account's existing authentication method;
 * authentication resolves the account from the credential's user handle and hands a verified
 * {@link User} back to the controller, which finishes the shared login ceremony.
 */
@Service
@RequiredArgsConstructor
public class WebAuthnService {
    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);

    private final WebAuthnRelyingPartyOperations rpOperations;
    private final UserCredentialRepository userCredentials;
    private final WebauthnUserEntityMapper userEntityMapper;
    private final WebauthnCredentialMapper credentialMapper;
    private final PrivilegedCredentialAttestationMapper attestationMapper;
    private final SessionSecurityService sessionSecurityService;
    private final UserMapper userMapper;
    private final PrivilegedAccountService privilegedAccountService;
    private final PasskeyBootstrapConfirmationPolicy bootstrapConfirmationPolicy;
    private final AuditService auditService;

    /**
     * Issues registration options for the authenticated user, first ensuring a stable user handle
     * exists so {@code excludeCredentials} is populated and enrolled credentials resolve back to the
     * account.
     * @param auth the authenticated principal (a Connex {@link User})
     * @return creation options to hand to the browser
     */
    @Transactional
    public PublicKeyCredentialCreationOptions createRegistrationOptions(Authentication auth) {
        ensureUserEntity((User) auth.getPrincipal());
        return rpOperations.createPublicKeyCredentialCreationOptions(
            new ImmutablePublicKeyCredentialCreationOptionsRequest(auth));
    }

    /**
     * Verifies an attestation response and persists the new credential.
     *
     * <p>Takes the account root before writing so enrollment serializes against
     * {@link #recover(int)} and {@link #delete(int, String)} rather than interleaving with their
     * credential reads and deletes. After taking that lock, the method compares the request
     * session's expected epoch with the current account epoch before verifying or saving anything.
     * This under-lock fence refuses a registration request admitted before recovery committed but
     * waiting behind recovery's account lock. Saving the replacement credential clears the durable
     * recovery handoff in the same transaction, so neither can commit without the other.
     *
     * @param expectedUserId the authenticated account completing the ceremony
     * <p>The same lock carries the first-passkey confirmation fence (#1506). Privilege is read
     * here, not at options time, because an account can be promoted between the two phases: a
     * ceremony started while unprivileged would otherwise finish as a privileged enrollment and
     * stamp the session as stepped-up without the out-of-band proof ever being required.
     * Privilege can also arrive through a custom role the account already holds, which no
     * account-row lock serialises, so the assigned role rows are locked here too and the
     * transaction reads committed state rather than a snapshot taken before the promotion. A
     * refusal here is audited like the controller's, but only once the transaction completes: an
     * immediate independent append would wait on this transaction's own account lock (#1995).
     *
     * @param expectedSessionEpoch the epoch stamped into the request's authenticated session
     * @param bootstrapConfirmationSatisfied whether the session carries a redeemed out-of-band
     *     first-passkey confirmation
     * @param evidence what the enrolling session proves about the new passkey's provenance
     * @param options the options issued in {@link #createRegistrationOptions}
     * @param credential the client's attestation response
     * @param label the user-supplied nickname
     * @return the stored credential and its row id
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RegisteredPasskey finishRegistration(
            int expectedUserId,
            Integer expectedSessionEpoch,
            boolean bootstrapConfirmationSatisfied,
            EnrollmentEvidence evidence,
            PublicKeyCredentialCreationOptions options,
            PublicKeyCredential<AuthenticatorAttestationResponse> credential,
            String label) {
        if (userMapper.lockById(expectedUserId) == null) {
            throw new BadCredentialsException("Passkey registration is not bound to the current account");
        }
        Integer currentSessionEpoch = userMapper.currentSessionEpoch(expectedUserId);
        if (expectedSessionEpoch == null
                || currentSessionEpoch == null
                || !expectedSessionEpoch.equals(currentSessionEpoch)) {
            throw new ForbiddenException("Authenticated session is no longer current");
        }
        userMapper.lockAssignedCustomRoleIds(expectedUserId);
        SessionEpochRestampGrant restampGrant = userMapper.epochRestampGrant(expectedUserId);
        if (restampGrant != null && evidence.sessionPrimaryId() == null) {
            throw new ConflictException(
                    "The session changed while the passkey was being registered; try again");
        }
        if (!hasPasskey(expectedUserId)
                && !bootstrapConfirmationSatisfied
                && bootstrapConfirmationPolicy.requiresConfirmation(expectedUserId)) {
            deferBootstrapConfirmationRefusalAudit(expectedUserId);
            throw new ForbiddenException(
                    "Confirm the emailed enrollment link before adding the first passkey");
        }
        PublicKeyCredentialUserEntity optionUser = options.getUser();
        Integer optionUserId = optionUser == null
            ? null
            : userEntityMapper.findUserIdByHandle(optionUser.getId().toBase64UrlString());
        if (optionUserId == null || optionUserId != expectedUserId) {
            throw new BadCredentialsException("Passkey registration is not bound to the current account");
        }
        CredentialRecord record = rpOperations.registerCredential(
            new ImmutableRelyingPartyRegistrationRequest(options, new RelyingPartyPublicKey(credential, label)));
        userMapper.clearEpochRestampGrant(expectedUserId);
        WebauthnCredentialRow registered = credentialMapper.findByCredentialId(record.getCredentialId().getBytes());
        if (registered == null || registered.getId() == null) {
            throw new IllegalStateException("The registered passkey was not stored");
        }
        recordProvenance(expectedUserId, currentSessionEpoch, evidence, restampGrant, registered.getId());
        User user = userMapper.getUserById(expectedUserId);
        if (user == null) {
            throw new BadCredentialsException("Passkey registration is not bound to the current account");
        }
        auditService.recordStrict("auth.passkey.register", "user", expectedUserId, user.getDisplayName(),
                "Passkey registered", auditService.singleChange("label", null, label));
        return new RegisteredPasskey(record, registered.getId());
    }

    /**
     * Records where a new passkey's privileged coverage comes from (#1534), under the account
     * lock {@link #finishRegistration} holds. Founder coverage is written first, so the direct
     * source wins over an inherited row for the same organization. A passkey inherits coverage only
     * from the credential behind a step-up that is still fresh and still the account's. It gets
     * break-glass assurance only when this session is the one the operator recovery granted the
     * restamp to, at the epoch that recovery committed. {@link #finishRegistration} refuses, before
     * registering anything, an enrollment that cannot name its session while such a grant is
     * outstanding, so a rotated session id retries instead of spending the grant without the
     * assurance.
     */
    private void recordProvenance(
            int userId,
            int sessionEpoch,
            EnrollmentEvidence evidence,
            SessionEpochRestampGrant restampGrant,
            int credentialRowId) {
        attestationMapper.insertFounderCoverage(credentialRowId, userId);
        boolean breakGlass = restampGrant != null
            && restampGrant.epoch() == sessionEpoch
            && evidence.sessionPrimaryId() != null
            && evidence.sessionPrimaryId().equals(restampGrant.sessionPrimaryId());
        StepUpProof proof = evidence.stepUpProof();
        if (proof != null
                && sessionSecurityService.isFresh(proof)
                && credentialMapper.findOwnedRowId(proof.credentialRowId(), userId) != null) {
            attestationMapper.insertInheritedCoverage(credentialRowId, proof.credentialRowId());
            if (!breakGlass) {
                credentialMapper.copyPrivilegedAssurance(credentialRowId, proof.credentialRowId());
            }
        }
        if (breakGlass) {
            credentialMapper.markBreakGlassAssurance(credentialRowId);
        }
    }

    /**
     * Issues discoverable-credential (usernameless) login options, so no passkey-existence
     * information leaks for a given account.
     * @return request options to hand to the browser
     */
    public PublicKeyCredentialRequestOptions createLoginOptions() {
        return rpOperations.createCredentialRequestOptions(
            new ImmutablePublicKeyCredentialRequestOptionsRequest(null));
    }

    /**
     * Issues assertion options for the authenticated account's own enrolled passkeys.
     * @param auth the current authenticated principal
     * @return request options restricted to the caller's credentials
     */
    public PublicKeyCredentialRequestOptions createStepUpOptions(Authentication auth) {
        User user = (User) auth.getPrincipal();
        if (!hasPasskey(user.getId())) {
            throw new PasskeyEnrollmentRequiredException();
        }
        return rpOperations.createCredentialRequestOptions(
            new ImmutablePublicKeyCredentialRequestOptionsRequest(auth));
    }

    /**
     * Verifies an assertion (advancing the signature counter) and resolves the owning account.
     * Does not establish a session — the controller runs the shared login ceremony.
     * @param options the options issued in {@link #createLoginOptions}
     * @param assertion the client's assertion response
     * @return the authenticated user and the passkey that signed the assertion
     */
    @Transactional
    public VerifiedPasskey finishLogin(PublicKeyCredentialRequestOptions options,
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion) {
        PublicKeyCredentialUserEntity entity =
            rpOperations.authenticate(new RelyingPartyAuthenticationRequest(options, assertion));
        String userHandle = entity.getId().toBase64UrlString();
        Integer userId = userEntityMapper.findUserIdByHandle(userHandle);
        if (userId == null) {
            throw new BadCredentialsException("Unknown passkey");
        }
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new BadCredentialsException("Unknown passkey");
        }
        WebauthnCredentialRow signer = credentialMapper.findByCredentialId(assertion.getRawId().getBytes());
        if (signer == null || signer.getId() == null || !userHandle.equals(signer.getUserEntityUserId())) {
            throw new BadCredentialsException("Unknown passkey");
        }
        return new VerifiedPasskey(user, signer.getId());
    }

    /**
     * Verifies a step-up assertion and ensures the credential belongs to the authenticated caller.
     * @param auth the current authenticated principal
     * @param options the options issued in {@link #createStepUpOptions}
     * @param assertion the client's assertion response
     * @return the current account and the passkey that signed the step-up
     */
    @Transactional
    public VerifiedPasskey finishStepUp(Authentication auth, PublicKeyCredentialRequestOptions options,
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion) {
        User currentUser = (User) auth.getPrincipal();
        VerifiedPasskey verified = finishLogin(options, assertion);
        if (verified.user().getId() != currentUser.getId()) {
            throw new BadCredentialsException("Passkey authentication failed");
        }
        return verified;
    }

    /**
     * Lists the enrolled passkeys owned by the given account.
     * @param userId the owning account
     * @return the account's passkeys (empty if none)
     */
    public List<PasskeyDto> listForUser(int userId) {
        WebauthnUserEntityRow entity = userEntityMapper.findByUserId(userId);
        if (entity == null) {
            return List.of();
        }
        return credentialMapper.findByUserEntityUserId(entity.getId()).stream()
            .map(this::toDto)
            .toList();
    }

    public boolean hasPasskey(int userId) {
        return credentialMapper.existsByUserId(userId);
    }

    /**
     * Renames a passkey the caller owns.
     * @param callerUserId the authenticated account
     * @param credentialId the target credential (base64url)
     * @param label the new nickname
     */
    @Transactional
    public String rename(int callerUserId, String credentialId, String label) {
        WebauthnCredentialRow row = requireOwned(callerUserId, credentialId);
        credentialMapper.updateLabel(row.getCredentialId(), label);
        User user = requireUser(callerUserId);
        auditService.recordStrict("auth.passkey.rename", "user", callerUserId, user.getDisplayName(),
                "Passkey renamed", auditService.singleChange("label", row.getLabel(), label));
        return row.getLabel();
    }

    /**
     * Deletes a passkey the caller owns.
     *
     * <p>Runs at READ COMMITTED and locks the account row and its assigned custom roles before the
     * last-credential privilege check, so a promotion that commits while this transaction is waiting
     * is observed rather than read from a stale snapshot.
     *
     * @param callerUserId the authenticated account
     * @param credentialId the target credential (base64url)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String delete(int callerUserId, String credentialId) {
        if (userMapper.lockById(callerUserId) == null) {
            throw new ResourceNotFoundException("Passkey not found");
        }
        userMapper.lockAssignedCustomRoleIds(callerUserId);
        WebauthnUserEntityRow entity = userEntityMapper.findByUserId(callerUserId);
        if (entity == null) {
            throw new ResourceNotFoundException("Passkey not found");
        }
        List<WebauthnCredentialRow> credentials =
                credentialMapper.findByUserEntityUserIdForUpdate(entity.getId());
        WebauthnCredentialRow row = credentials.stream()
                .filter(candidate -> MessageDigest.isEqual(
                        candidate.getCredentialId(), Bytes.fromBase64(credentialId).getBytes()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Passkey not found"));
        if (credentials.size() == 1 && privilegedAccountService.isPrivileged(callerUserId)) {
            throw new LastPasskeyRemovalForbiddenException();
        }
        userCredentials.delete(Bytes.fromBase64(credentialId));
        User user = requireUser(callerUserId);
        auditService.recordStrict("auth.passkey.delete", "user", callerUserId, user.getDisplayName(),
                "Passkey removed", auditService.singleChange("label", row.getLabel(), null));
        return row.getLabel();
    }

    /**
     * Removes every credential after the controller has verified the account and operator recovery
     * proofs. The account row and credential rows serialize recovery with concurrent promotion,
     * enrollment, and removal.
     *
     * <p>Removing nothing is a legitimate outcome. Recovery is an operator-authorized authorization
     * ceremony as much as a deletion: it is the documented route back for a privileged account that
     * cannot satisfy the emailed first-enrollment confirmation, and such an account has never
     * enrolled, so it has no credential to remove. Refusing it here would leave that route
     * unexecutable and the account with no way through at all.
     *
     * @param callerUserId recovering account
     * @return number of credentials removed, zero when none were enrolled
     */
    @Transactional
    public int recover(int callerUserId) {
        if (userMapper.lockById(callerUserId) == null) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        WebauthnUserEntityRow entity = userEntityMapper.findByUserId(callerUserId);
        if (entity == null) {
            return 0;
        }
        List<WebauthnCredentialRow> credentials =
                credentialMapper.findByUserEntityUserIdForUpdate(entity.getId());
        if (credentials.isEmpty()) {
            return 0;
        }
        for (WebauthnCredentialRow credential : credentials) {
            userCredentials.delete(new Bytes(credential.getCredentialId()));
        }
        return credentials.size();
    }

    private void ensureUserEntity(User user) {
        if (userEntityMapper.findByUserId(user.getId()) == null) {
            WebauthnUserEntityRow row = new WebauthnUserEntityRow();
            row.setId(Bytes.random().toBase64UrlString());
            row.setUserId(user.getId());
            row.setName(user.getUsername());
            row.setDisplayName(user.getDisplayName());
            userEntityMapper.insert(row);
        }
    }

    private WebauthnCredentialRow requireOwned(int callerUserId, String credentialId) {
        byte[] id = Bytes.fromBase64(credentialId).getBytes();
        WebauthnCredentialRow row = credentialMapper.findByCredentialId(id);
        if (row == null) {
            throw new ResourceNotFoundException("Passkey not found");
        }
        Integer owner = userEntityMapper.findUserIdByHandle(row.getUserEntityUserId());
        if (owner == null || owner != callerUserId) {
            throw new ResourceNotFoundException("Passkey not found");
        }
        return row;
    }

    private User requireUser(int userId) {
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        return user;
    }

    private PasskeyDto toDto(WebauthnCredentialRow row) {
        List<String> transports = row.getTransports() == null || row.getTransports().isBlank()
            ? List.of()
            : List.of(row.getTransports().split(","));
        return new PasskeyDto(
            new Bytes(row.getCredentialId()).toBase64UrlString(),
            row.getLabel(),
            transports,
            row.getCreatedAt(),
            row.getLastUsedAt());
    }

    /**
     * Records the refused first-passkey enrollment as the controller does at the ceremony phases,
     * deferred until this transaction completes because it holds the account row exclusively. The
     * target label is the account's display name, read under that lock as the confirmation policy
     * reads it. A failed read leaves the label empty rather than changing the refusal the caller
     * receives.
     *
     * @param userId the account whose enrollment was refused
     */
    private void deferBootstrapConfirmationRefusalAudit(int userId) {
        String label = null;
        try {
            User user = userMapper.getUserById(userId);
            label = user == null ? null : user.getDisplayName();
        } catch (DataAccessException unavailable) {
            log.warn("Could not read the refused account's label for its audit: {}",
                    unavailable.getClass().getSimpleName());
        }
        auditService.deferFailureScoped(
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_ACTION,
                "user",
                userId,
                null,
                null,
                label,
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_SUMMARY,
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_REASON);
    }
}
