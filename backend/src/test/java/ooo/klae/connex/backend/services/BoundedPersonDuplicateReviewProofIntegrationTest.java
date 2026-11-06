package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.exceptions.DuplicateReviewException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises issued bounded-person proofs through committed creation transactions and real database locks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BoundedPersonDuplicateReviewProofIntegrationTest {
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private PersonService personService;
    @Autowired private DuplicatePreflightService preflightService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;
    @Autowired private PlatformTransactionManager transactionManager;

    private Organization organization;
    private Workspace workspace;
    private Workspace otherWorkspace;
    private User actor;
    private User otherActor;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        organization = new Organization();
        organization.setName("Bounded proof " + suffix);
        organization.setSlug("bounded-proof-" + suffix);
        organizationMapper.insert(organization);
        workspace = workspace("primary-" + suffix);
        otherWorkspace = workspace("other-" + suffix);
        actor = member("first-" + suffix);
        otherActor = member("second-" + suffix);
        seedBroadMatches(workspace);
        seedBroadMatches(otherWorkspace);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        for (Workspace fixture : new Workspace[] {workspace, otherWorkspace}) {
            if (fixture == null) {
                continue;
            }
            int workspaceId = fixture.getId();
            jdbcTemplate.update("DELETE FROM deal_duplicate_review_proof WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM duplicate_review_decision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM identity_collision WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person_identity WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        for (User principal : new User[] {actor, otherActor}) {
            if (principal != null) {
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", principal.getId());
            }
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void clientCalculatedFingerprintCannotAuthorizeCreationWithoutReview() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : List.of(Integer.toString(workspace.getId()), "person", "true", "true", "true", "jordan lee")) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        String forged = HexFormat.of().formatHex(digest.digest());
        assertThrows(DuplicateReviewException.class, () -> create(actor, workspace, person(), forged));
        assertEquals(51, countPeople());
    }

    @Test
    void proofIsPreservedDuringFinalReviewButCannotCrossActorWorkspaceOrInput() {
        DuplicatePreflightResponse review = review(null);
        assertTrue(review.allowSeparateCreation());
        assertTrue(review.candidates().isEmpty());
        assertEquals(review.reviewToken(), review(review.reviewToken()).reviewToken());
        assertThrows(DuplicateReviewException.class,
            () -> create(otherActor, workspace, person(), review.reviewToken()));
        assertThrows(DuplicateReviewException.class,
            () -> create(actor, otherWorkspace, person(), review.reviewToken()));
        Person changed = person();
        changed.setEmail("changed@example.test");
        assertThrows(DuplicateReviewException.class,
            () -> create(actor, workspace, changed, review.reviewToken()));
        assertEquals(51, countPeople());
        create(actor, workspace, person(), review.reviewToken());
        assertEquals(52, countPeople());
    }

    @Test
    void expiredProofIsRefusedAndRequiresFreshReview() {
        String proof = review(null).reviewToken();
        jdbcTemplate.update("UPDATE deal_duplicate_review_proof SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE workspace_id = ?",
            workspace.getId());
        assertThrows(DuplicateReviewException.class, () -> create(actor, workspace, person(), proof));
        assertEquals(51, countPeople());
        String refreshed = review(proof).reviewToken();
        assertNotEquals(proof, refreshed);
        create(actor, workspace, person(), refreshed);
        assertEquals(52, countPeople());
    }

    @Test
    void consumedProofCannotBeReplayedEvenAfterTheCreatedRecordIsDeleted() {
        String proof = review(null).reviewToken();
        Person created = create(actor, workspace, person(), proof);
        assertThrows(DuplicateReviewException.class, () -> create(actor, workspace, person(), proof));
        jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ? AND id = ?", workspace.getId(), created.getId());
        assertEquals(51, countPeople());
        assertThrows(DuplicateReviewException.class, () -> create(actor, workspace, person(), proof));
    }

    @Test
    void newMatchesOutsideTheRenderedPrefixInvalidateAnUnconsumedProof() {
        jdbcTemplate.update("UPDATE person SET name = 'Jordan Lee' WHERE workspace_id = ?", workspace.getId());
        DuplicatePreflightResponse reviewed = review(null);
        inTransaction(actor, workspace, () -> personService.create(person()));
        assertEquals(reviewed.candidates(), review(null).candidates());
        assertThrows(DuplicateReviewException.class,
            () -> create(actor, workspace, person(), reviewed.reviewToken()));
    }

    @Test
    void unsharedWorkspaceChangesDoNotInvalidateTheVisibleNameProof() {
        String proof = review(null).reviewToken();
        inTransaction(actor, otherWorkspace, () -> personService.create(person()));
        create(actor, workspace, person(), proof);
        assertEquals(52, countPeople());
    }

    @Test
    void rolledBackCreateDoesNotConsumeTheProof() {
        String proof = review(null).reviewToken();
        withContext(actor, workspace, () -> {
            TransactionTemplate transaction = transaction();
            transaction.executeWithoutResult(status -> {
                personService.createReviewed(person(), proof);
                status.setRollbackOnly();
            });
            return true;
        });
        assertEquals(51, countPeople());
        create(actor, workspace, person(), proof);
        assertEquals(52, countPeople());
    }

    @Test
    void twoConcurrentCreatesWithOneProofProduceOneRecordAndOneRefusal() throws Exception {
        jdbcTemplate.update("UPDATE person SET name = 'Jordan Lee' WHERE workspace_id = ?", workspace.getId());
        String proof = review(null).reviewToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> attempt = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    create(actor, workspace, person(), proof);
                    return "created";
                } catch (DuplicateReviewException exception) {
                    return "refused";
                }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            assertEquals(List.of("created", "refused"), List.of(
                first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream().sorted().toList());
        } finally {
            start.countDown();
        }
        assertEquals(52, countPeople());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_duplicate_review_proof WHERE workspace_id = ?", Integer.class, workspace.getId()));
    }

    private void seedBroadMatches(Workspace target) {
        inTransaction(actor, target, () -> {
            for (int index = 0; index < 51; index++) {
                Person person = person();
                person.setWorkspaceId(target.getId());
                person.setName("Jordan Lee Group " + index);
                personMapper.insert(person);
            }
            return true;
        });
    }

    private DuplicatePreflightResponse review(String proof) {
        return inTransaction(actor, workspace, () -> preflightService.preflightPerson(
            new PersonDuplicatePreflightRequest("Jordan Lee", List.of(), List.of(), proof)));
    }

    private Person create(User principal, Workspace target, Person person, String proof) {
        return inTransaction(principal, target, () -> personService.createReviewed(person, proof));
    }

    private int countPeople() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ?", Integer.class, workspace.getId()));
    }

    private Workspace workspace(String suffix) {
        Workspace created = new Workspace();
        created.setOrgId(organization.getId());
        created.setName("Proof " + suffix);
        created.setSlug("proof-" + suffix);
        workspaceMapper.insert(created);
        return created;
    }

    private User member(String suffix) {
        User member = new User();
        member.setUsername("proof_" + suffix);
        member.setEmail(suffix + "@example.test");
        member.setDisplayName("Proof " + suffix);
        member.setPasswordHash("fixture");
        member.setTimezone("UTC");
        userMapper.insert(member);
        workspaceMapper.addMember(workspace.getId(), member.getId(), "member");
        workspaceMapper.addMember(otherWorkspace.getId(), member.getId(), "member");
        return member;
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private <T> T inTransaction(User principal, Workspace target, Callable<T> work) {
        return withContext(principal, target, () -> Objects.requireNonNull(transaction().execute(status -> call(work))));
    }

    private <T> T withContext(User principal, Workspace target, Callable<T> work) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        request.getSession().setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, principal.getId());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tenantContext.set(target.getId(), organization.getId(), principal.getId(), "member", null);
        try {
            return call(work);
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
            tenantContext.clear();
        }
    }

    private static <T> T call(Callable<T> work) {
        try {
            return work.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Bounded proof test failed", exception);
        }
    }

    private static Person person() {
        Person person = new Person();
        person.setName("Jordan Lee");
        return person;
    }
}
