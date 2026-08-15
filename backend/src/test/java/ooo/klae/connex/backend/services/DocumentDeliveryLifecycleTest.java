package ooo.klae.connex.backend.services;

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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRecipientRequest;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.signature.DocumentSignatureProvider;
import ooo.klae.connex.backend.signature.ProviderEvent;
import ooo.klae.connex.backend.signature.ProviderSignedArtifact;
import ooo.klae.connex.backend.signature.SendCommand;
import ooo.klae.connex.backend.signature.SendOutcome;
import ooo.klae.connex.backend.signature.SendRecipientOutcome;
import ooo.klae.connex.backend.signature.VoidCommand;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

@Import(DocumentDeliveryLifecycleTest.TestSignatureProviderConfiguration.class)
class DocumentDeliveryLifecycleTest extends AbstractDocumentDeliveryServiceTest {
    @Autowired DocumentDeliveryScheduler scheduler;
    @Autowired DocumentSignatureWebhookService webhookService;
    @Autowired DealService dealService;
    @Autowired TenantLifecycleMapper lifecycleMapper;

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
    void providerArtifactStagedBeforeTheFinalSignerIsUsedAtCompletion() {
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
    void networkedOutboundProviderExecutionFailsClosed() {
        DocumentFixture fixture = finalDocument();
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setProvider("test_signature");
        request.setRecipients(List.of(signer("provider@example.test", 1)));

        assertThrows(ServiceUnavailableException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request));
        assertEquals("final", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
    }

    @Test
    void lifecycleRegistryAndManagedObjectEnumerationCoverEveryDeliveryTable() {
        List<String> tables = List.of(
            "document_delivery_event",
            "document_delivery_artifact",
            "document_delivery_recipient",
            "document_delivery");
        List<Integer> orders = tables.stream()
            .map(TenantLifecycleRegistry::require)
            .peek(declaration -> assertTrue(declaration.direct()))
            .map(TenantLifecycleRegistry.TableLifecycle::deleteOrder)
            .toList();

        assertEquals(List.of(112, 114, 116, 118), orders);
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
                    return Optional.of(new ProviderEvent(
                        Integer.parseInt(headers.get("x-workspace")),
                        headers.get("x-envelope"),
                        headers.get("x-recipient"),
                        headers.get("x-event-id"),
                        headers.get("x-event-type"),
                        "verified test callback",
                        LocalDateTime.now(),
                        Optional.ofNullable(headers.get("x-artifact-content-type"))
                            .map(contentType -> new ProviderSignedArtifact(contentType, body))));
                }

            };
        }
    }
}
