package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.DeliveryProviderConfig;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.CampaignDispatchClaimBoundary;
import ooo.klae.connex.backend.delivery.CampaignFrequencyAdmissionService;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProperties;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchStatus;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.RenderedMessage;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.esp.HttpEspDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.http.DeadlineBoundHttpTransport;
import ooo.klae.connex.backend.delivery.provider.sms.SmsHttpDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignDeliveryReconciliationRequest;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.DeliveryProviderConfigRequest;
import ooo.klae.connex.backend.dto.DeliveryWebhookTokenDto;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.DeliveryProviderConfigMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.secrets.SecretPurpose;
import ooo.klae.connex.backend.tenant.Permission;

import tools.jackson.databind.ObjectMapper;

/** Exercises channel secrets and receipt-aware frequency caps through real services and mappers. */
@TestPropertySource(properties = {
        "connex.delivery.enabled=true",
        "connex.delivery.audience-export-provider-deadline-ms=60000"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliverySecurityIntegrationTest extends CampaignRealDbTestSupport {

    private static final String LEGACY_SHARED_CREDENTIAL_PURPOSE = "workspace.delivery.provider_credential";
    private static final long DISPATCH_LEASE_MICROS = 60_000_000L;
    private static final String EXPIRED_AUDIENCE_RESERVATION =
            "AMBIGUOUS: Audience dispatch did not finish before its reservation expired";
    private static final String EXPIRED_NON_IDEMPOTENT_CLAIM =
            "AMBIGUOUS: Worker claim expired on a transport without idempotent submission";
    private static final String EXPIRED_CHANGED_TARGET_CLAIM =
            "AMBIGUOUS: Worker claim expired after the delivery target changed";
    private static final String EARLIER_ATTEMPT_AMBIGUOUS =
            "AMBIGUOUS: An earlier provider attempt requires reconciliation";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @Autowired private DeliveryProviderConfigService configService;
    @MockitoSpyBean private DeliveryProviderConfigMapper configMapper;
    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService sendService;
    @Autowired private CampaignDispatchService dispatchService;
    @Autowired private CampaignTriggeredSendService triggeredSendService;
    @MockitoSpyBean private CampaignDeliveryMapper deliveryMapper;
    @Autowired private DeliveryWebhookService webhookService;
    @Autowired private ConsentService consentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private CampaignFrequencyAdmissionService frequencyAdmissionService;
    @MockitoSpyBean private CampaignSendMapper campaignSendMapper;
    @Autowired private AudienceEligibilityService audienceEligibilityService;
    @Autowired private DeliveryProperties deliveryProperties;
    @MockitoSpyBean private WorkspaceMapper workspaceMapperSpy;
    @MockitoSpyBean private CampaignDispatchClaimBoundary claimBoundary;
    @MockitoSpyBean private WorkflowTriggeredSendGate triggeredSendGate;
    @MockitoBean private AiEndpointAddressValidator endpointValidator;
    @MockitoBean private DeliveryProviderRouter router;

    private final List<RecordedSubmission> submissions = new CopyOnWriteArrayList<>();
    private final ThreadLocal<String> operation = new ThreadLocal<>();
    private Runnable submissionObserver = () -> {};
    private int responseStatus = 200;
    private boolean responseOmitsMessageId;
    private MockedConstruction<DeadlineBoundHttpTransport> transports;

    /** Uses a member with explicit grants so fixtures exercise permissions without privileged MFA. */
    @Override
    protected User newCampaignAdmin(Workspace targetWorkspace) {
        return newCampaignActor(targetWorkspace, List.of(
                Permission.WORKSPACE_SETTINGS, Permission.CAMPAIGN_VIEW, Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND, Permission.CONSENT_MANAGE)).actor();
    }

    @BeforeEach
    void recordProviderTransports() {
        when(endpointValidator.isFetchable(anyString(), eq(false))).thenReturn(true);
        transports = mockConstruction(DeadlineBoundHttpTransport.class, (transport, context) ->
                when(transport.post(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
                    URI endpoint = invocation.getArgument(0);
                    Map<String, String> headers = invocation.getArgument(1);
                    boolean omitsMessageId = responseOmitsMessageId;
                    submissions.add(new RecordedSubmission(endpoint, Map.copyOf(headers)));
                    int submission = submissions.size();
                    submissionObserver.run();
                    byte[] body = (omitsMessageId
                            ? "{}"
                            : "{\"messageId\":\"message-" + submission + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    return new DeadlineBoundHttpTransport.Response(responseStatus, body);
                }));
        HttpEspDeliveryProvider email = new HttpEspDeliveryProvider(new DeliveryProperties(), new ObjectMapper());
        SmsHttpDeliveryProvider sms = new SmsHttpDeliveryProvider(new DeliveryProperties(), new ObjectMapper());
        when(router.dispatcherFor(HttpEspDeliveryProvider.PROVIDER_ID)).thenReturn(email);
        when(router.dispatcherFor(SmsHttpDeliveryProvider.PROVIDER_ID)).thenReturn(sms);
        when(router.eventSourceFor(HttpEspDeliveryProvider.PROVIDER_ID)).thenReturn(email);
    }

    @AfterEach
    void closeRecordingTransports() {
        ReflectionTestUtils.setField(dispatchService, "nanoTimeSource", (LongSupplier) System::nanoTime);
        if (transports != null) {
            transports.close();
        }
        jdbcTemplate.update("DELETE FROM secret_value WHERE scope_type = 'workspace' AND scope_id = ?",
                workspace.getId());
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryChannel.class, names = {"EMAIL", "SMS"})
    void channelKeysRemainIsolatedThroughSaveRotateAndDelete(DeliveryChannel changedChannel) {
        DeliveryChannel otherChannel = changedChannel == DeliveryChannel.EMAIL
                ? DeliveryChannel.SMS : DeliveryChannel.EMAIL;
        configService.save(providerRequest(changedChannel, key(changedChannel)));
        configService.save(providerRequest(otherChannel, key(otherChannel)));
        String changedReference = storedConfig(changedChannel).getCredentialRef();
        String otherReference = storedConfig(otherChannel).getCredentialRef();
        assertNotEquals(changedReference, otherReference);
        assertDispatchCredential(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        assertDispatchCredential(DeliveryChannel.SMS, key(DeliveryChannel.SMS));

        configService.save(providerRequest(changedChannel, "rotated-key"));
        assertEquals(changedReference, storedConfig(changedChannel).getCredentialRef());
        assertEquals(otherReference, storedConfig(otherChannel).getCredentialRef());
        assertDispatchCredential(changedChannel, "rotated-key");
        assertDispatchCredential(otherChannel, key(otherChannel));

        configService.delete(changedChannel.token());
        assertNull(storedConfig(changedChannel));
        assertDispatchCredential(otherChannel, key(otherChannel));
        configService.save(providerRequest(changedChannel, "replacement-key"));
        assertDispatchCredential(changedChannel, "replacement-key");
        assertDispatchCredential(otherChannel, key(otherChannel));
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryChannel.class, names = {"EMAIL", "SMS"})
    void resolutionKeepsEndpointAndCredentialTogetherWhileRotationWaits(DeliveryChannel channel) throws Exception {
        configService.save(providerRequest(channel, "key-a"));
        DeliveryProviderConfig before = storedConfig(channel);
        DeliveryProviderConfigRequest rotation = providerRequest(channel, "key-b");
        rotation.setEndpoint("https://rotated.provider.test/send");
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch writerAttempted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        DeliveryProviderConfigMapper realConfig = sqlSession.getMapper(DeliveryProviderConfigMapper.class);
        WorkspaceMapper realWorkspace = sqlSession.getMapper(WorkspaceMapper.class);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            DeliveryProviderConfig current = realConfig.findByWorkspaceChannelForShare(workspace.getId(), channel.token());
            if ("reader".equals(operation.get()) && firstRead.compareAndSet(true, false)) {
                readerLocked.countDown();
                await(releaseReader);
            }
            return current;
        }).when(configMapper).findByWorkspaceChannelForShare(workspace.getId(), channel.token());
        doAnswer(invocation -> {
            writerAttempted.countDown();
            return realWorkspace.lockActiveIdentity(workspace.getId());
        }).when(workspaceMapperSpy).lockActiveIdentity(workspace.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResolvedDeliveryProvider> reader = executor.submit(() -> asActor("reader",
                    () -> configService.resolveForWorkspace(workspace.getId(), channel)));
            assertTrue(readerLocked.await(20, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> asActor("writer", () -> configService.save(rotation)));
            assertTrue(writerAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> writer.get(500, TimeUnit.MILLISECONDS));
            releaseReader.countDown();
            ResolvedDeliveryProvider resolved = reader.get(20, TimeUnit.SECONDS);
            writer.get(20, TimeUnit.SECONDS);
            assertEquals(before.getConfigGeneration(), resolved.configGeneration());
            assertResolvedCredential(resolved, before.getEndpoint(), "key-a");
            ResolvedDeliveryProvider rotated = configService.resolveForWorkspace(workspace.getId(), channel);
            assertEquals(before.getCredentialRef(), storedConfig(channel).getCredentialRef());
            assertTrue(rotated.configGeneration() > resolved.configGeneration());
            assertResolvedCredential(rotated, rotation.getEndpoint(), "key-b");
        } finally {
            releaseReader.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryChannel.class, names = {"EMAIL", "SMS"})
    void staleBlankKeySaveWaitsForRotationAndRefusesTheOldEndpoint(DeliveryChannel channel) throws Exception {
        configService.save(providerRequest(channel, "key-a"));
        DeliveryProviderConfig before = storedConfig(channel);
        DeliveryProviderConfigRequest rotation = providerRequest(channel, "key-b");
        rotation.setEndpoint("https://rotated.provider.test/send");
        DeliveryProviderConfigRequest stale = providerRequest(channel, "");
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch staleAttempted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        WorkspaceMapper realWorkspace = sqlSession.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if ("stale".equals(operation.get())) {
                staleAttempted.countDown();
            }
            Workspace locked = realWorkspace.lockActiveIdentity(workspace.getId());
            if ("writer".equals(operation.get())) {
                writerLocked.countDown();
                await(releaseWriter);
            }
            return locked;
        }).when(workspaceMapperSpy).lockActiveIdentity(workspace.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> asActor("writer", () -> configService.save(rotation)));
            assertTrue(writerLocked.await(20, TimeUnit.SECONDS));
            Future<?> oldSave = executor.submit(() -> asActor("stale", () -> configService.save(stale)));
            assertTrue(staleAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> oldSave.get(500, TimeUnit.MILLISECONDS));
            releaseWriter.countDown();
            writer.get(20, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> oldSave.get(20, TimeUnit.SECONDS));
            assertInstanceOf(BadRequestException.class, failure.getCause());
            assertEquals(before.getConfigGeneration() + 1, storedConfig(channel).getConfigGeneration());
            assertResolvedCredential(configService.resolveForWorkspace(workspace.getId(), channel),
                    rotation.getEndpoint(), "key-b");
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryChannel.class, names = {"EMAIL", "SMS"})
    void rotationAfterResolutionRefusesEgressOnTheOwnedClaim(DeliveryChannel channel) {
        Person person = recipient();
        configService.save(providerRequest(channel, "key-a"));
        CampaignSendDto send = readySend(person, channel);
        int deliveryId = pendingDelivery(send);
        DeliveryProviderConfigRequest rotation = providerRequest(channel, "key-b");
        rotation.setEndpoint("https://rotated.provider.test/send");
        doAnswer(invocation -> {
            configService.save(rotation);
            return null;
        }).when(claimBoundary).afterClaim(workspace.getId(), deliveryId);

        dispatch(send);

        CampaignDelivery refused = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", refused.getStatus());
        assertEquals("delivery_target_changed", refused.getLastErrorCode());
        assertNull(refused.getReconciliationRequiredAt());
        assertNull(refused.getFrequencyReservedAt());
        assertNull(refused.getSubmittedAt());
        assertEquals(0, submissions.size());
        assertResolvedCredential(configService.resolveForWorkspace(workspace.getId(), channel),
                rotation.getEndpoint(), "key-b");
    }

    @ParameterizedTest
    @CsvSource({"EMAIL, delivered", "EMAIL, not_delivered", "SMS, delivered", "SMS, not_delivered"})
    void rotationAfterRecoveredSubmissionRemainsReconcilable(DeliveryChannel channel, String resolution) {
        Person person = recipient();
        DeliveryProviderConfigRequest provider = providerRequest(channel, "key-a");
        provider.setIdempotentSubmission(true);
        configService.save(provider);
        CampaignSendDto first = readySend(person, channel);
        int firstId = pendingDelivery(first);
        asTriggeredSend(first);
        when(triggeredSendGate.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            throw new AssertionError("Simulated worker loss after provider submission");
        }).when(deliveryMapper).markTriggeredDispatched(
                eq(workspace.getId()), eq(firstId), anyString(), anyString(), anyString());

        AssertionError workerLoss = assertThrows(AssertionError.class,
                () -> dispatchService.processSend(workspace.getId(), first.id()));
        assertEquals("Simulated worker loss after provider submission", workerLoss.getMessage());

        CampaignDelivery abandoned = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("dispatching", abandoned.getStatus());
        assertEquals(1, abandoned.getAttemptCount());
        assertNotNull(abandoned.getFrequencyReservedAt());
        assertNull(abandoned.getSubmittedAt());
        assertEquals(1, submissions.size());
        assertEquals(URI.create(provider.getEndpoint()), submissions.getFirst().endpoint());
        assertEquals("Bearer key-a", submissions.getFirst().headers().get("Authorization"));
        expireDispatchLease(firstId);
        when(triggeredSendGate.enabled()).thenReturn(false);
        assertTrue(dispatchService.processSend(workspace.getId(), first.id()));
        CampaignDelivery recovered = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("pending", recovered.getStatus());
        assertEquals("deadline_ambiguous", recovered.getLastErrorCode());
        assertEquals(abandoned.getFrequencyReservedAt(), recovered.getFrequencyReservedAt());
        assertNull(recovered.getReconciliationRequiredAt());

        DeliveryProviderConfigRequest rotation = providerRequest(channel, "key-b");
        rotation.setEndpoint("https://rotated.provider.test/send");
        doAnswer(invocation -> {
            CampaignDelivery reclaimed = deliveryMapper.getDelivery(workspace.getId(), firstId);
            assertEquals("dispatching", reclaimed.getStatus());
            assertEquals(2, reclaimed.getAttemptCount());
            assertEquals("deadline_ambiguous", reclaimed.getLastErrorCode());
            configService.save(rotation);
            return null;
        }).when(claimBoundary).afterClaim(workspace.getId(), firstId);
        when(triggeredSendGate.enabled()).thenReturn(true);

        assertTrue(dispatchService.processSend(workspace.getId(), first.id()));

        CampaignDelivery refused = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("failed", refused.getStatus());
        assertEquals("deadline_ambiguous", refused.getLastErrorCode());
        assertTrue(refused.getLastError().startsWith("AMBIGUOUS:"));
        assertNotNull(refused.getReconciliationRequiredAt());
        assertNull(refused.getReconciliationOutcome());
        assertNotNull(refused.getFrequencyReservedAt());
        assertNull(refused.getSubmittedAt());
        var recipientRow = deliveryMapper.listRecipients(workspace.getId(), first.campaignId(), first.id(),
                List.of("failed"), null, 10, 0).getFirst();
        assertEquals(refused.getReconciliationRequiredAt(), recipientRow.reconciliationRequiredAt());
        assertEquals("deadline_ambiguous", recipientRow.reasonCode());
        assertEquals(1, submissions.size());
        CampaignSendDto blockedSend = readySend(person, channel);
        int blockedId = pendingDelivery(blockedSend);
        dispatch(blockedSend);
        assertEquals("frequency_capped", deliveryMapper.getDelivery(workspace.getId(), blockedId).getSkipReason());
        assertEquals(1, submissions.size());

        var result = triggeredSendService.reconcile(first.campaignId(), firstId,
                new CampaignDeliveryReconciliationRequest(resolution));

        boolean delivered = "delivered".equals(resolution);
        assertEquals(delivered ? "dispatched" : "failed", result.status());
        assertFalse(result.reconciliationRequired());
        CampaignDelivery reconciled = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("operator_" + resolution, reconciled.getReconciliationOutcome());
        assertNull(reconciled.getReconciliationRequiredAt());
        assertNull(reconciled.getLastErrorCode());
        if (delivered) {
            assertEquals(refused.getFrequencyReservedAt(), reconciled.getFrequencyReservedAt());
            assertNotNull(reconciled.getSubmittedAt());
        } else {
            assertNull(reconciled.getFrequencyReservedAt());
            assertNull(reconciled.getSubmittedAt());
        }
        assertEquals(1, submissions.size());
        CampaignSendDto following = readySend(person, channel);
        int followingId = pendingDelivery(following);
        dispatch(following);
        CampaignDelivery next = deliveryMapper.getDelivery(workspace.getId(), followingId);
        assertEquals(delivered ? "skipped" : "dispatched", next.getStatus());
        assertEquals(delivered ? "frequency_capped" : null, next.getSkipReason());
        assertEquals(delivered ? 1 : 2, submissions.size());
    }

    @Test
    void v207ClearsAmbiguousReferencesAndRequiresBothKeysToBeReentered() throws Exception {
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        configService.save(providerRequest(DeliveryChannel.SMS, key(DeliveryChannel.SMS)));
        DeliveryProviderConfig sms = storedConfig(DeliveryChannel.SMS);
        sms.setCredentialRef(storedConfig(DeliveryChannel.EMAIL).getCredentialRef());
        sms.setEnabled(false);
        configMapper.upsert(sms, false);
        long emailGeneration = storedConfig(DeliveryChannel.EMAIL).getConfigGeneration();
        long smsGeneration = storedConfig(DeliveryChannel.SMS).getConfigGeneration();
        seedLegacySharedCredentialRow();
        assertEquals(1, secretRowsWithPurpose(LEGACY_SHARED_CREDENTIAL_PURPOSE));

        try (InputStream script = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/tenant/V207__reset_ambiguous_delivery_credentials.sql"))) {
            assertTrue(jdbcTemplate.update(new String(script.readAllBytes(), StandardCharsets.UTF_8)) >= 2);
        }
        try (InputStream purge = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/control/V209__purge_shared_delivery_credential_secrets.sql"))) {
            assertTrue(jdbcTemplate.update(new String(purge.readAllBytes(), StandardCharsets.UTF_8)) >= 1);
        }
        sqlSession.clearCache();

        assertEquals(0, secretRowsWithPurpose(LEGACY_SHARED_CREDENTIAL_PURPOSE));
        assertEquals(1, secretRowsWithPurpose(
                SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL_EMAIL.value()));
        assertEquals(1, secretRowsWithPurpose(
                SecretPurpose.WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL_SMS.value()));
        for (DeliveryChannel channel : List.of(DeliveryChannel.EMAIL, DeliveryChannel.SMS)) {
            DeliveryProviderConfig config = storedConfig(channel);
            assertNull(config.getCredentialRef());
            assertNull(config.getCredentialLast4());
            assertFalse(config.isEnabled());
            assertEquals((channel == DeliveryChannel.EMAIL ? emailGeneration : smsGeneration) + 1,
                    config.getConfigGeneration());
            assertThrows(BadRequestException.class,
                    () -> configService.save(providerRequest(channel, "")));
            configService.save(providerRequest(channel, key(channel)));
            assertDispatchCredential(channel, key(channel));
        }
    }

    @Test
    void deliveredReceiptCannotReopenTheFrequencyWindowAndSmsRemainsIndependent() throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        configService.save(providerRequest(DeliveryChannel.SMS, key(DeliveryChannel.SMS)));
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int firstDeliveryId = pendingDelivery(first);
        int secondDeliveryId = pendingDelivery(second);
        dispatch(first);
        CampaignDelivery firstDelivery = deliveryMapper.getDelivery(workspace.getId(), firstDeliveryId);
        assertEquals("dispatched", firstDelivery.getStatus());
        ingestDelivered(firstDelivery);
        assertEquals("delivered", deliveryMapper.getDelivery(workspace.getId(), firstDeliveryId).getStatus());

        dispatch(second);

        CampaignDelivery capped = deliveryMapper.getDelivery(workspace.getId(), secondDeliveryId);
        assertEquals("skipped", capped.getStatus());
        assertEquals("frequency_capped", capped.getSkipReason());
        assertEquals(1, submissions.size());
        assertEquals("Bearer " + key(DeliveryChannel.EMAIL), submissions.getFirst().headers().get("Authorization"));
        dispatch(readySend(person, DeliveryChannel.SMS));
        assertEquals(2, submissions.size());
        assertEquals("Bearer " + key(DeliveryChannel.SMS), submissions.getLast().headers().get("Authorization"));
    }

    @Test
    void missingDispatchEventAndLateReceiptDoNotReopenExpiredSubmissionWindow() throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(first);
        dispatch(first);
        assertNotNull(deliveryMapper.getDelivery(workspace.getId(), deliveryId).getSubmittedAt());
        LocalDateTime submittedAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(25).withNano(0);
        jdbcTemplate.update("UPDATE campaign_delivery SET submitted_at = ?, updated_at = ?"
                        + " WHERE workspace_id = ? AND id = ?",
                submittedAt, submittedAt, workspace.getId(), deliveryId);
        jdbcTemplate.update("DELETE FROM campaign_delivery_event"
                        + " WHERE workspace_id = ? AND delivery_id = ? AND event_type = 'dispatched'",
                workspace.getId(), deliveryId);
        assertEquals(0, recentCount(person));
        ingestDelivered(deliveryMapper.getDelivery(workspace.getId(), deliveryId));

        assertEquals(0, recentCount(person));
        assertEquals(submittedAt, deliveryMapper.getDelivery(workspace.getId(), deliveryId).getSubmittedAt());
        dispatch(readySend(person, DeliveryChannel.EMAIL));
        assertEquals(2, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "bounced", "complained", "failed"})
    void submittedDeliveryCountsAfterEveryReceiptStatus(String status) {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        dispatch(send);

        assertEquals(1, deliveryMapper.applyProviderStatus(workspace.getId(), deliveryId, status,
                List.of("dispatched")));
        assertEquals(1, recentCount(person));

        jdbcTemplate.update("DELETE FROM campaign_delivery_event"
                        + " WHERE workspace_id = ? AND delivery_id = ? AND event_type = 'dispatched'",
                workspace.getId(), deliveryId);
        assertEquals(1, recentCount(person));
    }

    private int recentCount(Person person) {
        return deliveryMapper.recentDispatchCount(workspace.getId(), person.getId(), "email", 0,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(24));
    }

    @Test
    @Transactional
    void v208BackfillsTheEarliestDispatchEventOrImmutableCreationTime() throws Exception {
        Person person = recipient();
        CampaignSendDto withEvents = readySend(person, DeliveryChannel.EMAIL);
        CampaignSendDto withoutEvents = readySend(person, DeliveryChannel.EMAIL);
        int withEventsId = pendingDelivery(withEvents);
        int withoutEventsId = pendingDelivery(withoutEvents);
        LocalDateTime created = LocalDateTime.now(ZoneOffset.UTC).minusHours(30).withNano(0);
        LocalDateTime firstSubmission = created.plusHours(2);
        LocalDateTime receipt = created.plusHours(29);
        for (int id : List.of(withEventsId, withoutEventsId)) {
            jdbcTemplate.update("UPDATE campaign_delivery SET status = 'delivered', submitted_at = NULL,"
                            + " frequency_reserved_at = NULL, created_at = ?, updated_at = ?"
                            + " WHERE workspace_id = ? AND id = ?",
                    created, receipt, workspace.getId(), id);
        }
        for (LocalDateTime eventTime : List.of(firstSubmission, firstSubmission.plusHours(1))) {
            CampaignDeliveryEvent event = new CampaignDeliveryEvent();
            event.setWorkspaceId(workspace.getId());
            event.setDeliveryId(withEventsId);
            event.setEventType("dispatched");
            deliveryMapper.insertEvent(event);
            jdbcTemplate.update("UPDATE campaign_delivery_event SET created_at = ? WHERE workspace_id = ? AND id = ?",
                    eventTime, workspace.getId(), event.getId());
        }
        try (InputStream script = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/tenant/V208__persist_delivery_submission_and_frequency_reservation.sql"))) {
            String migration = new String(script.readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : migration.substring(migration.indexOf("UPDATE campaign_delivery d")).split(";")) {
                if (!statement.isBlank()) {
                    jdbcTemplate.update(statement);
                }
            }
        }
        sqlSession.clearCache();

        CampaignDelivery eventBackfill = deliveryMapper.getDelivery(workspace.getId(), withEventsId);
        CampaignDelivery creationBackfill = deliveryMapper.getDelivery(workspace.getId(), withoutEventsId);
        assertEquals(firstSubmission, eventBackfill.getSubmittedAt());
        assertEquals(firstSubmission, eventBackfill.getFrequencyReservedAt());
        assertEquals(created, creationBackfill.getSubmittedAt());
        assertEquals(created, creationBackfill.getFrequencyReservedAt());
        assertEquals(receipt, eventBackfill.getUpdatedAt());
        assertEquals(receipt, creationBackfill.getUpdatedAt());
        assertEquals(0, recentCount(person));
    }

    @Test
    void concurrentClaimsReserveOneWindowBeforeEitherSubmissionCompletes() throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int firstDeliveryId = pendingDelivery(first);
        int secondDeliveryId = pendingDelivery(second);
        sendService.queueSend(first.campaignId(), first.id());
        sendService.queueSend(second.campaignId(), second.id());
        CountDownLatch claimed = new CountDownLatch(2);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        doAnswer(invocation -> {
            claimed.countDown();
            await(claimed);
            if ("second".equals(operation.get())) {
                await(firstLocked);
            }
            return null;
        }).when(claimBoundary).afterClaim(eq(workspace.getId()), anyInt());
        WorkspaceMapper realMapper = sqlSession.getMapper(WorkspaceMapper.class);
        doAnswer(invocation -> {
            if ("second".equals(operation.get())) {
                secondAttempted.countDown();
            }
            Workspace locked = realMapper.lockActiveIdentity(workspace.getId());
            if ("first".equals(operation.get())) {
                firstLocked.countDown();
                await(releaseFirst);
            }
            return locked;
        }).when(workspaceMapperSpy).lockActiveIdentity(workspace.getId());
        submissionObserver = () -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            submissionStarted.countDown();
            await(releaseReceipt);
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstWorker = executor.submit(() -> asActor("first",
                    () -> dispatchService.processSend(workspace.getId(), first.id())));
            Future<Boolean> secondWorker = executor.submit(() -> asActor("second",
                    () -> dispatchService.processSend(workspace.getId(), second.id())));
            assertTrue(firstLocked.await(20, TimeUnit.SECONDS));
            assertTrue(secondAttempted.await(20, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> secondWorker.get(500, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(submissionStarted.await(20, TimeUnit.SECONDS));
            assertTrue(secondWorker.get(20, TimeUnit.SECONDS));
            CampaignDelivery pendingReceipt = deliveryMapper.getDelivery(workspace.getId(), firstDeliveryId);
            assertEquals("dispatching", pendingReceipt.getStatus());
            assertNull(pendingReceipt.getSubmittedAt());
            assertNotNull(pendingReceipt.getFrequencyReservedAt());
            assertEquals("frequency_capped", deliveryMapper.getDelivery(
                    workspace.getId(), secondDeliveryId).getSkipReason());
            assertEquals(1, submissions.size());
            releaseReceipt.countDown();
            assertTrue(firstWorker.get(20, TimeUnit.SECONDS));
            assertEquals(1, submissions.size());
        } finally {
            releaseFirst.countDown();
            releaseReceipt.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void providerRejectionAfterEgressRetainsTheFrequencyReservation() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int firstId = pendingDelivery(first);
        responseStatus = 503;
        dispatch(first);
        CampaignDelivery rejected = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("failed", rejected.getStatus());
        assertNull(rejected.getSubmittedAt());
        assertNotNull(rejected.getFrequencyReservedAt());
        responseStatus = 200;
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int secondId = pendingDelivery(second);

        dispatch(second);

        assertEquals("frequency_capped", deliveryMapper.getDelivery(workspace.getId(), secondId).getSkipReason());
        assertEquals(1, submissions.size());
    }

    @Test
    void successfulSubmissionTimestampSurvivesDispatchEventPersistenceFailure() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int id = pendingDelivery(send);
        doAnswer(invocation -> {
            CampaignDeliveryEvent event = invocation.getArgument(0);
            if ("dispatched".equals(event.getEventType())) {
                throw new IllegalStateException("Dispatch event persistence failed");
            }
            sqlSession.getMapper(CampaignDeliveryMapper.class).insertEvent(event);
            return null;
        }).when(deliveryMapper).insertEvent(any(CampaignDeliveryEvent.class));

        dispatch(send);

        CampaignDelivery submitted = deliveryMapper.getDelivery(workspace.getId(), id);
        assertEquals("dispatched", submitted.getStatus());
        assertNotNull(submitted.getSubmittedAt());
        assertFalse(deliveryMapper.hasEvent(workspace.getId(), id, "dispatched"));
        assertEquals(1, recentCount(person));
    }

    @Test
    void operatorConfirmationPreservesTheOriginalAmbiguousAttemptTime() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int id = pendingDelivery(send);
        assertEquals(1, deliveryMapper.claim(workspace.getId(), id));
        LocalDateTime originalAttempt = LocalDateTime.now(ZoneOffset.UTC).minusHours(25).withNano(0);
        jdbcTemplate.update("UPDATE campaign_delivery SET frequency_reserved_at = ? WHERE workspace_id = ? AND id = ?",
                originalAttempt, workspace.getId(), id);
        assertEquals(1, deliveryMapper.markAmbiguous(workspace.getId(), id, "Ambiguous attempt", "deadline_ambiguous"));

        assertEquals(1, deliveryMapper.resolveReconciliation(workspace.getId(), send.campaignId(), id,
                "dispatched", "operator_delivered", null));

        assertEquals(originalAttempt, deliveryMapper.getDelivery(workspace.getId(), id).getSubmittedAt());
        assertEquals(0, recentCount(person));
        dispatch(readySend(person, DeliveryChannel.EMAIL));
        assertEquals(1, submissions.size());
    }

    @Test
    void operatorConfirmedNonDeliveryReleasesTheReservationForTheNextSend() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int firstId = pendingDelivery(first);
        sendService.queueSend(first.campaignId(), first.id());
        assertEquals(1, deliveryMapper.claim(workspace.getId(), firstId));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), firstId, person.getId(), "email", null, 24));
        assertEquals(1, deliveryMapper.markAmbiguous(workspace.getId(), firstId,
                "Ambiguous attempt", "deadline_ambiguous"));
        assertNotNull(deliveryMapper.getDelivery(workspace.getId(), firstId).getFrequencyReservedAt());

        assertEquals(1, deliveryMapper.resolveReconciliation(workspace.getId(), first.campaignId(), firstId,
                "failed", "operator_not_delivered", "Operator confirmed non-delivery"));

        CampaignDelivery resolved = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertNull(resolved.getFrequencyReservedAt());
        assertNull(resolved.getSubmittedAt());
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int secondId = pendingDelivery(second);
        dispatch(second);
        assertEquals("dispatched", deliveryMapper.getDelivery(workspace.getId(), secondId).getStatus());
        assertEquals(1, submissions.size());
    }

    @ParameterizedTest
    @CsvSource({"1, false, false", "2, false, false", "2, true, false", "2, true, true"})
    void provenPreEgressFailurePreservesRecoveredUncertaintyAndReleasesCleanGateAttempts(
            int attempt, boolean gateToggle, boolean recoveredBeforeGate) {
        Person person = recipient();
        DeliveryProviderConfigRequest replaySafe =
                providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        replaySafe.setIdempotentSubmission(!gateToggle || recoveredBeforeGate);
        configService.save(replaySafe);
        ResolvedDeliveryProvider target =
                configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int firstId = pendingDelivery(first);
        asTriggeredSend(first);
        if (gateToggle) {
            LocalDateTime priorReservation = null;
            if (recoveredBeforeGate) {
                String previousOwner = UUID.randomUUID().toString();
                assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), firstId, previousOwner,
                        DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
                assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                        workspace.getId(), firstId, person.getId(), "email", previousOwner, 24));
                priorReservation = deliveryMapper.getDelivery(workspace.getId(), firstId).getFrequencyReservedAt();
                assertNotNull(priorReservation);
                expireDispatchLease(firstId);
                when(triggeredSendGate.enabled()).thenReturn(false);
                assertTrue(dispatchService.processSend(workspace.getId(), first.id()));
                assertEquals("pending", deliveryMapper.getDelivery(workspace.getId(), firstId).getStatus());
            }
            CampaignDeliveryMapper realMapper = sqlSession.getMapper(CampaignDeliveryMapper.class);
            doAnswer(invocation -> {
                int updated = realMapper.reserveFrequencyWindow(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
                assertEquals(1, updated);
                when(triggeredSendGate.enabled()).thenReturn(false);
                return updated;
            }).when(deliveryMapper).reserveFrequencyWindow(
                    eq(workspace.getId()), eq(firstId), eq(person.getId()), eq("email"), anyString(), anyLong());
            for (int claimedAttempt = recoveredBeforeGate ? 2 : 1; claimedAttempt <= attempt; claimedAttempt++) {
                when(triggeredSendGate.enabled()).thenReturn(true);
                assertTrue(dispatchService.processSend(workspace.getId(), first.id()));
                CampaignDelivery released = deliveryMapper.getDelivery(workspace.getId(), firstId);
                assertEquals(claimedAttempt, released.getAttemptCount());
                assertEquals("pending", released.getStatus());
                assertEquals(priorReservation, released.getFrequencyReservedAt());
                assertNull(released.getSubmittedAt());
                assertEquals(0, submissions.size());
            }
            CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
            int secondId = pendingDelivery(second);
            dispatch(second);
            CampaignDelivery following = deliveryMapper.getDelivery(workspace.getId(), secondId);
            assertEquals(recoveredBeforeGate ? "skipped" : "dispatched", following.getStatus());
            assertEquals(recoveredBeforeGate ? "frequency_capped" : null, following.getSkipReason());
            assertEquals(recoveredBeforeGate ? 0 : 1, submissions.size());
            return;
        }
        String owner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), firstId, owner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        if (attempt == 2) {
            expireDispatchLease(firstId);
            assertTrue(dispatchService.processSend(workspace.getId(), first.id()));
            assertEquals("pending", deliveryMapper.getDelivery(workspace.getId(), firstId).getStatus());
            owner = UUID.randomUUID().toString();
            assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), firstId, owner,
                    DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        }
        assertEquals(attempt, deliveryMapper.getDelivery(workspace.getId(), firstId).getAttemptCount());
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), firstId, person.getId(), "email", owner, 24));

        assertEquals(attempt == 1 ? 1 : 0,
                deliveryMapper.releaseFrequencyWindowBeforeEgress(workspace.getId(), firstId, owner));

        assertEquals(1, deliveryMapper.markTriggeredFailed(workspace.getId(), firstId, owner,
                "Deadline before egress", "provider_timeout"));
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int secondId = pendingDelivery(second);
        dispatch(second);

        assertEquals(attempt == 1 ? "dispatched" : "skipped",
                deliveryMapper.getDelivery(workspace.getId(), secondId).getStatus());
        assertEquals(attempt == 1 ? 1 : 0, submissions.size());
    }

    @ParameterizedTest
    @CsvSource({"false, false, false", "false, true, false", "true, false, false", "true, true, false",
            "true, false, true", "true, true, true"})
    void preEgressRejectionAfterRetryReleasesOnlyCleanGateHistory(
            boolean recovered, boolean deadlineExpires, boolean legacyRecovery) {
        Person person = recipient();
        DeliveryProviderConfigRequest provider = providerRequest(DeliveryChannel.EMAIL, "key-a");
        provider.setIdempotentSubmission(true);
        configService.save(provider);
        ResolvedDeliveryProvider target = configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int firstId = pendingDelivery(first);
        asTriggeredSend(first);
        String owner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), firstId, owner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), firstId, person.getId(), "email", owner, 24));
        if (recovered) {
            assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery"
                            + " SET frequency_reserved_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 HOUR)"
                            + " WHERE workspace_id = ? AND id = ?", workspace.getId(), firstId));
            sqlSession.clearCache();
            expireDispatchLease(firstId);
            assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                    workspace.getId(), firstId, target.attemptTargetFingerprint()));
        } else {
            assertEquals(1, deliveryMapper.releaseTriggeredClaim(workspace.getId(), firstId, owner, null));
        }
        if (legacyRecovery) {
            assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery SET last_error_code = NULL"
                            + " WHERE workspace_id = ? AND id = ?", workspace.getId(), firstId));
            sqlSession.clearCache();
        }
        CampaignDelivery pending = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("pending", pending.getStatus());
        assertEquals(recovered && !legacyRecovery ? "deadline_ambiguous" : null, pending.getLastErrorCode());
        assertEquals(recovered ? null : "Claim released before provider egress", pending.getLastError());
        assertEquals(recovered, pending.getFrequencyReservedAt() != null);
        if (deadlineExpires) {
            AtomicLong now = new AtomicLong(System.nanoTime());
            ReflectionTestUtils.setField(dispatchService, "nanoTimeSource", (LongSupplier) now::get);
            CampaignDeliveryMapper realMapper = sqlSession.getMapper(CampaignDeliveryMapper.class);
            doAnswer(invocation -> {
                int updated = realMapper.reserveFrequencyWindow(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
                assertEquals(1, updated);
                now.addAndGet(TimeUnit.MINUTES.toNanos(2));
                return updated;
            }).when(deliveryMapper).reserveFrequencyWindow(
                    eq(workspace.getId()), eq(firstId), eq(person.getId()), eq("email"), anyString(), anyLong());
        }
        var transport = router.dispatcherFor(target.providerId());
        MessageDispatcher rejecting = mock(MessageDispatcher.class);
        when(rejecting.dispatch(any(), any())).thenReturn(
                DispatchReceipt.rejectedBeforeEgress("No usable ESP credential is configured"));
        when(router.dispatcherFor(target.providerId())).thenReturn(rejecting);
        when(triggeredSendGate.enabled()).thenReturn(true);

        assertTrue(dispatchService.processSend(workspace.getId(), first.id()));

        CampaignDelivery failed = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals(2, failed.getAttemptCount());
        assertEquals("failed", failed.getStatus());
        assertEquals(pending.getFrequencyReservedAt(), failed.getFrequencyReservedAt());
        assertEquals(recovered, failed.getReconciliationRequiredAt() != null);
        if (recovered) {
            assertEquals("deadline_ambiguous", failed.getLastErrorCode());
        }
        assertNull(failed.getSubmittedAt());
        assertEquals(0, submissions.size());
        when(router.dispatcherFor(target.providerId())).thenReturn(transport);
        ReflectionTestUtils.setField(dispatchService, "nanoTimeSource", (LongSupplier) System::nanoTime);
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int secondId = pendingDelivery(second);
        dispatch(second);
        assertEquals(recovered ? "skipped" : "dispatched",
                deliveryMapper.getDelivery(workspace.getId(), secondId).getStatus());
        assertEquals(recovered ? 0 : 1, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetValidationFailureRestoresTheReservationBeforeRecordingFailure(boolean recovered) {
        Person person = recipient();
        DeliveryProviderConfigRequest provider = providerRequest(DeliveryChannel.EMAIL, "key-a");
        provider.setIdempotentSubmission(true);
        configService.save(provider);
        ResolvedDeliveryProvider target = configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int firstId = pendingDelivery(first);
        asTriggeredSend(first);
        if (recovered) {
            String owner = UUID.randomUUID().toString();
            assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), firstId, owner,
                    DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
            assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                    workspace.getId(), firstId, person.getId(), "email", owner, 24));
            assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery"
                            + " SET frequency_reserved_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 HOUR)"
                            + " WHERE workspace_id = ? AND id = ?", workspace.getId(), firstId));
            expireDispatchLease(firstId);
            assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                    workspace.getId(), firstId, target.attemptTargetFingerprint()));
        }
        LocalDateTime priorReservation = deliveryMapper.getDelivery(workspace.getId(), firstId).getFrequencyReservedAt();
        AtomicBoolean failTargetCheck = new AtomicBoolean();
        DeliveryProviderConfigMapper realConfigMapper = sqlSession.getMapper(DeliveryProviderConfigMapper.class);
        doAnswer(invocation -> {
            if (failTargetCheck.getAndSet(false)) {
                throw new IllegalStateException("Configuration read failed");
            }
            return realConfigMapper.findByWorkspaceChannelForShare(invocation.getArgument(0), invocation.getArgument(1));
        }).when(configMapper).findByWorkspaceChannelForShare(workspace.getId(), "email");
        CampaignDeliveryMapper realDeliveryMapper = sqlSession.getMapper(CampaignDeliveryMapper.class);
        doAnswer(invocation -> {
            int updated = realDeliveryMapper.reserveFrequencyWindow(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
            assertEquals(1, updated);
            failTargetCheck.set(true);
            return updated;
        }).when(deliveryMapper).reserveFrequencyWindow(
                eq(workspace.getId()), eq(firstId), eq(person.getId()), eq("email"), anyString(), anyLong());
        when(triggeredSendGate.enabled()).thenReturn(true);

        assertTrue(dispatchService.processSend(workspace.getId(), first.id()));

        CampaignDelivery failed = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertFalse(failTargetCheck.get());
        assertEquals(recovered ? 2 : 1, failed.getAttemptCount());
        assertEquals("failed", failed.getStatus());
        assertEquals(priorReservation, failed.getFrequencyReservedAt());
        assertEquals(recovered, failed.getReconciliationRequiredAt() != null);
        assertEquals(recovered ? "deadline_ambiguous" : "relay_error", failed.getLastErrorCode());
        assertNull(failed.getSubmittedAt());
        assertEquals(0, submissions.size());
        CampaignSendDto second = readySend(person, DeliveryChannel.EMAIL);
        int secondId = pendingDelivery(second);
        dispatch(second);
        assertEquals(recovered ? "skipped" : "dispatched",
                deliveryMapper.getDelivery(workspace.getId(), secondId).getStatus());
        assertEquals(recovered ? 0 : 1, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonOutboundUpdatesPreserveResolvedAndRecoverableTargets(boolean webhookOnly) {
        DeliveryChannel channel = DeliveryChannel.EMAIL;
        Person person = recipient();
        DeliveryProviderConfigRequest provider = providerRequest(channel, key(channel));
        provider.setIdempotentSubmission(true);
        configService.save(provider);
        ResolvedDeliveryProvider target = configService.resolveForWorkspace(workspace.getId(), channel);
        CampaignSendDto send = readySend(person, channel);
        int deliveryId = pendingDelivery(send);
        asTriggeredSend(send);
        String owner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), deliveryId, owner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));

        if (webhookOnly) {
            configService.issueWebhookToken(channel.token());
            DeliveryProviderConfig firstWebhook = storedConfig(channel);
            configService.issueWebhookToken(channel.token());
            assertNotEquals(firstWebhook.getWebhookTokenHash(), storedConfig(channel).getWebhookTokenHash());
        } else {
            provider.setApiKey(null);
            configService.save(provider);
        }
        assertEquals(target.configGeneration(), storedConfig(channel).getConfigGeneration());
        assertEquals(target.attemptTargetFingerprint(),
                configService.resolveForWorkspace(workspace.getId(), channel).attemptTargetFingerprint());
        assertTrue(configService.isCurrentClaimTarget(target, deliveryId, owner));
        expireDispatchLease(deliveryId);
        when(triggeredSendGate.enabled()).thenReturn(false);
        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        assertEquals("pending", deliveryMapper.getDelivery(workspace.getId(), deliveryId).getStatus());
        when(triggeredSendGate.enabled()).thenReturn(true);
        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        CampaignDelivery dispatched = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("dispatched", dispatched.getStatus());
        assertEquals(2, dispatched.getAttemptCount());
        assertNull(dispatched.getReconciliationRequiredAt());
        assertEquals(1, submissions.size());
    }

    @Test
    void aReceiptOnANeverSubmittedRowCannotStampAFutureSubmissionTime() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        sendService.queueSend(send.campaignId(), send.id());
        assertEquals(1, deliveryMapper.claim(workspace.getId(), deliveryId));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), deliveryId, person.getId(), "email", null, 24));
        CampaignDelivery reserved = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertNull(reserved.getSubmittedAt());
        assertTrue(reserved.getFrequencyReservedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        assertEquals(1, deliveryMapper.markAmbiguous(
                workspace.getId(), deliveryId, "Ambiguous provider attempt", "deadline_ambiguous"));
        CampaignDelivery ambiguous = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", ambiguous.getStatus());
        assertNotNull(ambiguous.getReconciliationRequiredAt());
        assertNull(ambiguous.getSubmittedAt());
        assertEquals(reserved.getFrequencyReservedAt(), ambiguous.getFrequencyReservedAt());

        assertEquals(1, deliveryMapper.applyProviderStatus(
                workspace.getId(), deliveryId, "delivered", List.of("failed")));

        CampaignDelivery stamped = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertNotNull(stamped.getSubmittedAt());
        assertFalse(stamped.getSubmittedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        assertTrue(stamped.getSubmittedAt().isBefore(reserved.getFrequencyReservedAt()));
    }

    @Test
    void aSendPausedBetweenClaimAndReservationTerminatesTheDeliveryInsteadOfStrandingIt() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        doAnswer(invocation -> {
            jdbcTemplate.update("UPDATE campaign_send SET status = 'paused'"
                            + " WHERE workspace_id = ? AND id = ?",
                    workspace.getId(), send.id());
            return null;
        }).when(claimBoundary).afterClaim(workspace.getId(), deliveryId);

        dispatch(send);

        CampaignDelivery terminated = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertNotEquals("dispatching", terminated.getStatus());
        assertEquals("skipped", terminated.getStatus());
        assertEquals("not_dispatchable", terminated.getSkipReason());
        assertNull(terminated.getFrequencyReservedAt());
        assertEquals(0, submissions.size());
    }

    @Test
    void anAbandonedAudienceAttemptBecomesReconcilableWithoutReplayOnceItsReservationExpires() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = strandedAudienceAttempt(person, send);
        LocalDateTime reservation = expireReservation(deliveryId, reservationGraceSeconds() + 60);

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", swept.getStatus());
        assertNull(swept.getSkipReason());
        assertEquals(EXPIRED_AUDIENCE_RESERVATION, swept.getLastError());
        assertEquals("deadline_ambiguous", swept.getLastErrorCode());
        assertNotNull(swept.getReconciliationRequiredAt());
        assertNull(swept.getReconciliationOutcome());
        assertEquals(reservation, swept.getFrequencyReservedAt());
        assertNull(swept.getSubmittedAt());
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, submissions.size());
        var recipientRow = deliveryMapper.listRecipients(workspace.getId(), send.campaignId(), send.id(),
                List.of("failed"), null, 10, 0).getFirst();
        assertEquals(swept.getReconciliationRequiredAt(), recipientRow.reconciliationRequiredAt());
        assertEquals("deadline_ambiguous", recipientRow.reasonCode());

        assertEquals(0, sqlSession.getMapper(CampaignDeliveryMapper.class).markDispatched(
                workspace.getId(), deliveryId, HttpEspDeliveryProvider.PROVIDER_ID, "late-message"));
        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery settled = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", settled.getStatus());
        assertNull(settled.getProviderMessageId());
        assertNull(settled.getSubmittedAt());
        assertEquals(swept.getReconciliationRequiredAt(), settled.getReconciliationRequiredAt());
        assertEquals(reservation, settled.getFrequencyReservedAt());
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, deliveryEvents(deliveryId, "dispatched"));
        assertEquals(0, submissions.size());
    }

    @Test
    void anAudienceReservationStillInsideItsGraceIsLeftUntouched() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = strandedAudienceAttempt(person, send);
        LocalDateTime reservation = expireReservation(deliveryId, 5);

        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery live = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("dispatching", live.getStatus());
        assertNull(live.getLastError());
        assertNull(live.getLastErrorCode());
        assertNull(live.getReconciliationRequiredAt());
        assertEquals(reservation, live.getFrequencyReservedAt());
        assertEquals(0, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "not_delivered"})
    void operatorResolutionOfASweptAudienceAttemptDecidesTheNextSendsFrequencyCap(String resolution) {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto first = readySend(person, DeliveryChannel.EMAIL);
        int firstId = pendingDelivery(first);
        doAnswer(invocation -> {
            throw new AssertionError("Simulated worker loss after provider submission");
        }).when(deliveryMapper).markDispatched(
                eq(workspace.getId()), eq(firstId), anyString(), anyString());
        sendService.queueSend(first.campaignId(), first.id());

        AssertionError workerLoss = assertThrows(AssertionError.class,
                () -> dispatchService.processSend(workspace.getId(), first.id()));
        assertEquals("Simulated worker loss after provider submission", workerLoss.getMessage());

        CampaignDelivery abandoned = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("dispatching", abandoned.getStatus());
        assertNotNull(abandoned.getFrequencyReservedAt());
        assertNull(abandoned.getSubmittedAt());
        assertEquals(1, submissions.size());
        CampaignSendDto blocked = readySend(person, DeliveryChannel.EMAIL);
        int blockedId = pendingDelivery(blocked);
        dispatch(blocked);
        assertEquals("frequency_capped", deliveryMapper.getDelivery(workspace.getId(), blockedId).getSkipReason());
        assertEquals("dispatching", deliveryMapper.getDelivery(workspace.getId(), firstId).getStatus());
        expireReservation(firstId, reservationGraceSeconds() + 60);

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("failed", swept.getStatus());
        assertEquals("deadline_ambiguous", swept.getLastErrorCode());
        assertNotNull(swept.getReconciliationRequiredAt());
        assertEquals(1, submissions.size());

        var result = triggeredSendService.reconcile(first.campaignId(), firstId,
                new CampaignDeliveryReconciliationRequest(resolution));

        boolean delivered = "delivered".equals(resolution);
        assertEquals(delivered ? "dispatched" : "failed", result.status());
        assertFalse(result.reconciliationRequired());
        CampaignDelivery reconciled = deliveryMapper.getDelivery(workspace.getId(), firstId);
        assertEquals("operator_" + resolution, reconciled.getReconciliationOutcome());
        assertEquals(delivered, reconciled.getFrequencyReservedAt() != null);
        CampaignSendDto following = readySend(person, DeliveryChannel.EMAIL);
        int followingId = pendingDelivery(following);
        dispatch(following);
        CampaignDelivery next = deliveryMapper.getDelivery(workspace.getId(), followingId);
        assertEquals(delivered ? "skipped" : "dispatched", next.getStatus());
        assertEquals(delivered ? "frequency_capped" : null, next.getSkipReason());
        assertEquals(delivered ? 1 : 2, submissions.size());
    }

    @Test
    void leasedAndTriggeredClaimsNeverMatchTheAudienceReservationSweep() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        ResolvedDeliveryProvider target = configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        long graceMicros = reservationGraceMicros();
        CampaignSendDto triggered = readySend(person, DeliveryChannel.EMAIL);
        int triggeredId = pendingDelivery(triggered);
        asTriggeredSend(triggered);
        String owner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), triggeredId, owner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), triggeredId, person.getId(), "email", owner, 24));
        expireReservation(triggeredId, reservationGraceSeconds() + 60);
        expireDispatchLease(triggeredId);
        Person other = recipient();
        CampaignSendDto audience = readySend(other, DeliveryChannel.EMAIL);
        int audienceId = strandedAudienceAttempt(other, audience);
        expireReservation(audienceId, reservationGraceSeconds() + 60);
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery SET dispatch_lease_owner = ?,"
                        + " dispatch_lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE)"
                        + " WHERE workspace_id = ? AND id = ?",
                UUID.randomUUID().toString(), workspace.getId(), audienceId));
        sqlSession.clearCache();

        assertTrue(deliveryMapper.expiredAudienceReservationsPage(workspace.getId(), graceMicros, 10).isEmpty());
        for (int id : List.of(triggeredId, audienceId)) {
            assertEquals(0, deliveryMapper.markExpiredAudienceReservationAmbiguous(workspace.getId(),
                    id, graceMicros, EXPIRED_AUDIENCE_RESERVATION, "deadline_ambiguous"));
        }
        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery recovered = deliveryMapper.getDelivery(workspace.getId(), triggeredId);
        assertEquals("failed", recovered.getStatus());
        assertEquals(EXPIRED_NON_IDEMPOTENT_CLAIM, recovered.getLastError());
        assertNotNull(recovered.getReconciliationRequiredAt());
        CampaignDelivery leased = deliveryMapper.getDelivery(workspace.getId(), audienceId);
        assertEquals("dispatching", leased.getStatus());
        assertNull(leased.getReconciliationRequiredAt());
        assertEquals(0, deliveryEvents(audienceId, "failed"));
        assertEquals(0, submissions.size());

        assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery SET dispatch_lease_owner = NULL,"
                        + " dispatch_lease_until = NULL WHERE workspace_id = ? AND id = ?",
                workspace.getId(), audienceId));
        asTriggeredSend(audience);
        assertTrue(deliveryMapper.expiredAudienceReservationsPage(workspace.getId(), graceMicros, 10).isEmpty());
        assertEquals(0, deliveryMapper.markExpiredAudienceReservationAmbiguous(workspace.getId(),
                audienceId, graceMicros, EXPIRED_AUDIENCE_RESERVATION, "deadline_ambiguous"));
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_send SET origin = 'audience', status = 'completed'"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), audience.id()));
        sqlSession.clearCache();
        assertEquals(List.of(audienceId), deliveryMapper.expiredAudienceReservationsPage(
                workspace.getId(), graceMicros, 10).stream().map(CampaignDelivery::getId).toList());
    }

    @Test
    void schedulerDiscoversAWorkspaceWhoseOnlyWorkIsAnExpiredAudienceReservation() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = strandedAudienceAttempt(person, send);
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_send SET status = 'completed'"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), send.id()));
        sqlSession.clearCache();
        long graceMicros = reservationGraceMicros();
        expireReservation(deliveryId, 5);
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));
        expireReservation(deliveryId, reservationGraceSeconds() + 60);
        assertTrue(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", swept.getStatus());
        assertNotNull(swept.getReconciliationRequiredAt());
        var settledSend = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settledSend.getStatus());
        assertEquals(1, settledSend.getFailedCount());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));
        assertEquals(0, submissions.size());
    }

    @Test
    void schedulerDiscoveryFollowsOutstandingAudienceAttemptsAndNotSettledSendHistory() {
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        long graceMicros = reservationGraceMicros();
        Person delivered = recipient();
        CampaignSendDto history = readySend(delivered, DeliveryChannel.EMAIL);
        int deliveredId = pendingDelivery(history);
        dispatch(history);
        expireReservation(deliveredId, reservationGraceSeconds() + 60);

        assertEquals("dispatched", deliveryMapper.getDelivery(workspace.getId(), deliveredId).getStatus());
        assertEquals("completed", campaignSendMapper.getSend(workspace.getId(), history.id()).getStatus());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));

        Person stranded = recipient();
        CampaignSendDto owner = readySend(stranded, DeliveryChannel.EMAIL);
        int strandedId = strandedAudienceAttempt(stranded, owner);
        expireReservation(strandedId, reservationGraceSeconds() + 60);
        asTriggeredSend(owner);

        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));

        assertEquals(1, jdbcTemplate.update("UPDATE campaign_send SET origin = 'audience',"
                + " status = 'completed' WHERE workspace_id = ? AND id = ?", workspace.getId(), owner.id()));
        sqlSession.clearCache();

        assertTrue(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));
        assertEquals(1, submissions.size());
    }

    @Test
    void theAudienceReservationCompareAndSetOnlyMarksAnAttemptThatIsStillAbandoned() {
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        long graceMicros = reservationGraceMicros();
        Person person = recipient();
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int sweptId = strandedAudienceAttempt(person, send);
        expireReservation(sweptId, 5);
        assertEquals(0, lateAudienceSweep(sweptId, graceMicros));
        LocalDateTime reservation = expireReservation(sweptId, reservationGraceSeconds() + 60);
        markSubmitted(sweptId, true);
        assertEquals(0, lateAudienceSweep(sweptId, graceMicros));
        assertEquals("dispatching", deliveryMapper.getDelivery(workspace.getId(), sweptId).getStatus());
        markSubmitted(sweptId, false);

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), sweptId);
        assertEquals("failed", swept.getStatus());
        assertNotNull(swept.getReconciliationRequiredAt());
        assertEquals(0, lateAudienceSweep(sweptId, graceMicros));
        CampaignDelivery unchanged = deliveryMapper.getDelivery(workspace.getId(), sweptId);
        assertEquals("failed", unchanged.getStatus());
        assertEquals(EXPIRED_AUDIENCE_RESERVATION, unchanged.getLastError());
        assertEquals("deadline_ambiguous", unchanged.getLastErrorCode());
        assertEquals(swept.getReconciliationRequiredAt(), unchanged.getReconciliationRequiredAt());
        assertEquals(reservation, unchanged.getFrequencyReservedAt());
        assertEquals(1, deliveryEvents(sweptId, "failed"));

        Person rejected = recipient();
        int failedId = strandedAudienceAttempt(rejected, readySend(rejected, DeliveryChannel.EMAIL));
        assertEquals(1, deliveryMapper.markFailed(
                workspace.getId(), failedId, "Provider rejected the message", "provider_rejected"));
        Person capped = recipient();
        int skippedId = strandedAudienceAttempt(capped, readySend(capped, DeliveryChannel.EMAIL));
        assertEquals(1, deliveryMapper.markSkipped(workspace.getId(), skippedId, "frequency_capped"));
        for (int id : List.of(failedId, skippedId)) {
            expireReservation(id, reservationGraceSeconds() + 60);
            assertEquals(0, lateAudienceSweep(id, graceMicros));
            assertNull(deliveryMapper.getDelivery(workspace.getId(), id).getReconciliationRequiredAt());
            assertEquals(0, deliveryEvents(id, "failed"));
        }
        CampaignDelivery definitiveFailure = deliveryMapper.getDelivery(workspace.getId(), failedId);
        assertEquals("failed", definitiveFailure.getStatus());
        assertEquals("Provider rejected the message", definitiveFailure.getLastError());
        assertEquals("provider_rejected", definitiveFailure.getLastErrorCode());
        CampaignDelivery skip = deliveryMapper.getDelivery(workspace.getId(), skippedId);
        assertEquals("skipped", skip.getStatus());
        assertEquals("frequency_capped", skip.getSkipReason());
        assertNull(skip.getLastError());
        assertNull(skip.getLastErrorCode());
        assertEquals(0, submissions.size());
    }

    @Test
    void aCounterRefreshThatFailsAfterTheSweepIsRepairedWhenTheOperatorResolvesTheRow() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = strandedAudienceAttempt(person, send);
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_send SET status = 'completed'"
                + " WHERE workspace_id = ? AND id = ?", workspace.getId(), send.id()));
        sqlSession.clearCache();
        expireReservation(deliveryId, reservationGraceSeconds() + 60);
        long graceMicros = reservationGraceMicros();
        CampaignSendMapper realSendMapper = sqlSession.getMapper(CampaignSendMapper.class);
        AtomicBoolean refreshFailed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (refreshFailed.compareAndSet(false, true)) {
                throw new IllegalStateException("Simulated counter refresh failure");
            }
            return realSendMapper.refreshCounters(workspace.getId(), send.id());
        }).when(campaignSendMapper).refreshCounters(workspace.getId(), send.id());

        assertEquals(1, dispatchService.processWorkspace(workspace.getId()));

        assertTrue(refreshFailed.get());
        assertEquals("failed", deliveryMapper.getDelivery(workspace.getId(), deliveryId).getStatus());
        assertEquals(0, campaignSendMapper.getSend(workspace.getId(), send.id()).getFailedCount());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        var stale = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", stale.getStatus());
        assertEquals(0, stale.getFailedCount());

        triggeredSendService.reconcile(send.campaignId(), deliveryId,
                new CampaignDeliveryReconciliationRequest("not_delivered"));
        sqlSession.clearCache();

        CampaignDelivery resolved = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("operator_not_delivered", resolved.getReconciliationOutcome());
        var repaired = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", repaired.getStatus());
        assertEquals(1, repaired.getFailedCount());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, graceMicros).contains(workspace.getId()));
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRunningSendWhoseLastAttemptIsSweptSettlesWhileItsProviderIsDisabled(boolean completionFailsOnce) {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = strandedAudienceAttempt(person, send);
        assertEquals(1, campaignSendMapper.markRunning(workspace.getId(), send.id()));
        expireReservation(deliveryId, reservationGraceSeconds() + 60);
        DeliveryProviderConfigRequest disabled = providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        disabled.setEnabled(false);
        configService.save(disabled);
        assertThrows(DeliveryProviderException.class,
                () -> configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL));
        CampaignSendMapper realSendMapper = sqlSession.getMapper(CampaignSendMapper.class);
        AtomicBoolean completionFailed = new AtomicBoolean(!completionFailsOnce);
        doAnswer(invocation -> {
            if (completionFailed.compareAndSet(false, true)) {
                throw new IllegalStateException("Simulated send completion failure");
            }
            return realSendMapper.markSettledAudienceSendCompleted(workspace.getId(), send.id());
        }).when(campaignSendMapper).markSettledAudienceSendCompleted(workspace.getId(), send.id());

        if (completionFailsOnce) {
            assertEquals(2, dispatchService.processWorkspace(workspace.getId()));
            var stillRunning = campaignSendMapper.getSend(workspace.getId(), send.id());
            assertEquals("running", stillRunning.getStatus());
            assertEquals(0, stillRunning.getFailedCount());
        }
        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", swept.getStatus());
        assertNotNull(swept.getReconciliationRequiredAt());
        var settled = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(1, settled.getFailedCount());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, reservationGraceMicros())
                .contains(workspace.getId()));
        assertEquals(0, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theRecoverySweepNeverCompletesASendWhileAnotherAttemptIsStillDispatching(boolean workerFinishes) {
        Person abandonedRecipient = recipient();
        Person inFlightRecipient = recipient();
        assertEquals(1, jdbcTemplate.update("UPDATE person SET name = ? WHERE workspace_id = ? AND id = ?",
                abandonedRecipient.getName() + " peer", workspace.getId(), inFlightRecipient.getId()));
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(abandonedRecipient, DeliveryChannel.EMAIL);
        int abandonedId = deliveryMapper.getBySendAndPerson(
                workspace.getId(), send.id(), abandonedRecipient.getId()).getId();
        int inFlightId = deliveryMapper.getBySendAndPerson(
                workspace.getId(), send.id(), inFlightRecipient.getId()).getId();
        sendService.queueSend(send.campaignId(), send.id());
        assertEquals(1, deliveryMapper.claim(workspace.getId(), abandonedId));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), abandonedId, abandonedRecipient.getId(), "email", null, 24));
        DeliveryProviderConfigRequest disabled = providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        disabled.setEnabled(false);
        submissionObserver = () -> {
            sqlSession.clearCache();
            CampaignDelivery live = deliveryMapper.getDelivery(workspace.getId(), inFlightId);
            assertEquals("dispatching", live.getStatus());
            assertNotNull(live.getFrequencyReservedAt());
            expireReservation(abandonedId, reservationGraceSeconds() + 60);
            configService.save(disabled);

            int failedWork = dispatchService.processWorkspace(workspace.getId());

            CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), abandonedId);
            assertEquals("failed", swept.getStatus());
            assertNotNull(swept.getReconciliationRequiredAt());
            var stillRunning = campaignSendMapper.getSend(workspace.getId(), send.id());
            assertEquals("running", stillRunning.getStatus());
            assertNull(stillRunning.getCompletedAt());
            assertEquals(1, stillRunning.getFailedCount());
            assertEquals(1, failedWork);
            assertFalse(campaignSendMapper.audienceSendsAwaitingRecoverySettlement(workspace.getId(), 10)
                    .contains(send.id()));
            CampaignDelivery untouched = deliveryMapper.getDelivery(workspace.getId(), inFlightId);
            assertEquals("dispatching", untouched.getStatus());
            assertNull(untouched.getLastError());
            assertNull(untouched.getLastErrorCode());
            assertNull(untouched.getReconciliationRequiredAt());
            assertNull(untouched.getSubmittedAt());
            assertEquals(live.getFrequencyReservedAt(), untouched.getFrequencyReservedAt());
            assertEquals(0, deliveryEvents(inFlightId, "failed"));
            if (!workerFinishes) {
                throw new AssertionError("Simulated worker loss after provider submission");
            }
        };

        if (workerFinishes) {
            assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        } else {
            AssertionError workerLoss = assertThrows(AssertionError.class,
                    () -> dispatchService.processSend(workspace.getId(), send.id()));
            assertEquals("Simulated worker loss after provider submission", workerLoss.getMessage());
            assertEquals("dispatching", deliveryMapper.getDelivery(workspace.getId(), inFlightId).getStatus());
            assertEquals("running", campaignSendMapper.getSend(workspace.getId(), send.id()).getStatus());
            expireReservation(inFlightId, reservationGraceSeconds() + 60);
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
        }

        CampaignDelivery finished = deliveryMapper.getDelivery(workspace.getId(), inFlightId);
        assertEquals(workerFinishes ? "dispatched" : "failed", finished.getStatus());
        assertEquals(workerFinishes ? null : EXPIRED_AUDIENCE_RESERVATION, finished.getLastError());
        assertEquals(!workerFinishes, finished.getReconciliationRequiredAt() != null);
        var settled = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(workerFinishes ? 1 : 0, settled.getDispatchedCount());
        assertEquals(workerFinishes ? 1 : 2, settled.getFailedCount());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, reservationGraceMicros())
                .contains(workspace.getId()));
        assertEquals(1, submissions.size());
    }

    @Test
    void theRecoverySweepNeverCompletesASendWhileAnotherDeliveryIsStillPending() {
        Person abandonedRecipient = recipient();
        Person waitingRecipient = recipient();
        assertEquals(1, jdbcTemplate.update("UPDATE person SET name = ? WHERE workspace_id = ? AND id = ?",
                abandonedRecipient.getName() + " peer", workspace.getId(), waitingRecipient.getId()));
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(abandonedRecipient, DeliveryChannel.EMAIL);
        int abandonedId = deliveryMapper.getBySendAndPerson(
                workspace.getId(), send.id(), abandonedRecipient.getId()).getId();
        int waitingId = deliveryMapper.getBySendAndPerson(
                workspace.getId(), send.id(), waitingRecipient.getId()).getId();
        sendService.queueSend(send.campaignId(), send.id());
        assertEquals(1, deliveryMapper.claim(workspace.getId(), abandonedId));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), abandonedId, abandonedRecipient.getId(), "email", null, 24));
        assertEquals(1, campaignSendMapper.markRunning(workspace.getId(), send.id()));
        expireReservation(abandonedId, reservationGraceSeconds() + 60);
        DeliveryProviderConfigRequest disabled = providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        disabled.setEnabled(false);
        configService.save(disabled);

        assertEquals(1, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), abandonedId);
        assertEquals("failed", swept.getStatus());
        assertNotNull(swept.getReconciliationRequiredAt());
        var stillRunning = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("running", stillRunning.getStatus());
        assertNull(stillRunning.getCompletedAt());
        assertEquals(1, stillRunning.getFailedCount());
        assertEquals(0, stillRunning.getDispatchedCount());
        CampaignDelivery waiting = deliveryMapper.getDelivery(workspace.getId(), waitingId);
        assertEquals("pending", waiting.getStatus());
        assertEquals(0, waiting.getAttemptCount());
        assertNull(waiting.getFrequencyReservedAt());
        assertFalse(campaignSendMapper.audienceSendsAwaitingRecoverySettlement(workspace.getId(), 10)
                .contains(send.id()));
        assertTrue(campaignSendMapper.workspaceIdsWithQueuedSends(false, reservationGraceMicros())
                .contains(workspace.getId()));
        assertEquals(0, submissions.size());

        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        assertEquals("dispatched", deliveryMapper.getDelivery(workspace.getId(), waitingId).getStatus());
        var settled = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(1, settled.getDispatchedCount());
        assertEquals(1, settled.getFailedCount());
        assertFalse(campaignSendMapper.workspaceIdsWithQueuedSends(false, reservationGraceMicros())
                .contains(workspace.getId()));
        assertEquals(1, submissions.size());
    }

    @Test
    void aSubmissionThatLosesToTheSweepKeepsItsCorrelationSoAHardBounceStillSuppresses() throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        AtomicReference<LocalDateTime> reservation = new AtomicReference<>();
        submissionObserver = () -> {
            reservation.set(expireReservation(deliveryId, reservationGraceSeconds() + 60));
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
            CampaignDelivery overtaken = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("failed", overtaken.getStatus());
            assertNull(overtaken.getProviderMessageId());
        };

        dispatch(send);

        assertEquals(1, submissions.size());
        CampaignDelivery late = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", late.getStatus());
        assertEquals(EXPIRED_AUDIENCE_RESERVATION, late.getLastError());
        assertEquals("deadline_ambiguous", late.getLastErrorCode());
        assertNotNull(late.getReconciliationRequiredAt());
        assertNull(late.getReconciliationOutcome());
        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, late.getProviderId());
        assertEquals("message-1", late.getProviderMessageId());
        assertNull(late.getSubmittedAt());
        assertEquals(reservation.get(), late.getFrequencyReservedAt());
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, deliveryEvents(deliveryId, "dispatched"));
        assertEquals("completed", campaignSendMapper.getSend(workspace.getId(), send.id()).getStatus());
        assertFalse(suppressedOrRevoked(person));

        assertEquals(1, ingest("{\"event\":\"bounce\",\"bounceType\":\"hard\",\"eventId\":\"bounce-"
                + unique() + "\",\"messageId\":\"message-1\"}"));

        CampaignDelivery bounced = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("bounced", bounced.getStatus());
        assertNull(bounced.getReconciliationRequiredAt());
        assertEquals(1, deliveryEvents(deliveryId, "bounced"));
        assertTrue(audienceEligibilityService.suppressedIds(
                workspace.getId(), List.of(person.getId()), "email").contains(person.getId()));
        assertTrue(audienceEligibilityService.revokedConsentIds(
                workspace.getId(), List.of(person.getId()), "email", "marketing").contains(person.getId()));
    }

    @Test
    void theLateCorrelationCompareAndSetOnlyTouchesASweptRowWithoutAProviderId() {
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        Person person = recipient();
        int sweptId = strandedAudienceAttempt(person, readySend(person, DeliveryChannel.EMAIL));
        Person resolvedPerson = recipient();
        CampaignSendDto resolvedSend = readySend(resolvedPerson, DeliveryChannel.EMAIL);
        int resolvedId = strandedAudienceAttempt(resolvedPerson, resolvedSend);
        Person ambiguousPerson = recipient();
        int ambiguousId = strandedAudienceAttempt(ambiguousPerson, readySend(ambiguousPerson, DeliveryChannel.EMAIL));
        assertEquals(1, deliveryMapper.markAmbiguous(workspace.getId(), ambiguousId,
                "AMBIGUOUS: Provider outcome could not be persisted definitively", "relay_error"));
        assertEquals(0, lateCorrelation(sweptId, "early-message"));
        for (int id : List.of(sweptId, resolvedId)) {
            expireReservation(id, reservationGraceSeconds() + 60);
        }
        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
        triggeredSendService.reconcile(resolvedSend.campaignId(), resolvedId,
                new CampaignDeliveryReconciliationRequest("not_delivered"));

        assertEquals(1, lateCorrelation(sweptId, "late-message"));
        assertEquals(0, lateCorrelation(sweptId, "second-message"));
        assertEquals(1, lateCorrelation(resolvedId, "resolved-message"));
        assertEquals(0, lateCorrelation(ambiguousId, "ambiguous-message"));

        CampaignDelivery correlated = deliveryMapper.getDelivery(workspace.getId(), sweptId);
        assertEquals("failed", correlated.getStatus());
        assertNotNull(correlated.getReconciliationRequiredAt());
        assertEquals(EXPIRED_AUDIENCE_RESERVATION, correlated.getLastError());
        assertEquals("late-message", correlated.getProviderMessageId());
        CampaignDelivery resolved = deliveryMapper.getDelivery(workspace.getId(), resolvedId);
        assertEquals("failed", resolved.getStatus());
        assertEquals("operator_not_delivered", resolved.getReconciliationOutcome());
        assertNull(resolved.getReconciliationRequiredAt());
        assertNull(resolved.getFrequencyReservedAt());
        assertEquals("resolved-message", resolved.getProviderMessageId());
        CampaignDelivery untouched = deliveryMapper.getDelivery(workspace.getId(), ambiguousId);
        assertNull(untouched.getProviderId());
        assertNull(untouched.getProviderMessageId());
        assertEquals(0, submissions.size());
    }

    @Test
    void aTerminalWriteThatLandsWhileASweptSendSettlesIsNeverLostFromItsCounters() {
        Person abandonedRecipient = recipient();
        Person inFlightRecipient = peerOf(abandonedRecipient);
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(abandonedRecipient, DeliveryChannel.EMAIL);
        sendService.queueSend(send.campaignId(), send.id());
        int abandonedId = claimedAudienceAttempt(send, abandonedRecipient);
        int inFlightId = claimedAudienceAttempt(send, inFlightRecipient);
        assertEquals(1, campaignSendMapper.markRunning(workspace.getId(), send.id()));
        expireReservation(abandonedId, reservationGraceSeconds() + 60);
        disableEmailProvider();
        CampaignSendMapper realSendMapper = sqlSession.getMapper(CampaignSendMapper.class);
        AtomicBoolean terminalWriteLanded = new AtomicBoolean();
        doAnswer(invocation -> {
            int refreshed = realSendMapper.refreshCounters(workspace.getId(), send.id());
            if (terminalWriteLanded.compareAndSet(false, true)) {
                completeInFlightAttempt(inFlightId, "late-message");
            }
            return refreshed;
        }).when(campaignSendMapper).refreshCounters(workspace.getId(), send.id());

        int sweptPass = dispatchService.processWorkspace(workspace.getId());

        assertTrue(terminalWriteLanded.get());
        var stillRunning = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("running", stillRunning.getStatus());
        assertNull(stillRunning.getCompletedAt());
        assertEquals("dispatched", deliveryMapper.getDelivery(workspace.getId(), inFlightId).getStatus());
        assertEquals(1, sweptPass);

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        var settled = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(1, settled.getDispatchedCount());
        assertEquals(1, settled.getFailedCount());
        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), abandonedId);
        assertEquals("failed", swept.getStatus());
        assertNotNull(swept.getReconciliationRequiredAt());
        assertEquals(0, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "not_delivered"})
    void aSubmissionThatLosesToTheSweepStillCorrelatesAfterAnOperatorResolvedIt(String resolution)
            throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        submissionObserver = () -> {
            expireReservation(deliveryId, reservationGraceSeconds() + 60);
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
            CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("failed", swept.getStatus());
            assertNotNull(swept.getReconciliationRequiredAt());
            triggeredSendService.reconcile(send.campaignId(), deliveryId,
                    new CampaignDeliveryReconciliationRequest(resolution));
            sqlSession.clearCache();
            CampaignDelivery resolved = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("operator_" + resolution, resolved.getReconciliationOutcome());
            assertNull(resolved.getProviderMessageId());
        };

        dispatch(send);
        sqlSession.clearCache();

        boolean delivered = "delivered".equals(resolution);
        assertEquals(1, submissions.size());
        CampaignDelivery late = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals(delivered ? "dispatched" : "failed", late.getStatus());
        assertEquals("operator_" + resolution, late.getReconciliationOutcome());
        assertNull(late.getReconciliationRequiredAt());
        assertEquals(delivered, late.getFrequencyReservedAt() != null);
        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, late.getProviderId());
        assertEquals("message-1", late.getProviderMessageId());
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, deliveryEvents(deliveryId, "dispatched"));
        assertFalse(suppressedOrRevoked(person));

        assertEquals(1, ingest("{\"event\":\"bounce\",\"bounceType\":\"hard\",\"eventId\":\"bounce-"
                + unique() + "\",\"messageId\":\"message-1\"}"));

        CampaignDelivery bounced = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals(delivered ? "bounced" : "failed", bounced.getStatus());
        assertEquals("operator_" + resolution, bounced.getReconciliationOutcome());
        assertNull(bounced.getReconciliationRequiredAt());
        assertEquals(1, deliveryEvents(deliveryId, "bounced"));
        assertTrue(audienceEligibilityService.suppressedIds(
                workspace.getId(), List.of(person.getId()), "email").contains(person.getId()));
        assertTrue(audienceEligibilityService.revokedConsentIds(
                workspace.getId(), List.of(person.getId()), "email", "marketing").contains(person.getId()));
    }

    @Test
    void aTriggeredSubmissionThatLosesToTheClaimSweepKeepsItsCorrelationSoAHardBounceStillSuppresses()
            throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        asTriggeredSend(send);
        when(triggeredSendGate.enabled()).thenReturn(true);
        submissionObserver = () -> {
            expireDispatchLease(deliveryId);
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
            CampaignDelivery overtaken = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("failed", overtaken.getStatus());
            assertEquals(EXPIRED_NON_IDEMPOTENT_CLAIM, overtaken.getLastError());
            assertNull(overtaken.getProviderMessageId());
        };

        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        sqlSession.clearCache();

        assertEquals(1, submissions.size());
        CampaignDelivery late = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", late.getStatus());
        assertEquals(EXPIRED_NON_IDEMPOTENT_CLAIM, late.getLastError());
        assertEquals("deadline_ambiguous", late.getLastErrorCode());
        assertNotNull(late.getReconciliationRequiredAt());
        assertNull(late.getReconciliationOutcome());
        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, late.getProviderId());
        assertEquals("message-1", late.getProviderMessageId());
        assertNull(late.getSubmittedAt());
        assertNotNull(late.getFrequencyReservedAt());
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, deliveryEvents(deliveryId, "dispatched"));
        assertFalse(suppressedOrRevoked(person));

        assertEquals(1, ingest("{\"event\":\"bounce\",\"bounceType\":\"hard\",\"eventId\":\"bounce-"
                + unique() + "\",\"messageId\":\"message-1\"}"));

        CampaignDelivery bounced = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("bounced", bounced.getStatus());
        assertNull(bounced.getReconciliationRequiredAt());
        assertEquals(1, deliveryEvents(deliveryId, "bounced"));
        assertTrue(audienceEligibilityService.suppressedIds(
                workspace.getId(), List.of(person.getId()), "email").contains(person.getId()));
        assertTrue(audienceEligibilityService.revokedConsentIds(
                workspace.getId(), List.of(person.getId()), "email", "marketing").contains(person.getId()));
    }

    @Test
    void theLateTriggeredCorrelationCompareAndSetRefusesRequeuedAndReclaimedAttempts() {
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        when(triggeredSendGate.enabled()).thenReturn(false);
        ResolvedDeliveryProvider target =
                configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        Person sweptPerson = recipient();
        int sweptId = expiredTriggeredClaim(sweptPerson, readySend(sweptPerson, DeliveryChannel.EMAIL), target);
        Person resolvedPerson = recipient();
        CampaignSendDto resolvedSend = readySend(resolvedPerson, DeliveryChannel.EMAIL);
        int resolvedId = expiredTriggeredClaim(resolvedPerson, resolvedSend, target);
        Person requeuedPerson = recipient();
        int requeuedId = expiredTriggeredClaim(
                requeuedPerson, readySend(requeuedPerson, DeliveryChannel.EMAIL), target);
        Person reclaimedPerson = recipient();
        int reclaimedId = expiredTriggeredClaim(
                reclaimedPerson, readySend(reclaimedPerson, DeliveryChannel.EMAIL), target);
        assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                workspace.getId(), requeuedId, target.attemptTargetFingerprint()));
        assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                workspace.getId(), reclaimedId, target.attemptTargetFingerprint()));
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), reclaimedId,
                UUID.randomUUID().toString(), DISPATCH_LEASE_MICROS, target.providerId(),
                target.attemptTargetFingerprint()));

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
        triggeredSendService.reconcile(resolvedSend.campaignId(), resolvedId,
                new CampaignDeliveryReconciliationRequest("not_delivered"));
        sqlSession.clearCache();

        assertEquals(0, deliveryMapper.attachLateTriggeredProviderCorrelation(workspace.getId(), sweptId,
                target.providerId(), "a-different-attempt-fingerprint", "changed-target-message"));
        assertEquals(0, deliveryMapper.attachLateTriggeredProviderCorrelation(workspace.getId(), sweptId,
                SmsHttpDeliveryProvider.PROVIDER_ID, target.attemptTargetFingerprint(), "other-provider-message"));
        assertEquals(0, lateTriggeredCorrelation(requeuedId, target, "requeued-message"));
        assertEquals(0, lateTriggeredCorrelation(reclaimedId, target, "reclaimed-message"));
        assertEquals(1, lateTriggeredCorrelation(sweptId, target, "late-message"));
        assertEquals(0, lateTriggeredCorrelation(sweptId, target, "second-message"));
        assertEquals(1, lateTriggeredCorrelation(resolvedId, target, "resolved-message"));
        sqlSession.clearCache();

        CampaignDelivery correlated = deliveryMapper.getDelivery(workspace.getId(), sweptId);
        assertEquals("failed", correlated.getStatus());
        assertEquals(EXPIRED_NON_IDEMPOTENT_CLAIM, correlated.getLastError());
        assertNotNull(correlated.getReconciliationRequiredAt());
        assertNull(correlated.getReconciliationOutcome());
        assertEquals("late-message", correlated.getProviderMessageId());
        assertNotNull(correlated.getFrequencyReservedAt());
        CampaignDelivery resolved = deliveryMapper.getDelivery(workspace.getId(), resolvedId);
        assertEquals("operator_not_delivered", resolved.getReconciliationOutcome());
        assertNull(resolved.getReconciliationRequiredAt());
        assertEquals("resolved-message", resolved.getProviderMessageId());
        CampaignDelivery requeued = deliveryMapper.getDelivery(workspace.getId(), requeuedId);
        assertEquals("pending", requeued.getStatus());
        assertNull(requeued.getProviderMessageId());
        CampaignDelivery reclaimed = deliveryMapper.getDelivery(workspace.getId(), reclaimedId);
        assertEquals("dispatching", reclaimed.getStatus());
        assertNull(reclaimed.getProviderMessageId());
        assertEquals(0, submissions.size());
    }

    @Test
    void theLateTriggeredCorrelationAttachesToATargetChangeSweepAndToARequeuedAttemptDrivenTerminal() {
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        ResolvedDeliveryProvider target =
                configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        Person changedTargetPerson = recipient();
        int changedTargetId = expiredTriggeredClaim(changedTargetPerson,
                readySend(changedTargetPerson, DeliveryChannel.EMAIL), target);
        Person requeuedPerson = recipient();
        int requeuedId = expiredTriggeredClaim(
                requeuedPerson, readySend(requeuedPerson, DeliveryChannel.EMAIL), target);
        assertEquals(1, deliveryMapper.markExpiredTriggeredClaimAmbiguous(workspace.getId(),
                changedTargetId, EXPIRED_CHANGED_TARGET_CLAIM, "delivery_target_changed"));
        assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                workspace.getId(), requeuedId, target.attemptTargetFingerprint()));
        String laterOwner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), requeuedId, laterOwner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        assertEquals(1, deliveryMapper.markTriggeredAmbiguous(workspace.getId(), requeuedId, laterOwner,
                EARLIER_ATTEMPT_AMBIGUOUS, "deadline_ambiguous"));
        sqlSession.clearCache();

        assertEquals(1, lateTriggeredCorrelation(changedTargetId, target, "changed-target-message"));
        assertEquals(1, lateTriggeredCorrelation(requeuedId, target, "earlier-attempt-message"));

        CampaignDelivery changedTarget = deliveryMapper.getDelivery(workspace.getId(), changedTargetId);
        assertEquals("failed", changedTarget.getStatus());
        assertEquals(EXPIRED_CHANGED_TARGET_CLAIM, changedTarget.getLastError());
        assertEquals("delivery_target_changed", changedTarget.getLastErrorCode());
        assertNotNull(changedTarget.getReconciliationRequiredAt());
        assertNull(changedTarget.getReconciliationOutcome());
        assertNull(changedTarget.getSubmittedAt());
        assertNotNull(changedTarget.getFrequencyReservedAt());
        assertEquals("changed-target-message", changedTarget.getProviderMessageId());
        CampaignDelivery requeued = deliveryMapper.getDelivery(workspace.getId(), requeuedId);
        assertEquals("failed", requeued.getStatus());
        assertEquals(EARLIER_ATTEMPT_AMBIGUOUS, requeued.getLastError());
        assertEquals("deadline_ambiguous", requeued.getLastErrorCode());
        assertNotNull(requeued.getReconciliationRequiredAt());
        assertNull(requeued.getReconciliationOutcome());
        assertNull(requeued.getSubmittedAt());
        assertEquals("earlier-attempt-message", requeued.getProviderMessageId());
        assertEquals(0, submissions.size());
    }

    @Test
    void aTriggeredReceiptWithoutAMessageIdRecordsNoLateCorrelationAfterTheClaimSweep() {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        asTriggeredSend(send);
        when(triggeredSendGate.enabled()).thenReturn(true);
        responseOmitsMessageId = true;
        submissionObserver = () -> {
            expireDispatchLease(deliveryId);
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
        };

        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        sqlSession.clearCache();

        assertEquals(1, submissions.size());
        verify(deliveryMapper, never()).attachLateTriggeredProviderCorrelation(
                anyInt(), anyInt(), any(), any(), any());
        CampaignDelivery uncorrelated = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("failed", uncorrelated.getStatus());
        assertEquals(EXPIRED_NON_IDEMPOTENT_CLAIM, uncorrelated.getLastError());
        assertNotNull(uncorrelated.getReconciliationRequiredAt());
        assertNull(uncorrelated.getReconciliationOutcome());
        assertNull(uncorrelated.getProviderMessageId());
    }

    @Test
    void aTriggeredReplayThatNamesNoMessageIdKeepsTheOriginalCorrelationSoAHardBounceStillSuppresses()
            throws Exception {
        Person person = recipient();
        configService.save(idempotentProviderRequest());
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        asTriggeredSend(send);
        when(triggeredSendGate.enabled()).thenReturn(true);
        submissionObserver = () -> {
            if (submissions.size() > 1) {
                return;
            }
            responseOmitsMessageId = true;
            expireDispatchLease(deliveryId);
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
            sqlSession.clearCache();
            CampaignDelivery replayed = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("dispatched", replayed.getStatus());
            assertNotNull(replayed.getSubmittedAt());
            assertNull(replayed.getProviderMessageId());
        };

        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        sqlSession.clearCache();

        assertEquals(2, submissions.size());
        assertEquals(submissions.getFirst().headers().get(IDEMPOTENCY_HEADER),
                submissions.getLast().headers().get(IDEMPOTENCY_HEADER));
        CampaignDelivery late = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("dispatched", late.getStatus());
        assertNull(late.getReconciliationRequiredAt());
        assertNull(late.getReconciliationOutcome());
        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, late.getProviderId());
        assertEquals("message-1", late.getProviderMessageId());
        assertEquals(1, deliveryEvents(deliveryId, "dispatched"));
        assertEquals(0, deliveryEvents(deliveryId, "failed"));
        assertFalse(suppressedOrRevoked(person));

        assertEquals(1, ingest("{\"event\":\"bounce\",\"bounceType\":\"hard\",\"eventId\":\"bounce-"
                + unique() + "\",\"messageId\":\"message-1\"}"));

        CampaignDelivery bounced = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("bounced", bounced.getStatus());
        assertEquals(1, deliveryEvents(deliveryId, "bounced"));
        assertTrue(audienceEligibilityService.suppressedIds(
                workspace.getId(), List.of(person.getId()), "email").contains(person.getId()));
        assertTrue(audienceEligibilityService.revokedConsentIds(
                workspace.getId(), List.of(person.getId()), "email", "marketing").contains(person.getId()));
    }

    @Test
    void theLateTriggeredCorrelationTakesAnUncorrelatedReplayButNeverALeasedOrCorrelatedOne() {
        configService.save(idempotentProviderRequest());
        when(triggeredSendGate.enabled()).thenReturn(false);
        ResolvedDeliveryProvider target =
                configService.resolveForWorkspace(workspace.getId(), DeliveryChannel.EMAIL);
        Person replayedPerson = recipient();
        int replayedId = expiredTriggeredClaim(
                replayedPerson, readySend(replayedPerson, DeliveryChannel.EMAIL), target);
        Person correlatedPerson = recipient();
        int correlatedId = expiredTriggeredClaim(
                correlatedPerson, readySend(correlatedPerson, DeliveryChannel.EMAIL), target);
        String replayOwner = UUID.randomUUID().toString();
        String correlatedOwner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                workspace.getId(), replayedId, target.attemptTargetFingerprint()));
        assertEquals(1, deliveryMapper.recoverExpiredTriggeredClaim(
                workspace.getId(), correlatedId, target.attemptTargetFingerprint()));
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), replayedId, replayOwner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), correlatedId, correlatedOwner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        sqlSession.clearCache();

        assertEquals(0, lateTriggeredCorrelation(replayedId, target, "still-leased-message"));

        assertEquals(1, deliveryMapper.markTriggeredDispatched(
                workspace.getId(), replayedId, replayOwner, target.providerId(), null));
        assertEquals(1, deliveryMapper.markTriggeredDispatched(
                workspace.getId(), correlatedId, correlatedOwner, target.providerId(), "replay-message"));
        sqlSession.clearCache();

        assertEquals(0, deliveryMapper.attachLateTriggeredProviderCorrelation(workspace.getId(), replayedId,
                target.providerId(), "a-different-attempt-fingerprint", "changed-target-message"));
        assertEquals(0, deliveryMapper.attachLateTriggeredProviderCorrelation(workspace.getId(), replayedId,
                SmsHttpDeliveryProvider.PROVIDER_ID, target.attemptTargetFingerprint(), "other-provider-message"));
        assertEquals(1, lateTriggeredCorrelation(replayedId, target, "late-message"));
        assertEquals(0, lateTriggeredCorrelation(replayedId, target, "second-message"));
        assertEquals(0, lateTriggeredCorrelation(correlatedId, target, "overwriting-message"));

        CampaignDelivery correlated = deliveryMapper.getDelivery(workspace.getId(), replayedId);
        assertEquals("dispatched", correlated.getStatus());
        assertEquals("late-message", correlated.getProviderMessageId());
        assertNull(correlated.getReconciliationRequiredAt());
        assertNull(correlated.getReconciliationOutcome());
        assertNotNull(correlated.getSubmittedAt());
        CampaignDelivery untouched = deliveryMapper.getDelivery(workspace.getId(), correlatedId);
        assertEquals("dispatched", untouched.getStatus());
        assertEquals("replay-message", untouched.getProviderMessageId());
        assertEquals(0, submissions.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "not_delivered"})
    void aTriggeredSubmissionThatLosesToTheClaimSweepStillCorrelatesAfterAnOperatorResolvedIt(String resolution)
            throws Exception {
        Person person = recipient();
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(person, DeliveryChannel.EMAIL);
        int deliveryId = pendingDelivery(send);
        asTriggeredSend(send);
        when(triggeredSendGate.enabled()).thenReturn(true);
        submissionObserver = () -> {
            expireDispatchLease(deliveryId);
            assertEquals(0, dispatchService.processWorkspace(workspace.getId()));
            CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("failed", swept.getStatus());
            assertNotNull(swept.getReconciliationRequiredAt());
            triggeredSendService.reconcile(send.campaignId(), deliveryId,
                    new CampaignDeliveryReconciliationRequest(resolution));
            sqlSession.clearCache();
            CampaignDelivery resolved = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
            assertEquals("operator_" + resolution, resolved.getReconciliationOutcome());
            assertNull(resolved.getProviderMessageId());
        };

        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
        sqlSession.clearCache();

        boolean delivered = "delivered".equals(resolution);
        assertEquals(1, submissions.size());
        CampaignDelivery late = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals(delivered ? "dispatched" : "failed", late.getStatus());
        assertEquals("operator_" + resolution, late.getReconciliationOutcome());
        assertNull(late.getReconciliationRequiredAt());
        assertEquals(delivered, late.getFrequencyReservedAt() != null);
        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, late.getProviderId());
        assertEquals("message-1", late.getProviderMessageId());
        assertEquals(1, deliveryEvents(deliveryId, "failed"));
        assertEquals(0, deliveryEvents(deliveryId, "dispatched"));
        assertFalse(suppressedOrRevoked(person));

        assertEquals(1, ingest("{\"event\":\"bounce\",\"bounceType\":\"hard\",\"eventId\":\"bounce-"
                + unique() + "\",\"messageId\":\"message-1\"}"));

        CampaignDelivery bounced = deliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals(delivered ? "bounced" : "failed", bounced.getStatus());
        assertEquals("operator_" + resolution, bounced.getReconciliationOutcome());
        assertEquals(1, deliveryEvents(deliveryId, "bounced"));
        assertTrue(audienceEligibilityService.suppressedIds(
                workspace.getId(), List.of(person.getId()), "email").contains(person.getId()));
        assertTrue(audienceEligibilityService.revokedConsentIds(
                workspace.getId(), List.of(person.getId()), "email", "marketing").contains(person.getId()));
    }

    @Test
    void aWebhookThatClearsTheReconciliationMarkerCannotStrandARunningSend() throws Exception {
        Person abandonedRecipient = recipient();
        Person inFlightRecipient = peerOf(abandonedRecipient);
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(abandonedRecipient, DeliveryChannel.EMAIL);
        sendService.queueSend(send.campaignId(), send.id());
        int abandonedId = claimedAudienceAttempt(send, abandonedRecipient);
        int inFlightId = claimedAudienceAttempt(send, inFlightRecipient);
        assertEquals(1, campaignSendMapper.markRunning(workspace.getId(), send.id()));
        expireReservation(abandonedId, reservationGraceSeconds() + 60);
        disableEmailProvider();

        assertEquals(1, dispatchService.processWorkspace(workspace.getId()));

        CampaignDelivery swept = deliveryMapper.getDelivery(workspace.getId(), abandonedId);
        assertEquals("failed", swept.getStatus());
        assertNotNull(swept.getReconciliationRequiredAt());
        assertEquals("running", campaignSendMapper.getSend(workspace.getId(), send.id()).getStatus());
        assertEquals(1, lateCorrelation(abandonedId, "message-1"));

        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        assertEquals(1, ingest("{\"event\":\"delivered\",\"eventId\":\"receipt-" + unique()
                + "\",\"messageId\":\"message-1\"}"));
        disableEmailProvider();

        CampaignDelivery receipted = deliveryMapper.getDelivery(workspace.getId(), abandonedId);
        assertEquals("delivered", receipted.getStatus());
        assertNull(receipted.getReconciliationRequiredAt());
        completeInFlightAttempt(inFlightId, "peer-message");
        assertTrue(campaignSendMapper.audienceSendsAwaitingRecoverySettlement(workspace.getId(), 10)
                .contains(send.id()));

        assertEquals(0, dispatchService.processWorkspace(workspace.getId()));

        var settled = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(1, settled.getDispatchedCount());
        assertEquals(0, settled.getFailedCount());
        assertFalse(campaignSendMapper.audienceSendsAwaitingRecoverySettlement(workspace.getId(), 10)
                .contains(send.id()));
        assertEquals(0, submissions.size());
    }

    @Test
    void theSettlementCompletionRefusesUntilNoDeliveryIsOutstanding() {
        Person abandonedRecipient = recipient();
        Person peerRecipient = peerOf(abandonedRecipient);
        configService.save(providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL)));
        CampaignSendDto send = readySend(abandonedRecipient, DeliveryChannel.EMAIL);
        sendService.queueSend(send.campaignId(), send.id());
        int abandonedId = claimedAudienceAttempt(send, abandonedRecipient);
        int peerId = deliveryMapper.getBySendAndPerson(
                workspace.getId(), send.id(), peerRecipient.getId()).getId();
        assertEquals(1, campaignSendMapper.markRunning(workspace.getId(), send.id()));

        assertEquals("pending", deliveryMapper.getDelivery(workspace.getId(), peerId).getStatus());
        assertEquals(0, campaignSendMapper.markSettledAudienceSendCompleted(
                workspace.getId(), send.id()));

        assertEquals(1, deliveryMapper.claim(workspace.getId(), peerId));
        assertEquals(0, campaignSendMapper.markSettledAudienceSendCompleted(
                workspace.getId(), send.id()));

        completeInFlightAttempt(peerId, "peer-message");
        assertEquals("dispatching", deliveryMapper.getDelivery(workspace.getId(), abandonedId).getStatus());
        assertEquals(0, campaignSendMapper.markSettledAudienceSendCompleted(
                workspace.getId(), send.id()));
        assertEquals("running", campaignSendMapper.getSend(workspace.getId(), send.id()).getStatus());

        completeInFlightAttempt(abandonedId, "abandoned-message");
        assertEquals(1, campaignSendMapper.markSettledAudienceSendCompleted(
                workspace.getId(), send.id()));

        var settled = campaignSendMapper.getSend(workspace.getId(), send.id());
        assertEquals("completed", settled.getStatus());
        assertNotNull(settled.getCompletedAt());
        assertEquals(0, campaignSendMapper.markSettledAudienceSendCompleted(
                workspace.getId(), send.id()));
        assertEquals(0, submissions.size());
    }

    private Person peerOf(Person recipient) {
        Person peer = recipient();
        assertEquals(1, jdbcTemplate.update("UPDATE person SET name = ? WHERE workspace_id = ? AND id = ?",
                recipient.getName() + " peer", workspace.getId(), peer.getId()));
        return peer;
    }

    private int claimedAudienceAttempt(CampaignSendDto send, Person person) {
        int deliveryId = deliveryMapper.getBySendAndPerson(
                workspace.getId(), send.id(), person.getId()).getId();
        assertEquals(1, deliveryMapper.claim(workspace.getId(), deliveryId));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), deliveryId, person.getId(), "email", null, 24));
        return deliveryId;
    }

    private void completeInFlightAttempt(int deliveryId, String providerMessageId) {
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery"
                        + " SET status = 'dispatched', provider_id = ?, provider_message_id = ?,"
                        + " submitted_at = UTC_TIMESTAMP(6)"
                        + " WHERE workspace_id = ? AND id = ? AND status = 'dispatching'",
                HttpEspDeliveryProvider.PROVIDER_ID, providerMessageId, workspace.getId(), deliveryId));
        sqlSession.clearCache();
    }

    private void disableEmailProvider() {
        DeliveryProviderConfigRequest disabled =
                providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        disabled.setEnabled(false);
        configService.save(disabled);
    }

    private int lateCorrelation(int deliveryId, String providerMessageId) {
        int updated = deliveryMapper.attachLateAudienceProviderCorrelation(workspace.getId(), deliveryId,
                HttpEspDeliveryProvider.PROVIDER_ID, providerMessageId, EXPIRED_AUDIENCE_RESERVATION,
                "deadline_ambiguous");
        sqlSession.clearCache();
        return updated;
    }

    private int lateTriggeredCorrelation(
            int deliveryId, ResolvedDeliveryProvider target, String providerMessageId) {
        int updated = deliveryMapper.attachLateTriggeredProviderCorrelation(workspace.getId(), deliveryId,
                target.providerId(), target.attemptTargetFingerprint(), providerMessageId);
        sqlSession.clearCache();
        return updated;
    }

    private int expiredTriggeredClaim(Person person, CampaignSendDto send, ResolvedDeliveryProvider target) {
        int deliveryId = pendingDelivery(send);
        asTriggeredSend(send);
        String owner = UUID.randomUUID().toString();
        assertEquals(1, deliveryMapper.claimTriggered(workspace.getId(), deliveryId, owner,
                DISPATCH_LEASE_MICROS, target.providerId(), target.attemptTargetFingerprint()));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), deliveryId, person.getId(), "email", owner, 24));
        expireDispatchLease(deliveryId);
        return deliveryId;
    }

    private boolean suppressedOrRevoked(Person person) {
        return audienceEligibilityService.suppressedIds(
                        workspace.getId(), List.of(person.getId()), "email").contains(person.getId())
                || audienceEligibilityService.revokedConsentIds(
                        workspace.getId(), List.of(person.getId()), "email", "marketing").contains(person.getId());
    }

    private int lateAudienceSweep(int deliveryId, long graceMicros) {
        int updated = deliveryMapper.markExpiredAudienceReservationAmbiguous(workspace.getId(), deliveryId,
                graceMicros, "AMBIGUOUS: Late overlapping sweep", "relay_error");
        sqlSession.clearCache();
        return updated;
    }

    private void markSubmitted(int deliveryId, boolean submitted) {
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery"
                        + " SET submitted_at = IF(?, UTC_TIMESTAMP(6), NULL)"
                        + " WHERE workspace_id = ? AND id = ?",
                submitted, workspace.getId(), deliveryId));
        sqlSession.clearCache();
    }

    private int strandedAudienceAttempt(Person person, CampaignSendDto send) {
        int deliveryId = pendingDelivery(send);
        sendService.queueSend(send.campaignId(), send.id());
        assertEquals(1, deliveryMapper.claim(workspace.getId(), deliveryId));
        assertEquals(CampaignFrequencyAdmissionService.Admission.RESERVED, frequencyAdmissionService.reserve(
                workspace.getId(), deliveryId, person.getId(), "email", null, 24));
        return deliveryId;
    }

    private LocalDateTime expireReservation(int deliveryId, long secondsAgo) {
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery"
                        + " SET frequency_reserved_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL ? SECOND)"
                        + " WHERE workspace_id = ? AND id = ?",
                secondsAgo, workspace.getId(), deliveryId));
        sqlSession.clearCache();
        LocalDateTime reservation = deliveryMapper.getDelivery(workspace.getId(), deliveryId).getFrequencyReservedAt();
        assertNotNull(reservation);
        return reservation;
    }

    private long reservationGraceSeconds() {
        return deliveryProperties.providerCallReservationGrace().toSeconds();
    }

    private long reservationGraceMicros() {
        return deliveryProperties.providerCallReservationGrace().toNanos() / 1_000L;
    }

    private int deliveryEvents(int deliveryId, String eventType) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM campaign_delivery_event"
                        + " WHERE workspace_id = ? AND delivery_id = ? AND event_type = ?",
                Integer.class, workspace.getId(), deliveryId, eventType);
        return Objects.requireNonNull(count);
    }

    private <T> T asActor(String name, Supplier<T> action) {
        operation.set(name);
        authenticateAs(currentUser, workspace.getId());
        try {
            return action.get();
        } finally {
            clearAuthentication();
            operation.remove();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating delivery workers", exception);
        }
    }

    private void ingestDelivered(CampaignDelivery delivery) throws Exception {
        assertEquals(1, ingest("{\"event\":\"delivered\",\"eventId\":\"receipt-" + unique()
                + "\",\"messageId\":\"" + delivery.getProviderMessageId() + "\"}"));
    }

    private int ingest(String payload) throws Exception {
        DeliveryWebhookTokenDto webhook = configService.issueWebhookToken("email");
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(webhook.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body));
        return webhookService.ingest(HttpEspDeliveryProvider.PROVIDER_ID, webhook.token(), body,
                Map.of(HttpEspDeliveryProvider.SIGNATURE_HEADER, signature));
    }

    private void dispatch(CampaignSendDto send) {
        sendService.queueSend(send.campaignId(), send.id());
        assertTrue(dispatchService.processSend(workspace.getId(), send.id()));
    }

    private int pendingDelivery(CampaignSendDto send) {
        List<Integer> pending = deliveryMapper.pendingDeliveryIds(workspace.getId(), send.id());
        assertEquals(1, pending.size());
        return pending.getFirst();
    }

    private Person recipient() {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Delivery recipient " + unique());
        person.setEmail(unique() + "@recipient.test");
        person.setPhone("+14155552671");
        personMapper.insert(person);
        for (DeliveryChannel channel : List.of(DeliveryChannel.EMAIL, DeliveryChannel.SMS)) {
            consentService.setForPerson(person.getId(), new ContactChannelConsentRequest(
                    channel.token(), "marketing", "granted", "manual", null, null));
        }
        return person;
    }

    private CampaignSendDto readySend(Person person, DeliveryChannel channel) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Delivery security " + unique(), null, channel.token(), null,
                currentUser.getId(), null, null, null, null, null));
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(person.getName());
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        campaignService.setAudience(campaign.id(), new CampaignAudienceRequest(
                "person", definition, channel.token(), "marketing"));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = sendService.createMessage(campaign.id(),
                new CampaignMessageRequest("Delivery message", channel.token()));
        sendService.addRevision(campaign.id(), message.id(), channel == DeliveryChannel.EMAIL
                ? new CampaignMessageRevisionRequest("en", "Hello", "<p>{{unsubscribe_url}}</p>", null)
                : new CampaignMessageRevisionRequest("en", null, null, "Hello {{unsubscribe_url}}"));
        return sendService.createSend(campaign.id(), new CampaignSendRequest(1, message.id(), 1, null, null));
    }

    private DeliveryProviderConfigRequest providerRequest(DeliveryChannel channel, String apiKey) {
        DeliveryProviderConfigRequest request = new DeliveryProviderConfigRequest();
        request.setChannel(channel.token());
        request.setProvider(channel == DeliveryChannel.EMAIL
                ? HttpEspDeliveryProvider.PROVIDER_ID : SmsHttpDeliveryProvider.PROVIDER_ID);
        request.setEnabled(true);
        request.setEndpoint("https://" + channel.token() + ".provider.test/send");
        request.setFromAddress(channel == DeliveryChannel.EMAIL ? "sender@provider.test" : "Connex");
        request.setApiKey(apiKey);
        return request;
    }

    private DeliveryProviderConfigRequest idempotentProviderRequest() {
        DeliveryProviderConfigRequest request =
                providerRequest(DeliveryChannel.EMAIL, key(DeliveryChannel.EMAIL));
        request.setIdempotentSubmission(true);
        return request;
    }

    private DeliveryProviderConfig storedConfig(DeliveryChannel channel) {
        return configMapper.findByWorkspaceChannel(workspace.getId(), channel.token());
    }

    private void seedLegacySharedCredentialRow() {
        jdbcTemplate.update("INSERT INTO secret_value (scope_type, scope_id, purpose, key_id,"
                        + " key_algorithm, data_algorithm, encrypted_data_key, ciphertext)"
                        + " VALUES ('workspace', ?, ?, 'legacy-kek', 'AES', 'AES/GCM/NoPadding',"
                        + " 'legacy-wrapped-data-key', 'legacy-ciphertext')",
                workspace.getId(), LEGACY_SHARED_CREDENTIAL_PURPOSE);
    }

    private int secretRowsWithPurpose(String purpose) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM secret_value"
                        + " WHERE scope_type = 'workspace' AND scope_id = ? AND purpose = ?",
                Integer.class, workspace.getId(), purpose);
        return Objects.requireNonNull(count);
    }

    private void asTriggeredSend(CampaignSendDto send) {
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_send SET origin = 'triggered',"
                        + " status = 'triggered' WHERE workspace_id = ? AND id = ?",
                workspace.getId(), send.id()));
        sqlSession.clearCache();
    }

    private void expireDispatchLease(int deliveryId) {
        assertEquals(1, jdbcTemplate.update("UPDATE campaign_delivery"
                        + " SET dispatch_lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)"
                        + " WHERE workspace_id = ? AND id = ?",
                workspace.getId(), deliveryId));
        sqlSession.clearCache();
    }

    private void assertDispatchCredential(DeliveryChannel channel, String expectedKey) {
        var target = configService.resolveForWorkspace(workspace.getId(), channel);
        DeliveryRequest request = new DeliveryRequest(channel,
                channel == DeliveryChannel.EMAIL ? "recipient@recipient.test" : "+14155552671",
                new RenderedMessage("Hello", "<p>Hello</p>", "Hello"), null, unique(),
                System.nanoTime() + 5_000_000_000L);
        assertEquals(DispatchStatus.SENT, router.dispatcherFor(target.providerId()).dispatch(target, request).status());
        assertEquals(URI.create("https://" + channel.token() + ".provider.test/send"),
                submissions.getLast().endpoint());
        assertEquals("Bearer " + expectedKey, submissions.getLast().headers().get("Authorization"));
    }

    private void assertResolvedCredential(ResolvedDeliveryProvider target, String endpoint, String expectedKey) {
        DeliveryRequest request = new DeliveryRequest(target.channel(),
                target.channel() == DeliveryChannel.EMAIL ? "recipient@recipient.test" : "+14155552671",
                new RenderedMessage("Hello", "<p>Hello</p>", "Hello"), null, unique(),
                System.nanoTime() + 5_000_000_000L);
        assertEquals(DispatchStatus.SENT, router.dispatcherFor(target.providerId()).dispatch(target, request).status());
        assertEquals(URI.create(endpoint), submissions.getLast().endpoint());
        assertEquals("Bearer " + expectedKey, submissions.getLast().headers().get("Authorization"));
    }

    private static String key(DeliveryChannel channel) {
        return channel.token() + "-private-key";
    }

    private record RecordedSubmission(URI endpoint, Map<String, String> headers) {
    }
}
