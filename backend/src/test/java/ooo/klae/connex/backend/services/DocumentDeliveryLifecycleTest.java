package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRecipientRequest;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.signature.DocumentSignatureProvider;
import ooo.klae.connex.backend.signature.ProviderEvent;
import ooo.klae.connex.backend.signature.ProviderSignedArtifact;
import ooo.klae.connex.backend.signature.SendCommand;
import ooo.klae.connex.backend.signature.SendOutcome;
import ooo.klae.connex.backend.signature.SendRecipientOutcome;
import ooo.klae.connex.backend.signature.VoidCommand;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

@Import(DocumentDeliveryLifecycleTest.TestSignatureProviderConfiguration.class)
class DocumentDeliveryLifecycleTest extends AbstractDocumentDeliveryServiceTest {
    @Autowired DocumentDeliveryScheduler scheduler;
    @Autowired DocumentSignatureWebhookService webhookService;
    @Autowired DocumentDeliveryLifecycleService lifecycleService;
    @Autowired DealService dealService;
    @Autowired DealDocumentMapper documentMapper;
    @Autowired DocumentDeliveryMapper deliveryMapper;
    @Autowired TenantLifecycleMapper lifecycleMapper;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void expirySweepIsIdempotentInvalidatesTokensAndRestoresTheDocument() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        installToken(delivery.recipients().getFirst().id());
        jdbcTemplate.update(
            "UPDATE document_delivery SET expires_at = ? WHERE workspace_id = ? AND id = ?",
            LocalDateTime.now().minusMinutes(1),
            workspace.getId(),
            delivery.id());

        scheduler.expire();
        scheduler.expire();

        assertEquals("expired", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals("final", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
        assertEquals(1, countExternalOrSystemEvents(delivery.id(), "expired"));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_recipient WHERE workspace_id = ? "
                + "AND delivery_id = ? AND token_hash IS NOT NULL",
            Integer.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void terminalProviderEventBeforeExpiryWinsAfterSchedulerExpiry() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(fixture, "test_signature");
        LocalDateTime expiresAt = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime occurredAt = expiresAt.minusSeconds(1);
        jdbcTemplate.update(
            "UPDATE document_delivery SET expires_at = ? WHERE workspace_id = ? AND id = ?",
            expiresAt,
            workspace.getId(),
            delivery.id());

        scheduler.expire();
        byte[] signedPdf = "%PDF-1.7 occurred-before-expiry"
            .getBytes(StandardCharsets.US_ASCII);
        assertTrue(webhookService.ingest("test_signature", Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(delivery.recipients().getFirst().id()),
            "x-event-id", "completed-before-expiry",
            "x-event-type", "completed",
            "x-occurred-at", occurredAt.toString(),
            "x-artifact-content-type", "application/pdf"), signedPdf));

        assertEquals("completed", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(occurredAt, jdbcTemplate.queryForObject(
            "SELECT completed_at FROM document_delivery WHERE workspace_id = ? AND id = ?",
            LocalDateTime.class,
            workspace.getId(),
            delivery.id()));
        assertEquals("signed", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
    }

    @Test
    void terminalProviderEventAtExpiryCannotReviveTheEnvelope() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(fixture, "test_signature");
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5).withNano(0);
        jdbcTemplate.update(
            "UPDATE document_delivery SET expires_at = ? WHERE workspace_id = ? AND id = ?",
            expiresAt,
            workspace.getId(),
            delivery.id());

        assertTrue(webhookService.ingest("test_signature", Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(delivery.recipients().getFirst().id()),
            "x-event-id", "completed-at-expiry",
            "x-event-type", "completed",
            "x-occurred-at", expiresAt.toString(),
            "x-artifact-content-type", "application/pdf"),
            "%PDF-1.7 too-late".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("expired", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ?",
            Integer.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void webhookReplayIsAppliedOnceAndLateEventCannotRegressTerminalState() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(fixture, "test_signature");
        int recipientId = delivery.recipients().getFirst().id();
        String envelope = delivery.providerEnvelopeId();
        String providerRecipient = Integer.toString(recipientId);
        Map<String, String> viewed = Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", envelope,
            "x-recipient", providerRecipient,
            "x-event-id", "viewed-1",
            "x-event-type", "viewed");

        assertTrue(webhookService.ingest("test_signature", viewed, new byte[0]));
        assertFalse(webhookService.ingest("test_signature", viewed, new byte[0]));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND external_event_id = 'viewed-1'",
            Integer.class,
            workspace.getId(),
            delivery.id()));

        Map<String, String> expired = Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", envelope,
            "x-recipient", providerRecipient,
            "x-event-id", "expired-2",
            "x-event-type", "expired");
        assertTrue(webhookService.ingest("test_signature", expired, new byte[0]));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_recipient WHERE workspace_id = ? "
                + "AND delivery_id = ? AND token_hash IS NOT NULL",
            Integer.class,
            workspace.getId(),
            delivery.id()));
        Map<String, String> late = Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", envelope,
            "x-recipient", providerRecipient,
            "x-event-id", "late-viewed-2",
            "x-event-type", "viewed");
        assertTrue(webhookService.ingest("test_signature", late, new byte[0]));
        assertEquals("expired", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void unknownAndInAppWebhookProvidersAreNotExposed() {
        assertThrows(ResourceNotFoundException.class, () -> webhookService.ingest(
            "unknown", Map.of(), new byte[0]));
        assertThrows(ResourceNotFoundException.class, () -> webhookService.ingest(
            "in_app", Map.of(), new byte[0]));
    }

    @Test
    void webhookRefusesDisabledGateOtherTenantAndMissingOccurrenceTime() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(fixture, "test_signature");
        Map<String, String> valid = Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(delivery.recipients().getFirst().id()),
            "x-event-id", "gate-test-viewed",
            "x-event-type", "viewed");

        signatureProperties.setEnabled(false);
        assertThrows(ServiceUnavailableException.class,
            () -> webhookService.ingest("test_signature", valid, new byte[0]));
        signatureProperties.setEnabled(true);

        ooo.klae.connex.backend.beans.Workspace other =
            new ooo.klae.connex.backend.beans.Workspace();
        other.setOrgId(workspace.getOrgId());
        other.setName("Webhook other workspace " + unique());
        other.setSlug("webhook-other-" + unique());
        workspaceMapper.insert(other);
        assertThrows(ResourceNotFoundException.class, () -> webhookService.ingest(
            "test_signature",
            Map.of(
                "x-workspace", Integer.toString(other.getId()),
                "x-envelope", delivery.providerEnvelopeId(),
                "x-recipient", Integer.toString(delivery.recipients().getFirst().id()),
                "x-event-id", "other-tenant-viewed",
                "x-event-type", "viewed"),
            new byte[0]));

        assertThrows(IllegalStateException.class, () -> webhookService.ingest(
            "test_signature",
            Map.of(
                "x-workspace", Integer.toString(workspace.getId()),
                "x-envelope", delivery.providerEnvelopeId(),
                "x-recipient", Integer.toString(delivery.recipients().getFirst().id()),
                "x-event-id", "missing-occurrence",
                "x-event-type", "viewed",
                "x-missing-occurrence", "true"),
            new byte[0]));
    }

    @Test
    void ambiguousProviderRecipientRoutingFailsClosed() {
        DocumentDeliveryRecipient first = new DocumentDeliveryRecipient();
        first.setProviderRecipientId("same-provider-id");
        DocumentDeliveryRecipient second = new DocumentDeliveryRecipient();
        second.setProviderRecipientId("same-provider-id");

        assertThrows(IllegalStateException.class, () ->
            DocumentSignatureWebhookService.recipientFor(
                List.of(first, second), "same-provider-id"));
    }

    @Test
    void authenticatedProviderCompletionStoresItsSignedPdfInTheSharedArtifactModel() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(fixture, "test_signature");
        int recipientId = delivery.recipients().getFirst().id();
        byte[] signedPdf = "%PDF-1.7 authenticated-provider-artifact"
            .getBytes(StandardCharsets.US_ASCII);
        Map<String, String> completed = Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(recipientId),
            "x-event-id", "completed-pdf-1",
            "x-event-type", "completed",
            "x-artifact-content-type", "application/pdf");

        assertTrue(webhookService.ingest("test_signature", completed, signedPdf));

        assertEquals("application/pdf", jdbcTemplate.queryForObject(
            "SELECT content_type FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals((long) signedPdf.length, jdbcTemplate.queryForObject(
            "SELECT byte_length FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            Long.class,
            workspace.getId(),
            delivery.id()));
        assertEquals("signed", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
    }

    @Test
    void providerArtifactReceivedBeforeTheFinalSignerIsReusedAtCompletion() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(
            fixture,
            "test_signature",
            signer("first-provider@example.test", 1),
            signer("second-provider@example.test", 2));
        byte[] signedPdf = "%PDF-1.7 staged-before-final-signer"
            .getBytes(StandardCharsets.US_ASCII);

        assertTrue(webhookService.ingest("test_signature", Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(delivery.recipients().getFirst().id()),
            "x-event-id", "completed-first-with-pdf",
            "x-event-type", "completed",
            "x-artifact-content-type", "application/pdf"), signedPdf));
        assertEquals("sent", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            Integer.class,
            workspace.getId(),
            delivery.id()));
        assertEquals((long) signedPdf.length, jdbcTemplate.queryForObject(
            "SELECT byte_length FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            Long.class,
            workspace.getId(),
            delivery.id()));

        assertTrue(webhookService.ingest("test_signature", Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(delivery.recipients().getLast().id()),
            "x-event-id", "completed-second-without-pdf",
            "x-event-type", "completed"), new byte[0]));
        assertEquals("completed", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ?",
            Integer.class,
            workspace.getId(),
            delivery.id()));
        assertEquals("application/pdf", jdbcTemplate.queryForObject(
            "SELECT content_type FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            String.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void providerArtifactAfterRecipientCompletionFinishesTheWaitingEnvelope() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(fixture, "test_signature");
        int recipientId = delivery.recipients().getFirst().id();

        assertTrue(webhookService.ingest("test_signature", Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(recipientId),
            "x-event-id", "recipient-completed-without-pdf",
            "x-event-type", "completed"), new byte[0]));
        assertEquals("sent", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));

        byte[] signedPdf = "%PDF-1.7 artifact-after-recipient-completion"
            .getBytes(StandardCharsets.US_ASCII);
        assertTrue(webhookService.ingest("test_signature", Map.of(
            "x-workspace", Integer.toString(workspace.getId()),
            "x-envelope", delivery.providerEnvelopeId(),
            "x-recipient", Integer.toString(recipientId),
            "x-event-id", "envelope-completed-with-pdf",
            "x-event-type", "completed",
            "x-artifact-content-type", "application/pdf"), signedPdf));
        assertEquals("completed", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals((long) signedPdf.length, jdbcTemplate.queryForObject(
            "SELECT byte_length FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            Long.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void completionUsesMaximumSignerDecisionAndCertificateIsOrderIndependent() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = sendWithProvider(
            fixture,
            "test_signature",
            signer("first-order@example.test", 1),
            signer("second-order@example.test", 2));
        LocalDateTime earlier = LocalDateTime.now().minusMinutes(2).withNano(0);
        LocalDateTime later = earlier.plusMinutes(1);
        byte[] signedPdf = "%PDF-1.7 deterministic-completion"
            .getBytes(StandardCharsets.US_ASCII);

        byte[] canonical = certificateForCallbackOrder(
            fixture,
            delivery,
            delivery.recipients().getLast().id(),
            later,
            delivery.recipients().getFirst().id(),
            earlier,
            delivery.recipients().getFirst().id(),
            signedPdf);
        byte[] reversed = certificateForCallbackOrder(
            fixture,
            delivery,
            delivery.recipients().getFirst().id(),
            earlier,
            delivery.recipients().getLast().id(),
            later,
            delivery.recipients().getFirst().id(),
            signedPdf);

        assertArrayEquals(canonical, reversed);
        JsonNode certificate = objectMapper.readTree(canonical);
        assertEquals(15, certificate.size());
        for (String field : List.of(
                "workspaceId",
                "dealId",
                "documentId",
                "documentVersion",
                "documentType",
                "approvalRequestId",
                "approvalOutcome",
                "approvalPolicyId",
                "provider",
                "providerEnvelopeId",
                "deliveryId",
                "sentAt",
                "completedAt",
                "signedDocumentSha256",
                "recipients")) {
            assertTrue(certificate.has(field));
        }
        assertEquals(workspace.getId(), certificate.path("workspaceId").asInt());
        assertEquals(fixture.deal().getId(), certificate.path("dealId").asInt());
        assertEquals(fixture.document().id(), certificate.path("documentId").asInt());
        assertEquals(fixture.document().version(), certificate.path("documentVersion").asInt());
        assertEquals(fixture.document().type(), certificate.path("documentType").asString());
        assertTrue(certificate.path("approvalRequestId").isNull());
        assertEquals("no_approval_required", certificate.path("approvalOutcome").asString());
        assertTrue(certificate.path("approvalPolicyId").isNull());
        assertEquals(64, certificate.path("signedDocumentSha256").asString().length());
        assertEquals(2, certificate.path("recipients").size());
        assertEquals(11, certificate.path("recipients").get(0).size());
    }

    @Test
    void certificateRetainsAppliedPolicyIdAfterTheLiveForeignKeyIsCleared() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(
            fixture, signer("policy-snapshot@example.test", 1));
        DealDocument document = documentMapper.getById(
            workspace.getId(), fixture.document().id());
        DocumentDelivery persisted = deliveryMapper.getById(
            workspace.getId(), delivery.id());
        ooo.klae.connex.backend.beans.DocumentApproval approval =
            new ooo.klae.connex.backend.beans.DocumentApproval();
        approval.setId(51);
        approval.setStatus("approved");
        approval.setPolicyId(null);
        approval.setPolicyIdSnapshot(37);
        approval.setPolicyBinding("applied");

        byte[] certificateBytes = lifecycleService.certificateBytes(
            workspace.getId(),
            fixture.deal(),
            document,
            approval,
            persisted,
            deliveryMapper.getRecipients(workspace.getId(), delivery.id()),
            LocalDateTime.now().withNano(0),
            "a".repeat(64));

        JsonNode certificate = objectMapper.readTree(certificateBytes);
        assertEquals(51, certificate.path("approvalRequestId").asInt());
        assertEquals("approved", certificate.path("approvalOutcome").asString());
        assertEquals(37, certificate.path("approvalPolicyId").asInt());

        approval.setPolicyIdSnapshot(null);
        approval.setPolicyBinding("unknown_legacy");
        assertThrows(IllegalStateException.class, () -> lifecycleService.certificateBytes(
            workspace.getId(),
            fixture.deal(),
            document,
            approval,
            persisted,
            deliveryMapper.getRecipients(workspace.getId(), delivery.id()),
            LocalDateTime.now().withNano(0),
            "a".repeat(64)));
    }

    @Test
    void networkedOutboundProviderExecutionFailsClosed() {
        DocumentFixture fixture = finalDocument();
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setProvider("test_signature");
        request.setRecipients(List.of(signer("provider@example.test", 1)));

        assertThrows(ServiceUnavailableException.class, () -> deliveryService.send(
            fixture.deal().getId(),
            fixture.document().id(),
            request,
            UUID.randomUUID().toString()));
        assertEquals("final", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
    }

    @Test
    void lifecycleRegistryAndManagedObjectEnumerationCoverEveryDeliveryTable() {
        List<String> tables = List.of(
            "document_delivery_request",
            "document_delivery_event",
            "document_delivery_artifact",
            "document_delivery_recipient",
            "document_delivery");
        List<Integer> orders = tables.stream()
            .map(TenantLifecycleRegistry::require)
            .peek(declaration -> assertTrue(declaration.direct()))
            .map(TenantLifecycleRegistry.TableLifecycle::deleteOrder)
            .toList();

        assertEquals(List.of(111, 112, 114, 116, 118), orders);
        assertTrue(orders.stream().allMatch(order -> order <
            TenantLifecycleRegistry.require("deal_document").deleteOrder()));

        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        acceptanceService.accept(
            token,
            new AcceptDocumentRequest("Signer"),
            "192.0.2.20",
            "lifecycle-agent");
        List<String> artifactKeys = jdbcTemplate.queryForList(
            "SELECT object_key FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? ORDER BY object_key",
            String.class,
            workspace.getId(),
            delivery.id());
        assertEquals(2, artifactKeys.size());
        assertTrue(lifecycleMapper.findLifecycleObjectKeysAfter(
            workspace.getId(), "", 100).containsAll(artifactKeys));

        dealService.delete(fixture.deal().getId());

        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ? "
                + "AND object_key IN (?, ?)",
            Integer.class,
            workspace.getId(),
            artifactKeys.get(0),
            artifactKeys.get(1)));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ?",
            Integer.class,
            workspace.getId(),
            delivery.id()));
    }

    private DocumentDeliveryDto sendWithProvider(
            DocumentFixture fixture, String provider) {
        return sendWithProvider(fixture, provider, signer("provider@example.test", 1));
    }

    private DocumentDeliveryDto sendWithProvider(
            DocumentFixture fixture,
            String provider,
            SendDeliveryRecipientRequest... recipients) {
        DocumentDeliveryDto delivery = send(fixture, recipients);
        String envelope = "test:" + workspace.getId() + ":" + delivery.id();
        jdbcTemplate.update(
            "UPDATE document_delivery SET provider = ?, provider_envelope_id = ? "
                + "WHERE workspace_id = ? AND id = ?",
            provider,
            envelope,
            workspace.getId(),
            delivery.id());
        jdbcTemplate.update(
            "UPDATE document_delivery_recipient SET provider_recipient_id = CAST(id AS CHAR) "
                + "WHERE workspace_id = ? AND delivery_id = ?",
            workspace.getId(),
            delivery.id());
        return deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()).stream()
            .filter(candidate -> candidate.id() == delivery.id())
            .findFirst()
            .orElseThrow();
    }

    private int countExternalOrSystemEvents(int deliveryId, String type) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND event_type = ?",
            Integer.class,
            workspace.getId(),
            deliveryId,
            type);
    }

    private byte[] certificateForCallbackOrder(
            DocumentFixture fixture,
            DocumentDeliveryDto delivery,
            int firstRecipientId,
            LocalDateTime firstOccurredAt,
            int secondRecipientId,
            LocalDateTime secondOccurredAt,
            int artifactRecipientId,
            byte[] signedPdf) {
        AtomicReference<byte[]> certificate = new AtomicReference<>();
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(status -> {
            completeProviderRecipient(
                delivery,
                firstRecipientId,
                firstOccurredAt,
                artifactRecipientId,
                signedPdf);
            completeProviderRecipient(
                delivery,
                secondRecipientId,
                secondOccurredAt,
                artifactRecipientId,
                signedPdf);
            LocalDateTime expectedCompletedAt = firstOccurredAt.isAfter(secondOccurredAt)
                ? firstOccurredAt : secondOccurredAt;
            assertEquals(expectedCompletedAt, jdbcTemplate.queryForObject(
                "SELECT completed_at FROM document_delivery WHERE workspace_id = ? AND id = ?",
                LocalDateTime.class,
                workspace.getId(),
                delivery.id()));
            int certificateId = jdbcTemplate.queryForObject(
                "SELECT id FROM document_delivery_artifact WHERE workspace_id = ? "
                    + "AND delivery_id = ? AND kind = 'certificate'",
                Integer.class,
                workspace.getId(),
                delivery.id());
            try (ManagedContent content = deliveryService.downloadArtifact(
                    fixture.deal().getId(),
                    fixture.document().id(),
                    delivery.id(),
                    certificateId)) {
                certificate.set(content.inputStream().readAllBytes());
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Certificate bytes could not be read", exception);
            }
            status.setRollbackOnly();
        });
        return certificate.get();
    }

    private void completeProviderRecipient(
            DocumentDeliveryDto delivery,
            int recipientId,
            LocalDateTime occurredAt,
            int artifactRecipientId,
            byte[] signedPdf) {
        Map<String, String> headers = recipientId == artifactRecipientId
            ? Map.of(
                "x-workspace", Integer.toString(workspace.getId()),
                "x-envelope", delivery.providerEnvelopeId(),
                "x-recipient", Integer.toString(recipientId),
                "x-event-id", "decision-" + recipientId,
                "x-event-type", "completed",
                "x-occurred-at", occurredAt.toString(),
                "x-artifact-content-type", "application/pdf")
            : Map.of(
                "x-workspace", Integer.toString(workspace.getId()),
                "x-envelope", delivery.providerEnvelopeId(),
                "x-recipient", Integer.toString(recipientId),
                "x-event-id", "decision-" + recipientId,
                "x-event-type", "completed",
                "x-occurred-at", occurredAt.toString());
        byte[] body = recipientId == artifactRecipientId ? signedPdf : new byte[0];
        assertTrue(webhookService.ingest("test_signature", headers, body));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSignatureProviderConfiguration {
        @Bean
        DocumentSignatureProvider testSignatureProvider() {
            return new DocumentSignatureProvider() {
                @Override
                public String key() {
                    return "test_signature";
                }

                @Override
                public SendOutcome send(SendCommand command) {
                    String envelope = command.providerEnvelopeId() == null
                        ? "test:" + command.workspaceId() + ":" + command.deliveryId()
                        : command.providerEnvelopeId();
                    ArrayList<SendRecipientOutcome> recipients = new ArrayList<>();
                    for (var recipient : command.recipients()) {
                        recipients.add(new SendRecipientOutcome(
                            recipient.recipientId(),
                            Integer.toString(recipient.recipientId()),
                            Optional.empty()));
                    }
                    return new SendOutcome(envelope, recipients);
                }

                @Override
                public void voidEnvelope(VoidCommand command) {
                }

                @Override
                public Optional<ProviderEvent> parseWebhook(
                        String provider, Map<String, String> headers, byte[] body) {
                    LocalDateTime occurredAt = "true".equals(
                        headers.get("x-missing-occurrence"))
                            ? null
                            : headers.containsKey("x-occurred-at")
                                ? LocalDateTime.parse(headers.get("x-occurred-at"))
                                : LocalDateTime.now();
                    return Optional.of(new ProviderEvent(
                        Integer.parseInt(headers.get("x-workspace")),
                        headers.get("x-envelope"),
                        headers.get("x-recipient"),
                        headers.get("x-event-id"),
                        headers.get("x-event-type"),
                        "verified test callback",
                        occurredAt,
                        Optional.ofNullable(headers.get("x-artifact-content-type"))
                            .map(contentType -> new ProviderSignedArtifact(contentType, body))));
                }

            };
        }
    }
}
