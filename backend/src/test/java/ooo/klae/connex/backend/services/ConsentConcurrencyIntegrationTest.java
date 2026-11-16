package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyService;
import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.exceptions.StaleRecordException;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Races committed consent snapshots and independent privacy patches through their actual SQL fences. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConsentConcurrencyIntegrationTest {
    @Autowired private ConsentService consentService;
    @Autowired private ConsentMapper consentMapper;
    @Autowired private PersonService personService;
    @Autowired private PersonMapper personMapper;
    @Autowired private OrganizationMapper organizations;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AuditService audits;
    @MockitoBean private SessionSecurityService sessionSecurity;
    @MockitoBean private AiAgentPrivacyService privacy;
    private Organization organization;
    private Workspace workspace;
    private User actor;
    private Person person;

    @BeforeEach
    void createCommittedFixture() {
        String key = UUID.randomUUID().toString();
        organization = new Organization();
        organization.setName("Consent concurrency");
        organization.setSlug("consent-" + key);
        organizations.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Consent concurrency");
        workspace.setSlug("consent-" + key);
        workspaces.insert(workspace);
        actor = new User();
        actor.setUsername("consent-" + key);
        actor.setDisplayName("Consent concurrency");
        actor.setEmail(key + "@example.invalid");
        actor.setPasswordHash("fixture");
        actor.setTimezone("UTC");
        users.insert(actor);
        workspaces.addMember(workspace.getId(), actor.getId(), "owner");
        person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Consent concurrency");
        personMapper.insert(person);
    }

    @AfterEach
    void removeCommittedFixture() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (workspace != null) {
            jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (actor != null) jdbc.update("DELETE FROM app_user WHERE id = ?", actor.getId());
        if (organization != null) jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
    }

    @Test
    void twoManualWritersWithTheSameVersionHaveOneWinnerAndOneHistoryEvent() throws Exception {
        long version = asActor(() -> consentService.setForPersonConditionally(person.getId(), request("unknown", 0)).version());
        assertOneWinner(version);
        assertEquals(version + 1, current().getVersion());
        assertEquals(2, eventCount());
    }

    @Test
    void twoAbsentSnapshotWritersHaveExactlyOneInsertWinner() throws Exception {
        assertOneWinner(0);
        assertEquals(1, current().getVersion());
        assertEquals(1, eventCount());
    }

    @Test
    void threeAbsentWritersReturnTwoConflictsWithoutUpgradingDuplicateKeyLocks() throws Exception {
        java.util.List<Integer> results = race(java.util.List.of(() -> write("granted", 0), () -> write("revoked", 0), () -> write("unknown", 0)));
        assertEquals(1, results.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, current().getVersion());
        assertEquals(1, eventCount());
    }

    @Test
    void theMapperPredicateIndependentlyRefusesAStaleVersionAndTimestampPreservingRevocation() {
        long version = asActor(() -> consentService.setForPersonConditionally(person.getId(), request("unknown", 0)).version());
        var timestamp = current().getUpdatedAt();
        jdbc.update("UPDATE contact_channel_consent SET status='revoked', updated_at=updated_at, version=999 WHERE workspace_id=? AND person_id=?",
                workspace.getId(), person.getId());
        ContactChannelConsent update = current();
        assertEquals(timestamp, update.getUpdatedAt());
        assertEquals(version + 1, update.getVersion());
        update.setStatus("granted");
        assertEquals(0, asActor(() -> consentMapper.updateIfVersion(update, version)));
        assertThrows(StaleRecordException.class, () -> asActor(() ->
                consentService.setForPersonConditionally(person.getId(), request("granted", version))));
        assertEquals("revoked", current().getStatus());
    }

    @Test
    void concurrentIndependentRestrictionPatchesPreserveBothHolds() throws Exception {
        race(() -> asActor(() -> personService.updateProcessingRestrictions(person.getId(), true, null)),
                () -> asActor(() -> personService.updateProcessingRestrictions(person.getId(), null, true)));
        Person stored = asActor(() -> personMapper.getOwnedPersonAddresses(workspace.getId(), person.getId()));
        assertNotNull(stored);
        var suspended = jdbc.queryForObject("SELECT suspended_at IS NOT NULL FROM person WHERE workspace_id=? AND id=?",
                Boolean.class, workspace.getId(), person.getId());
        var sharing = jdbc.queryForObject("SELECT provision_ceased_at IS NOT NULL FROM person WHERE workspace_id=? AND id=?",
                Boolean.class, workspace.getId(), person.getId());
        assertEquals(Boolean.TRUE, suspended);
        assertEquals(Boolean.TRUE, sharing);
    }

    private void assertOneWinner(long version) throws Exception {
        var results = race(() -> write("granted", version), () -> write("revoked", version));
        assertEquals(1, results.getFirst() + results.getLast());
        assertEquals(results.getFirst() == 1 ? "granted" : "revoked", current().getStatus());
    }

    private int write(String status, long version) {
        return asActor(() -> {
            try {
                consentService.setForPersonConditionally(person.getId(), request(status, version));
                return 1;
            } catch (StaleRecordException conflict) {
                return 0;
            }
        });
    }

    private <T> java.util.List<T> race(Supplier<T> first, Supplier<T> second) throws Exception {
        return race(java.util.List.of(first, second));
    }

    private <T> java.util.List<T> race(java.util.List<Supplier<T>> writers) throws Exception {
        CountDownLatch ready = new CountDownLatch(writers.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(writers.size())) {
            var futures = writers.stream().map(writer -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return writer.get();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            java.util.List<T> results = new java.util.ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return java.util.List.copyOf(results);
        } finally { start.countDown(); }
    }

    private ContactChannelConsent current() {
        return asActor(() -> consentMapper.getForPerson(workspace.getId(), person.getId()).getFirst());
    }

    private int eventCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM contact_channel_consent_event WHERE workspace_id=? AND person_id=?",
                Integer.class, workspace.getId(), person.getId());
        if (count == null) throw new IllegalStateException("Missing history count");
        return count;
    }

    private ContactChannelConsentRequest request(String status, long version) {
        return new ContactChannelConsentRequest("email", "marketing", status, "manual", null, null, version);
    }

    private <T> T asActor(Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        tenantContext.set(workspace.getId(), organization.getId(), actor.getId(), "owner", null);
        try { return work.get(); }
        finally { SecurityContextHolder.clearContext(); tenantContext.clear(); }
    }
}
