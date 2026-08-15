package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.signature.RecipientDeliveryLink;
import ooo.klae.connex.backend.signature.SendOutcome;
import ooo.klae.connex.backend.signature.SendRecipientOutcome;

class DocumentDeliveryServiceTest extends AbstractDocumentDeliveryServiceTest {

    @Test
    void sendRefusesDisabledGateNonFinalDocumentLiveEnvelopeAndMissingPermission() {
        DocumentFixture fixture = finalDocument();
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setRecipients(List.of(signer("one@example.test", 1)));

        signatureProperties.setEnabled(false);
        assertThrows(ServiceUnavailableException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, requestKey()));
        signatureProperties.setEnabled(true);

        DealDocumentDto draft = documentService.generate(
            fixture.deal().getId(), fixture.document().templateId());
        assertThrows(BadRequestException.class, () -> deliveryService.send(
            fixture.deal().getId(), draft.id(), request, requestKey()));

        send(fixture, signer("one@example.test", 1));
        assertThrows(BadRequestException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, requestKey()));

        DocumentFixture another = finalDocument();
        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class, () -> deliveryService.send(
            another.deal().getId(), another.document().id(), request, requestKey()));
    }

    @Test
    void successfulSendFreezesRecipientsMovesDocumentAndWritesOneEvent() {
        DocumentFixture fixture = finalDocument();

        DocumentDeliveryDto delivery = send(
            fixture,
            signer("DUPLICATE.CASE@example.test", 1),
            viewer("viewer@example.test", 2));

        assertEquals("sent", delivery.status());
        assertEquals(2, delivery.recipients().size());
        assertEquals("duplicate.case@example.test", delivery.recipients().getFirst().email());
        assertEquals("sent", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event "
                + "WHERE workspace_id = ? AND delivery_id = ? AND event_type = 'sent'",
            Integer.class,
            workspace.getId(),
            delivery.id()));
        List<String> hashes = jdbcTemplate.queryForList(
            "SELECT token_hash FROM document_delivery_recipient "
                + "WHERE workspace_id = ? AND delivery_id = ? ORDER BY id",
            String.class,
            workspace.getId(),
            delivery.id());
        assertEquals(2, hashes.size());
        assertNotNull(hashes.get(0));
        assertNotEquals(hashes.get(0), hashes.get(1));
    }

    @Test
    void sendReplayReturnsTheOriginalEnvelopeWithoutRepeatingEffects() {
        DocumentFixture fixture = finalDocument();
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setRecipients(List.of(signer("replay@example.test", 1)));
        String key = requestKey();
        int synchronizationsBefore = TransactionSynchronizationManager
            .getSynchronizations().size();

        DocumentDeliveryDto first = deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, key);
        String firstTokenHash = tokenHash(first.recipients().getFirst().id());
        DocumentDeliveryDto replayed = deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, key);

        assertEquals(first.id(), replayed.id());
        assertEquals(firstTokenHash, tokenHash(first.recipients().getFirst().id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery WHERE workspace_id = ? AND document_id = ?",
            Integer.class,
            workspace.getId(),
            fixture.document().id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND event_type = 'sent'",
            Integer.class,
            workspace.getId(),
            first.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_request WHERE workspace_id = ? "
                + "AND idempotency_key = ?",
            Integer.class,
            workspace.getId(),
            key));
        assertEquals(synchronizationsBefore + 1, TransactionSynchronizationManager
            .getSynchronizations().size());
        assertThrows(BadRequestException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, requestKey()));
    }

    @Test
    void resendReplacesTokenAndVoidInvalidatesEveryToken() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(
            fixture,
            signer("one@example.test", 1),
            signer("two@example.test", 2));
        int recipientId = delivery.recipients().getFirst().id();
        String before = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            recipientId);

        deliveryService.resend(
            fixture.deal().getId(),
            fixture.document().id(),
            delivery.id(),
            recipientId,
            requestKey());
        String after = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            recipientId);
        assertNotEquals(before, after);

        deliveryService.voidDelivery(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), "Incorrect signer");
        assertEquals("final", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_recipient "
                + "WHERE workspace_id = ? AND delivery_id = ? AND token_hash IS NOT NULL",
            Integer.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void resendReplayReturnsTheOriginalTokenWithoutRepeatingEffects() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("resend-replay@example.test", 1));
        int recipientId = delivery.recipients().getFirst().id();
        String before = tokenHash(recipientId);
        String key = requestKey();
        int synchronizationsBefore = TransactionSynchronizationManager
            .getSynchronizations().size();

        DocumentDeliveryDto first = deliveryService.resend(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), recipientId, key);
        String refreshed = tokenHash(recipientId);
        DocumentDeliveryDto replayed = deliveryService.resend(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), recipientId, key);

        assertEquals(first.id(), replayed.id());
        assertNotEquals(before, refreshed);
        assertEquals(refreshed, tokenHash(recipientId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND event_type = 'resent'",
            Integer.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(synchronizationsBefore + 1, TransactionSynchronizationManager
            .getSynchronizations().size());
    }

    @Test
    void supersedingSentDocumentVoidsItsLiveEnvelope() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("one@example.test", 1));

        DealDocumentDto superseded = documentService.updateStatus(
            fixture.deal().getId(), fixture.document().id(), "superseded");

        assertEquals("superseded", superseded.status());
        assertEquals("voided", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void supersedingSentDocumentRequiresDocumentSendPermission() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("one@example.test", 1));
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> documentService.updateStatus(
            fixture.deal().getId(), fixture.document().id(), "superseded"));

        assertEquals("sent", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void deliveryReadsRefuseDisabledGateMissingPermissionAndOtherTenant() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("one@example.test", 1));
        int artifactId = insertArtifact(delivery.id());

        signatureProperties.setEnabled(false);
        assertThrows(ServiceUnavailableException.class, () -> deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()));
        assertThrows(ServiceUnavailableException.class, () -> deliveryService.downloadArtifact(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), artifactId));
        signatureProperties.setEnabled(true);

        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class, () -> deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()));
        assertThrows(ForbiddenException.class, () -> deliveryService.downloadArtifact(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), artifactId));

        Workspace other = new Workspace();
        other.setOrgId(workspace.getOrgId());
        other.setName("Other workspace " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, other.getId());

        assertThrows(ResourceNotFoundException.class, () -> deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()));
        assertThrows(ResourceNotFoundException.class, () -> deliveryService.downloadArtifact(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), artifactId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM document_delivery_artifact WHERE workspace_id = ?",
            Integer.class,
            other.getId()));
    }

    @Test
    void providerOutcomeRejectsDuplicateProviderRecipientIdentifiers() {
        DocumentDeliveryRecipient first = new DocumentDeliveryRecipient();
        first.setId(101);
        DocumentDeliveryRecipient second = new DocumentDeliveryRecipient();
        second.setId(102);
        SendOutcome outcome = new SendOutcome("envelope", List.of(
            new SendRecipientOutcome(
                first.getId(),
                "duplicated",
                java.util.Optional.of(new RecipientDeliveryLink("a".repeat(64), "/first"))),
            new SendRecipientOutcome(
                second.getId(),
                "duplicated",
                java.util.Optional.of(new RecipientDeliveryLink("b".repeat(64), "/second")))));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> deliveryService.validateOutcome(List.of(first, second), outcome));

        assertTrue(exception.getMessage().contains("provider recipient id"));
    }

    @Test
    void sendRefusesUncertifiableLegacyApprovalBeforeCreatingEffects() {
        DocumentFixture fixture = finalDocument();
        jdbcTemplate.update(
            "INSERT INTO document_approval (workspace_id, deal_id, document_id, status, "
                + "mode, separation_of_duties, policy_binding, requested_by) "
                + "VALUES (?, ?, ?, 'approved', 'sequential', 'strict', "
                + "'unknown_legacy', ?)",
            workspace.getId(),
            fixture.deal().getId(),
            fixture.document().id(),
            currentUser.getId());
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setRecipients(List.of(signer("legacy-policy@example.test", 1)));

        assertThrows(BadRequestException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, requestKey()));

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery WHERE workspace_id = ? AND document_id = ?",
            Integer.class,
            workspace.getId(),
            fixture.document().id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_request WHERE workspace_id = ? "
                + "AND document_id = ?",
            Integer.class,
            workspace.getId(),
            fixture.document().id()));
    }

    private String tokenHash(int recipientId) {
        return jdbcTemplate.queryForObject(
            "SELECT token_hash FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            recipientId);
    }

    private int insertArtifact(int deliveryId) {
        jdbcTemplate.update(
            "INSERT INTO document_delivery_artifact (workspace_id, delivery_id, kind, object_key, "
                + "content_type, byte_length, sha256) "
                + "VALUES (?, ?, 'certificate', ?, 'application/json', 1, ?)",
            workspace.getId(),
            deliveryId,
            "document-artifacts/" + workspace.getId() + "/" + deliveryId + "/test.json",
            "0".repeat(64));
        return jdbcTemplate.queryForObject(
            "SELECT id FROM document_delivery_artifact WHERE workspace_id = ? AND delivery_id = ? "
                + "AND kind = 'certificate'",
            Integer.class,
            workspace.getId(),
            deliveryId);
    }

    private static String requestKey() {
        return UUID.randomUUID().toString();
    }
}
