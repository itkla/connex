package ooo.klae.connex.backend.webauthn;

import java.util.List;

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
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates the WebAuthn ceremonies over Spring Security's {@code WebAuthnRelyingPartyOperations}
 * (which performs attestation/assertion verification) and owns the durable handle&harr;{@code app_user}
 * link plus credential-ownership enforcement. Passkeys are additive: enrollment requires an
 * authenticated session; authentication resolves the account from the credential's user handle and
 * hands a verified {@link User} back to the controller, which finishes the shared login ceremony.
 */
@Service
@RequiredArgsConstructor
public class WebAuthnService {

    private final WebAuthnRelyingPartyOperations rpOperations;
    private final UserCredentialRepository userCredentials;
    private final WebauthnUserEntityMapper userEntityMapper;
    private final WebauthnCredentialMapper credentialMapper;
    private final UserMapper userMapper;

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
     * @param options the options issued in {@link #createRegistrationOptions}
     * @param credential the client's attestation response
     * @param label the user-supplied nickname
     * @return the stored credential record
     */
    @Transactional
    public CredentialRecord finishRegistration(PublicKeyCredentialCreationOptions options,
            PublicKeyCredential<AuthenticatorAttestationResponse> credential, String label) {
        CredentialRecord record = rpOperations.registerCredential(
            new ImmutableRelyingPartyRegistrationRequest(options, new RelyingPartyPublicKey(credential, label)));
        userCredentials.save(record);
        return record;
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
        if (listForUser(user.getId()).isEmpty()) {
            throw new BadRequestException("No passkey enrolled");
        }
        return rpOperations.createCredentialRequestOptions(
            new ImmutablePublicKeyCredentialRequestOptionsRequest(auth));
    }

    /**
     * Verifies an assertion (advancing the signature counter) and resolves the owning account.
     * Does not establish a session — the controller runs the shared login ceremony.
     * @param options the options issued in {@link #createLoginOptions}
     * @param assertion the client's assertion response
     * @return the authenticated user
     */
    @Transactional
    public User finishLogin(PublicKeyCredentialRequestOptions options,
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion) {
        PublicKeyCredentialUserEntity entity =
            rpOperations.authenticate(new RelyingPartyAuthenticationRequest(options, assertion));
        Integer userId = userEntityMapper.findUserIdByHandle(entity.getId().toBase64UrlString());
        if (userId == null) {
            throw new BadCredentialsException("Unknown passkey");
        }
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new BadCredentialsException("Unknown passkey");
        }
        return user;
    }

    /**
     * Verifies a step-up assertion and ensures the credential belongs to the authenticated caller.
     * @param auth the current authenticated principal
     * @param options the options issued in {@link #createStepUpOptions}
     * @param assertion the client's assertion response
     */
    @Transactional
    public void finishStepUp(Authentication auth, PublicKeyCredentialRequestOptions options,
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion) {
        User currentUser = (User) auth.getPrincipal();
        User assertedUser = finishLogin(options, assertion);
        if (assertedUser.getId() != currentUser.getId()) {
            throw new BadCredentialsException("Passkey authentication failed");
        }
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
        return !listForUser(userId).isEmpty();
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
        return row.getLabel();
    }

    /**
     * Deletes a passkey the caller owns.
     * @param callerUserId the authenticated account
     * @param credentialId the target credential (base64url)
     */
    @Transactional
    public String delete(int callerUserId, String credentialId) {
        WebauthnCredentialRow row = requireOwned(callerUserId, credentialId);
        userCredentials.delete(Bytes.fromBase64(credentialId));
        return row.getLabel();
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
}
