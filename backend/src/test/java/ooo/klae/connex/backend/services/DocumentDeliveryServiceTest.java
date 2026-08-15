package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;

class DocumentDeliveryServiceTest extends AbstractDocumentDeliveryServiceTest {

    @Test
    void sendRefusesDisabledGateNonFinalDocumentLiveEnvelopeAndMissingPermission() {
        DocumentFixture fixture = finalDocument();
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setRecipients(List.of(signer("one@example.test", 1)));

        signatureProperties.setEnabled(false);
        assertThrows(ServiceUnavailableException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request));
        signatureProperties.setEnabled(true);

        DealDocumentDto draft = documentService.generate(
            fixture.deal().getId(), fixture.document().templateId());
        assertThrows(BadRequestException.class, () -> deliveryService.send(
            fixture.deal().getId(), draft.id(), request));

        send(fixture, signer("one@example.test", 1));
        assertThrows(BadRequestException.class, () -> deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request));

        DocumentFixture another = finalDocument();
        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class, () -> deliveryService.send(
            another.deal().getId(), another.document().id(), request));
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
            fixture.deal().getId(), fixture.document().id(), delivery.id(), recipientId);
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
    void artifactDownloadIsWorkspaceScoped() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("one@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        acceptanceService.accept(
            token,
            new ooo.klae.connex.backend.dto.AcceptDocumentRequest("External Signer"),
            "192.0.2.10",
            "test-agent");
        int artifactId = jdbcTemplate.queryForObject(
            "SELECT id FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? ORDER BY id LIMIT 1",
            Integer.class,
            workspace.getId(),
            delivery.id());

        Workspace other = new Workspace();
        other.setOrgId(workspace.getOrgId());
        other.setName("Other workspace " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, other.getId());

        assertThrows(ResourceNotFoundException.class, () -> deliveryService.downloadArtifact(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), artifactId));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM document_delivery_artifact WHERE workspace_id = ?",
            Integer.class,
            other.getId()));
    }
}
