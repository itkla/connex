package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.delivery.CampaignUnsubscribeTokenRegistrationService;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Pins the registrar's parent lock and stale-owner fence using observed physical MySQL waits. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignUnsubscribeTokenConcurrencyIntegrationTest extends CampaignRealDbTestSupport {
    private static final String FINGERPRINT = "f".repeat(64);
    @Autowired private CampaignUnsubscribeTokenRegistrationService registration;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private CampaignMessageMapper messageMapper;
    @Autowired private CampaignSendMapper sendMapper;
    @MockitoSpyBean private CampaignDeliveryMapper deliveryMapper;
    @Autowired private SqlSession sqlSession;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationBlocksRecoveryOrDeletionUntilCommitWhileUnrelatedDeliveryProgresses(boolean delete)
            throws Exception {
        List<Integer> deliveries = deliveries();
        int deliveryId = deliveries.getFirst();
        int unrelatedId = deliveries.getLast();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), deliveryId,
                "registrar", 1_000_000L, "http_esp", FINGERPRINT));
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), unrelatedId,
                "unrelated", 60_000_000L, "http_esp", FINGERPRINT));
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CampaignDeliveryMapper real = sqlSession.getMapper(CampaignDeliveryMapper.class);
        doAnswer(invocation -> {
            boolean owned = real.claimStillOwned(workspace.getId(), deliveryId, "registrar");
            assertTrue(owned);
            validated.countDown();
            await(release);
            return owned;
        }).when(deliveryMapper).claimStillOwned(workspace.getId(), deliveryId, "registrar");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> registrar = executor.submit(() -> registration.register(
                    workspace.getId(), deliveryId, "registrar", "a".repeat(64)));
            await(validated);
            awaitExpiry(deliveryId);
            AtomicLong waitingConnection = new AtomicLong();
            CountDownLatch attempting = new CountDownLatch(1);
            Future<Integer> contender = executor.submit(() -> transaction().execute(status -> {
                waitingConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                attempting.countDown();
                return delete
                        ? jdbc.update("DELETE FROM campaign_delivery WHERE workspace_id = ? AND id = ?",
                                workspace.getId(), deliveryId)
                        : deliveryMapper.recoverExpiredTriggeredClaim(workspace.getId(), deliveryId, FINGERPRINT);
            }));
            await(attempting);
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(),
                    "campaign_delivery", String.valueOf(deliveryId));
            assertTrue(registration.register(workspace.getId(), unrelatedId, "unrelated", "b".repeat(64)));
            assertEquals(0, digestCount(deliveryId));
            release.countDown();
            assertTrue(registrar.get(20, TimeUnit.SECONDS));
            assertEquals(1, contender.get(20, TimeUnit.SECONDS));
            assertEquals(delete ? 0 : 1, digestCount(deliveryId));
            assertEquals(1, digestCount(unrelatedId));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void waitingStaleOwnerIsRevalidatedAfterTheLockIsGranted() throws Exception {
        int deliveryId = deliveries().getFirst();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), deliveryId,
                "stale", 60_000_000L, "http_esp", FINGERPRINT));
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transaction().executeWithoutResult(status -> {
                deliveryMapper.getDeliveryForUpdate(workspace.getId(), deliveryId);
                assertEquals(1, deliveryMapper.releaseTriggeredClaim(workspace.getId(), deliveryId, "stale", null));
                assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), deliveryId,
                        "replacement", 60_000_000L, "http_esp", FINGERPRINT));
                holderLocked.countDown();
                await(release);
            }));
            await(holderLocked);
            Future<Boolean> registrar = executor.submit(() -> transaction().execute(status -> {
                waitingConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                attempting.countDown();
                return registration.register(workspace.getId(), deliveryId, "stale", "c".repeat(64));
            }));
            await(attempting);
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, waitingConnection.get(),
                    "campaign_delivery", String.valueOf(deliveryId));
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
            assertFalse(registrar.get(20, TimeUnit.SECONDS));
            assertEquals(0, digestCount(deliveryId));
            assertTrue(registration.register(workspace.getId(), deliveryId, "replacement", "d".repeat(64)));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiredOwnerAndMissingDeliveryCannotRegister() {
        int deliveryId = deliveries().getFirst();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), deliveryId,
                "expired", 60_000_000L, "http_esp", FINGERPRINT));
        jdbc.update("UPDATE campaign_delivery SET dispatch_lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), deliveryId);
        assertFalse(registration.register(workspace.getId(), deliveryId, "expired", "e".repeat(64)));
        assertFalse(registration.register(workspace.getId(), Integer.MAX_VALUE, "expired", "e".repeat(64)));
        assertEquals(0, digestCount(deliveryId));
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private int digestCount(int deliveryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_delivery_unsubscribe_token WHERE delivery_id = ?",
                Integer.class, deliveryId);
    }

    private void awaitExpiry(int deliveryId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT dispatch_lease_until < UTC_TIMESTAMP(6)"
                    + " FROM campaign_delivery WHERE id = ?", Boolean.class, deliveryId))) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Claim did not expire on the database clock");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private List<Integer> deliveries() {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName("Token registration concurrency");
        campaign.setType("email");
        campaign.setStatus("active");
        campaignMapper.insertCampaign(campaign);
        CampaignMessage message = new CampaignMessage();
        message.setWorkspaceId(workspace.getId());
        message.setCampaignId(campaign.getId());
        message.setChannel("email");
        message.setName("Concurrency");
        message.setStatus("final");
        messageMapper.insertMessage(message);
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setCampaignId(campaign.getId());
        snapshot.setVersion(1);
        snapshot.setRecordType("person");
        snapshot.setDefinitionJson("{}");
        snapshot.setChannel("email");
        snapshot.setPurpose("marketing");
        snapshot.setOrigin("triggered");
        snapshot.setTriggeredMessageId(message.getId());
        snapshot.setTriggeredMessageVersion(1);
        campaignMapper.insertSnapshot(snapshot);
        CampaignSend send = new CampaignSend();
        send.setWorkspaceId(workspace.getId());
        send.setCampaignId(campaign.getId());
        send.setSnapshotId(snapshot.getId());
        send.setMessageId(message.getId());
        send.setMessageVersion(1);
        send.setChannel("email");
        send.setPurpose("marketing");
        send.setOrigin("triggered");
        send.setStatus("triggered");
        sendMapper.insertSend(send);
        CampaignDelivery first = new CampaignDelivery();
        first.setSendId(send.getId());
        first.setAddress("first@example.test");
        first.setStatus("pending");
        CampaignDelivery second = new CampaignDelivery();
        second.setSendId(send.getId());
        second.setAddress("second@example.test");
        second.setStatus("pending");
        deliveryMapper.insertDeliveries(workspace.getId(), List.of(first, second));
        return deliveryMapper.pendingDeliveryIds(workspace.getId(), send.getId());
    }
}
