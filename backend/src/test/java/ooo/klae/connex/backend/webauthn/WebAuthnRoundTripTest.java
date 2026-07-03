package ooo.klae.connex.backend.webauthn;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientOutputs;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.transaction.annotation.Transactional;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;

/**
 * Full register&rarr;authenticate crypto round-trip using a webauthn4j-test virtual authenticator.
 * Drives Spring Security's relying-party operations with real attestation and assertion responses,
 * proving that verification, persistence through the MyBatis repositories, handle&rarr;account
 * resolution, and the signature counter all work end to end. Also confirms the ceremony JSON mapper
 * serializes options in the browser-facing shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class WebAuthnRoundTripTest {

    private static final String RP_ID = "example.com";
    private static final String RP_NAME = "Connex Test";
    private static final String ORIGIN = "https://example.com";

    @Autowired UserMapper userMapper;
    @Autowired WebauthnUserEntityMapper userEntityMapper;
    @Autowired UserCredentialRepository userCredentials;
    @Autowired PublicKeyCredentialUserEntityRepository userEntities;
    @Autowired WebAuthnJsonMapper json;

    private WebAuthnRelyingPartyOperations testOps() {
        PublicKeyCredentialRpEntity rp = PublicKeyCredentialRpEntity.builder().id(RP_ID).name(RP_NAME).build();
        Webauthn4JRelyingPartyOperations impl =
            new Webauthn4JRelyingPartyOperations(userEntities, userCredentials, rp, Set.of(ORIGIN));
        impl.setWebAuthnManager(WebAuthnManager.createNonStrictWebAuthnManager());
        return impl;
    }

    @Test
    void register_then_authenticate_roundTrips() {
        WebAuthnRelyingPartyOperations ops = testOps();
        ClientPlatform client = new ClientPlatform(
            new Origin(ORIGIN),
            new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));

        String s = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("pk_" + s);
        user.setDisplayName("Passkey " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("x");
        user.setTimezone("UTC");
        userMapper.insert(user);

        Bytes handle = Bytes.random();
        WebauthnUserEntityRow row = new WebauthnUserEntityRow();
        row.setId(handle.toBase64UrlString());
        row.setUserId(user.getId());
        row.setName(user.getUsername());
        row.setDisplayName(user.getDisplayName());
        userEntityMapper.insert(row);

        PublicKeyCredentialCreationOptions creation =
            ops.createPublicKeyCredentialCreationOptions(
                () -> new TestingAuthenticationToken(user.getUsername(), "n/a", "ROLE_USER"));

        String optionsJson = json.write(creation);
        assertTrue(optionsJson.contains("challenge"), "serialized options carry a challenge");
        assertTrue(optionsJson.contains("\"rp\""), "serialized options carry the rp entity");

        com.webauthn4j.data.PublicKeyCredentialCreationOptions w4jCreate =
            new com.webauthn4j.data.PublicKeyCredentialCreationOptions(
                new com.webauthn4j.data.PublicKeyCredentialRpEntity(RP_ID, RP_NAME),
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
            com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput> made = client.create(w4jCreate);

        AuthenticatorAttestationResponse attResp = AuthenticatorAttestationResponse.builder()
            .attestationObject(new Bytes(made.getResponse().getAttestationObject()))
            .clientDataJSON(new Bytes(made.getResponse().getClientDataJSON()))
            .transports(AuthenticatorTransport.INTERNAL)
            .build();
        PublicKeyCredential<AuthenticatorAttestationResponse> regCred =
            PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
                .id(made.getId())
                .rawId(new Bytes(made.getRawId()))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(attResp)
                .clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs())
                .build();

        CredentialRecord stored = ops.registerCredential(
            new ImmutableRelyingPartyRegistrationRequest(creation, new RelyingPartyPublicKey(regCred, "Test Passkey")));
        userCredentials.save(stored);
        CredentialRecord persisted = userCredentials.findByCredentialId(stored.getCredentialId());
        assertNotNull(persisted, "credential persisted");
        assertNotNull(persisted.getCreated(), "created timestamp persisted");
        assertTrue(
            java.time.Duration.between(persisted.getCreated(), java.time.Instant.now()).abs().toHours() < 1,
            "created timestamp is app-written (no timezone skew from a DB default)");
        long countAfterRegister = persisted.getSignatureCount();

        PublicKeyCredentialRequestOptions request = ops.createCredentialRequestOptions(() -> null);
        com.webauthn4j.data.PublicKeyCredentialRequestOptions w4jRequest =
            new com.webauthn4j.data.PublicKeyCredentialRequestOptions(
                new DefaultChallenge(request.getChallenge().getBytes()), null, RP_ID, null,
                com.webauthn4j.data.UserVerificationRequirement.PREFERRED, null);
        com.webauthn4j.data.PublicKeyCredential<com.webauthn4j.data.AuthenticatorAssertionResponse,
            com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput> got = client.get(w4jRequest);

        AuthenticatorAssertionResponse asrResp = AuthenticatorAssertionResponse.builder()
            .authenticatorData(new Bytes(got.getResponse().getAuthenticatorData()))
            .clientDataJSON(new Bytes(got.getResponse().getClientDataJSON()))
            .signature(new Bytes(got.getResponse().getSignature()))
            .userHandle(new Bytes(got.getResponse().getUserHandle()))
            .build();
        PublicKeyCredential<AuthenticatorAssertionResponse> asrCred =
            PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
                .id(got.getId())
                .rawId(new Bytes(got.getRawId()))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(asrResp)
                .clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs())
                .build();

        PublicKeyCredentialUserEntity resolved =
            ops.authenticate(new RelyingPartyAuthenticationRequest(request, asrCred));

        assertEquals(user.getUsername(), resolved.getName());
        assertEquals(handle.toBase64UrlString(), resolved.getId().toBase64UrlString());
        assertEquals(user.getId(), userEntityMapper.findUserIdByHandle(resolved.getId().toBase64UrlString()));
        long countAfterAuth = userCredentials.findByCredentialId(stored.getCredentialId()).getSignatureCount();
        assertTrue(countAfterAuth > countAfterRegister, "signature counter advances on authentication");
    }
}
