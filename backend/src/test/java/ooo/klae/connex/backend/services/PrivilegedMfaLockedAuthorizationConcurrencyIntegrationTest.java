package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.config.PrivilegedMfaEnforcementFilter;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.PrivilegedMfaRequestPolicy;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter;
import ooo.klae.connex.backend.publicapi.ApiCredentialPrincipal;
import ooo.klae.connex.backend.publicapi.ApiCredentialService;
import ooo.klae.connex.backend.publicapi.ApiRateLimiter;
import ooo.klae.connex.backend.publicapi.ApiScope;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.util.ClientIpResolver;
import tools.jackson.databind.ObjectMapper;

/** Real filter admission, old snapshots and current authority are exercised on committed fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "connex.security.privileged-mfa.enforced=true",
    "connex.security.privileged-mfa.attestation-enforced=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PrivilegedMfaLockedAuthorizationConcurrencyIntegrationTest {
    @Autowired private WorkspaceService workspaceService;
    @Autowired private ReportService reportService;
    @Autowired private ReportMapper reportMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PrivilegedAccountService privilegedAccountService;
    @Autowired private PrivilegedMfaProperties properties;
    @Autowired private AuditService auditService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private DenialAuditRateLimiter limiter;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Organization organization;
    private Workspace workspace;
    private User actor;
    private WorkspaceRole customRole;
    private ReportDefinition report;
    private MockMvc mvc;
    private TransactionTemplate transaction;
    private final CountDownLatch snapshotWarmed = new CountDownLatch(1);
    private final CountDownLatch startLocking = new CountDownLatch(1);
    private final CountDownLatch filterAdmitted = new CountDownLatch(1);
    private final CountDownLatch startTransaction = new CountDownLatch(1);
    private boolean waitBeforeLocking;
    private boolean waitBeforeTransaction;
    private boolean expectOrdinarySnapshot;
    private boolean patFixture;

    @BeforeEach
    void setUp() {
        clearContext();
        String suffix = UUID.randomUUID().toString();
        organization = new Organization();
        organization.setName("Locked MFA " + suffix);
        organization.setSlug("locked-mfa-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Before");
        workspace.setSlug("locked-mfa-" + suffix);
        workspaceMapper.insert(workspace);
        actor = new User();
        actor.setUsername("locked_mfa_" + suffix);
        actor.setDisplayName("Locked MFA Actor");
        actor.setEmail(suffix + "@locked-mfa.example.com");
        actor.setTimezone("UTC");
        actor.setPasswordHash("{noop}fixture-unused");
        userMapper.insert(actor);
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Filter browser = new PrivilegedMfaEnforcementFilter(properties, privilegedAccountService,
            sessionSecurityService, auditService, limiter, new ClientIpResolver(""));
        mvc = MockMvcBuilders.standaloneSetup(new MutationController())
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), tenantContext))
            .addFilters(browser).build();
    }

    @AfterEach
    void cleanUp() {
        clearContext();
        if (workspace != null) {
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (actor != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        }
        if (organization != null) {
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "snapshot", "shared", "mutex", "member", "authorization",
        "administrator", "administratorWithAssignedRoles", "approval", "identity", "invite", "role"})
    void builtInPromotionCommittedWhileMutationWaitsIsRefused(String helper) throws Exception {
        racePromotion(helper, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "snapshot", "shared", "mutex", "member", "authorization", "approval",
        "administrator", "administratorWithAssignedRoles", "reportDelete"})
    void customPermissionGrantCommittedWhileMutationWaitsIsRefused(String helper) throws Exception {
        assignOrdinaryCustomRole();
        if ("reportDelete".equals(helper)) {
            createOwnReport();
        }
        racePromotion(helper, true, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "admin"})
    void sameOrganizationPromotionAfterTheFilterAndBeforeTheTransactionIsRefused(String role) throws Exception {
        waitBeforeTransaction = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> mutation = executor.submit(() -> {
                authenticate();
                try {
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/locked-mfa-fixture").param("helper", "snapshot")).andReturn().getResponse();
                    assertEquals(403, response.getStatus(), response.getContentAsString());
                    assertTrue(response.getContentAsString().contains("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));
                } finally {
                    clearContext();
                }
                return null;
            });
            assertTrue(filterAdmitted.await(30, TimeUnit.SECONDS));
            assertEquals(1L, snapshotWarmed.getCount());
            grantOrganizationRole(role);
            startTransaction.countDown();
            mutation.get(30, TimeUnit.SECONDS);
        } finally {
            startTransaction.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals("Before", workspaceMapper.getActiveById(workspace.getId()).getName());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'auth.mfa.policy.denied'",
            Integer.class, actor.getId()));
    }

    @Test
    void organizationPromotionAfterTheRepeatableReadSnapshotRemainsOutsideTheFence() throws Exception {
        waitBeforeLocking = true;
        expectOrdinarySnapshot = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> mutation = executor.submit(() -> {
                authenticate();
                try {
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/locked-mfa-fixture").param("helper", "snapshot")).andReturn().getResponse();
                    assertEquals(200, response.getStatus(), response.getContentAsString());
                } finally {
                    clearContext();
                }
                return null;
            });
            assertTrue(snapshotWarmed.await(30, TimeUnit.SECONDS));
            grantOrganizationRole("admin");
            startLocking.countDown();
            mutation.get(30, TimeUnit.SECONDS);
        } finally {
            startLocking.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals("After", workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @Test
    void newlyPrivilegedActorWithAnUnapprovedPasskeyGetsTheAttestationShape() throws Exception {
        enroll(false);
        racePromotion("snapshot", false, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void patWriteFixtureRechecksAfterAuthenticationTransactionEnds(boolean attestation) throws Exception {
        patFixture = true;
        if (attestation) {
            enroll(false);
        }
        racePromotion("snapshot", false, attestation);
    }

    private void racePromotion(String helper, boolean custom, boolean attestation) throws Exception {
        expectOrdinarySnapshot = true;
        CountDownLatch promotionHeld = new CountDownLatch(1);
        CountDownLatch releasePromotion = new CountDownLatch(1);
        AtomicLong promotionConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> promotion = executor.submit(() -> transaction.executeWithoutResult(status -> {
                promotionConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                if (!custom) {
                    assertNotNull(userMapper.lockById(actor.getId()));
                }
                assertNotNull(workspaceMapper.lockWorkspace(workspace.getId()));
                if (custom) {
                    assertNotNull(roleMapper.lockRole(workspace.getId(), customRole.getId()));
                    roleMapper.insertPermissions(workspace.getId(), customRole.getId(), List.of(Permission.TEAM_MANAGE.name()));
                } else {
                    workspaceMapper.lockAuthorizationMembership(workspace.getId(), actor.getId());
                    workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
                }
                promotionHeld.countDown();
                await(releasePromotion);
            }));
            assertTrue(promotionHeld.await(30, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> {
                authenticate();
                try {
                    var response = patFixture ? invokePatWriteFixture(helper)
                        : mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/locked-mfa-fixture").param("helper", helper)
                            .contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
                    assertEquals(patFixture ? 401 : 403, response.getStatus(), response.getContentAsString());
                    var body = new ObjectMapper().readTree(response.getContentAsByteArray());
                    String code = attestation ? "PRIVILEGED_MFA_ATTESTATION_REQUIRED"
                        : "PRIVILEGED_MFA_ENROLLMENT_REQUIRED";
                    var error = patFixture ? body.path("error") : body;
                    assertEquals(patFixture ? code.toLowerCase(java.util.Locale.ROOT) : code,
                        error.path("code").stringValue());
                    assertEquals(attestation ? "A passkey must be approved before this privileged account can continue"
                        : "A passkey must be enrolled before this privileged account can continue",
                        error.path("message").stringValue());
                } finally {
                    clearContext();
                }
                return null;
            });
            assertTrue(snapshotWarmed.await(30, TimeUnit.SECONDS));
            awaitBlockedBy(promotionConnection.get(), mutation);
            releasePromotion.countDown();
            promotion.get(30, TimeUnit.SECONDS);
            mutation.get(30, TimeUnit.SECONDS);
        } finally {
            releasePromotion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals("Before", workspaceMapper.getActiveById(workspace.getId()).getName());
        if (report != null) {
            assertNotNull(reportMapper.getDefinition(workspace.getId(), report.getId()));
        }
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_log WHERE entity_type = 'user' AND entity_id = ?
              AND action = 'auth.mfa.policy.denied' AND workspace_id IS NULL AND org_id IS NULL
            """, Integer.class, actor.getId()));
    }

    @Test
    void promotionInTheReverseOrderWaitsUntilTheAdmittedMutationCommits() throws Exception {
        CountDownLatch mutationHeld = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        AtomicLong mutationConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> mutation = executor.submit(() -> {
                bindRequest();
                try {
                    transaction.executeWithoutResult(status -> {
                        mutationConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        authorize("snapshot");
                        mutationHeld.countDown();
                        await(releaseMutation);
                        workspaceMapper.updateIdentity(workspace.getId(), "After", null);
                    });
                } finally {
                    clearContext();
                }
            });
            assertTrue(mutationHeld.await(30, TimeUnit.SECONDS));
            Future<?> promotion = executor.submit(() -> transaction.executeWithoutResult(status -> {
                userMapper.lockById(actor.getId());
                workspaceMapper.lockWorkspace(workspace.getId());
                workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
            }));
            awaitBlockedBy(mutationConnection.get(), promotion);
            releaseMutation.countDown();
            mutation.get(30, TimeUnit.SECONDS);
            promotion.get(30, TimeUnit.SECONDS);
        } finally {
            releaseMutation.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals("After", workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @Test
    void deletedCredentialInAnOldSnapshotCannotSatisfyANewPromotion() throws Exception {
        enroll(true);
        waitBeforeLocking = true;
        expectOrdinarySnapshot = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> mutation = executor.submit(() -> {
                authenticate();
                try {
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/locked-mfa-fixture").param("helper", "snapshot")).andReturn().getResponse();
                    assertEquals(403, response.getStatus(), response.getContentAsString());
                    assertTrue(response.getContentAsString().contains("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));
                } finally {
                    clearContext();
                }
                return null;
            });
            assertTrue(snapshotWarmed.await(30, TimeUnit.SECONDS));
            transaction.executeWithoutResult(status -> {
                userMapper.lockById(actor.getId());
                jdbc.update("DELETE c FROM webauthn_credential c JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id WHERE e.user_id = ?", actor.getId());
                workspaceMapper.lockWorkspace(workspace.getId());
                workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
            });
            startLocking.countDown();
            mutation.get(30, TimeUnit.SECONDS);
        } finally {
            startLocking.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals("Before", workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @Test
    void freshSufficiencyStatementDiscardsAnEarlierMyBatisAnswer() {
        enroll(false);
        transaction.executeWithoutResult(status -> {
            userMapper.lockByIdForShare(actor.getId());
            workspaceMapper.lockActiveWorkspaceForShare(workspace.getId());
            assertEquals(List.of(false), userMapper.lockedWorkspaceMfaPasskeySufficiency(actor.getId(), workspace.getId(), true));
            jdbc.update("UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS', privileged_assured_at = UTC_TIMESTAMP() WHERE user_entity_user_id = ?", actor.getUsername());
            assertEquals(List.of(true), userMapper.lockedWorkspaceMfaPasskeySufficiency(actor.getId(), workspace.getId(), true));
        });
    }

    @Test
    void coverageBelongsToTheAccountAndTheAuthorizingOrganization() {
        enroll(false);
        Organization other = new Organization();
        other.setName("Other MFA org");
        other.setSlug("other-mfa-" + UUID.randomUUID());
        organizationMapper.insert(other);
        try {
            transaction.executeWithoutResult(status -> {
                userMapper.lockByIdForShare(actor.getId());
                workspaceMapper.lockActiveWorkspaceForShare(workspace.getId());
                int credential = jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE user_entity_user_id = ?",
                    Integer.class, actor.getUsername());
                jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANTOR')",
                    credential, other.getId());
                assertEquals(List.of(false), userMapper.lockedWorkspaceMfaPasskeySufficiency(actor.getId(), workspace.getId(), true));
                jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANTOR')",
                    credential, organization.getId());
                assertEquals(List.of(true), userMapper.lockedWorkspaceMfaPasskeySufficiency(actor.getId(), workspace.getId(), true));
            });
        } finally {
            jdbc.update("DELETE FROM organization WHERE id = ?", other.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void credentialContentionRetriesOnlyForLocallyPrivilegedActors(boolean privileged) throws Exception {
        enroll(true);
        if (privileged) {
            workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        }
        CountDownLatch credentialHeld = new CountDownLatch(1);
        CountDownLatch releaseCredential = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> assertion = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE user_entity_user_id = ? FOR UPDATE",
                    Integer.class, actor.getUsername());
                credentialHeld.countDown();
                await(releaseCredential);
            }));
            assertTrue(credentialHeld.await(30, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> {
                bindRequest();
                try {
                    if (privileged) {
                        assertThrows(ConflictException.class, () -> transaction.executeWithoutResult(status -> authorize("snapshot")));
                    } else {
                        transaction.executeWithoutResult(status -> authorize("snapshot"));
                    }
                } finally {
                    clearContext();
                }
            });
            mutation.get(20, TimeUnit.SECONDS);
            assertFalse(assertion.isDone());
            releaseCredential.countDown();
            assertion.get(30, TimeUnit.SECONDS);
        } finally {
            releaseCredential.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void admissionDoesNotOpenAConsistentSnapshotBeforeAnAggregateMutex() throws Exception {
        enroll(true);
        workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        Organization probe = new Organization();
        probe.setName("Before snapshot probe");
        probe.setSlug("snapshot-probe-" + UUID.randomUUID());
        organizationMapper.insert(probe);
        bindRequest();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                authorize("snapshot");
                Future<?> writer = executor.submit(() -> jdbc.update(
                    "UPDATE organization SET name = 'Committed after authorization' WHERE id = ?", probe.getId()));
                try {
                    writer.get(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                assertEquals("Committed after authorization", jdbc.queryForObject(
                    "SELECT name FROM organization WHERE id = ?", String.class, probe.getId()));
            });
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            jdbc.update("DELETE FROM organization WHERE id = ?", probe.getId());
        }
    }

    @Test
    void attestationDisabledDoesNotLockApprovalCoverage() throws Exception {
        enroll(false);
        int credential = jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE user_entity_user_id = ?",
            Integer.class, actor.getUsername());
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANTOR')",
            credential, organization.getId());
        CountDownLatch coverageHeld = new CountDownLatch(1);
        CountDownLatch releaseCoverage = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> coverage = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT org_id FROM privileged_credential_attestation WHERE credential_row_id = ? AND org_id = ? FOR UPDATE",
                    Integer.class, credential, organization.getId());
                coverageHeld.countDown();
                await(releaseCoverage);
            }));
            assertTrue(coverageHeld.await(30, TimeUnit.SECONDS));
            transaction.executeWithoutResult(status -> {
                userMapper.lockByIdForShare(actor.getId());
                workspaceMapper.lockActiveWorkspaceForShare(workspace.getId());
                assertEquals(List.of(true), userMapper.lockedWorkspaceMfaPasskeySufficiency(actor.getId(), workspace.getId(), false));
            });
            assertFalse(coverage.isDone());
            releaseCoverage.countDown();
            coverage.get(30, TimeUnit.SECONDS);
        } finally {
            releaseCoverage.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void currentCredentialReadDoesNotLockAnotherAccountsCredential() throws Exception {
        enroll(true);
        workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        User other = new User();
        other.setUsername("other_mfa_" + UUID.randomUUID());
        other.setDisplayName("Other credential owner");
        other.setEmail(other.getUsername() + "@locked-mfa.example.com");
        other.setTimezone("UTC");
        other.setPasswordHash("{noop}fixture-unused");
        userMapper.insert(other);
        jdbc.update("INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
            other.getUsername(), other.getId(), other.getUsername(), other.getDisplayName());
        jdbc.update("INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key) VALUES (?, ?, ?)",
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), other.getUsername(), new byte[] {1});
        int otherCredential = jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE user_entity_user_id = ?",
            Integer.class, other.getUsername());
        CountDownLatch credentialHeld = new CountDownLatch(1);
        CountDownLatch releaseCredential = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> assertion = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE id = ? FOR UPDATE", Integer.class, otherCredential);
                credentialHeld.countDown();
                await(releaseCredential);
            }));
            assertTrue(credentialHeld.await(30, TimeUnit.SECONDS));
            bindRequest();
            transaction.executeWithoutResult(status -> authorize("snapshot"));
            assertFalse(assertion.isDone());
            releaseCredential.countDown();
            assertion.get(30, TimeUnit.SECONDS);
        } finally {
            releaseCredential.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            jdbc.update("DELETE FROM app_user WHERE id = ?", other.getId());
        }
    }

    @Test
    void filtersStillRejectCoverageSplitAcrossDifferentCredentials() throws Exception {
        enroll(false);
        workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        int first = jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE user_entity_user_id = ?",
            Integer.class, actor.getUsername());
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANTOR')",
            first, organization.getId());
        Organization other = new Organization();
        other.setName("Other privileged organization");
        other.setSlug("split-mfa-" + UUID.randomUUID());
        organizationMapper.insert(other);
        try {
            jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'admin')", other.getId(), actor.getId());
            byte[] secondId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key) VALUES (?, ?, ?)",
                secondId, actor.getUsername(), new byte[] {1});
            int second = jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE credential_id = ?", Integer.class, secondId);
            jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source) VALUES (?, ?, 'GRANTOR')",
                second, other.getId());
            assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, privilegedAccountService.enforcedStanding(actor.getId()));
            authenticate();
            var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/locked-mfa-fixture").param("helper", "snapshot")).andReturn().getResponse();
            assertEquals(403, response.getStatus(), response.getContentAsString());
            assertTrue(response.getContentAsString().contains("PRIVILEGED_MFA_ATTESTATION_REQUIRED"));
            assertEquals(1L, snapshotWarmed.getCount());
            assertEquals("Before", workspaceMapper.getActiveById(workspace.getId()).getName());
        } finally {
            jdbc.update("DELETE FROM organization WHERE id = ?", other.getId());
        }
    }

    @Test
    void freshOrganizationPrivilegeStatementDiscardsAnEarlierMyBatisAnswer() {
        transaction.executeWithoutResult(status -> {
            assertFalse(userMapper.hasWorkspaceOrganizationPrivilege(actor.getId(), workspace.getId()));
            jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'admin')",
                organization.getId(), actor.getId());
            assertTrue(userMapper.hasWorkspaceOrganizationPrivilege(actor.getId(), workspace.getId()));
            jdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", organization.getId());
            assertFalse(userMapper.hasWorkspaceOrganizationPrivilege(actor.getId(), workspace.getId()));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrator", "administratorWithAssignedRoles", "reportDelete"})
    void ordinaryCustomOverlaysRemainAdmittedByAdministratorHelpers(String helper) throws Exception {
        assignOrdinaryCustomRole();
        if ("reportDelete".equals(helper)) {
            createOwnReport();
        }
        authenticate();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/locked-mfa-fixture").param("helper", helper)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("After", workspaceMapper.getActiveById(workspace.getId()).getName());
        if (report != null) {
            assertNull(reportMapper.getDefinition(workspace.getId(), report.getId()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryAndSufficientlyEnrolledPrivilegedActorsCanMutate(boolean privileged) throws Exception {
        if (privileged) {
            enroll(true);
            workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        }
        authenticate();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/locked-mfa-fixture").param("helper", "snapshot")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("After", workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/webauthn/register", "/api/workspaces/123/leave", "/api/document-acceptance/exchange"})
    void enrollmentSafeExitAndRecipientExemptionsReachLockedAuthorization(String path) throws Exception {
        workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        authenticate();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post(path).param("helper", "snapshot")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("After", workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @Test
    void backgroundAuthorizationKeepsItsExistingBehaviorWithoutAnHttpDispatch() {
        workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        authenticate();
        transaction.executeWithoutResult(status -> {
            authorize("snapshot");
            workspaceMapper.updateIdentity(workspace.getId(), "After", null);
        });
        assertEquals("After", workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @Test
    void refusalAuditRunsAfterTheAccountRootIsReleasedEvenWithAQueuedWriter() throws Exception {
        workspaceMapper.updateMemberRole(workspace.getId(), actor.getId(), "owner");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch writerHeld = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        Future<?>[] writer = new Future<?>[1];
        bindRequest();
        try {
            assertThrows(PrivilegedMfaEnrollmentRequiredException.class, () -> transaction.executeWithoutResult(status -> {
                long holder = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                userMapper.lockByIdForShare(actor.getId());
                writer[0] = executor.submit(() -> transaction.executeWithoutResult(writerStatus -> {
                    userMapper.lockById(actor.getId());
                    writerHeld.countDown();
                    releaseWriter.countDown();
                }));
                try {
                    awaitBlockedBy(holder, writer[0]);
                    assertThrows(PrivilegedMfaEnrollmentRequiredException.class, () -> authorize("snapshot"));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'auth.mfa.policy.denied'", Integer.class, actor.getId()));
                    assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
                    assertFalse(writer[0].isDone());
                    throw new PrivilegedMfaEnrollmentRequiredException();
                } catch (Exception failure) {
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException(failure);
                }
            }));
            assertTrue(writerHeld.await(30, TimeUnit.SECONDS));
            assertTrue(releaseWriter.await(30, TimeUnit.SECONDS));
            writer[0].get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'auth.mfa.policy.denied'", Integer.class, actor.getId()));
    }

    private void authorize(String helper) {
        int workspaceId = workspace.getId();
        int actorId = actor.getId();
        Map<Integer, Set<Permission>> required = Map.of(actorId, Set.of(Permission.PERSON_UPDATE));
        switch (helper) {
            case "plain" -> workspaceService.lockAndRequirePermissions(workspaceId, required);
            case "snapshot" -> workspaceService.lockAndRequirePermissionsSnapshot(workspaceId, required);
            case "shared" -> workspaceService.lockAndRequirePermissionsSnapshotForShare(workspaceId, required);
            case "mutex" -> workspaceService.lockAndRequirePermissionsWithWorkspaceMutex(workspaceId, required);
            case "member" -> workspaceService.lockedMemberPermissionsAndMemberships(workspaceId, actorId, Set.of());
            case "authorization" -> workspaceService.lockedMemberAuthorization(workspaceId, actorId);
            case "administrator" -> assertEquals(customRole == null,
                workspaceService.isLockedBuiltInAdministrator(workspaceId, actorId));
            case "administratorWithAssignedRoles" -> assertEquals(customRole == null, workspaceService
                .isLockedBuiltInAdministratorWithAssignedRoles(workspaceId, actorId, actorId));
            case "reportDelete" -> reportService.delete(report.getId());
            case "approval" -> workspaceService.lockApprovalMutationMemberships(workspaceId, actorId, Set.of());
            case "identity" -> ReflectionTestUtils.invokeMethod(workspaceService, "lockWorkspaceIdentityMutation", workspaceId, actorId);
            case "invite" -> workspaceService.lockInviteGrantAuthorization(workspaceId, actorId, null, "member");
            case "role" -> workspaceService.lockRoleCreationAuthorization(workspaceId, actorId, Set.of(Permission.PERSON_UPDATE));
            default -> throw new IllegalArgumentException("Unknown helper");
        }
    }

    private MockHttpServletResponse invokePatWriteFixture(String helper) throws Exception {
        ApiCredentialService credentials = mock(ApiCredentialService.class);
        ApiRateLimiter apiLimiter = mock(ApiRateLimiter.class);
        TenantCatalogResolver resolver = mock(TenantCatalogResolver.class);
        String token = "cnx_pat_" + "a".repeat(43);
        var routing = new ApiCredentialService.RoutingBinding(17, workspace.getId(), organization.getId(), actor.getId(), "a".repeat(64));
        var principal = new ApiCredentialPrincipal(17, actor.getId(), workspace.getId(), organization.getId(),
            "Locked fixture", Set.of(ApiScope.CRM_READ), Set.of(ApiScope.CRM_READ), LocalDateTime.now().plusDays(1));
        when(credentials.resolveRoutingBinding(token)).thenReturn(Optional.of(routing));
        when(credentials.authenticate(routing)).thenReturn(Optional.of(new ApiCredentialService.AuthenticatedCredential(actor, principal)));
        when(apiLimiter.acquireBeforeAuthentication(any(), any())).thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        when(apiLimiter.acquire(17)).thenReturn(new ApiRateLimiter.Decision(true, 10, 9, 60, 0));
        when(resolver.resolveCatalog(organization.getId())).thenReturn(jdbc.queryForObject("SELECT DATABASE()", String.class));
        var filter = new ApiCredentialAuthenticationFilter(credentials, apiLimiter, new ObjectMapper(), properties,
            privilegedAccountService, auditService, limiter, new ClientIpResolver(""), resolver, tenantContext,
            request -> ApiCredentialAuthenticationFilter.RouteTransactionMode.WRITE, transactionManager);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/locked-mfa-fixture");
        request.addHeader("Authorization", "Bearer " + token);
        request.setRemoteAddr("192.0.2.91");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            new MutationController().mutate(helper);
        });
        return response;
    }

    private void assignOrdinaryCustomRole() {
        customRole = new WorkspaceRole();
        customRole.setWorkspaceId(workspace.getId());
        customRole.setName("Ordinary role");
        roleMapper.insertRole(customRole);
        roleMapper.insertPermissions(workspace.getId(), customRole.getId(),
            List.of(Permission.PERSON_UPDATE.name(), Permission.REPORT_DELETE.name()));
        workspaceMapper.setMemberCustomRole(workspace.getId(), actor.getId(), customRole.getId());
    }

    private void createOwnReport() {
        report = new ReportDefinition();
        report.setWorkspaceId(workspace.getId());
        report.setName("Unscheduled own report");
        report.setCadence("weekly");
        report.setConfigJson("{}");
        report.setCreatedBy(actor.getId());
        reportMapper.insertDefinition(report);
    }

    private void grantOrganizationRole(String role) {
        transaction.executeWithoutResult(status -> {
            userMapper.lockByIdForShare(actor.getId());
            organizationMapper.lockById(organization.getId());
            jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, ?)",
                organization.getId(), actor.getId(), role);
        });
    }

    private void enroll(boolean approved) {
        jdbc.update("INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
            actor.getUsername(), actor.getId(), actor.getUsername(), actor.getDisplayName());
        jdbc.update("INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key, privileged_assurance, privileged_assured_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), actor.getUsername(), new byte[] {1},
            approved ? "BREAK_GLASS" : null, approved ? LocalDateTime.now() : null);
    }

    private void bindRequest() {
        authenticate();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/locked-mfa-fixture");
        request.setRemoteAddr("192.0.2.91");
        PrivilegedMfaRequestPolicy.bindBrowser(request, actor.getId(), request.getRequestURI());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), actor.getId(), "session", null);
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(45, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private void awaitBlockedBy(long blockingConnection, Future<?> waiter) throws Exception {
        awaitBlockedBy(blockingConnection, waiter, null);
    }

    private void awaitBlockedBy(long blockingConnection, Future<?> waiter, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                waiter.get();
                throw new AssertionError("Expected a real authorization lock wait");
            }
            Integer waits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                  ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads blocking ON blocking.THREAD_ID = waits.BLOCKING_THREAD_ID
                WHERE requested.OBJECT_SCHEMA = DATABASE() AND blocking.PROCESSLIST_ID = ?
                  AND (? IS NULL OR requested.OBJECT_NAME = ?)
                """, Integer.class, blockingConnection, table, table);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No observed MySQL lock wait");
    }

    @RestController
    class MutationController {
        @PostMapping({"/api/locked-mfa-fixture", "/api/auth/webauthn/register", "/api/workspaces/{id}/leave",
            "/api/document-acceptance/exchange"})
        void mutate(@RequestParam String helper) {
            if (waitBeforeTransaction) {
                filterAdmitted.countDown();
                await(startTransaction);
            }
            transaction.executeWithoutResult(status -> {
                var standing = userMapper.privilegedMfaStanding(actor.getId());
                assertNotNull(standing);
                if (expectOrdinarySnapshot) {
                    assertFalse(standing.privileged());
                }
                snapshotWarmed.countDown();
                if (waitBeforeLocking) {
                    await(startLocking);
                }
                authorize(helper);
                workspaceMapper.updateIdentity(workspace.getId(), "After", null);
            });
        }
    }
}
