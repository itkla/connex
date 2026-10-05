package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.session.StepUpProof;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.webauthn.EnrollmentEvidence;
import ooo.klae.connex.backend.webauthn.RegisteredPasskey;
import ooo.klae.connex.backend.webauthn.VerifiedPasskey;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import ooo.klae.connex.backend.webauthn.WebauthnCredentialRow;
import ooo.klae.connex.backend.webauthn.WebauthnUserEntityRow;

/**
 * Proves privileged passkey provenance against the real schema and real WebAuthn ceremonies
 * (#1534, slice 1): what founding, a fresh step-up and operator recovery each record, which
 * source wins, how coverage cascades, and that founding and registration racing on the same
 * account still leave the new passkey covered in either commit order.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrivilegedCredentialProvenanceIntegrationTest {
    private static final String RP_ID = "localhost";
    private static final String ORIGIN = "http://localhost:3000";
    private static final EnrollmentEvidence NO_EVIDENCE = new EnrollmentEvidence(null, null);

    @Autowired private WebAuthnService webAuthnService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private PrivilegedCredentialAttestationMapper attestationMapper;
    @Autowired private WebauthnCredentialMapper credentialMapper;
    @Autowired private WebauthnUserEntityMapper userEntityMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        for (int organizationId : organizationIds) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
        }
        for (int userId : userIds) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    void aPasskeyRegisteredAfterAStepUpInheritsTheSigningPasskeysCoverageAndAssurance() {
        User user = newUser();
        Organization organization = newOrganization();
        SoftwarePasskey first = register(user, NO_EVIDENCE);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'GRANTOR')",
            first.rowId(), organization.getId());
        jdbcTemplate.update(
            "UPDATE webauthn_credential SET privileged_assurance = 'GRANDFATHERED',"
                + " privileged_assured_at = UTC_TIMESTAMP(3) WHERE id = ?",
            first.rowId());

        VerifiedPasskey signed = stepUp(user, first);
        StepUpProof proof = stampedProof(user, signed);
        SoftwarePasskey second = register(user, new EnrollmentEvidence(null, proof));

        assertEquals(first.rowId(), signed.credentialRowId());
        assertEquals(List.of(new Coverage(organization.getId(), "INHERITED")), coverage(second.rowId()));
        assertEquals("GRANDFATHERED", assurance(second.rowId()));
        assertNotNull(assuredAt(second.rowId()));
    }

    @Test
    void aPasskeyRegisteredWithoutAStepUpInheritsNothing() {
        User user = newUser();
        Organization organization = newOrganization();
        SoftwarePasskey first = register(user, NO_EVIDENCE);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'GRANTOR')",
            first.rowId(), organization.getId());

        SoftwarePasskey second = register(user, NO_EVIDENCE);

        assertEquals(List.of(), coverage(second.rowId()));
        assertNull(assurance(second.rowId()));
    }

    @Test
    void aFounderRegisteringASecondPasskeyKeepsDirectFounderCoverage() {
        User founder = newUser();
        SoftwarePasskey first = register(founder, NO_EVIDENCE);
        int organizationId = found(founder);

        VerifiedPasskey signed = stepUp(founder, first);
        SoftwarePasskey second = register(founder, new EnrollmentEvidence(null, stampedProof(founder, signed)));

        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(first.rowId()));
        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(second.rowId()));
    }

    /**
     * After an operator recovery, only the session the recovery granted the restamp to earns
     * break-glass assurance. A second session of the same account enrolling first gets none, and
     * its registration consumes the grant (#1534).
     */
    @Test
    void onlyTheRecoverySessionEarnsBreakGlassAssurance() {
        User racing = newUser();
        int racingEpoch = userMapper.currentSessionEpoch(racing.getId());
        userMapper.grantEpochRestamp(racing.getId(), "recovery-session", racingEpoch);

        SoftwarePasskey raced = register(racing, new EnrollmentEvidence("another-session", null));

        assertNull(assurance(raced.rowId()));
        assertNull(userMapper.epochRestampGrant(racing.getId()));

        User recovering = newUser();
        int recoveringEpoch = userMapper.currentSessionEpoch(recovering.getId());
        userMapper.grantEpochRestamp(recovering.getId(), "recovery-session", recoveringEpoch);

        SoftwarePasskey recovered = register(recovering, new EnrollmentEvidence("recovery-session", null));

        assertEquals("BREAK_GLASS", assurance(recovered.rowId()));
        assertNotNull(assuredAt(recovered.rowId()));
    }

    @Test
    void foundingFlagsTheFounderAndCoversTheirPasskeysInThatOrganizationOnly() {
        User founder = newUser();
        String handle = newHandle(founder);
        int firstRowId = insertCredential(handle);
        int secondRowId = insertCredential(handle);
        Organization elsewhere = newOrganization();
        orgMemberMapper.addMember(elsewhere.getId(), founder.getId(), "owner");

        int organizationId = found(founder);

        assertTrue(isFounder(organizationId, founder.getId()));
        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(firstRowId));
        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(secondRowId));
        assertFalse(isFounder(elsewhere.getId(), founder.getId()));
    }

    @Test
    void founderCoverageFollowsOnlyAFounderWhoStillOwnsTheOrganization() {
        User founder = newUser();
        String handle = newHandle(founder);
        int organizationId = found(founder);

        orgMemberMapper.addMember(organizationId, founder.getId(), "admin");
        int whileDemoted = insertCredential(handle);
        attestationMapper.insertFounderCoverage(whileDemoted, founder.getId());

        orgMemberMapper.addMember(organizationId, founder.getId(), "owner");
        int whileOwner = insertCredential(handle);
        attestationMapper.insertFounderCoverage(whileOwner, founder.getId());

        orgMemberMapper.addMemberClearingFounder(organizationId, founder.getId(), "owner");
        int afterReset = insertCredential(handle);
        attestationMapper.insertFounderCoverage(afterReset, founder.getId());

        assertEquals(List.of(), coverage(whileDemoted));
        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(whileOwner));
        assertFalse(isFounder(organizationId, founder.getId()));
        assertEquals(List.of(), coverage(afterReset));
    }

    @Test
    void inheritanceNeverReplacesADirectSourceAndCopiesOnlyRealAssurance() {
        User user = newUser();
        String handle = newHandle(user);
        Organization granted = newOrganization();
        int organizationId = found(user);
        int source = insertCredential(handle);
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'GRANTOR'), (?, ?, 'GRANTOR')",
            source, granted.getId(), source, organizationId);
        int unassured = insertCredential(handle);

        int target = insertCredential(handle);
        attestationMapper.insertFounderCoverage(target, user.getId());
        attestationMapper.insertInheritedCoverage(target, source);
        int copied = credentialMapper.copyPrivilegedAssurance(target, unassured);

        assertEquals(List.of(
                new Coverage(granted.getId(), "INHERITED"),
                new Coverage(organizationId, "FOUNDER")),
            coverage(target));
        assertEquals(0, copied);
        assertNull(assurance(target));
    }

    @Test
    void anOrganizationBeingTornDownGetsNoNewCoverage() {
        User founder = newUser();
        String handle = newHandle(founder);
        int source = insertCredential(handle);
        int organizationId = found(founder);
        jdbcTemplate.update(
            "UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", organizationId);

        int registered = insertCredential(handle);
        attestationMapper.insertFounderCoverage(registered, founder.getId());
        attestationMapper.insertInheritedCoverage(registered, source);

        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(source));
        assertEquals(List.of(), coverage(registered));
    }

    @Test
    void coverageCascadesWithItsPasskeyAndItsOrganization() {
        User user = newUser();
        String handle = newHandle(user);
        int kept = insertCredential(handle);
        int removed = insertCredential(handle);
        int organizationId = found(user);
        Organization other = newOrganization();
        jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'GRANTOR')",
            kept, other.getId());

        jdbcTemplate.update("DELETE FROM webauthn_credential WHERE id = ?", removed);
        jdbcTemplate.update("DELETE FROM organization WHERE id = ?", other.getId());

        assertEquals(List.of(), coverage(removed));
        assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(kept));
    }

    @Test
    void theSchemaRejectsAnUnknownSourceAndAssuranceWithoutItsTime() {
        User user = newUser();
        int rowId = insertCredential(newHandle(user));
        Organization organization = newOrganization();

        DataAccessException unknownSource = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
                + " VALUES (?, ?, 'OPERATOR')",
            rowId, organization.getId()));
        DataAccessException untimedAssurance = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
            "UPDATE webauthn_credential SET privileged_assurance = 'GRANDFATHERED' WHERE id = ?", rowId));

        assertTrue(unknownSource.getMostSpecificCause().getMessage()
            .contains("chk_privileged_credential_attestation_source"));
        assertTrue(untimedAssurance.getMostSpecificCause().getMessage()
            .contains("chk_webauthn_credential_privileged_assured_at"));
    }

    /**
     * Founding holds the owner's account row shared while it commits the founder flag. A
     * registration of the same account waits for that row, then reads the committed flag and
     * covers its new passkey in the new organization.
     */
    @Test
    void aRegistrationWaitingOnFoundingStillTakesTheFounderCoverage() throws Exception {
        User founder = newUser();
        String handle = newHandle(founder);
        CountDownLatch founded = new CountDownLatch(1);
        CountDownLatch releaseFounding = new CountDownLatch(1);
        CountDownLatch registrationAttempted = new CountDownLatch(1);
        AtomicLong registrationConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> founding = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    userMapper.lockByIdForShare(founder.getId());
                    int organizationId = insertOrganization();
                    orgMemberService.addFoundingOwner(organizationId, founder.getId());
                    founded.countDown();
                    await(releaseFounding);
                    return organizationId;
                }));
            assertTrue(founded.await(30, TimeUnit.SECONDS), "founding never held the account row");
            Future<Integer> registration = executor.submit(() -> readCommitted().execute(status -> {
                registrationConnection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                registrationAttempted.countDown();
                userMapper.lockById(founder.getId());
                int rowId = insertCredential(handle);
                attestationMapper.insertFounderCoverage(rowId, founder.getId());
                return rowId;
            }));
            assertTrue(registrationAttempted.await(30, TimeUnit.SECONDS), "registration never started");
            MySqlLockWaitProbe.awaitExclusiveRecordLock(
                jdbcTemplate, registrationConnection.get(), "app_user", Integer.toString(founder.getId()));
            releaseFounding.countDown();

            int organizationId = founding.get(30, TimeUnit.SECONDS);
            int rowId = registration.get(30, TimeUnit.SECONDS);

            assertEquals(List.of(new Coverage(organizationId, "FOUNDER")), coverage(rowId));
        } finally {
            releaseFounding.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    /**
     * A registration holds the account row exclusively while it commits a new passkey. Founding by
     * the same account waits for that row, then covers the committed passkey in the new
     * organization.
     */
    @Test
    void foundingWaitingOnARegistrationStillCoversTheNewPasskey() throws Exception {
        User founder = newUser();
        String handle = newHandle(founder);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        CountDownLatch foundingAttempted = new CountDownLatch(1);
        AtomicLong foundingConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> registration = executor.submit(() -> readCommitted().execute(status -> {
                userMapper.lockById(founder.getId());
                int rowId = insertCredential(handle);
                attestationMapper.insertFounderCoverage(rowId, founder.getId());
                registered.countDown();
                await(releaseRegistration);
                return rowId;
            }));
            assertTrue(registered.await(30, TimeUnit.SECONDS), "registration never held the account row");
            Future<int[]> founding = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    foundingConnection.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    foundingAttempted.countDown();
                    userMapper.lockByIdForShare(founder.getId());
                    int organizationId = insertOrganization();
                    int covered = orgMemberService.addFoundingOwner(organizationId, founder.getId());
                    return new int[] {organizationId, covered};
                }));
            assertTrue(foundingAttempted.await(30, TimeUnit.SECONDS), "founding never started");
            MySqlLockWaitProbe.awaitSharedRecordLock(
                jdbcTemplate, foundingConnection.get(), "app_user", Integer.toString(founder.getId()));
            releaseRegistration.countDown();

            int rowId = registration.get(30, TimeUnit.SECONDS);
            int[] founded = founding.get(30, TimeUnit.SECONDS);

            assertEquals(1, founded[1]);
            assertEquals(List.of(new Coverage(founded[0], "FOUNDER")), coverage(rowId));
        } finally {
            releaseRegistration.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private record Coverage(int orgId, String source) {
    }

    private record SoftwarePasskey(ClientPlatform client, int rowId) {
    }

    private SoftwarePasskey register(User user, EnrollmentEvidence evidence) {
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
            creation, attestation, "Provenance " + made.getId().substring(0, 6));
        authenticator.setCountUpEnabled(true);
        return new SoftwarePasskey(client, registered.credentialRowId());
    }

    private VerifiedPasskey stepUp(User user, SoftwarePasskey passkey) {
        Authentication auth = authenticate(user);
        PublicKeyCredentialRequestOptions options = webAuthnService.createStepUpOptions(auth);
        com.webauthn4j.data.PublicKeyCredential<com.webauthn4j.data.AuthenticatorAssertionResponse,
            com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput> got =
            passkey.client().get(new com.webauthn4j.data.PublicKeyCredentialRequestOptions(
                new DefaultChallenge(options.getChallenge().getBytes()), null, RP_ID, null,
                com.webauthn4j.data.UserVerificationRequirement.PREFERRED, null));
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion =
            PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
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
        return webAuthnService.finishStepUp(auth, options, assertion);
    }

    private StepUpProof stampedProof(User user, VerifiedPasskey signed) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.markStepUp(request, user.getId(), signed.credentialRowId());
        StepUpProof proof = sessionSecurityService.recentStepUpProof(request.getSession(false), user.getId());
        assertNotNull(proof);
        return proof;
    }

    private Authentication authenticate(User user) {
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    private int found(User founder) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            userMapper.lockByIdForShare(founder.getId());
            int organizationId = insertOrganization();
            orgMemberService.addFoundingOwner(organizationId, founder.getId());
            return organizationId;
        });
    }

    private int insertOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Provenance Org " + suffix);
        organization.setSlug("provenance-org-" + suffix);
        organizationMapper.insert(organization);
        synchronized (organizationIds) {
            organizationIds.add(organization.getId());
        }
        return organization.getId();
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setId(insertOrganization());
        return organization;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("provenance_" + suffix);
        user.setDisplayName("Provenance " + suffix);
        user.setEmail("provenance-" + suffix + "@example.com");
        user.setPasswordHash("unused");
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return userMapper.getUserById(user.getId());
    }

    private String newHandle(User user) {
        WebauthnUserEntityRow row = new WebauthnUserEntityRow();
        row.setId(Bytes.random().toBase64UrlString());
        row.setUserId(user.getId());
        row.setName(user.getUsername());
        row.setDisplayName(user.getDisplayName());
        userEntityMapper.insert(row);
        return row.getId();
    }

    private int insertCredential(String handle) {
        WebauthnCredentialRow row = new WebauthnCredentialRow();
        row.setCredentialId(Bytes.random().getBytes());
        row.setUserEntityUserId(handle);
        row.setCredentialType("public-key");
        row.setPublicKey(Bytes.random().getBytes());
        row.setLabel("Provenance fixture");
        row.setCreatedAt(Instant.now());
        credentialMapper.insert(row);
        return row.getId();
    }

    private boolean isFounder(int organizationId, int userId) {
        Boolean founder = jdbcTemplate.queryForObject(
            "SELECT founder FROM org_member WHERE org_id = ? AND user_id = ?",
            Boolean.class, organizationId, userId);
        return Boolean.TRUE.equals(founder);
    }

    private List<Coverage> coverage(int credentialRowId) {
        return jdbcTemplate.query(
            "SELECT org_id, source FROM privileged_credential_attestation"
                + " WHERE credential_row_id = ? ORDER BY org_id",
            (resultSet, rowNumber) -> new Coverage(resultSet.getInt("org_id"), resultSet.getString("source")),
            credentialRowId);
    }

    private String assurance(int credentialRowId) {
        return jdbcTemplate.queryForObject(
            "SELECT privileged_assurance FROM webauthn_credential WHERE id = ?", String.class, credentialRowId);
    }

    private java.sql.Timestamp assuredAt(int credentialRowId) {
        return jdbcTemplate.queryForObject(
            "SELECT privileged_assured_at FROM webauthn_credential WHERE id = ?",
            java.sql.Timestamp.class, credentialRowId);
    }

    private TransactionTemplate readCommitted() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the held transaction was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while holding the account row", interrupted);
        }
    }
}
