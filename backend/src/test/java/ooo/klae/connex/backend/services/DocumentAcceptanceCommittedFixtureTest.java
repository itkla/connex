package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryArtifact;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DeclineDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptanceDecisionDto;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;

class DocumentAcceptanceCommittedFixtureTest
        extends AbstractCommittedDocumentDeliveryServiceTest {
    private static final String CERTIFICATE_FIXTURE =
        "fixtures/document-delivery-certificate-v1.json.template";

    @Autowired private DocumentDeliveryMapper deliveryMapper;

    @Test
    void viewerCanReadAndRecordAViewWithoutReceivingDecisionControls() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, viewer("viewer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptancePreviewDto preview =
            acceptanceService.preview(token, "192.0.2.17");
        DocumentAcceptancePreviewDto viewed =
            acceptanceService.markViewed(token, "192.0.2.17");

        assertFalse(preview.actionable());
        assertFalse(viewed.actionable());
        assertThrows(ResourceNotFoundException.class, () -> acceptanceService.accept(
            token,
            new AcceptDocumentRequest("Viewer"),
            "192.0.2.17",
            "viewer-agent"));
        assertThrows(ResourceNotFoundException.class, () -> acceptanceService.decline(
            token,
            new DeclineDocumentRequest("Viewer cannot decide"),
            "192.0.2.17",
            "viewer-agent"));
        assertEquals(1, countEvents(delivery.id(), "viewed"));
    }

    @Test
    void repeatedDeclineIsIdempotentAcrossCommittedEvidenceActivityAndNotification() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptanceDecisionDto first = acceptanceService.decline(
            token,
            new DeclineDocumentRequest("Commercial terms were not accepted"),
            "192.0.2.18",
            "decline-agent");
        DocumentAcceptanceDecisionDto second = acceptanceService.decline(
            token,
            new DeclineDocumentRequest("Changed reason"),
            "198.51.100.18",
            "changed-agent");

        assertEquals(first, second);
        assertEquals(1, countEvents(delivery.id(), "declined"));
        assertEquals(1, activityCount(fixture, "declined"));
        assertEquals(1, notificationCount(delivery.id(), "document.delivery_declined"));
        assertEquals("Commercial terms were not accepted", jdbcTemplate.queryForObject(
            "SELECT decline_reason FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.recipients().getFirst().id()));
    }

    @Test
    void repeatedViewIsIdempotentAcrossCommittedTransactions() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptancePreviewDto first = acceptanceService.markViewed(
            token,
            "192.0.2.19");
        DocumentAcceptancePreviewDto second = acceptanceService.markViewed(
            token,
            "198.51.100.19");

        assertEquals(first, second);
        assertEquals(1, countEvents(delivery.id(), "viewed"));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_recipient WHERE workspace_id = ? "
                + "AND id = ? AND first_viewed_at IS NOT NULL",
            Integer.class,
            workspace.getId(),
            delivery.recipients().getFirst().id()));
    }

    @Test
    void repeatedAcceptIsIdempotentAcrossCommittedEvidenceActivityAndNotification() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptanceDecisionDto first = acceptanceService.accept(
            token,
            new AcceptDocumentRequest("Committed Signer"),
            "192.0.2.20",
            "accept-agent");
        DocumentAcceptanceDecisionDto second = acceptanceService.accept(
            token,
            new AcceptDocumentRequest("Changed Signer"),
            "198.51.100.20",
            "changed-agent");

        assertEquals(first, second);
        assertEquals(1, countEvents(delivery.id(), "completed"));
        assertEquals(1, activityCount(fixture, "completed"));
        assertEquals(1, notificationCount(delivery.id(), "document.delivery_completed"));
        assertEquals("Committed Signer", jdbcTemplate.queryForObject(
            "SELECT typed_name FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.recipients().getFirst().id()));
    }

    @Test
    void enrichedPreviewPreservesStoredSignedDocumentAndCertificateBytes() throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        String frozenContent = jdbcTemplate.queryForObject(
            "SELECT content FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            fixture.document().id());
        byte[] frozenBeforePreview = Objects.requireNonNull(frozenContent)
            .getBytes(StandardCharsets.UTF_8);
        String expectedSignedDocumentSha256 = sha256(frozenContent);

        acceptanceService.preview(token, "192.0.2.30");
        acceptanceService.markViewed(token, "192.0.2.30");
        acceptanceService.accept(
            token,
            new AcceptDocumentRequest("External Signer"),
            "192.0.2.30",
            "artifact-preservation-agent");

        DocumentDelivery persistedDelivery = deliveryMapper.getById(
            workspace.getId(), delivery.id());
        DocumentDeliveryRecipient recipient = deliveryMapper.getRecipients(
            workspace.getId(), delivery.id()).getFirst();
        DocumentDeliveryArtifact signedDocument = deliveryMapper.getArtifactByKind(
            workspace.getId(), delivery.id(), "signed_document");
        DocumentDeliveryArtifact certificate = deliveryMapper.getArtifactByKind(
            workspace.getId(), delivery.id(), "certificate");

        assertArrayEquals(
            frozenBeforePreview,
            artifactBytes(fixture, delivery.id(), signedDocument.getId()));
        assertEquals(expectedSignedDocumentSha256, signedDocument.getSha256());
        assertArrayEquals(
            expectedCertificateBytes(
                fixture,
                persistedDelivery,
                recipient,
                token,
                expectedSignedDocumentSha256),
            artifactBytes(fixture, delivery.id(), certificate.getId()));
    }

    private byte[] expectedCertificateBytes(
            DocumentFixture fixture,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            String token,
            String signedDocumentSha256) throws IOException {
        InputStream input = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(CERTIFICATE_FIXTURE),
            "Certificate fixture is missing");
        String expected;
        try (input) {
            expected = new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        }
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("{{workspaceId}}", Integer.toString(workspace.getId()));
        replacements.put("{{dealId}}", Integer.toString(fixture.deal().getId()));
        replacements.put("{{documentId}}", Integer.toString(fixture.document().id()));
        replacements.put("{{documentVersion}}", Integer.toString(fixture.document().version()));
        replacements.put("{{providerEnvelopeId}}", "in_app:" + workspace.getId()
            + ":" + delivery.getId());
        replacements.put("{{deliveryId}}", Integer.toString(delivery.getId()));
        replacements.put("{{sentAt}}", delivery.getSentAt().toString());
        replacements.put("{{completedAt}}", delivery.getCompletedAt().toString());
        replacements.put("{{signedDocumentSha256}}", signedDocumentSha256);
        replacements.put("{{recipientId}}", Integer.toString(recipient.getId()));
        replacements.put("{{firstViewedAt}}", recipient.getFirstViewedAt().toString());
        replacements.put("{{decidedAt}}", recipient.getDecidedAt().toString());
        String evidenceScope = workspace.getId() + ":" + delivery.getId()
            + ":" + recipient.getId();
        String tokenHash = sha256(token);
        replacements.put("{{evidenceIpHash}}", expectedEvidenceHash(
            tokenHash, "ip:" + evidenceScope, "192.0.2.30"));
        replacements.put("{{evidenceAgentHash}}", expectedEvidenceHash(
            tokenHash, "agent:" + evidenceScope, "artifact-preservation-agent"));
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            expected = expected.replace(replacement.getKey(), replacement.getValue());
        }
        return expected.getBytes(StandardCharsets.UTF_8);
    }

    private static String expectedEvidenceHash(String key, String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return java.util.HexFormat.of().formatHex(
                mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private byte[] artifactBytes(DocumentFixture fixture, int deliveryId, int artifactId)
            throws IOException {
        try (ManagedContent content = deliveryService.downloadArtifact(
                fixture.deal().getId(),
                fixture.document().id(),
                deliveryId,
                artifactId)) {
            return content.inputStream().readAllBytes();
        }
    }

    private int countEvents(int deliveryId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? "
                + "AND delivery_id = ? AND event_type = ?",
            Integer.class,
            workspace.getId(),
            deliveryId,
            eventType);
    }

    private int activityCount(DocumentFixture fixture, String transition) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM activity WHERE workspace_id = ? AND deal_id = ? AND subject = ?",
            Integer.class,
            workspace.getId(),
            fixture.deal().getId(),
            "Document delivery " + transition);
    }

    private int notificationCount(int deliveryId, String type) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND type = ? "
                + "AND dedupe_key = ?",
            Integer.class,
            workspace.getId(),
            type,
            type + ":" + deliveryId + ":" + currentUser.getId());
    }
}
