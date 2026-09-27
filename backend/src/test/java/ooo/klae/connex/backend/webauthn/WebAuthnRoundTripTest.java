package ooo.klae.connex.backend.webauthn;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

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
    @Autowired OrganizationMapper organizationMapper;
    @Autowired WorkspaceMapper workspaceMapper;
    @Autowired WebauthnCredentialMapper credentialMapper;
    @Autowired WebauthnUserEntityMapper userEntityMapper;
    @Autowired UserCredentialRepository userCredentials;
    @Autowired PublicKeyCredentialUserEntityRepository userEntities;
    @Autowired WebAuthnJsonMapper json;
    @Autowired PlatformTransactionManager transactionManager;

    private WebAuthnRelyingPartyOperations testOps(UserCredentialRepository credentials) {
        PublicKeyCredentialRpEntity rp = PublicKeyCredentialRpEntity.builder().id(RP_ID).name(RP_NAME).build();
        Webauthn4JRelyingPartyOperations impl =
            new Webauthn4JRelyingPartyOperations(userEntities, credentials, rp, Set.of(ORIGIN));
        impl.setWebAuthnManager(WebAuthnManager.createNonStrictWebAuthnManager());
        return impl;
    }

    @Test
    void register_then_authenticate_roundTrips() {
        RegisteredPasskey passkey = register(0, true);
        PublicKeyCredentialUserEntity resolved = passkey.ops().authenticate(assertion(passkey));

        assertEquals(passkey.user().getUsername(), resolved.getName());
        assertEquals(passkey.handle().toBase64UrlString(), resolved.getId().toBase64UrlString());
        assertEquals(passkey.user().getId(), userEntityMapper.findUserIdByHandle(resolved.getId().toBase64UrlString()));
        long countAfterAuth = userCredentials.findByCredentialId(passkey.credential().getCredentialId()).getSignatureCount();
        assertTrue(countAfterAuth > passkey.credential().getSignatureCount(), "signature counter advances on authentication");
    }

    @ParameterizedTest
    @EnumSource(value = Isolation.class, names = { "REPEATABLE_READ", "READ_COMMITTED" })
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAssertions_rejectStaleCounterAndReplayAfterHigherCounterCommits(Isolation isolation)
            throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(isolation.value());
        RegisteredPasskey passkey = transaction.execute(status -> register(10, true));
        assertNotNull(passkey);
        RelyingPartyAuthenticationRequest lower = assertion(passkey);
        RelyingPartyAuthenticationRequest higher = assertion(passkey);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch lowerUpdateReady = new CountDownLatch(1);
        CountDownLatch higherCommitted = new CountDownLatch(1);
        WebauthnCredentialMapper observedMapper = mock(WebauthnCredentialMapper.class, delegatesTo(credentialMapper));
        doAnswer(invocation -> {
            WebauthnCredentialRow row = credentialMapper.findByCredentialId(invocation.getArgument(0, byte[].class));
            assertNotNull(row);
            if (bothRead.getCount() > 0) {
                assertEquals(10, row.getSignatureCount());
                bothRead.countDown();
                await(bothRead);
            }
            return row;
        }).when(observedMapper).findByCredentialId(any());
        doAnswer(invocation -> {
            WebauthnCredentialRow row = invocation.getArgument(0, WebauthnCredentialRow.class);
            long expectedCount = invocation.getArgument(1, Long.class);
            assertEquals(10, expectedCount);
            if (row.getSignatureCount() == 11) {
                lowerUpdateReady.countDown();
                await(higherCommitted);
            }
            int updated = credentialMapper.updateMutable(row, expectedCount);
            if (row.getSignatureCount() == 11) {
                assertEquals(0, updated, "the stale counter update is refused by the database");
            }
            if (row.getSignatureCount() == 12) {
                assertEquals(1, updated);
                await(lowerUpdateReady);
            }
            return updated;
        }).when(observedMapper).updateMutable(any(), anyLong());
        WebAuthnRelyingPartyOperations ops = testOps(new MyBatisUserCredentialRepository(observedMapper));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PublicKeyCredentialUserEntity> lowerResult = executor.submit(
                () -> transaction.execute(status -> ops.authenticate(lower)));
            Future<PublicKeyCredentialUserEntity> higherResult = executor.submit(
                () -> transaction.execute(status -> ops.authenticate(higher)));
            assertEquals(passkey.handle(), higherResult.get(20, TimeUnit.SECONDS).getId());
            assertEquals(12, userCredentials.findByCredentialId(passkey.credential().getCredentialId()).getSignatureCount());
            higherCommitted.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> lowerResult.get(20, TimeUnit.SECONDS));
            assertInstanceOf(BadCredentialsException.class, failure.getCause());
        } finally {
            higherCommitted.countDown();
            lowerUpdateReady.countDown();
            bothRead.countDown();
            bothRead.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(12, userCredentials.findByCredentialId(passkey.credential().getCredentialId()).getSignatureCount());
        passkey.authenticator().setCountUpEnabled(false);
        RelyingPartyAuthenticationRequest replay = assertion(passkey);
        assertThrows(BadCredentialsException.class,
            () -> transaction.execute(status -> passkey.ops().authenticate(replay)));
        assertEquals(12, userCredentials.findByCredentialId(passkey.credential().getCredentialId()).getSignatureCount());
    }

    @Test
    void zeroCounterAuthenticator_acceptsRepeatedAssertionsAndRejectsReplayOnceCounterAdvances() {
        RegisteredPasskey passkey = register(0, false);

        assertEquals(passkey.handle(), passkey.ops().authenticate(assertion(passkey)).getId());
        assertEquals(passkey.handle(), passkey.ops().authenticate(assertion(passkey)).getId());
        assertEquals(0, userCredentials.findByCredentialId(passkey.credential().getCredentialId()).getSignatureCount());

        passkey.authenticator().setCountUpEnabled(true);
        assertEquals(passkey.handle(), passkey.ops().authenticate(assertion(passkey)).getId());
        passkey.authenticator().setCountUpEnabled(false);
        RelyingPartyAuthenticationRequest replay = assertion(passkey);
        assertThrows(BadCredentialsException.class, () -> passkey.ops().authenticate(replay));
        assertEquals(1, userCredentials.findByCredentialId(passkey.credential().getCredentialId()).getSignatureCount());
    }

    private RegisteredPasskey register(int initialCount, boolean countUpEnabled) {
        WebAuthnRelyingPartyOperations ops = testOps(userCredentials);
        NoneAttestationAuthenticator authenticator = new NoneAttestationAuthenticator(
            AAGUID.ZERO, initialCount, true, new ObjectConverter());
        authenticator.setCountUpEnabled(false);
        ClientPlatform client = new ClientPlatform(
            new Origin(ORIGIN),
            new WebAuthnAuthenticatorAdaptor(authenticator));

        String s = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("pk_" + s);
        user.setDisplayName("Passkey " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("x");
        user.setTimezone("UTC");
        userMapper.insert(user);

        Organization organization = new Organization();
        organization.setName("Passkey " + s);
        organization.setSlug("pk-" + s);
        organizationMapper.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Passkey " + s);
        workspace.setSlug("pk-" + s);
        workspace.setTimezone("UTC");
        workspaceMapper.insert(workspace);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");

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
        CredentialRecord persisted = userCredentials.findByCredentialId(stored.getCredentialId());
        assertNotNull(persisted, "credential persisted");
        assertNotNull(persisted.getCreated(), "created timestamp persisted");
        assertTrue(
            java.time.Duration.between(persisted.getCreated(), java.time.Instant.now()).abs().toHours() < 1,
            "created timestamp is app-written (no timezone skew from a DB default)");
        assertEquals(initialCount, persisted.getSignatureCount());
        authenticator.setCountUpEnabled(countUpEnabled);
        return new RegisteredPasskey(ops, client, authenticator, stored, user, handle);
    }

    private RelyingPartyAuthenticationRequest assertion(RegisteredPasskey passkey) {
        PublicKeyCredentialRequestOptions request = passkey.ops().createCredentialRequestOptions(() -> null);
        com.webauthn4j.data.PublicKeyCredentialRequestOptions w4jRequest =
            new com.webauthn4j.data.PublicKeyCredentialRequestOptions(
                new DefaultChallenge(request.getChallenge().getBytes()), null, RP_ID, null,
                com.webauthn4j.data.UserVerificationRequirement.PREFERRED, null);
        com.webauthn4j.data.PublicKeyCredential<com.webauthn4j.data.AuthenticatorAssertionResponse,
            com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput> got = passkey.client().get(w4jRequest);

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

        return new RelyingPartyAuthenticationRequest(request, asrCred);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "assertion transactions reached the counter update barrier");
    }

    private record RegisteredPasskey(
        WebAuthnRelyingPartyOperations ops,
        ClientPlatform client,
        NoneAttestationAuthenticator authenticator,
        CredentialRecord credential,
        User user,
        Bytes handle) {}
}
