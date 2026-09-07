package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotDto;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises campaign serialization from non-transactional test methods.
 * {@link CampaignService#update(int, CampaignRequest)},
 * {@link CampaignService#setAudience(int, CampaignAudienceRequest)},
 * {@link CampaignService#snapshotAudience(int)}, and
 * {@link CampaignSendService#createSend(int, CampaignSendRequest)} use Spring's required
 * transactional propagation. Waiter calls therefore own their transactions, while each
 * holder call joins a test-owned transaction that remains open after the service returns.
 * The production path first serializes on the caller's membership row during locked
 * authorization and then on the campaign row as the aggregate mutex. The single-campaign outcome
 * cases and independent-campaign case therefore use distinct built-in admin users, allowing each
 * waiter to finish actor-specific authorization and request its campaign mutex while the holder
 * remains open. Built-in roles also avoid custom-role authority rows that could introduce another
 * shared lock.
 * Same-user concurrency is valid but serializes earlier on the shared membership row and cannot
 * exercise campaign-row contention while the first transaction is held.
 * The single-campaign state-visibility cases capture the waiter's connection on the test-only
 * MyBatis seam immediately before its production {@code CampaignMapper.getCampaignForUpdate}
 * query delegates, and capture the holder's transaction-bound connection after its production
 * service path acquires the campaign mutex. MySQL reported the waiter's connection blocked behind
 * the holder's connection while the holder held the production-acquired campaign mutex; the holder
 * committed only afterwards; the waiter then observed the committed state. The independent-campaign
 * deadlock case likewise captures the holder and both waiters by MySQL connection id, observes both
 * waiter/holder pairs without filtering on an InnoDB transaction id or index name, and verifies that
 * each waiter is blocked on its own campaign row before releasing both rows. The authorization
 * revocation case retains its transaction-owned lock-wait observation because that wait is
 * deterministic and part of the behavior under test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(CampaignConcurrencyIntegrationTest.MutexProbeConfiguration.class)
@TestPropertySource(properties = "connex.workflows.triggered-send.enabled=true")
class CampaignConcurrencyIntegrationTest extends CampaignRealDbTestSupport {

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private RoleService roleService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private MutexRequestProbe mutexRequestProbe;
    @Autowired private CampaignTriggeredSendService campaignTriggeredSendService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private CampaignSendMapper campaignSendMapper;
    @MockitoBean private CapabilityRegistry capabilityRegistry;
    @MockitoBean private DeliveryProviderConfigService deliveryProviderConfigService;

    @BeforeEach
    void enableTriggeredDelivery() {
        lenient().when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY))
            .thenReturn(true);
        lenient().when(deliveryProviderConfigService.isReady(anyInt(), eq(DeliveryChannel.EMAIL)))
            .thenReturn(true);
    }

    private static final String OBSERVED_WAIT_QUERY = """
            SELECT COUNT(*)
            FROM performance_schema.data_lock_waits w
            JOIN performance_schema.threads rt ON rt.THREAD_ID = w.REQUESTING_THREAD_ID
            JOIN performance_schema.threads bt ON bt.THREAD_ID = w.BLOCKING_THREAD_ID
            WHERE rt.PROCESSLIST_ID = ? AND bt.PROCESSLIST_ID = ?
            """;

    private static final String OBSERVED_CAMPAIGN_PAIR_WAIT_QUERY = """
            SELECT rt.PROCESSLIST_ID AS requesting_connection_id,
                   bt.PROCESSLIST_ID AS blocking_connection_id,
                   requested.OBJECT_SCHEMA, requested.OBJECT_NAME, requested.INDEX_NAME,
                   requested.LOCK_TYPE, requested.LOCK_MODE, requested.LOCK_DATA
            FROM performance_schema.data_lock_waits waits
            JOIN performance_schema.data_locks requested
              ON requested.ENGINE = waits.ENGINE
             AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads rt
              ON rt.THREAD_ID = waits.REQUESTING_THREAD_ID
            JOIN performance_schema.threads bt
              ON bt.THREAD_ID = waits.BLOCKING_THREAD_ID
            WHERE rt.PROCESSLIST_ID IN (?, ?)
              AND bt.PROCESSLIST_ID = ?
            """;

    @Test
    void concurrentTriggeredEnrollmentsWaitOnCampaignAndCatchTheUniqueRevisionRace()
            throws Exception {
        String prefix = "triggered-race-" + unique();
        Person person = person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignService.create(campaignRequest("Triggered race"));
        CampaignMessageDto message = campaignSendService.createMessage(
            campaign.id(), new CampaignMessageRequest("Triggered message", "email"));
        campaignSendService.addRevision(campaign.id(), message.id(), emailRevision());
        User waiter = newOutcomeWaiter();
        OutcomeBarrier barrier = new OutcomeBarrier();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CampaignTriggeredSendService.EnrollmentResult> holder = executor.submit(
                () -> holdCampaignOutcomeAs(
                    currentUser,
                    barrier,
                    "Triggered enrollment waiter did not start",
                    () -> campaignTriggeredSendService.enroll(person.getId(), message.id(), 1)));
            barrier.awaitHeld(
                "Triggered enrollment holder did not acquire the campaign lock",
                () -> diagnosticDump(barrier));
            Future<TimedResult<CampaignTriggeredSendService.EnrollmentResult>> waitingEnrollment =
                executor.submit(() -> timedOutcomeCall(
                    waiter,
                    campaign.id(),
                    barrier,
                    () -> campaignTriggeredSendService.enroll(person.getId(), message.id(), 1)));

            awaitObservedOutcomeWait(barrier, "Triggered enrollment");
            CampaignTriggeredSendService.EnrollmentResult first = awaitOutcomeHolder(
                holder, "Triggered enrollment", barrier);
            TimedResult<CampaignTriggeredSendService.EnrollmentResult> second = awaitOutcomeWaiter(
                waitingEnrollment, "Triggered enrollment", barrier);
            assertCompletedAfterHolderCommit(barrier, second, "Triggered enrollment");

            assertEquals("delivery_queued", first.outcome());
            assertEquals("delivery_dedup_skipped", second.value().outcome());
            assertEquals(first.sendId(), second.value().sendId());
            assertEquals(first.deliveryId(), second.value().deliveryId());
            assertEquals(1, campaignSendMapper.getSendsByCampaign(
                workspace.getId(), campaign.id()).size());
            assertEquals(1, campaignDeliveryMapper.pendingDeliveryIds(
                workspace.getId(), first.sendId()).size());
        } finally {
            barrier.abort();
            shutdown(executor);
        }
    }

    @Test
    void concurrentAudienceUpdatesAuditTheScopeTheyActuallyReplace() throws Exception {
        String prefix = "concurrent-audit-" + unique();
        CampaignDto campaign = campaignService.create(campaignRequest("Concurrent audience audit"));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        User waiter = newOutcomeWaiter();
        AudienceScope smsScope = new AudienceScope("sms", "product_update");
        AudienceScope emailScope = new AudienceScope("email", "customer_success");
        OutcomeBarrier holderBarrier = new OutcomeBarrier();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> holdCampaignOutcomeAs(
                    currentUser, holderBarrier,
                    "Concurrent audience update did not start", () -> {
                        campaignService.setAudience(
                                campaign.id(), audience(prefix, smsScope.channel(), smsScope.purpose()));
                        return null;
                    }));
            holderBarrier.awaitHeld(
                    "Audience update holder did not acquire the campaign lock",
                    () -> diagnosticDump(holderBarrier));
            Future<TimedResult<Void>> emailUpdate = executor.submit(() -> timedOutcomeCall(
                    waiter, campaign.id(), holderBarrier, () -> {
                        campaignService.setAudience(
                                campaign.id(), audience(
                                        prefix, emailScope.channel(), emailScope.purpose()));
                        return null;
                    }));
            awaitObservedOutcomeWait(holderBarrier, "Concurrent audience update");
            awaitOutcomeHolder(holder, "Concurrent audience update", holderBarrier);
            TimedResult<Void> waiterResult = awaitOutcomeWaiter(
                    emailUpdate, "Concurrent audience update", holderBarrier);
            assertCompletedAfterHolderCommit(
                    holderBarrier, waiterResult, "Concurrent audience update");
        } finally {
            holderBarrier.abort();
            shutdown(executor);
        }

        var current = campaignService.getAudience(campaign.id());
        assertNotNull(current);
        assertEquals(emailScope, new AudienceScope(current.channel(), current.purpose()));
        List<AuditScopeChange> storedChanges = jdbcTemplate.query("""
                SELECT actor_id, changes
                FROM audit_log
                WHERE workspace_id = ?
                  AND action = 'campaign.audience.set'
                  AND entity_type = 'campaign'
                  AND entity_id = ?
                ORDER BY id
                """, (resultSet, rowNumber) -> new AuditScopeChange(
                        resultSet.getInt("actor_id"), resultSet.getString("changes")),
                workspace.getId(), campaign.id());
        assertEquals(3, storedChanges.size());
        assertEquals(
                List.of(currentUser.getId(), currentUser.getId(), waiter.getId()),
                storedChanges.stream().map(AuditScopeChange::actorId).toList());
        List<JsonNode> changes = storedChanges.stream()
                .map(value -> objectMapper.readTree(value.changes()))
                .toList();

        assertEquals("email", changes.get(0).path("channel").path("new").asText());
        assertEquals("marketing", changes.get(0).path("purpose").path("new").asText());
        assertScopeChange(changes.get(1), new AudienceScope("email", "marketing"), smsScope);
        assertScopeChange(changes.get(2), smsScope, emailScope);
    }

    @Test
    void snapshotWaitingBehindAudienceUpdateFreezesTheCommittedScope() throws Exception {
        String prefix = "snapshot-current-" + unique();
        CampaignDto campaign = campaignService.create(campaignRequest("Current audience snapshot"));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        User waiter = newOutcomeWaiter();
        AudienceScope committedScope = new AudienceScope("sms", "product_update");
        OutcomeBarrier holderBarrier = new OutcomeBarrier();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = executor.submit(() -> holdCampaignOutcomeAs(
                    currentUser, holderBarrier,
                    "Audience snapshot waiter did not start", () -> {
                        campaignService.setAudience(campaign.id(), audience(
                                prefix, committedScope.channel(), committedScope.purpose()));
                        return null;
                    }));
            holderBarrier.awaitHeld(
                    "Audience update did not acquire the campaign lock",
                    () -> diagnosticDump(holderBarrier));
            Future<TimedResult<CampaignAudienceSnapshotDto>> snapshot = executor.submit(
                    () -> timedOutcomeCall(waiter, campaign.id(), holderBarrier,
                            () -> campaignService.snapshotAudience(campaign.id())));
            awaitObservedOutcomeWait(holderBarrier, "Audience snapshot");
            awaitOutcomeHolder(update, "Audience snapshot", holderBarrier);

            TimedResult<CampaignAudienceSnapshotDto> waiterResult =
                    awaitOutcomeWaiter(snapshot, "Audience snapshot", holderBarrier);
            assertCompletedAfterHolderCommit(holderBarrier, waiterResult, "Audience snapshot");
            CampaignAudienceSnapshotDto frozen = waiterResult.value();
            assertEquals(Integer.valueOf(waiter.getId()), frozen.createdById());
            assertEquals(committedScope, new AudienceScope(frozen.channel(), frozen.purpose()));
            var current = campaignService.getAudience(campaign.id());
            assertNotNull(current);
            assertEquals(committedScope, new AudienceScope(current.channel(), current.purpose()));
        } finally {
            holderBarrier.abort();
            shutdown(executor);
        }
    }

    @Test
    void concurrentSnapshotsAllocateDistinctCampaignVersions() throws Exception {
        String prefix = "snapshot-version-" + unique();
        CampaignDto campaign = campaignService.create(campaignRequest("Concurrent snapshots"));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        User waiter = newOutcomeWaiter();
        OutcomeBarrier holderBarrier = new OutcomeBarrier();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CampaignAudienceSnapshotDto> holder = executor.submit(() -> holdCampaignOutcomeAs(
                    currentUser, holderBarrier,
                    "Concurrent snapshot waiter did not start",
                    () -> campaignService.snapshotAudience(campaign.id())));
            holderBarrier.awaitHeld(
                    "First snapshot did not acquire the campaign lock",
                    () -> diagnosticDump(holderBarrier));
            Future<TimedResult<CampaignAudienceSnapshotDto>> second = executor.submit(
                    () -> timedOutcomeCall(waiter, campaign.id(), holderBarrier,
                            () -> campaignService.snapshotAudience(campaign.id())));

            awaitObservedOutcomeWait(holderBarrier, "Concurrent snapshot");
            CampaignAudienceSnapshotDto first = awaitOutcomeHolder(
                    holder, "Concurrent snapshot", holderBarrier);
            TimedResult<CampaignAudienceSnapshotDto> secondResult =
                    awaitOutcomeWaiter(second, "Concurrent snapshot", holderBarrier);
            assertCompletedAfterHolderCommit(holderBarrier, secondResult, "Concurrent snapshot");
            assertEquals(1, first.version());
            assertEquals(Integer.valueOf(currentUser.getId()), first.createdById());
            assertEquals(2, secondResult.value().version());
            assertEquals(Integer.valueOf(waiter.getId()), secondResult.value().createdById());
            assertEquals(List.of(2, 1), campaignService.listSnapshots(campaign.id()).stream()
                    .map(snapshot -> snapshot.version())
                    .toList());
        } finally {
            holderBarrier.abort();
            shutdown(executor);
        }
    }

    @Test
    void firstAudienceAndSnapshotInsertsForDifferentCampaignsDoNotDeadlock() throws Exception {
        String prefix = "first-row-" + unique();
        CampaignDto firstCampaign = campaignService.create(campaignRequest("First campaign"));
        CampaignDto secondCampaign = campaignService.create(campaignRequest("Second campaign"));
        User firstActor = newCampaignAdmin(workspace);
        User secondActor = newCampaignAdmin(workspace);
        List<Integer> campaignIds = List.of(firstCampaign.id(), secondCampaign.id()).stream()
                .sorted()
                .toList();
        CampaignPairBarrier audienceBarrier =
                new CampaignPairBarrier(firstCampaign.id(), secondCampaign.id());
        CampaignPairBarrier snapshotBarrier =
                new CampaignPairBarrier(firstCampaign.id(), secondCampaign.id());
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> audienceHolder = executor.submit(() -> holdCampaignLocksAs(
                    currentUser, campaignIds, audienceBarrier,
                    "Audience campaign-lock holder did not resume"));
            audienceBarrier.awaitHeld("Audience campaign-lock holder did not acquire both rows");
            Future<?> firstAudience = executor.submit(() -> setAudienceAs(
                    firstActor, firstCampaign.id(), prefix + "-first",
                    new AudienceScope("email", "marketing"), audienceBarrier));
            Future<?> secondAudience = executor.submit(() -> setAudienceAs(
                    secondActor, secondCampaign.id(), prefix + "-second",
                    new AudienceScope("sms", "product_update"), audienceBarrier));
            audienceBarrier.trackWaiters(firstAudience, secondAudience);
            awaitObservedCampaignPairWait(audienceBarrier, "Campaign audience inserts");
            audienceBarrier.releaseForCommit();
            awaitNoDeadlock(audienceHolder, "Campaign audience holder");
            awaitNoDeadlock(firstAudience, "First campaign audience insert");
            awaitNoDeadlock(secondAudience, "Second campaign audience insert");

            var firstStoredAudience = campaignService.getAudience(firstCampaign.id());
            var secondStoredAudience = campaignService.getAudience(secondCampaign.id());
            assertNotNull(firstStoredAudience);
            assertNotNull(secondStoredAudience);
            assertEquals("email", firstStoredAudience.channel());
            assertEquals("sms", secondStoredAudience.channel());

            Future<?> snapshotHolder = executor.submit(() -> holdCampaignLocksAs(
                    currentUser, campaignIds, snapshotBarrier,
                    "Snapshot campaign-lock holder did not resume"));
            snapshotBarrier.awaitHeld("Snapshot campaign-lock holder did not acquire both rows");
            Future<CampaignAudienceSnapshotDto> firstSnapshot = executor.submit(
                    () -> snapshotAs(firstActor, firstCampaign.id(), snapshotBarrier));
            Future<CampaignAudienceSnapshotDto> secondSnapshot = executor.submit(
                    () -> snapshotAs(secondActor, secondCampaign.id(), snapshotBarrier));
            snapshotBarrier.trackWaiters(firstSnapshot, secondSnapshot);
            awaitObservedCampaignPairWait(snapshotBarrier, "Campaign snapshot inserts");
            snapshotBarrier.releaseForCommit();
            awaitNoDeadlock(snapshotHolder, "Campaign snapshot holder");

            assertEquals(1, awaitNoDeadlock(
                    firstSnapshot, "First campaign snapshot insert").version());
            assertEquals(1, awaitNoDeadlock(
                    secondSnapshot, "Second campaign snapshot insert").version());
        } finally {
            audienceBarrier.abort();
            snapshotBarrier.abort();
            shutdown(executor);
        }
    }

    @Test
    void createSendSeesRevisionSnapshotAndMembersCommittedBeforeItsCampaignLock() throws Exception {
        String prefix = "send-current-" + unique();
        person(newCompany(), prefix + "-contact", prefix + "@example.com");
        CampaignDto campaign = campaignService.create(campaignRequest("Current send inputs"));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Current message", "email"));
        User waiter = newOutcomeWaiter();
        OutcomeBarrier holderBarrier = new OutcomeBarrier();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SendInputs> inputs = executor.submit(() -> holdCampaignOutcomeAs(
                    currentUser, holderBarrier,
                    "Campaign send waiter did not start", () -> {
                        CampaignAudienceSnapshotDto snapshot =
                                campaignService.snapshotAudience(campaign.id());
                        CampaignMessageDto revised = campaignSendService.addRevision(
                                campaign.id(), message.id(), emailRevision());
                        return new SendInputs(snapshot, revised);
                    }));
            holderBarrier.awaitHeld(
                    "Send-input holder did not acquire the campaign lock",
                    () -> diagnosticDump(holderBarrier));
            Future<TimedResult<CampaignSendDto>> send = executor.submit(() -> timedOutcomeCall(
                    waiter, campaign.id(), holderBarrier, () -> campaignSendService.createSend(
                            campaign.id(), new CampaignSendRequest(
                                    1, message.id(), 1, "marketing", null))));
            awaitObservedOutcomeWait(holderBarrier, "Campaign send");
            SendInputs committedInputs = awaitOutcomeHolder(inputs, "Campaign send", holderBarrier);
            TimedResult<CampaignSendDto> waiterResult = awaitOutcomeWaiter(
                    send, "Campaign send", holderBarrier);
            assertCompletedAfterHolderCommit(holderBarrier, waiterResult, "Campaign send");
            assertEquals(1, committedInputs.snapshot().version());
            assertEquals(Integer.valueOf(currentUser.getId()), committedInputs.snapshot().createdById());
            assertEquals(1, committedInputs.snapshot().members().size());
            assertEquals(1, committedInputs.message().revisions().size());
            assertEquals(1, waiterResult.value().messageVersion());
            assertEquals(1, waiterResult.value().totalRecipients());
            assertEquals(Integer.valueOf(waiter.getId()), waiterResult.value().createdById());
        } finally {
            holderBarrier.abort();
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(CampaignMutation.class)
    void authorizationLocksLinearizeMutationBeforeConcurrentRoleRevocation(CampaignMutation mutation)
            throws Exception {
        String prefix = "linearized-" + mutation.name().toLowerCase() + "-" + unique();
        CampaignDto campaign = campaignService.create(campaignRequest("Linearized campaign " + prefix));
        campaignService.setAudience(campaign.id(), audience(prefix, "email", "marketing"));
        Optional<CampaignMessageDto> message = Optional.empty();
        if (mutation == CampaignMutation.SEND) {
            campaignService.snapshotAudience(campaign.id());
            CampaignMessageDto createdMessage = campaignSendService.createMessage(
                    campaign.id(), new CampaignMessageRequest("Revoked message " + prefix, "email"));
            campaignSendService.addRevision(campaign.id(), createdMessage.id(), emailRevision());
            message = Optional.of(createdMessage);
        }
        Optional<CampaignMessageDto> configuredMessage = message;
        CampaignActorRole target = newCampaignActor(workspace);
        CampaignActorRole revoker = newCampaignActor(workspace, List.of(
                Permission.CAMPAIGN_VIEW,
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE,
                Permission.ROLE_MANAGE));
        TransactionBarrier holderBarrier = new TransactionBarrier();

        ExecutorService holderExecutor = namedExecutor("campaign-lock-holder");
        ExecutorService mutationExecutor = namedExecutor("linearized-mutation");
        ExecutorService revocationExecutor = namedExecutor("role-revoker");
        Future<?> holder = holderExecutor.submit(() -> holdCampaignLocksAs(
                currentUser, List.of(campaign.id()), holderBarrier,
                "Campaign lock holder did not resume"));
        try {
            holderBarrier.awaitHeld("Campaign lock holder did not acquire the campaign row");
            Future<?> applied = mutationExecutor.submit(() -> asActor(
                    target.actor(), () -> {
                        mutation.execute(campaignService, campaignSendService, campaign, configuredMessage,
                                prefix, currentUser.getId());
                        return null;
                    }));
            awaitHolderOwnedWait(holderBarrier, 1, "Authorized campaign mutation");
            Future<?> revoked = revocationExecutor.submit(() -> asActor(
                    revoker.actor(), () -> {
                        roleService.updateRole(
                            workspace.getId(), revoker.actor().getId(), target.role().getId(),
                            target.role().getName(), List.of(
                                    Permission.CAMPAIGN_VIEW.name(),
                                    Permission.CAMPAIGN_SEND.name(),
                                    Permission.CONSENT_MANAGE.name()));
                        return null;
                    }));
            awaitWorkspaceMutationRootWait();
            assertThrows(TimeoutException.class, () -> revoked.get(500, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> applied.get(500, TimeUnit.MILLISECONDS));
            holderBarrier.releaseForCommit();

            applied.get(20, TimeUnit.SECONDS);
            revoked.get(20, TimeUnit.SECONDS);
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            holderBarrier.abort();
            shutdown(mutationExecutor);
            shutdown(revocationExecutor);
            shutdown(holderExecutor);
        }

        authenticateAs(target.actor(), workspace.getId());
        assertThrows(ForbiddenException.class, () -> campaignService.update(
                campaign.id(), campaignRequest("Post-revocation mutation")));
        authenticateAs(currentUser, workspace.getId());
        mutation.assertApplied(campaignService, campaignSendService, campaign.id());
    }

    private void awaitHolderOwnedWait(
            TransactionBarrier holder,
            int minimumWaiters,
            String description) throws InterruptedException {
        long holderTransactionId = holder.engineTransactionId();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blocking
                      ON blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE()
                      AND requested.OBJECT_NAME = 'campaign'
                      AND blocking.OBJECT_SCHEMA = requested.OBJECT_SCHEMA
                      AND blocking.OBJECT_NAME = requested.OBJECT_NAME
                      AND requested.LOCK_TYPE = 'RECORD'
                      AND blocking.LOCK_TYPE = requested.LOCK_TYPE
                      AND blocking.ENGINE_TRANSACTION_ID = ?
                    """, Integer.class, holderTransactionId);
            if (waits != null && waits >= minimumWaiters) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException(
                description + " did not wait behind holder transaction " + holderTransactionId);
    }

    private void awaitObservedCampaignPairWait(
            CampaignPairBarrier barrier, String description) throws InterruptedException {
        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                description + " observer must run outside a transaction");
        if (!barrier.awaitWaiterConnections(20, TimeUnit.SECONDS)) {
            throw campaignPairInconclusive(
                    description + " waiter connections were not published before the latch deadline",
                    barrier);
        }
        long firstWaiterConnectionId = barrier.firstWaiterConnectionId();
        long secondWaiterConnectionId = barrier.secondWaiterConnectionId();
        long holderConnectionId = barrier.holderConnectionId();
        if (firstWaiterConnectionId == secondWaiterConnectionId
                || firstWaiterConnectionId == holderConnectionId
                || secondWaiterConnectionId == holderConnectionId) {
            throw campaignPairInconclusive(
                    description + " did not use three distinct MySQL connections", barrier);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            List<ObservedLockWait> waits = observedCampaignPairWaits(
                    firstWaiterConnectionId, secondWaiterConnectionId, holderConnectionId);
            Optional<ObservedLockWait> firstWait = waits.stream()
                    .filter(wait -> wait.requestingConnectionId() == firstWaiterConnectionId)
                    .findFirst();
            Optional<ObservedLockWait> secondWait = waits.stream()
                    .filter(wait -> wait.requestingConnectionId() == secondWaiterConnectionId)
                    .findFirst();
            if (firstWait.isPresent() && secondWait.isPresent()) {
                assertCampaignRowWait(
                        firstWait.orElseThrow(), holderConnectionId,
                        barrier.firstCampaignId(), description);
                assertCampaignRowWait(
                        secondWait.orElseThrow(), holderConnectionId,
                        barrier.secondCampaignId(), description);
                return;
            }
            Thread.sleep(10);
        }
        throw campaignPairInconclusive(
                description + " did not report both waiters blocked behind the holder"
                        + " before the observation deadline",
                barrier);
    }

    private List<ObservedLockWait> observedCampaignPairWaits(
            long firstWaiterConnectionId,
            long secondWaiterConnectionId,
            long holderConnectionId) {
        return jdbcTemplate.query(
                OBSERVED_CAMPAIGN_PAIR_WAIT_QUERY,
                (resultSet, rowNumber) -> new ObservedLockWait(
                        resultSet.getLong("requesting_connection_id"),
                        resultSet.getLong("blocking_connection_id"),
                        resultSet.getString("OBJECT_SCHEMA"),
                        resultSet.getString("OBJECT_NAME"),
                        resultSet.getString("INDEX_NAME"),
                        resultSet.getString("LOCK_TYPE"),
                        resultSet.getString("LOCK_MODE"),
                        resultSet.getString("LOCK_DATA")),
                firstWaiterConnectionId,
                secondWaiterConnectionId,
                holderConnectionId);
    }

    private static void assertCampaignRowWait(
            ObservedLockWait wait,
            long holderConnectionId,
            int campaignId,
            String description) {
        assertEquals(
                holderConnectionId,
                wait.blockingConnectionId(),
                description + " waiter was not blocked by the campaign-row holder");
        assertEquals(
                "campaign",
                wait.objectName(),
                description + " waiter for campaign " + campaignId + " blocked before its campaign row");
        assertEquals(
                "RECORD",
                wait.lockType(),
                description + " waiter for campaign " + campaignId + " did not request a row lock");
        assertEquals(
                Integer.toString(campaignId),
                wait.lockData(),
                description + " waiter blocked on another campaign row");
    }

    private AssertionError campaignPairInconclusive(
            String description, CampaignPairBarrier barrier) {
        return new AssertionError(
                "Campaign pair concurrency proof is inconclusive: " + description
                        + System.lineSeparator() + barrier.holderFailureSummary()
                        + System.lineSeparator() + barrier.waiterFutureSummary()
                        + System.lineSeparator() + diagnosticDump(barrier));
    }

    private void awaitWorkspaceMutationRootWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blocking
                      ON blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE()
                      AND requested.OBJECT_NAME = 'workspace'
                      AND blocking.OBJECT_SCHEMA = requested.OBJECT_SCHEMA
                      AND blocking.OBJECT_NAME = requested.OBJECT_NAME
                      AND blocking.INDEX_NAME = requested.INDEX_NAME
                      AND requested.LOCK_TYPE = 'RECORD'
                      AND blocking.LOCK_TYPE = requested.LOCK_TYPE
                      AND requested.LOCK_MODE LIKE 'X%'
                      AND blocking.LOCK_MODE LIKE 'S%'
                      AND requested.LOCK_DATA = blocking.LOCK_DATA
                      AND CAST(requested.LOCK_DATA AS UNSIGNED) = ?
                    """, Integer.class, workspace.getId());
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException(
                "Concurrent role revocation did not wait on the shared workspace mutation root");
    }

    private void setAudienceAs(
            User actor,
            int campaignId,
            String prefix,
            AudienceScope scope,
            CampaignPairBarrier barrier) {
        observedCampaignPairCall(actor, campaignId, barrier, () -> campaignService.setAudience(
                campaignId, audience(prefix, scope.channel(), scope.purpose())));
    }

    private CampaignAudienceSnapshotDto snapshotAs(
            User actor, int campaignId, CampaignPairBarrier barrier) {
        return observedCampaignPairCall(
                actor, campaignId, barrier, () -> campaignService.snapshotAudience(campaignId));
    }

    private void holdCampaignLocksAs(
            User actor,
            List<Integer> campaignIds,
            TransactionBarrier barrier,
            String timeoutMessage) {
        inHeldTransactionAs(actor, barrier, timeoutMessage, () -> {
            for (int campaignId : campaignIds) {
                Campaign locked = campaignMapper.getCampaignForUpdate(workspace.getId(), campaignId);
                assertNotNull(locked);
                assertEquals(campaignId, locked.getId());
            }
            return null;
        });
    }

    private void holdCampaignLocksAs(
            User actor,
            List<Integer> campaignIds,
            CampaignPairBarrier barrier,
            String timeoutMessage) {
        authenticateAs(actor, workspace.getId());
        try {
            try {
                outcomeTransaction().executeWithoutResult(status -> {
                    lockCampaignRows(campaignIds);
                    assertTrue(
                            TransactionSynchronizationManager.isActualTransactionActive(),
                            "Campaign pair holder must run inside its test-owned transaction");
                    Connection connection = DataSourceUtils.getConnection(dataSource);
                    try {
                        assertTrue(
                                DataSourceUtils.isConnectionTransactional(connection, dataSource),
                                "Campaign pair holder connection must be transaction-bound");
                        long firstConnectionId = connectionId(connection);
                        long secondConnectionId = connectionId(connection);
                        assertEquals(
                                firstConnectionId,
                                secondConnectionId,
                                "Campaign pair holder connection changed inside its transaction");
                        barrier.hold(
                                status,
                                firstConnectionId,
                                timeoutMessage,
                                () -> diagnosticDump(barrier));
                    } finally {
                        DataSourceUtils.releaseConnection(connection, dataSource);
                    }
                });
            } catch (RuntimeException | Error exception) {
                barrier.markHolderFailure(exception);
                throw exception;
            }
        } finally {
            clearAuthentication();
        }
    }

    private void lockCampaignRows(List<Integer> campaignIds) {
        for (int campaignId : campaignIds) {
            Campaign locked = campaignMapper.getCampaignForUpdate(workspace.getId(), campaignId);
            assertNotNull(locked);
            assertEquals(campaignId, locked.getId());
        }
    }

    private <T> T holdCampaignOutcomeAs(
            User actor,
            OutcomeBarrier barrier,
            String timeoutMessage,
            Supplier<T> action) {
        authenticateAs(actor, workspace.getId());
        try {
            try {
                T result = outcomeTransaction().execute(status -> {
                    T actionResult = action.get();
                    assertTrue(
                            TransactionSynchronizationManager.isActualTransactionActive(),
                            "Campaign outcome holder must run inside its test-owned transaction");
                    Connection connection = DataSourceUtils.getConnection(dataSource);
                    try {
                        assertTrue(
                                DataSourceUtils.isConnectionTransactional(connection, dataSource),
                                "Campaign outcome holder connection must be transaction-bound");
                        long firstConnectionId = connectionId(connection);
                        long secondConnectionId = connectionId(connection);
                        assertEquals(
                                firstConnectionId,
                                secondConnectionId,
                                "Campaign outcome holder connection changed inside its transaction");
                        barrier.hold(
                                status,
                                firstConnectionId,
                                timeoutMessage,
                                () -> diagnosticDump(barrier));
                    } finally {
                        DataSourceUtils.releaseConnection(connection, dataSource);
                    }
                    barrier.markCommitted(System.nanoTime());
                    return actionResult;
                });
                return result;
            } catch (RuntimeException | Error exception) {
                barrier.markHolderFailure(exception);
                throw exception;
            }
        } finally {
            clearAuthentication();
        }
    }

    private <T> T inHeldTransactionAs(
            User actor,
            TransactionBarrier barrier,
            String timeoutMessage,
            Supplier<T> action) {
        authenticateAs(actor, workspace.getId());
        try {
            return transaction().execute(status -> {
                T result = action.get();
                barrier.hold(status, currentEngineTransactionId(), timeoutMessage);
                return result;
            });
        } finally {
            clearAuthentication();
        }
    }

    private <T> TimedResult<T> timedOutcomeCall(
            User actor, int campaignId, OutcomeBarrier barrier, Supplier<T> action) {
        authenticateAs(actor, workspace.getId());
        mutexRequestProbe.arm(campaignId, barrier);
        try {
            long startNanos = System.nanoTime();
            T result = action.get();
            return new TimedResult<>(result, startNanos, System.nanoTime());
        } finally {
            mutexRequestProbe.clear();
            clearAuthentication();
        }
    }

    private <T> T observedCampaignPairCall(
            User actor, int campaignId, CampaignPairBarrier barrier, Supplier<T> action) {
        authenticateAs(actor, workspace.getId());
        mutexRequestProbe.arm(campaignId, barrier);
        try {
            return action.get();
        } finally {
            mutexRequestProbe.clear();
            clearAuthentication();
        }
    }

    private static void assertCompletedAfterHolderCommit(
            OutcomeBarrier barrier,
            TimedResult<?> result,
            String description) {
        assertTrue(
                barrier.observedWaitCount() > 0,
                description + " has no MySQL lock-wait row proving campaign-lock contention");
        long observedWaitNanos = barrier.observedWaitNanos();
        long commitNanos = barrier.commitNanos();
        assertTrue(
                result.startNanos() < observedWaitNanos,
                description + " MySQL wait was observed before the waiter call started");
        assertTrue(
                observedWaitNanos < commitNanos,
                description + " holder committed before MySQL reported the waiter blocked");
        assertTrue(
                result.doneNanos() > commitNanos,
                description + " completed before the holder's commit boundary");
    }

    private void awaitObservedOutcomeWait(OutcomeBarrier barrier, String description)
            throws InterruptedException {
        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                description + " observer must run outside a transaction");
        if (!barrier.awaitWaiterConnection(20, TimeUnit.SECONDS)) {
            throw inconclusive(
                    description + " waiter connection was not published before the latch deadline",
                    barrier);
        }
        long waiterConnectionId = barrier.waiterConnectionId();
        long holderConnectionId = barrier.holderConnectionId();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waitCount = jdbcTemplate.queryForObject(
                    OBSERVED_WAIT_QUERY,
                    Integer.class,
                    waiterConnectionId,
                    holderConnectionId);
            if (waitCount != null && waitCount > 0) {
                barrier.markWaitObserved(waitCount, System.nanoTime());
                barrier.releaseForCommit();
                return;
            }
            Thread.sleep(10);
        }
        throw inconclusive(
                description + " waiter was not reported blocked behind the holder before the observation deadline",
                barrier);
    }

    private AssertionError inconclusive(String description, OutcomeBarrier barrier) {
        return new AssertionError(
                "Campaign serialization proof is inconclusive: " + description + System.lineSeparator()
                        + barrier.holderFailureSummary() + System.lineSeparator()
                        + diagnosticDump(barrier));
    }

    private String diagnosticDump(OutcomeBarrier barrier) {
        long waiterConnectionId = barrier.waiterConnectionIdOrZero();
        long holderConnectionId = barrier.holderConnectionIdOrZero();
        try {
            List<?> locks = jdbcTemplate.queryForList("""
                    SELECT t.PROCESSLIST_ID AS connection_id,
                           l.OBJECT_SCHEMA, l.OBJECT_NAME, l.INDEX_NAME,
                           l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.LOCK_DATA
                    FROM performance_schema.data_locks l
                    JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
                    WHERE t.PROCESSLIST_ID IN (?, ?)
                    ORDER BY t.PROCESSLIST_ID, l.OBJECT_SCHEMA, l.OBJECT_NAME, l.INDEX_NAME
                    """, waiterConnectionId, holderConnectionId);
            List<?> transactions = jdbcTemplate.queryForList("""
                    SELECT trx_state, trx_query, trx_mysql_thread_id, trx_rows_locked
                    FROM information_schema.innodb_trx
                    WHERE trx_mysql_thread_id IN (?, ?)
                    ORDER BY trx_mysql_thread_id
                    """, waiterConnectionId, holderConnectionId);
            List<?> waiterThread = jdbcTemplate.queryForList("""
                    SELECT PROCESSLIST_STATE, PROCESSLIST_INFO
                    FROM performance_schema.threads
                    WHERE PROCESSLIST_ID = ?
                    """, waiterConnectionId);
            List<?> holderBlockedWaits = jdbcTemplate.queryForList("""
                    SELECT rt.PROCESSLIST_ID AS requesting_connection_id,
                           requested.OBJECT_SCHEMA, requested.OBJECT_NAME, requested.INDEX_NAME,
                           requested.LOCK_TYPE, requested.LOCK_MODE, requested.LOCK_DATA
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads rt
                      ON rt.THREAD_ID = waits.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads bt
                      ON bt.THREAD_ID = waits.BLOCKING_THREAD_ID
                    WHERE bt.PROCESSLIST_ID = ?
                    ORDER BY rt.PROCESSLIST_ID, requested.OBJECT_SCHEMA,
                             requested.OBJECT_NAME, requested.INDEX_NAME, requested.LOCK_DATA
                    """, holderConnectionId);
            return "waiterConnectionId=" + waiterConnectionId
                    + ", holderConnectionId=" + holderConnectionId
                    + System.lineSeparator() + "performance_schema.data_locks=" + locks
                    + System.lineSeparator()
                    + "holder-blocked performance_schema.data_lock_waits=" + holderBlockedWaits
                    + System.lineSeparator() + "information_schema.innodb_trx=" + transactions
                    + System.lineSeparator() + "waiter performance_schema.threads=" + waiterThread;
        } catch (RuntimeException exception) {
            return "waiterConnectionId=" + waiterConnectionId
                    + ", holderConnectionId=" + holderConnectionId
                    + System.lineSeparator() + "diagnostic dump failed: " + exception;
        }
    }

    private String diagnosticDump(CampaignPairBarrier barrier) {
        long firstWaiterConnectionId = barrier.firstWaiterConnectionIdOrZero();
        long secondWaiterConnectionId = barrier.secondWaiterConnectionIdOrZero();
        long holderConnectionId = barrier.holderConnectionIdOrZero();
        try {
            List<?> locks = jdbcTemplate.queryForList("""
                    SELECT t.PROCESSLIST_ID AS connection_id,
                           l.OBJECT_SCHEMA, l.OBJECT_NAME, l.INDEX_NAME,
                           l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.LOCK_DATA
                    FROM performance_schema.data_locks l
                    JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
                    WHERE t.PROCESSLIST_ID IN (?, ?, ?)
                    ORDER BY t.PROCESSLIST_ID, l.OBJECT_SCHEMA, l.OBJECT_NAME, l.INDEX_NAME
                    """, firstWaiterConnectionId, secondWaiterConnectionId, holderConnectionId);
            List<?> holderBlockedWaits = jdbcTemplate.queryForList("""
                    SELECT rt.PROCESSLIST_ID AS requesting_connection_id,
                           requested.OBJECT_SCHEMA, requested.OBJECT_NAME, requested.INDEX_NAME,
                           requested.LOCK_TYPE, requested.LOCK_MODE, requested.LOCK_DATA
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = waits.ENGINE
                     AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads rt
                      ON rt.THREAD_ID = waits.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads bt
                      ON bt.THREAD_ID = waits.BLOCKING_THREAD_ID
                    WHERE bt.PROCESSLIST_ID = ?
                    ORDER BY rt.PROCESSLIST_ID, requested.OBJECT_SCHEMA,
                             requested.OBJECT_NAME, requested.INDEX_NAME, requested.LOCK_DATA
                    """, holderConnectionId);
            List<?> transactions = jdbcTemplate.queryForList("""
                    SELECT trx_state, trx_query, trx_mysql_thread_id, trx_rows_locked
                    FROM information_schema.innodb_trx
                    WHERE trx_mysql_thread_id IN (?, ?, ?)
                    ORDER BY trx_mysql_thread_id
                    """, firstWaiterConnectionId, secondWaiterConnectionId, holderConnectionId);
            List<?> waiterThreads = jdbcTemplate.queryForList("""
                    SELECT PROCESSLIST_ID, PROCESSLIST_STATE, PROCESSLIST_INFO
                    FROM performance_schema.threads
                    WHERE PROCESSLIST_ID IN (?, ?)
                    ORDER BY PROCESSLIST_ID
                    """, firstWaiterConnectionId, secondWaiterConnectionId);
            return "firstWaiterConnectionId=" + firstWaiterConnectionId
                    + ", secondWaiterConnectionId=" + secondWaiterConnectionId
                    + ", holderConnectionId=" + holderConnectionId
                    + System.lineSeparator() + "performance_schema.data_locks=" + locks
                    + System.lineSeparator()
                    + "holder-blocked performance_schema.data_lock_waits=" + holderBlockedWaits
                    + System.lineSeparator() + "information_schema.innodb_trx=" + transactions
                    + System.lineSeparator()
                    + "waiter performance_schema.threads=" + waiterThreads;
        } catch (RuntimeException exception) {
            return "firstWaiterConnectionId=" + firstWaiterConnectionId
                    + ", secondWaiterConnectionId=" + secondWaiterConnectionId
                    + ", holderConnectionId=" + holderConnectionId
                    + System.lineSeparator() + "diagnostic dump failed: " + exception;
        }
    }

    private static <T> T awaitOutcomeHolder(
            Future<T> holder, String description, OutcomeBarrier barrier) {
        try {
            return holder.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    description + " proof is inconclusive: holder did not commit in time"
                            + System.lineSeparator() + barrier.holderFailureSummary(), exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            barrier.markHolderFailure(cause);
            throw new AssertionError(
                    description + " proof is inconclusive: holder failed"
                            + System.lineSeparator() + throwableSummary(cause), cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    description + " proof is inconclusive: holder wait was interrupted"
                            + System.lineSeparator() + barrier.holderFailureSummary(), exception);
        }
    }

    private static <T> T awaitOutcomeWaiter(
            Future<T> waiter, String description, OutcomeBarrier barrier) {
        try {
            return waiter.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError(
                    description + " proof is inconclusive: waiter did not finish in time"
                            + System.lineSeparator() + barrier.holderFailureSummary(), exception);
        } catch (ExecutionException exception) {
            throw new AssertionError(
                    description + " proof is inconclusive: waiter failed"
                            + System.lineSeparator() + barrier.holderFailureSummary(), exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    description + " proof is inconclusive: waiter wait was interrupted"
                            + System.lineSeparator() + barrier.holderFailureSummary(), exception);
        }
    }

    private static <T> T awaitNoDeadlock(Future<T> future, String description)
            throws InterruptedException, TimeoutException {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (hasSqlErrorCode(cause, 1213)) {
                throw new AssertionError(description + " failed with MySQL deadlock 1213", cause);
            }
            throw new AssertionError(description + " failed", cause);
        }
    }

    private static boolean hasSqlErrorCode(Throwable failure, int errorCode) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == errorCode) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String throwableSummary(Throwable throwable) {
        StringBuilder summary = new StringBuilder("holderException=").append(throwable);
        appendFirstFrames(summary, throwable);
        return summary.toString();
    }

    private static String waiterFutureSummary(String label, Future<?> future) {
        if (future == null) {
            return label + "Done=<not submitted>, " + label + "Exception=<not submitted>";
        }
        boolean done = future.isDone();
        StringBuilder summary = new StringBuilder(label).append("Done=").append(done)
                .append(", ").append(label).append("Exception=");
        if (!done) {
            return summary.append("<not completed>").toString();
        }
        try {
            future.get();
            return summary.append("<none>").toString();
        } catch (CancellationException exception) {
            summary.append(exception);
            appendFirstFrames(summary, exception);
            return summary.toString();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            summary.append(cause);
            appendFirstFrames(summary, cause);
            return summary.toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            summary.append(exception);
            appendFirstFrames(summary, exception);
            return summary.toString();
        }
    }

    private static void appendFirstFrames(StringBuilder summary, Throwable throwable) {
        StackTraceElement[] frames = throwable.getStackTrace();
        for (int index = 0; index < Math.min(5, frames.length); index++) {
            summary.append(System.lineSeparator()).append("    at ").append(frames[index]);
        }
    }

    private <T> T asActor(User actor, Supplier<T> action) {
        authenticateAs(actor, workspace.getId());
        try {
            return action.get();
        } finally {
            clearAuthentication();
        }
    }

    private User newOutcomeWaiter() {
        User waiter = newCampaignAdmin(workspace);
        assertNotEquals(currentUser.getId(), waiter.getId());
        return waiter;
    }

    private long currentEngineTransactionId() {
        Long transactionId = jdbcTemplate.queryForObject("""
                SELECT trx_id
                FROM information_schema.innodb_trx
                WHERE trx_mysql_thread_id = CONNECTION_ID()
                """, Long.class);
        if (transactionId == null) {
            throw new IllegalStateException("Campaign concurrency holder has no InnoDB transaction id");
        }
        return transactionId;
    }

    private TransactionTemplate transaction() {
        return transaction(30);
    }

    private TransactionTemplate outcomeTransaction() {
        return transaction(60);
    }

    private TransactionTemplate transaction(int timeoutSeconds) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(timeoutSeconds);
        return transaction;
    }

    private static long connectionId(Connection connection) {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT CONNECTION_ID()")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("MySQL did not return a connection id");
            }
            long connectionId = resultSet.getLong(1);
            if (resultSet.wasNull() || connectionId <= 0 || resultSet.next()) {
                throw new IllegalStateException("MySQL returned an invalid connection id");
            }
            return connectionId;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not capture the MySQL connection id", exception);
        }
    }

    private Person person(Company company, String name, String email) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Marketing contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private CampaignRequest campaignRequest(String name) {
        return new CampaignRequest(
                name, null, "email", null, currentUser.getId(), null, null,
                null, null, null);
    }

    private CampaignAudienceRequest audience(String prefix, String channel, String purpose) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(prefix);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition, channel, purpose);
    }

    private CampaignMessageRevisionRequest emailRevision() {
        return new CampaignMessageRevisionRequest(
                "en", "Hello", "<p>Hi</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>", null);
    }

    private static void assertScopeChange(
            JsonNode changes, AudienceScope previous, AudienceScope current) {
        assertEquals(previous.channel(), changes.path("channel").path("old").asText());
        assertEquals(current.channel(), changes.path("channel").path("new").asText());
        assertEquals(previous.purpose(), changes.path("purpose").path("old").asText());
        assertEquals(current.purpose(), changes.path("purpose").path("new").asText());
    }

    private static ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(task -> new Thread(task, name));
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(35, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Campaign concurrency worker did not terminate");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutexProbeConfiguration {
        @Bean
        MutexRequestProbe mutexRequestProbe() {
            return new MutexRequestProbe();
        }
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class})
    })
    static final class MutexRequestProbe implements Interceptor {
        private static final String MUTEX_STATEMENT_SUFFIX = "CampaignMapper.getCampaignForUpdate";

        private final ThreadLocal<ArmedProbe> armedProbe = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            ArmedProbe probe = armedProbe.get();
            if (probe != null
                    && invocation.getArgs()[0] instanceof MappedStatement statement
                    && statement.getId().endsWith(MUTEX_STATEMENT_SUFFIX)
                    && targetsCampaign(invocation.getArgs()[1], probe.campaignId())
                    && probe.captureOnce()) {
                Executor executor = (Executor) invocation.getTarget();
                Connection connection = executor.getTransaction().getConnection();
                probe.connectionSink().markWaiterConnection(
                        probe.campaignId(), connectionId(connection));
            }
            return invocation.proceed();
        }

        private void arm(int campaignId, WaiterConnectionSink connectionSink) {
            if (armedProbe.get() != null) {
                throw new IllegalStateException("Campaign mutex probe is already armed on this thread");
            }
            armedProbe.set(new ArmedProbe(campaignId, connectionSink));
        }

        private void clear() {
            armedProbe.remove();
        }

        private static boolean targetsCampaign(Object parameter, int campaignId) {
            MetaObject parameters = SystemMetaObject.forObject(parameter);
            if (!parameters.hasGetter("id")) {
                return false;
            }
            Object id = parameters.getValue("id");
            return id instanceof Number number && number.intValue() == campaignId;
        }

        private static final class ArmedProbe {
            private final int campaignId;
            private final WaiterConnectionSink connectionSink;
            private final AtomicBoolean captured = new AtomicBoolean();

            private ArmedProbe(int campaignId, WaiterConnectionSink connectionSink) {
                this.campaignId = campaignId;
                this.connectionSink = connectionSink;
            }

            private int campaignId() {
                return campaignId;
            }

            private WaiterConnectionSink connectionSink() {
                return connectionSink;
            }

            private boolean captureOnce() {
                return captured.compareAndSet(false, true);
            }
        }
    }

    private interface WaiterConnectionSink {
        void markWaiterConnection(int campaignId, long connectionId);
    }

    private static final class OutcomeBarrier implements WaiterConnectionSink {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch waiterConnectionCaptured = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch abort = new CountDownLatch(1);
        private final AtomicLong waiterConnectionId = new AtomicLong();
        private final AtomicLong holderConnectionId = new AtomicLong();
        private final AtomicLong observedWaitCount = new AtomicLong();
        private final AtomicLong observedWaitNanos = new AtomicLong();
        private final AtomicLong commitNanos = new AtomicLong();
        private final AtomicReference<Throwable> holderFailure = new AtomicReference<>();

        private void hold(
                TransactionStatus status,
                long connectionId,
                String timeoutMessage,
                Supplier<String> diagnosticDump) {
            if (!holderConnectionId.compareAndSet(0, connectionId)) {
                status.setRollbackOnly();
                throw new IllegalStateException("Campaign outcome holder connection was already captured");
            }
            held.countDown();
            try {
                if (!waiterConnectionCaptured.await(15, TimeUnit.SECONDS)) {
                    status.setRollbackOnly();
                    throw new AssertionError(
                            "Campaign serialization proof is inconclusive: " + timeoutMessage
                                    + System.lineSeparator() + diagnosticDump.get());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                status.setRollbackOnly();
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: holder wait was interrupted",
                        exception);
            }
            if (abort.getCount() == 0) {
                status.setRollbackOnly();
                return;
            }
            if (waiterConnectionId.get() == 0) {
                status.setRollbackOnly();
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: waiter connection was not captured");
            }
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    status.setRollbackOnly();
                    throw new AssertionError(
                            "Campaign serialization proof is inconclusive: observed-wait release timed out"
                                    + System.lineSeparator() + diagnosticDump.get());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                status.setRollbackOnly();
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: observed-wait release was interrupted",
                        exception);
            }
            if (abort.getCount() == 0) {
                status.setRollbackOnly();
            }
        }

        private void awaitHeld(String timeoutMessage, Supplier<String> diagnosticDump) {
            try {
                if (!held.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "Campaign serialization proof is inconclusive: " + timeoutMessage
                                    + System.lineSeparator() + holderFailureSummary()
                                    + System.lineSeparator() + diagnosticDump.get());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: holder wait was interrupted"
                                + System.lineSeparator() + holderFailureSummary()
                                + System.lineSeparator() + diagnosticDump.get(),
                        exception);
            }
        }

        @Override
        public void markWaiterConnection(int campaignId, long connectionId) {
            long captured = waiterConnectionId.get();
            if (captured != 0 && captured != connectionId) {
                throw new IllegalStateException("Campaign waiter changed MySQL connections");
            }
            if (waiterConnectionId.compareAndSet(0, connectionId)) {
                waiterConnectionCaptured.countDown();
            }
        }

        private void markCommitted(long committedAtNanos) {
            commitNanos.set(committedAtNanos);
        }

        private void markHolderFailure(Throwable exception) {
            holderFailure.compareAndSet(null, exception);
        }

        private String holderFailureSummary() {
            Throwable exception = holderFailure.get();
            return exception == null
                    ? "holderException=<none captured>"
                    : throwableSummary(exception);
        }

        private boolean awaitWaiterConnection(long timeout, TimeUnit unit)
                throws InterruptedException {
            return waiterConnectionCaptured.await(timeout, unit);
        }

        private void markWaitObserved(long waitCount, long observedAtNanos) {
            if (waitCount <= 0) {
                throw new IllegalArgumentException("Observed MySQL wait count must be positive");
            }
            observedWaitCount.set(waitCount);
            observedWaitNanos.set(observedAtNanos);
        }

        private void releaseForCommit() {
            release.countDown();
        }

        private void abort() {
            abort.countDown();
            waiterConnectionCaptured.countDown();
            release.countDown();
        }

        private long waiterConnectionId() {
            long connectionId = waiterConnectionId.get();
            if (connectionId == 0) {
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: waiter connection was not captured");
            }
            return connectionId;
        }

        private long holderConnectionId() {
            long connectionId = holderConnectionId.get();
            if (connectionId == 0) {
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: holder connection was not captured");
            }
            return connectionId;
        }

        private long waiterConnectionIdOrZero() {
            return waiterConnectionId.get();
        }

        private long holderConnectionIdOrZero() {
            return holderConnectionId.get();
        }

        private long observedWaitCount() {
            return observedWaitCount.get();
        }

        private long observedWaitNanos() {
            long timestamp = observedWaitNanos.get();
            if (timestamp == 0) {
                throw new AssertionError(
                        "Campaign serialization proof is inconclusive: MySQL wait was not observed");
            }
            return timestamp;
        }

        private long commitNanos() {
            long timestamp = commitNanos.get();
            if (timestamp == 0) {
                throw new IllegalStateException("Campaign concurrency holder did not reach commit");
            }
            return timestamp;
        }
    }

    private static final class CampaignPairBarrier implements WaiterConnectionSink {
        private final int firstCampaignId;
        private final int secondCampaignId;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch waiterConnectionsCaptured = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean commit = new AtomicBoolean();
        private final AtomicLong firstWaiterConnectionId = new AtomicLong();
        private final AtomicLong secondWaiterConnectionId = new AtomicLong();
        private final AtomicLong holderConnectionId = new AtomicLong();
        private final AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        private final AtomicReference<Future<?>> firstWaiterFuture = new AtomicReference<>();
        private final AtomicReference<Future<?>> secondWaiterFuture = new AtomicReference<>();

        private CampaignPairBarrier(int firstCampaignId, int secondCampaignId) {
            this.firstCampaignId = firstCampaignId;
            this.secondCampaignId = secondCampaignId;
        }

        private void hold(
                TransactionStatus status,
                long connectionId,
                String timeoutMessage,
                Supplier<String> diagnosticDump) {
            if (!holderConnectionId.compareAndSet(0, connectionId)) {
                status.setRollbackOnly();
                throw new IllegalStateException("Campaign pair holder connection was already captured");
            }
            held.countDown();
            try {
                if (!release.await(45, TimeUnit.SECONDS)) {
                    status.setRollbackOnly();
                    throw new AssertionError(
                            "Campaign pair concurrency proof is inconclusive: " + timeoutMessage
                                    + System.lineSeparator() + waiterFutureSummary()
                                    + System.lineSeparator() + diagnosticDump.get());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                status.setRollbackOnly();
                throw new AssertionError(
                        "Campaign pair concurrency proof is inconclusive: holder wait was interrupted"
                                + System.lineSeparator() + waiterFutureSummary(),
                        exception);
            }
            if (!commit.get()) {
                status.setRollbackOnly();
            }
        }

        private void awaitHeld(String timeoutMessage) {
            try {
                if (!held.await(20, TimeUnit.SECONDS)) {
                    throw new AssertionError(
                            "Campaign pair concurrency proof is inconclusive: " + timeoutMessage
                                    + System.lineSeparator() + holderFailureSummary()
                                    + System.lineSeparator() + waiterFutureSummary());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Campaign pair concurrency proof is inconclusive: holder wait was interrupted"
                                + System.lineSeparator() + holderFailureSummary()
                                + System.lineSeparator() + waiterFutureSummary(),
                        exception);
            }
        }

        @Override
        public void markWaiterConnection(int campaignId, long connectionId) {
            AtomicLong connection = connectionFor(campaignId);
            long captured = connection.get();
            if (captured != 0 && captured != connectionId) {
                throw new IllegalStateException(
                        "Campaign " + campaignId + " waiter changed MySQL connections");
            }
            if (connection.compareAndSet(0, connectionId)) {
                waiterConnectionsCaptured.countDown();
            }
        }

        private AtomicLong connectionFor(int campaignId) {
            if (campaignId == firstCampaignId) {
                return firstWaiterConnectionId;
            }
            if (campaignId == secondCampaignId) {
                return secondWaiterConnectionId;
            }
            throw new IllegalArgumentException("Unexpected campaign waiter " + campaignId);
        }

        private boolean awaitWaiterConnections(long timeout, TimeUnit unit)
                throws InterruptedException {
            return waiterConnectionsCaptured.await(timeout, unit);
        }

        private void trackWaiters(Future<?> first, Future<?> second) {
            if (!firstWaiterFuture.compareAndSet(null, first)
                    || !secondWaiterFuture.compareAndSet(null, second)) {
                throw new IllegalStateException("Campaign pair waiter futures were already captured");
            }
        }

        private String waiterFutureSummary() {
            return CampaignConcurrencyIntegrationTest.waiterFutureSummary(
                    "firstWaiterFuture", firstWaiterFuture.get())
                    + System.lineSeparator()
                    + CampaignConcurrencyIntegrationTest.waiterFutureSummary(
                            "secondWaiterFuture", secondWaiterFuture.get());
        }

        private void releaseForCommit() {
            commit.set(true);
            release.countDown();
        }

        private void abort() {
            waiterConnectionsCaptured.countDown();
            waiterConnectionsCaptured.countDown();
            release.countDown();
        }

        private void markHolderFailure(Throwable exception) {
            holderFailure.compareAndSet(null, exception);
        }

        private String holderFailureSummary() {
            Throwable exception = holderFailure.get();
            return exception == null
                    ? "holderException=<none captured>"
                    : throwableSummary(exception);
        }

        private int firstCampaignId() {
            return firstCampaignId;
        }

        private int secondCampaignId() {
            return secondCampaignId;
        }

        private long firstWaiterConnectionId() {
            return requiredConnectionId(
                    firstWaiterConnectionId.get(), "first campaign waiter");
        }

        private long secondWaiterConnectionId() {
            return requiredConnectionId(
                    secondWaiterConnectionId.get(), "second campaign waiter");
        }

        private long holderConnectionId() {
            return requiredConnectionId(holderConnectionId.get(), "campaign pair holder");
        }

        private long firstWaiterConnectionIdOrZero() {
            return firstWaiterConnectionId.get();
        }

        private long secondWaiterConnectionIdOrZero() {
            return secondWaiterConnectionId.get();
        }

        private long holderConnectionIdOrZero() {
            return holderConnectionId.get();
        }

        private long requiredConnectionId(long connectionId, String description) {
            if (connectionId == 0) {
                throw new AssertionError(
                        "Campaign pair concurrency proof is inconclusive: "
                                + description + " connection was not captured"
                                + System.lineSeparator() + waiterFutureSummary());
            }
            return connectionId;
        }
    }

    private static final class TransactionBarrier {
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean commit = new AtomicBoolean();
        private final AtomicLong engineTransactionId = new AtomicLong();

        private void hold(
                TransactionStatus status, long transactionId, String timeoutMessage) {
            engineTransactionId.set(transactionId);
            held.countDown();
            await(release, timeoutMessage);
            if (!commit.get()) {
                status.setRollbackOnly();
            }
        }

        private void awaitHeld(String timeoutMessage) {
            await(held, timeoutMessage);
        }

        private void releaseForCommit() {
            commit.set(true);
            release.countDown();
        }

        private void abort() {
            release.countDown();
        }

        private long engineTransactionId() {
            long transactionId = engineTransactionId.get();
            if (transactionId == 0) {
                throw new IllegalStateException("Campaign concurrency holder transaction was not captured");
            }
            return transactionId;
        }
    }

    private record AudienceScope(String channel, String purpose) {
    }

    private record AuditScopeChange(int actorId, String changes) {
    }

    private record ObservedLockWait(
            long requestingConnectionId,
            long blockingConnectionId,
            String objectSchema,
            String objectName,
            String indexName,
            String lockType,
            String lockMode,
            String lockData) {
    }

    private record SendInputs(
            CampaignAudienceSnapshotDto snapshot,
            CampaignMessageDto message) {
    }

    private record TimedResult<T>(T value, long startNanos, long doneNanos) {
    }

    private enum CampaignMutation {
        UPDATE {
            @Override
            void execute(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    CampaignDto campaign,
                    Optional<CampaignMessageDto> message,
                    String prefix,
                    int ownerId) {
                campaignService.update(campaign.id(), new CampaignRequest(
                        "Changed " + prefix, null, "email", "active", ownerId,
                        null, null, null, null, null));
            }

            @Override
            void assertApplied(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    int campaignId) {
                assertEquals("active", campaignService.get(campaignId).status());
            }
        },
        AUDIENCE {
            @Override
            void execute(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    CampaignDto campaign,
                    Optional<CampaignMessageDto> message,
                    String prefix,
                    int ownerId) {
                campaignService.setAudience(
                        campaign.id(), audienceRequest(prefix, "sms", "product_update"));
            }

            @Override
            void assertApplied(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    int campaignId) {
                assertEquals("sms", campaignService.getAudience(campaignId).channel());
                assertEquals("product_update", campaignService.getAudience(campaignId).purpose());
            }
        },
        SNAPSHOT {
            @Override
            void execute(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    CampaignDto campaign,
                    Optional<CampaignMessageDto> message,
                    String prefix,
                    int ownerId) {
                campaignService.snapshotAudience(campaign.id());
            }

            @Override
            void assertApplied(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    int campaignId) {
                assertEquals(1, campaignService.listSnapshots(campaignId).size());
            }
        },
        SEND {
            @Override
            void execute(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    CampaignDto campaign,
                    Optional<CampaignMessageDto> message,
                    String prefix,
                    int ownerId) {
                CampaignMessageDto configuredMessage = message.orElseThrow();
                campaignSendService.createSend(campaign.id(), new CampaignSendRequest(
                        1, configuredMessage.id(), 1, "marketing", null));
            }

            @Override
            void assertApplied(
                    CampaignService campaignService,
                    CampaignSendService campaignSendService,
                    int campaignId) {
                assertEquals(1, campaignSendService.listSends(campaignId).size());
            }
        };

        abstract void execute(
                CampaignService campaignService,
                CampaignSendService campaignSendService,
                CampaignDto campaign,
                Optional<CampaignMessageDto> message,
                String prefix,
                int ownerId);

        abstract void assertApplied(
                CampaignService campaignService,
                CampaignSendService campaignSendService,
                int campaignId);

        private static CampaignAudienceRequest audienceRequest(
                String prefix, String channel, String purpose) {
            SegmentCondition condition = new SegmentCondition();
            condition.setType("field");
            condition.setField("name");
            condition.setOp("starts_with");
            condition.setValue(prefix);
            SegmentDefinition definition = new SegmentDefinition();
            definition.setMatch("all");
            definition.setConditions(List.of(condition));
            return new CampaignAudienceRequest("person", definition, channel, purpose);
        }
    }
}
