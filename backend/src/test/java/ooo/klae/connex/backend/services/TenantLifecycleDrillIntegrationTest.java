package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DeliveryEvent;
import ooo.klae.connex.backend.delivery.DeliveryEventType;
import ooo.klae.connex.backend.delivery.ProviderEventSource;
import ooo.klae.connex.backend.delivery.provider.esp.HttpEspDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.smtp.BodySafeSmtpTransport;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.support.SmtpCaptureServer;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.CustomFieldValue;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Team;
import ooo.klae.connex.backend.beans.TeamMember;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.TenantResidualReport;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.TeamMapper;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.UploadContentInspector;
import ooo.klae.connex.backend.storage.UploadMalwareScanner;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "connex.tenant-lifecycle.teardown-settle-delay=0s",
        "connex.delivery.enabled=true",
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantLifecycleDrillIntegrationTest extends AbstractServiceTest {
    private static final byte[] BINARY = "tenant-lifecycle-binary".getBytes(StandardCharsets.UTF_8);

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @MockitoSpyBean private TenantLifecycleControlMapper controlMapper;
    @MockitoSpyBean private NotificationMapper notificationMapperSpy;
    @Autowired private TenantLifecycleControlOperations controlOperations;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private TenantExportService exportService;
    @Autowired private CampaignDispatchService campaignDispatchService;
    @Autowired private DeliveryWebhookService deliveryWebhookService;
    @Autowired private ConsentService consentService;
    @Autowired private SuppressionService suppressionService;
    @MockitoBean private DeliveryProviderConfigService deliveryProviderConfigService;
    @MockitoSpyBean private DeliveryProviderRouter deliveryProviderRouter;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private ManagedObjectService managedObjectService;
    @Autowired private UploadContentInspector uploadContentInspector;
    @Autowired private UploadMalwareScanner uploadMalwareScanner;
    @Autowired private ObjectStorageProperties objectStorageProperties;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired private CustomFieldValueMapper customFieldValueMapper;
    @Autowired private AiOutputCacheMapper aiOutputCacheMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private AuditIntegrityService auditIntegrityService;
    @Autowired private IdentityBackfillTransaction identityBackfillTransaction;
    @Autowired private RecordCreationTemplateService recordCreationTemplateService;
    @Autowired private TeamMapper teamMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;

    private Organization drillOrganization;
    private Organization otherOrganization;
    private Workspace drillWorkspace;
    private Workspace otherWorkspace;
    private User concurrentLeaver;

    @Test
    void providerReturnedMessageIdCannotPersistOrExportTheAttemptBearer() throws Exception {
        createDedicatedDrillRoots();
        seedUnsubscribeDigest();
        int workspaceId = drillWorkspace.getId();
        int sendId = jdbcTemplate.queryForObject("SELECT id FROM campaign_send WHERE workspace_id = ?",
                Integer.class, workspaceId);
        int messageId = jdbcTemplate.queryForObject("SELECT message_id FROM campaign_send WHERE id = ?",
                Integer.class, sendId);
        int deliveryId = jdbcTemplate.queryForObject("SELECT id FROM campaign_delivery WHERE send_id = ?",
                Integer.class, sendId);
        jdbcTemplate.update("""
                INSERT INTO campaign_message_revision
                    (workspace_id, message_id, version, locale, subject, body_html)
                VALUES (?, ?, 1, 'en', 'Provider receipt', '<a href="{{unsubscribe_url}}">Unsubscribe</a>')
                """, workspaceId, messageId);
        jdbcTemplate.update("UPDATE campaign_send SET status = 'queued' WHERE id = ?", sendId);
        jdbcTemplate.update("UPDATE campaign_delivery SET status = 'pending' WHERE id = ?", deliveryId);
        AtomicReference<String> bearer = new AtomicReference<>();
        MessageDispatcher dispatcher = org.mockito.Mockito.mock(MessageDispatcher.class);
        org.mockito.Mockito.when(dispatcher.dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    DeliveryRequest request = invocation.getArgument(1);
                    java.util.regex.Matcher token = java.util.regex.Pattern.compile("/unsubscribe#token=([a-f0-9]{64})")
                            .matcher(request.content().bodyHtml());
                    assertTrue(token.find());
                    bearer.set(token.group(1));
                    return DispatchReceipt.sent("receipt " + bearer.get() + " other " + "C".repeat(64) + " retained", "accepted");
                });
        org.mockito.Mockito.doReturn(dispatcher).when(deliveryProviderRouter).dispatcherFor(HttpEspDeliveryProvider.PROVIDER_ID);
        ResolvedDeliveryProvider target = ResolvedDeliveryProvider.of(HttpEspDeliveryProvider.PROVIDER_ID, DeliveryChannel.EMAIL, workspaceId, DeliveryCredentials.none());
        org.mockito.Mockito.when(deliveryProviderConfigService.resolveForWorkspace(workspaceId, DeliveryChannel.EMAIL)).thenReturn(target);
        org.mockito.Mockito.when(deliveryProviderConfigService.isCurrentClaimTarget(target, deliveryId, null)).thenReturn(true);
        assertTrue(campaignDispatchService.processSend(workspaceId, sendId));
        assertNotNull(bearer.get());
        assertEquals("dispatched", jdbcTemplate.queryForObject("SELECT status FROM campaign_delivery WHERE id = ?",
                String.class, deliveryId));
        String expected = "receipt " + OneTimeTokenDigest.sha256(bearer.get()) + " other " + "C".repeat(64) + " retained";
        assertEquals(expected,
                jdbcTemplate.queryForObject("SELECT provider_message_id FROM campaign_delivery WHERE id = ?",
                        String.class, deliveryId));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.prepare(drillOrganization.getId(), workspaceId, currentUser.getId()).writeTo(output);
        Map<String, byte[]> entries = zipEntries(output.toByteArray());
        assertTrue(text(entries, "data/campaign_delivery.jsonl").contains(expected));
        assertTrue(entries.values().stream().noneMatch(bytes -> new String(bytes, StandardCharsets.UTF_8).contains(bearer.get())));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"BOUNCED, opaque", "COMPLAINED, opaque", "BOUNCED, current", "COMPLAINED, current", "BOUNCED, legacy", "COMPLAINED, legacy", "BOUNCED, foreign", "COMPLAINED, foreign", "BOUNCED, overlap", "COMPLAINED, overlap"})
    void providerIdsCorrelateSuppressingWebhooksAndPreserveOpaqueHexIds(DeliveryEventType type, String idKind) {
        createDedicatedDrillRoots();
        String legacyBearer = seedUnsubscribeDigest();
        int workspaceId = drillWorkspace.getId();
        Person recipient = newPerson(newCompany());
        consentService.setForPerson(recipient.getId(), new ContactChannelConsentRequest(
                "email", "marketing", "granted", "initial", null, null));
        int sendId = jdbcTemplate.queryForObject("SELECT id FROM campaign_send WHERE workspace_id = ?",
                Integer.class, workspaceId);
        int messageId = jdbcTemplate.queryForObject("SELECT message_id FROM campaign_send WHERE id = ?",
                Integer.class, sendId);
        int deliveryId = jdbcTemplate.queryForObject("SELECT id FROM campaign_delivery WHERE send_id = ?",
                Integer.class, sendId);
        jdbcTemplate.update("""
                INSERT INTO campaign_message_revision
                    (workspace_id, message_id, version, locale, subject, body_html)
                VALUES (?, ?, 1, 'en', 'Opaque provider id', '<a href="{{unsubscribe_url}}">Unsubscribe</a>')
                """, workspaceId, messageId);
        jdbcTemplate.update("UPDATE campaign_send SET status = 'queued' WHERE id = ?", sendId);
        jdbcTemplate.update("UPDATE campaign_delivery SET status = 'pending', person_id = ?, address = ? WHERE id = ?",
                recipient.getId(), recipient.getEmail(), deliveryId);
        var overlap = "overlap".equals(idKind)
                ? ooo.klae.connex.backend.support.UnsubscribeCorrelationFixture.create() : null;
        String overlapCorrelation = overlap == null ? null
                : OneTimeTokenDigest.sha256(overlap.first()).substring(0, 63) + OneTimeTokenDigest.sha256(overlap.second());
        if (overlap != null) {
            jdbcTemplate.update("""
                    INSERT INTO campaign_delivery_unsubscribe_token (workspace_id, delivery_id, token_hash)
                    VALUES (?, ?, ?), (?, ?, ?)
                    """, workspaceId, deliveryId, OneTimeTokenDigest.sha256(overlap.first()),
                    workspaceId, deliveryId, OneTimeTokenDigest.sha256(overlap.second()));
        }
        Integer foreignDeliveryId = null;
        if ("foreign".equals(idKind)) {
            jdbcTemplate.update("""
                    INSERT INTO campaign_delivery (workspace_id, send_id, address, status)
                    SELECT workspace_id, send_id, 'foreign@example.test', 'dispatched'
                    FROM campaign_delivery WHERE id = ?
                    """, deliveryId);
            foreignDeliveryId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM campaign_delivery WHERE send_id = ?",
                    Integer.class, sendId);
            jdbcTemplate.update("""
                    UPDATE campaign_delivery_unsubscribe_token SET delivery_id = ?
                    WHERE workspace_id = ? AND token_hash = ?
                    """, foreignDeliveryId, workspaceId, OneTimeTokenDigest.sha256(legacyBearer));
            assertEquals(foreignDeliveryId, jdbcTemplate.queryForObject("""
                    SELECT delivery_id FROM campaign_delivery_unsubscribe_token
                    WHERE workspace_id = ? AND token_hash = ?
                    """, Integer.class, workspaceId, OneTimeTokenDigest.sha256(legacyBearer)));
        }
        AtomicReference<String> bearer = new AtomicReference<>();
        AtomicReference<String> providerMessageId = new AtomicReference<>();
        MessageDispatcher dispatcher = org.mockito.Mockito.mock(MessageDispatcher.class);
        org.mockito.Mockito.when(dispatcher.dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    DeliveryRequest request = invocation.getArgument(1);
                    java.util.regex.Matcher token = java.util.regex.Pattern.compile("/unsubscribe#token=([a-f0-9]{64})")
                            .matcher(request.content().bodyHtml());
                    assertTrue(token.find());
                    bearer.set(token.group(1));
                    if ("foreign".equals(idKind)) {
                        bearer.set(legacyBearer);
                    }
                    if (overlap != null) {
                        bearer.set(overlap.first());
                    }
                    String rawId = overlap == null ? bearer.get() : overlap.identifier();
                    providerMessageId.set(!"opaque".equals(idKind) ? "receipt:0" + rawId.toUpperCase(java.util.Locale.ROOT) + "f"
                            : "AB".repeat(32));
                    return DispatchReceipt.sent(providerMessageId.get(), "accepted");
                });
        org.mockito.Mockito.doReturn(dispatcher).when(deliveryProviderRouter).dispatcherFor(HttpEspDeliveryProvider.PROVIDER_ID);
        ResolvedDeliveryProvider target = ResolvedDeliveryProvider.of(
                HttpEspDeliveryProvider.PROVIDER_ID, DeliveryChannel.EMAIL, workspaceId, DeliveryCredentials.none());
        org.mockito.Mockito.when(deliveryProviderConfigService.resolveForWorkspace(workspaceId, DeliveryChannel.EMAIL)).thenReturn(target);
        org.mockito.Mockito.when(deliveryProviderConfigService.isCurrentClaimTarget(target, deliveryId, null)).thenReturn(true);

        if ("legacy".equals(idKind)) {
            bearer.set(legacyBearer);
            providerMessageId.set("receipt:0" + legacyBearer.toUpperCase(java.util.Locale.ROOT) + "f");
            jdbcTemplate.update("UPDATE campaign_delivery SET status = 'dispatched', provider_id = ?, provider_message_id = ? WHERE id = ?",
                    HttpEspDeliveryProvider.PROVIDER_ID, "receipt:0" + OneTimeTokenDigest.sha256(legacyBearer) + "f", deliveryId);
        } else {
            assertTrue(campaignDispatchService.processSend(workspaceId, sendId));
        }
        assertEquals("dispatched", jdbcTemplate.queryForObject("SELECT status FROM campaign_delivery WHERE id = ?",
                String.class, deliveryId));
        String expectedMessageId = !"opaque".equals(idKind)
                ? "receipt:0" + (overlapCorrelation == null ? OneTimeTokenDigest.sha256(bearer.get()) : overlapCorrelation) + "f"
                : "AB".repeat(32);
        assertEquals(expectedMessageId, jdbcTemplate.queryForObject("SELECT provider_message_id FROM campaign_delivery WHERE id = ?",
                String.class, deliveryId));
        assertEquals("granted", consentService.getForPerson(recipient.getId()).getFirst().status());
        assertTrue(suppressionService.list().isEmpty());

        ProviderEventSource source = org.mockito.Mockito.mock(ProviderEventSource.class);
        byte[] body = "authenticated webhook".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("signature", "verified-by-provider");
        org.mockito.Mockito.when(deliveryProviderConfigService.resolveByWebhookToken(
                org.mockito.ArgumentMatchers.eq("webhook-token"), org.mockito.ArgumentMatchers.any())).thenReturn(target);
        org.mockito.Mockito.doReturn(source).when(deliveryProviderRouter).eventSourceFor(HttpEspDeliveryProvider.PROVIDER_ID);
        org.mockito.Mockito.when(source.translate(body)).thenReturn(List.of(
                new DeliveryEvent(providerMessageId.get(), "event:" + (overlap == null ? bearer.get() : overlap.identifier()), type, null,
                        type.token() + " reason " + bearer.get().toUpperCase(java.util.Locale.ROOT))));

        assertEquals(1, deliveryWebhookService.ingest(HttpEspDeliveryProvider.PROVIDER_ID, "webhook-token", body, headers));
        assertEquals(0, deliveryWebhookService.ingest(HttpEspDeliveryProvider.PROVIDER_ID, "webhook-token", body, headers));
        org.mockito.Mockito.verify(source, org.mockito.Mockito.times(2)).verifySignature(target, body, headers);
        assertEquals("event:" + (overlapCorrelation == null ? OneTimeTokenDigest.sha256(bearer.get()) : overlapCorrelation), jdbcTemplate.queryForObject(
                "SELECT provider_event_id FROM campaign_delivery_event WHERE delivery_id = ? AND event_type = ?",
                String.class, deliveryId, type.token()));
        assertEquals(type.token() + " reason [redacted]", jdbcTemplate.queryForObject(
                "SELECT detail FROM campaign_delivery_event WHERE delivery_id = ? AND event_type = ?",
                String.class, deliveryId, type.token()));
        assertEquals(type.token(), jdbcTemplate.queryForObject("SELECT status FROM campaign_delivery WHERE id = ?",
                String.class, deliveryId));
        if (foreignDeliveryId != null) {
            assertEquals("dispatched", jdbcTemplate.queryForObject("SELECT status FROM campaign_delivery WHERE id = ?",
                    String.class, foreignDeliveryId));
        }
        String reason = type == DeliveryEventType.BOUNCED ? "hard_bounce" : "complaint";
        assertEquals(1, suppressionService.list().stream().filter(entry -> reason.equals(entry.reason())
                && recipient.getEmail().equals(entry.address())).count());
        assertEquals("revoked", consentService.getForPerson(recipient.getId()).getFirst().status());
        assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM contact_channel_consent_event WHERE workspace_id = ? AND person_id = ?
                """, Integer.class, workspaceId, recipient.getId()));
    }

    @Test
    void rejectingSmtpDiagnosticsCannotExportOrLogTheAttemptBearer() throws Exception {
        createDedicatedDrillRoots();
        seedUnsubscribeDigest();
        int workspaceId = drillWorkspace.getId();
        int sendId = jdbcTemplate.queryForObject("SELECT id FROM campaign_send WHERE workspace_id = ?",
                Integer.class, workspaceId);
        int messageId = jdbcTemplate.queryForObject("SELECT message_id FROM campaign_send WHERE id = ?",
                Integer.class, sendId);
        int deliveryId = jdbcTemplate.queryForObject("SELECT id FROM campaign_delivery WHERE send_id = ?",
                Integer.class, sendId);
        jdbcTemplate.update("""
                INSERT INTO campaign_message_revision
                    (workspace_id, message_id, version, locale, subject, body_html)
                VALUES (?, ?, 1, 'en', 'Rejected SMTP', '<a href="{{unsubscribe_url}}">Unsubscribe</a>')
                """, workspaceId, messageId);
        jdbcTemplate.update("UPDATE campaign_send SET status = 'queued' WHERE id = ?", sendId);
        jdbcTemplate.update("UPDATE campaign_delivery SET status = 'pending' WHERE id = ?", deliveryId);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLevel = logger.getLevel();
        java.util.logging.Logger smtpLogger = java.util.logging.Logger.getLogger(
                BodySafeSmtpTransport.class.getPackageName());
        java.util.logging.Level previousSmtpLevel = smtpLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        smtpLogger.setLevel(java.util.logging.Level.FINE);
        try (SmtpCaptureServer smtp = new SmtpCaptureServer(true)) {
            ResolvedMailConfig config = new ResolvedMailConfig("127.0.0.1", smtp.port(), null, null,
                    "sender@example.test", "Capture", false, false, false, 2000, 2000, 2000, false);
            org.mockito.Mockito.when(deliveryProviderConfigService.resolveForWorkspace(workspaceId, DeliveryChannel.EMAIL))
                    .thenReturn(new ResolvedDeliveryProvider("smtp", DeliveryChannel.EMAIL, workspaceId,
                            null, null, null, DeliveryCredentials.none(), false, "f".repeat(64), config));
            assertTrue(campaignDispatchService.processSend(workspaceId, sendId));
            String bearer = SmtpCaptureServer.unsubscribeBearer(smtp.awaitMessage());
            assertNotNull(bearer);
            String diagnostic = jdbcTemplate.queryForObject("SELECT last_error FROM campaign_delivery WHERE id = ?",
                    String.class, deliveryId);
            assertNotNull(diagnostic);
            assertTrue(diagnostic.contains("554 rejected [redacted] diagnostic [redacted] retained"));
            assertFalse(diagnostic.contains(bearer));
            assertFalse(diagnostic.contains("C".repeat(64)));
            String detail = jdbcTemplate.queryForObject("SELECT detail FROM campaign_delivery_event WHERE delivery_id = ?",
                    String.class, deliveryId);
            assertEquals(diagnostic, detail);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            exportService.prepare(drillOrganization.getId(), workspaceId, currentUser.getId()).writeTo(output);
            Map<String, byte[]> entries = zipEntries(output.toByteArray());
            assertTrue(text(entries, "data/campaign_delivery.jsonl").contains("[redacted]"));
            assertTrue(text(entries, "data/campaign_delivery_event.jsonl").contains("[redacted]"));
            assertTrue(entries.values().stream().noneMatch(bytes ->
                    new String(bytes, StandardCharsets.UTF_8).contains(bearer)));
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG));
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                    && BodySafeSmtpTransport.class.getPackageName().equals(event.getLoggerName())
                    && event.getFormattedMessage().contains("554 rejected [redacted] diagnostic [redacted] retained")));
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(bearer));
                assertFalse(event.getMessage().contains(bearer));
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) {
                        assertFalse(String.valueOf(argument).contains(bearer));
                    }
                }
                if (event.getThrowableProxy() != null) {
                    assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(bearer));
                }
            }
        } finally {
            logger.setLevel(previousLevel);
            smtpLogger.setLevel(previousSmtpLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @AfterEach
    void cleanCommittedDrillRoots() {
        if (otherOrganization != null) {
            if (otherWorkspace != null) {
                jdbcTemplate.update(
                    "DELETE FROM team_member WHERE workspace_id = ?",
                    otherWorkspace.getId());
                jdbcTemplate.update(
                    "DELETE FROM team WHERE workspace_id = ?",
                    otherWorkspace.getId());
                jdbcTemplate.update(
                    "DELETE FROM workspace WHERE id = ?",
                    otherWorkspace.getId());
            }
            jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?",
                otherOrganization.getId());
        }
        if (drillOrganization != null) {
            jdbcTemplate.update(
                "DELETE FROM tenant_operation_lease WHERE org_id = ?",
                drillOrganization.getId());
            jdbcTemplate.update(
                "UPDATE data_subject_request SET status = 'closed', closed_at = NOW()"
                    + " WHERE org_id = ? AND status NOT IN ('closed', 'refused')",
                drillOrganization.getId());
            WorkspaceLifecycleRef remaining = drillWorkspace == null
                ? null
                : controlMapper.findWorkspaceInOrg(
                    drillOrganization.getId(),
                    drillWorkspace.getId());
            if (remaining != null) {
                try {
                    teardownService.teardownWorkspace(
                        drillOrganization.getId(),
                        drillWorkspace.getId(),
                        currentUser.getId(),
                        drillWorkspace.getSlug());
                } catch (RuntimeException ignored) {
                }
            }
            jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?",
                drillOrganization.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE user_id = ?",
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
        if (concurrentLeaver != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE user_id = ?",
                concurrentLeaver.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                concurrentLeaver.getId());
        }
    }

    @Test
    void exportsRestrictedHoldingsAndBinaryThenTeardownLeavesNoResiduals() throws Exception {
        createDedicatedDrillRoots();
        Fixture fixture = seedRepresentativeTenant();
        String foreignTeamName = seedForeignTenantTeam();
        WorkspaceLifecycleRef target = controlMapper.findWorkspaceInOrg(
            drillOrganization.getId(),
            drillWorkspace.getId());
        assertNotNull(target);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.prepare(
            drillOrganization.getId(),
            drillWorkspace.getId(),
            currentUser.getId()).writeTo(output);
        Map<String, byte[]> entries = zipEntries(output.toByteArray());

        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("data/campaign_delivery_unsubscribe_token.jsonl"));
        assertTrue(text(entries, "data/campaign_delivery_unsubscribe_token.jsonl").contains(
            OneTimeTokenDigest.sha256(fixture.unsubscribeBearer())));
        assertFalse(text(entries, "data/campaign_delivery.jsonl").contains("unsubscribe_token"));
        assertTrue(entries.values().stream().noneMatch(bytes ->
            new String(bytes, StandardCharsets.UTF_8).contains(fixture.unsubscribeBearer())));
        assertTrue(entries.containsKey("data/person.jsonl"));
        assertTrue(entries.containsKey("data/person_identity.jsonl"));
        assertTrue(entries.containsKey("data/company.jsonl"));
        assertTrue(entries.containsKey("data/company_identity.jsonl"));
        assertTrue(entries.containsKey("data/identity_collision.jsonl"));
        assertTrue(entries.containsKey("data/duplicate_review_decision.jsonl"));
        assertTrue(entries.containsKey("data/deal.jsonl"));
        assertTrue(entries.containsKey("data/activity.jsonl"));
        assertTrue(entries.containsKey("data/note.jsonl"));
        assertTrue(entries.containsKey("data/task.jsonl"));
        assertTrue(entries.containsKey("data/custom_field_definition.jsonl"));
        assertTrue(entries.containsKey("data/custom_field_value.jsonl"));
        assertTrue(entries.containsKey("data/record_creation_template_set.jsonl"));
        assertTrue(entries.containsKey("data/record_creation_template.jsonl"));
        assertTrue(entries.containsKey("data/record_creation_template_version.jsonl"));
        assertTrue(entries.containsKey("data/team.jsonl"));
        assertTrue(entries.containsKey("data/team_member.jsonl"));
        assertTrue(entries.containsKey("data/notification.jsonl"));
        assertTrue(entries.containsKey("data/saved_view.jsonl"));
        assertTrue(entries.containsKey("data/ai_output_cache.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_session.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_session_participant.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_message.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_tool_call.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_turn.jsonl"));
        String personJsonl = text(entries, "data/person.jsonl");
        assertTrue(personJsonl.contains(fixture.restrictedPersonName()));
        assertTrue(personJsonl.contains("suspended_at"));
        assertTrue(personJsonl.contains("provision_ceased_at"));
        assertTrue(text(entries, "data/person_identity.jsonl").contains("backfill"));
        assertTrue(text(entries, "data/duplicate_review_decision.jsonl")
            .contains("evidence_fingerprint"));
        assertTrue(text(entries, "data/ai_chat_session.jsonl").contains(fixture.chatTitle()));
        assertTrue(text(entries, "data/ai_chat_message.jsonl").contains(fixture.chatMessage()));
        assertTrue(text(entries, "data/ai_chat_tool_call.jsonl").contains(fixture.toolName()));
        assertTrue(text(entries, "data/ai_chat_turn.jsonl").contains(fixture.terminalReason()));
        assertTrue(text(entries, "data/record_creation_template_set.jsonl").contains("person"));
        assertTrue(text(entries, "data/record_creation_template.jsonl")
            .contains(Integer.toString(fixture.templateRootId())));
        assertTrue(text(entries, "data/record_creation_template_version.jsonl")
            .contains(fixture.templateName()));
        String teamJsonl = text(entries, "data/team.jsonl");
        assertTrue(teamJsonl.contains(fixture.teamName()));
        assertFalse(teamJsonl.contains(foreignTeamName));
        assertArrayEquals(BINARY, entries.get("objects/" + fixture.objectKey()));
        String manifest = text(entries, "manifest.json");
        assertTrue(manifest.contains("\"schemaVersion\":1"));
        assertTrue(manifest.contains("\"objectCount\":1"));
        assertTrue(Files.exists(fixture.objectPath()));
        AuditLog retained = new AuditLog();
        retained.setWorkspaceId(drillWorkspace.getId());
        retained.setOrgId(drillOrganization.getId());
        retained.setAction("test.lifecycle.retained");
        retained.setEntityType("workspace");
        retained.setEntityId(drillWorkspace.getId());
        retained.setTargetLabel(drillWorkspace.getSlug());
        retained.setOutcome("success");
        retained.setSummary("Workspace audit integrity survives teardown");
        auditIntegrityService.appendIndependent(retained);

        teardownService.teardownWorkspace(
            drillOrganization.getId(),
            drillWorkspace.getId(),
            currentUser.getId(),
            drillWorkspace.getSlug());

        TenantResidualReport residual = teardownService.verifyWorkspaceDeleted(
            target,
            currentUser.getId());
        assertTrue(residual.clean());
        Set<String> lifecycleTables = new HashSet<>(
            TenantLifecycleRegistry.declarations().keySet());
        lifecycleTables.addAll(ControlWorkspaceLifecycleRegistry.declarations().keySet());
        assertEquals(lifecycleTables, residual.tableRows().keySet());
        assertEquals(0, residual.totalRows());
        assertTrue(residual.tableRows().values().stream().allMatch(count -> count == 0));
        assertEquals(0, rowCount("campaign_delivery_unsubscribe_token"));
        assertEquals(0, rowCount("ai_chat_session"));
        assertEquals(0, rowCount("ai_chat_session_participant"));
        assertEquals(0, rowCount("ai_chat_message"));
        assertEquals(0, rowCount("ai_chat_tool_call"));
        assertEquals(0, rowCount("ai_chat_turn"));
        assertEquals(0, rowCount("record_creation_template_set"));
        assertEquals(0, rowCount("record_creation_template"));
        assertEquals(0, rowCount("record_creation_template_version"));
        assertEquals(0, rowCount("duplicate_review_decision"));
        assertEquals(0, rowCount("team"));
        assertEquals(0, rowCount("team_member"));
        assertFalse(Files.exists(fixture.objectPath()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            drillWorkspace.getId()));
        assertTrue(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'tenant.workspace.teardown'"
                + " AND workspace_id IS NULL AND org_id = ?",
            Integer.class,
            drillOrganization.getId()) > 0);
        Map<String, Object> retainedReferences = jdbcTemplate.queryForMap(
            "SELECT workspace_id, org_id, integrity_workspace_id, integrity_org_id"
                + " FROM audit_log WHERE id = ?",
            retained.getId());
        assertNull(retainedReferences.get("workspace_id"));
        assertEquals(drillOrganization.getId(), retainedReferences.get("org_id"));
        assertEquals(
            drillWorkspace.getId(),
            retainedReferences.get("integrity_workspace_id"));
        assertEquals(
            drillOrganization.getId(),
            retainedReferences.get("integrity_org_id"));
        retained.setWorkspaceId(null);
        assertTrue(auditIntegrityService.hasValidIntegrity(retained));
        assertTrue(auditLogMapper.findOrgExport(
            drillOrganization.getId(),
            500,
            0).stream().noneMatch(
                entry -> "test.lifecycle.retained".equals(entry.getAction())));
    }

    @Test
    void tearsDownOrganizationAndAllWorkspaceRoots() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();

        teardownService.teardownOrganization(
            orgId,
            currentUser.getId(),
            drillOrganization.getSlug());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            workspaceId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization WHERE id = ?",
            Integer.class,
            orgId));
        assertTrue(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'tenant.organization.teardown'"
                + " AND org_id IS NULL"
                + " AND chain_scope_type = 'organization'"
                + " AND chain_scope_id = ?",
            Integer.class,
            orgId) > 0);
    }

    @Test
    void openSubjectRequestBlocksTeardownAndTerminalDeletionClearsTheRetainedLink() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        Person subject = newPerson(newCompany());
        long requestId = insertOpenSubjectRequest(orgId, workspaceId, subject.getId());

        assertThrows(
            ConflictException.class,
            () -> teardownService.teardownWorkspace(
                orgId,
                workspaceId,
                currentUser.getId(),
                drillWorkspace.getSlug()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            workspaceId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE id = ?",
            Integer.class,
            subject.getId()));

        jdbcTemplate.update(
            "UPDATE data_subject_request SET status = 'closed', closed_at = NOW() WHERE id = ?",
            requestId);
        teardownService.teardownWorkspace(
            orgId,
            workspaceId,
            currentUser.getId(),
            drillWorkspace.getSlug());

        Map<String, Object> retained = jdbcTemplate.queryForMap(
            "SELECT org_id, subject_workspace_id, subject_person_id"
                + " FROM data_subject_request WHERE id = ?",
            requestId);
        assertEquals(orgId, retained.get("org_id"));
        assertNull(retained.get("subject_workspace_id"));
        assertNull(retained.get("subject_person_id"));
    }

    @Test
    void aSubjectRequestOpenedAfterTheUnlockedCheckStillRefusesTheLifecycleFence() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        Person subject = newPerson(newCompany());
        insertOpenSubjectRequest(orgId, workspaceId, subject.getId());

        assertThrows(
            ConflictException.class,
            () -> controlOperations.acquireWorkspaceTeardown(
                orgId,
                workspaceId,
                currentUser.getId()));
        assertEquals("active", jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM workspace WHERE id = ?",
            String.class,
            workspaceId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_operation_lease WHERE workspace_id = ?",
            Integer.class,
            workspaceId));

        assertThrows(
            ConflictException.class,
            () -> controlOperations.markOrganizationTearingDown(orgId, currentUser.getId()));
        assertEquals("active", jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM organization WHERE id = ?",
            String.class,
            orgId));
    }

    @Test
    void memberLeaveAndWorkspaceTeardownCompleteWithoutCredentialDeadlock() throws Exception {
        createDedicatedDrillRoots();
        concurrentLeaver = newUser();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        int leaverId = concurrentLeaver.getId();
        insertApiCredential(orgId, workspaceId, leaverId, "Leave versus teardown");
        NotificationMapper realNotificationMapper =
            sqlSessionTemplate.getMapper(NotificationMapper.class);
        CountDownLatch leaveInsideTransaction = new CountDownLatch(1);
        CountDownLatch teardownMarkCommitted = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            leaveInsideTransaction.countDown();
            if (!teardownMarkCommitted.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Teardown lifecycle mark did not commit");
            }
            return realNotificationMapper.lockRecipientMemberships(leaverId);
        }).when(notificationMapperSpy).lockRecipientMemberships(leaverId);

        Throwable leaveFailure;
        AcquiredWorkspace acquired;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> leave = executor.submit(() -> captureFailure(() ->
                workspaceService.leaveWorkspace(workspaceId, leaverId)));
            assertTrue(leaveInsideTransaction.await(10, TimeUnit.SECONDS));
            try {
                acquired = controlOperations.acquireWorkspaceTeardown(
                    orgId, workspaceId, currentUser.getId());
            } finally {
                teardownMarkCommitted.countDown();
            }
            leaveFailure = leave.get(20, TimeUnit.SECONDS);
        }

        assertFalse(hasDeadlockCause(leaveFailure));
        assertNull(leaveFailure);
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
            Integer.class,
            workspaceId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE workspace_id = ?"
                + " AND action = 'api_credential.membership_removed'",
            Integer.class,
            workspaceId));
        controlOperations.deleteWorkspaceRoot(
            orgId, workspaceId, currentUser.getId(), acquired.lease());
        controlOperations.completeWorkspaceCleanup(
            orgId, workspaceId, currentUser.getId(), acquired.lease());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            workspaceId));
    }

    @Test
    void persistedExportLeasesNeverAgeOutAndRemainFailClosed() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        otherOrganization = new Organization();
        otherOrganization.setName("Lifecycle Neighbour " + unique());
        otherOrganization.setSlug("lifecycle-neighbour-" + unique());
        organizationMapper.insert(otherOrganization);
        String fresh = insertOperationLease(orgId, workspaceId, "export", 0);
        String stale = insertOperationLease(orgId, workspaceId, "export", 60);
        String teardown = insertOperationLease(orgId, workspaceId, "teardown", 60);
        String neighbour = insertOperationLease(
            otherOrganization.getId(),
            workspaceId + 1,
            "export",
            0);

        assertEquals(2, controlMapper.countOperationLeases(workspaceId, "export"));
        assertEquals(3, controlMapper.countOperationLeasesInOrg(orgId));
        assertEquals(1, controlMapper.countOperationLeasesInOrg(otherOrganization.getId()));

        assertTrue(leaseExists(fresh));
        assertTrue(leaseExists(stale));
        assertTrue(leaseExists(teardown));
        assertTrue(leaseExists(neighbour));
    }

    private String insertOperationLease(
            int orgId,
            int workspaceId,
            String leaseKind,
            int ageMinutes) {
        String token = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO tenant_operation_lease"
                + " (org_id, workspace_id, lease_kind, lease_token, created_at)"
                + " VALUES (?, ?, ?, ?, NOW(6) - INTERVAL ? MINUTE)",
            orgId,
            workspaceId,
            leaseKind,
            token,
            ageMinutes);
        return token;
    }

    private String seedForeignTenantTeam() {
        otherOrganization = new Organization();
        otherOrganization.setName("Lifecycle Foreign " + unique());
        otherOrganization.setSlug("lifecycle-foreign-" + unique());
        organizationMapper.insert(otherOrganization);
        otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(otherOrganization.getId());
        otherWorkspace.setName("Lifecycle Foreign " + unique());
        otherWorkspace.setSlug("lifecycle-foreign-" + unique());
        workspaceMapper.insert(otherWorkspace);
        String teamName = "Foreign lifecycle team " + unique();
        Team team = new Team();
        team.setWorkspaceId(otherWorkspace.getId());
        team.setName(teamName);
        teamMapper.insert(team);
        return teamName;
    }

    private boolean leaseExists(String leaseToken) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_operation_lease WHERE lease_token = ?",
            Integer.class,
            leaseToken);
        assertNotNull(count);
        return count == 1;
    }

    private void insertApiCredential(
            int orgId, int workspaceId, int userId, String name) {
        Long membershipId = jdbcTemplate.queryForObject(
            "SELECT membership_id FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
            Long.class,
            workspaceId,
            userId);
        assertNotNull(membershipId);
        String tokenHash = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
            "INSERT INTO api_credential"
                + " (workspace_id, organization_id, created_by_id, membership_id, name,"
                + " token_hash, token_last4, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, '562x', UTC_TIMESTAMP(6) + INTERVAL 1 DAY)",
            workspaceId,
            orgId,
            userId,
            membershipId,
            name,
            tokenHash);
    }

    private static Throwable captureFailure(FailingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static boolean hasDeadlockCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getErrorCode() == 1213) {
                return true;
            }
            if (cause.getMessage() != null
                    && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    private long insertOpenSubjectRequest(int orgId, int workspaceId, int personId) {
        jdbcTemplate.update(
            "INSERT INTO data_subject_request"
                + " (org_id, request_type, status, requester_name, subject_name,"
                + " subject_workspace_id, subject_person_id)"
                + " VALUES (?, 'disclosure', 'received', ?, ?, ?, ?)",
            orgId,
            "Requester " + unique(),
            "Subject " + unique(),
            workspaceId,
            personId);
        Long requestId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(requestId);
        return requestId;
    }

    private void createDedicatedDrillRoots() {
        drillOrganization = new Organization();
        drillOrganization.setName("Lifecycle Org " + unique());
        drillOrganization.setSlug("lifecycle-org-" + unique());
        organizationMapper.insert(drillOrganization);
        orgMemberService.addFoundingOwner(
            drillOrganization.getId(),
            currentUser.getId());
        drillWorkspace = new Workspace();
        drillWorkspace.setOrgId(drillOrganization.getId());
        drillWorkspace.setName("Lifecycle Workspace " + unique());
        drillWorkspace.setSlug("lifecycle-workspace-" + unique());
        workspaceMapper.insert(drillWorkspace);
        workspaceMapper.addMember(
            drillWorkspace.getId(),
            currentUser.getId(),
            "owner");
        workspace = drillWorkspace;
        authenticateAs(currentUser, drillWorkspace.getId());
    }

    private Fixture seedRepresentativeTenant() {
        Company company = newCompany();
        Person visible = newPerson(company);
        newPerson(company);
        Person restricted = newPerson(company);
        personMapper.updateProcessingRestrictions(
            drillWorkspace.getId(),
            restricted.getId(),
            true,
            true);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        newActivity(currentUser, visible, deal);
        newNote(currentUser, visible, deal);
        newTask(currentUser, visible, deal);
        Tag tag = newTag();
        personMapper.addTag(
            drillWorkspace.getId(),
            visible.getId(),
            tag.getId());
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(drillWorkspace.getId());
        definition.setEntityType("person");
        definition.setFieldKey("lifecycle_" + unique());
        definition.setLabel("Lifecycle field");
        definition.setFieldType("text");
        definition.setDataClassification("standard");
        customFieldDefinitionMapper.insert(definition);
        CustomFieldValue value = new CustomFieldValue();
        value.setWorkspaceId(drillWorkspace.getId());
        value.setDefinitionId(definition.getId());
        value.setEntityType("person");
        value.setEntityId(visible.getId());
        value.setValueText("retained");
        customFieldValueMapper.upsert(value);
        newNotification(drillWorkspace.getId(), currentUser.getId());
        jdbcTemplate.update(
            "INSERT INTO saved_view"
                + " (workspace_id, user_id, record_type, name, config_json)"
                + " VALUES (?, ?, 'person', ?, JSON_OBJECT())",
            drillWorkspace.getId(),
            currentUser.getId(),
            "Lifecycle view " + unique());
        AiOutputCache cache = new AiOutputCache();
        cache.setWorkspaceId(drillWorkspace.getId());
        cache.setFeature("lifecycle_drill");
        cache.setSubjectAId(deal.getId());
        cache.setSubjectBId(0);
        cache.setContentHash("a".repeat(64));
        cache.setPayload("{\"retained\":true}");
        cache.setWarnings(0);
        cache.setGeneratedAt("2026-07-25T00:00:00Z");
        aiOutputCacheMapper.upsert(cache);
        String chatTitle = "Lifecycle assistant session " + unique();
        String chatMessage = "Lifecycle assistant message " + unique();
        String toolName = "lifecycle_lookup_" + unique();
        String terminalReason = "Lifecycle timeout " + unique();
        KeyHolder chatSessionKeyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_chat_session"
                    + " (workspace_id, created_by_user_id, title, visibility, status)"
                    + " VALUES (?, ?, ?, 'shared', 'active')",
                Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, drillWorkspace.getId());
            statement.setInt(2, currentUser.getId());
            statement.setString(3, chatTitle);
            return statement;
        }, chatSessionKeyHolder);
        Number chatSessionKey = chatSessionKeyHolder.getKey();
        assertNotNull(chatSessionKey);
        int chatSessionId = chatSessionKey.intValue();
        jdbcTemplate.update(
            "INSERT INTO ai_chat_session_participant"
                + " (workspace_id, session_id, user_id) VALUES (?, ?, ?)",
            drillWorkspace.getId(),
            chatSessionId,
            currentUser.getId());
        KeyHolder chatMessageKeyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_chat_message"
                    + " (workspace_id, session_id, seq, author_kind, author_user_id, content)"
                    + " VALUES (?, ?, 1, 'user', ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, drillWorkspace.getId());
            statement.setInt(2, chatSessionId);
            statement.setInt(3, currentUser.getId());
            statement.setString(4, chatMessage);
            return statement;
        }, chatMessageKeyHolder);
        Number chatMessageKey = chatMessageKeyHolder.getKey();
        assertNotNull(chatMessageKey);
        int chatMessageId = chatMessageKey.intValue();
        jdbcTemplate.update(
            "INSERT INTO ai_chat_tool_call"
                + " (workspace_id, message_id, tool_name, status, arguments_json, result_json)"
                + " VALUES (?, ?, ?, 'executed', JSON_OBJECT('recognizable', true),"
                + " JSON_OBJECT('retained', true))",
            drillWorkspace.getId(),
            chatMessageId,
            toolName);
        jdbcTemplate.update(
            "INSERT INTO ai_chat_turn"
                + " (workspace_id, session_id, requested_by_user_id, status, terminal_reason)"
                + " VALUES (?, ?, ?, 'timed_out', ?)",
            drillWorkspace.getId(),
            chatSessionId,
            currentUser.getId(),
            terminalReason);
        identityBackfillTransaction.backfillPersonPage(
            null, drillWorkspace.getId(), 0, 500);
        identityBackfillTransaction.backfillCompanyPage(
            null, drillWorkspace.getId(), 0, 500);
        int collisionMemberships = identityBackfillTransaction.rebuildCollisionReport(
            null, drillWorkspace.getId());
        assertTrue(rowCount("person_identity") > 0);
        assertTrue(rowCount("company_identity") > 0);
        assertTrue(collisionMemberships > 0);
        assertTrue(rowCount("duplicate_review_decision") > 0);
        String templateName = "Lifecycle template " + unique();
        RecordCreationTemplateDto template = recordCreationTemplateService.create(
            new RecordCreationTemplateCreateRequestDto(
                RecordCreationRecordType.person,
                new LocalizedTextDto(templateName, "ライフサイクルテンプレート"),
                null,
                new RecordCreationTemplateDefinitionDto(1, List.of(
                    new RecordCreationTemplateGroupDto(
                        "basics",
                        new LocalizedTextDto("Basics", "基本情報"),
                        null,
                        List.of(new RecordCreationTemplateFieldDto(
                            "name", false, null, null, null))))),
                true,
                0));
        int templateRootId = Integer.parseInt(template.id().substring("workspace:".length()));
        String teamName = "Lifecycle team " + unique();
        Team team = new Team();
        team.setWorkspaceId(drillWorkspace.getId());
        team.setName(teamName);
        team.setManagerUserId(currentUser.getId());
        teamMapper.insert(team);
        TeamMember teamMember = new TeamMember();
        teamMember.setWorkspaceId(drillWorkspace.getId());
        teamMember.setTeamId(team.getId());
        teamMember.setUserId(currentUser.getId());
        teamMember.setRole("manager");
        teamMapper.upsertMember(teamMember);
        StoredBinary binary = storeAttachment(company);
        String token = binary.url().substring("/api/attachments/content/".length());
        String objectKey = "workspaces/" + drillWorkspace.getId()
            + "/attachments/" + token;
        Path objectPath = objectStorageProperties.filesystemRootPath()
            .resolve(objectKey + ".object");
        return new Fixture(
            restricted.getName(),
            objectKey,
            objectPath,
            chatTitle,
            chatMessage,
            toolName,
            terminalReason,
            templateRootId,
            templateName,
            teamName,
            seedUnsubscribeDigest());
    }

    private String seedUnsubscribeDigest() {
        int workspaceId = drillWorkspace.getId();
        jdbcTemplate.update("INSERT INTO campaign (workspace_id, name, type, status) VALUES (?, 'Unsubscribe export', 'email', 'draft')", workspaceId);
        int campaignId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM campaign WHERE workspace_id = ?", Integer.class, workspaceId);
        jdbcTemplate.update("INSERT INTO campaign_message (workspace_id, campaign_id, channel, name, status) VALUES (?, ?, 'email', 'Export', 'final')", workspaceId, campaignId);
        int messageId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM campaign_message WHERE workspace_id = ?", Integer.class, workspaceId);
        jdbcTemplate.update("""
            INSERT INTO campaign_audience_snapshot
                (workspace_id, campaign_id, version, record_type, definition_json,
                 estimated_included, excluded_total, excluded_consent, excluded_suppressed, excluded_restricted)
            VALUES (?, ?, 1, 'person', '{}', 0, 0, 0, 0, 0)
            """, workspaceId, campaignId);
        int snapshotId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM campaign_audience_snapshot WHERE workspace_id = ?", Integer.class, workspaceId);
        jdbcTemplate.update("""
            INSERT INTO campaign_send (workspace_id, campaign_id, snapshot_id, message_id, message_version)
            VALUES (?, ?, ?, ?, 1)
            """, workspaceId, campaignId, snapshotId, messageId);
        int sendId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM campaign_send WHERE workspace_id = ?", Integer.class, workspaceId);
        jdbcTemplate.update("INSERT INTO campaign_delivery (workspace_id, send_id, address, status) VALUES (?, ?, 'export@example.test', 'dispatched')", workspaceId, sendId);
        int deliveryId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM campaign_delivery WHERE workspace_id = ?", Integer.class, workspaceId);
        String bearer = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
            INSERT INTO campaign_delivery_unsubscribe_token (workspace_id, delivery_id, token_hash)
            VALUES (?, ?, ?), (?, ?, ?)
            """, workspaceId, deliveryId, OneTimeTokenDigest.sha256(bearer),
            workspaceId, deliveryId, OneTimeTokenDigest.sha256(bearer + "replay"));
        return bearer;
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            drillWorkspace.getId());
    }

    private StoredBinary storeAttachment(Company company) {
        AtomicReference<StoredBinary> stored = new AtomicReference<>();
        ScannedUpload scanned = uploadMalwareScanner.scan(uploadContentInspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("lifecycle.txt", "text/plain", BINARY)));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            StoredBinary binary = managedObjectService.storeInspectedAttachment(
                drillWorkspace.getId(), scanned);
            Attachment attachment = new Attachment();
            attachment.setWorkspaceId(drillWorkspace.getId());
            attachment.setEntityType("company");
            attachment.setEntityId(company.getId());
            attachment.setFileName(binary.fileName());
            attachment.setUrl(binary.url());
            attachment.setContentType(binary.contentType());
            attachment.setSize(binary.size());
            attachment.setUploadedBy(currentUser);
            attachment.setScanState("clean");
            attachment.setScanEngine("ClamAV");
            attachment.setScanDatabaseVersion(scanned.report().databaseVersion());
            attachment.setScanSignature(scanned.report().signature());
            attachment.setScannedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            attachment.setScanExpiresAt(java.time.LocalDateTime.ofInstant(
                scanned.report().validUntil(), java.time.ZoneOffset.UTC));
            attachment.setScanAttempts(1);
            attachmentMapper.insert(attachment);
            stored.set(binary);
        });
        return stored.get();
    }

    private static Map<String, byte[]> zipEntries(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String path) {
        return new String(entries.get(path), StandardCharsets.UTF_8);
    }

    private record Fixture(
            String restrictedPersonName,
            String objectKey,
            Path objectPath,
            String chatTitle,
            String chatMessage,
            String toolName,
            String terminalReason,
            int templateRootId,
            String templateName,
            String teamName,
            String unsubscribeBearer) {
    }

    @FunctionalInterface
    private interface FailingAction {
        void run() throws Exception;
    }
}
