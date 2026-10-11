package ooo.klae.connex.backend.integration;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientOutputs;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.webauthn.EnrollmentEvidence;
import ooo.klae.connex.backend.webauthn.RegisteredPasskey;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Drives real WebAuthn ceremonies through the application's relying party with a software
 * authenticator, for integration tests that need genuine registrations and assertions.
 */
final class SoftwarePasskeys {
    static final String RP_ID = "localhost";
    static final String ORIGIN = "http://localhost:3000";

    private SoftwarePasskeys() {
    }

    /**
     * A passkey held by a software authenticator.
     *
     * @param client the client platform that holds the authenticator
     * @param rowId the passkey's {@code webauthn_credential.id}
     */
    record SoftwarePasskey(ClientPlatform client, int rowId) {
    }

    /** Authenticates the account in the current security context. */
    static Authentication authenticate(User user) {
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    /** Registers a new software passkey through {@link WebAuthnService#finishRegistration}. */
    static SoftwarePasskey register(
            WebAuthnService webAuthnService,
            UserMapper userMapper,
            User user,
            EnrollmentEvidence evidence,
            String label) {
        Authentication auth = authenticate(user);
        PublicKeyCredentialCreationOptions creation = webAuthnService.createRegistrationOptions(auth);
        NoneAttestationAuthenticator authenticator = new NoneAttestationAuthenticator(
            AAGUID.ZERO, 0, true, new ObjectConverter());
        authenticator.setCountUpEnabled(false);
        ClientPlatform client = new ClientPlatform(
            new Origin(ORIGIN), new WebAuthnAuthenticatorAdaptor(authenticator));
        com.webauthn4j.data.PublicKeyCredentialCreationOptions request =
            new com.webauthn4j.data.PublicKeyCredentialCreationOptions(
                new com.webauthn4j.data.PublicKeyCredentialRpEntity(RP_ID, "Connex"),
                new com.webauthn4j.data.PublicKeyCredentialUserEntity(
                    creation.getUser().getId().getBytes(), user.getUsername(), user.getDisplayName()),
                new DefaultChallenge(creation.getChallenge().getBytes()),
                List.of(new com.webauthn4j.data.PublicKeyCredentialParameters(
                    com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY,
                    com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier.ES256)),
                null,
                null,
                new com.webauthn4j.data.AuthenticatorSelectionCriteria(
                    null,
                    com.webauthn4j.data.ResidentKeyRequirement.REQUIRED,
                    com.webauthn4j.data.UserVerificationRequirement.PREFERRED),
                null,
                null);
        com.webauthn4j.data.PublicKeyCredential<com.webauthn4j.data.AuthenticatorAttestationResponse,
            com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput> made = client.create(request);
        PublicKeyCredential<AuthenticatorAttestationResponse> attestation =
            PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
                .id(made.getId())
                .rawId(new Bytes(made.getRawId()))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(AuthenticatorAttestationResponse.builder()
                    .attestationObject(new Bytes(made.getResponse().getAttestationObject()))
                    .clientDataJSON(new Bytes(made.getResponse().getClientDataJSON()))
                    .transports(AuthenticatorTransport.INTERNAL)
                    .build())
                .clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs())
                .build();
        RegisteredPasskey registered = webAuthnService.finishRegistration(
            user.getId(), userMapper.currentSessionEpoch(user.getId()), true, evidence,
            creation, attestation, label + " " + made.getId().substring(0, 6));
        authenticator.setCountUpEnabled(true);
        return new SoftwarePasskey(client, registered.credentialRowId());
    }

    /** Signs request options with the passkey, as a browser would answer them. */
    static PublicKeyCredential<AuthenticatorAssertionResponse> sign(
            SoftwarePasskey passkey, PublicKeyCredentialRequestOptions options) {
        com.webauthn4j.data.PublicKeyCredential<com.webauthn4j.data.AuthenticatorAssertionResponse,
            com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput> got =
            passkey.client().get(new com.webauthn4j.data.PublicKeyCredentialRequestOptions(
                new DefaultChallenge(options.getChallenge().getBytes()), null, RP_ID, null,
                com.webauthn4j.data.UserVerificationRequirement.PREFERRED, null));
        return PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
            .id(got.getId())
            .rawId(new Bytes(got.getRawId()))
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(AuthenticatorAssertionResponse.builder()
                .authenticatorData(new Bytes(got.getResponse().getAuthenticatorData()))
                .clientDataJSON(new Bytes(got.getResponse().getClientDataJSON()))
                .signature(new Bytes(got.getResponse().getSignature()))
                .userHandle(new Bytes(got.getResponse().getUserHandle()))
                .build())
            .clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs())
            .build();
    }
}
