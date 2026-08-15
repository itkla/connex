package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DeclineDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptanceDecisionDto;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class DocumentAcceptanceServiceTest extends AbstractDocumentDeliveryServiceTest {

    @Test
    void previewReturnsFrozenContentAndRecordsNothing() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptancePreviewDto first =
            acceptanceService.preview(token, "192.0.2.10");
        DocumentAcceptancePreviewDto second =
            acceptanceService.preview(token, "192.0.2.10");

        assertEquals(0, countEvents(delivery.id(), "viewed"));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_recipient WHERE workspace_id = ? "
                + "AND delivery_id = ? AND first_viewed_at IS NOT NULL",
            Integer.class,
            workspace.getId(),
            delivery.id()));

        acceptanceService.markViewed(token, "192.0.2.10");
        acceptanceService.markViewed(token, "192.0.2.10");

        assertEquals(fixture.deal().getName(), first.dealName());
        assertEquals(fixture.document().content(), first.content());
        assertEquals("s***@example.test", first.recipientEmail());
        assertTrue(first.actionable());
        assertEquals(first.content(), second.content());
        assertEquals(1, countEvents(delivery.id(), "viewed"));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_recipient WHERE workspace_id = ? "
                + "AND delivery_id = ? AND first_viewed_at IS NOT NULL",
            Integer.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void onlySignerAcceptanceCompletesAndRecordsByteExactArtifactsAndOneActivity()
            throws Exception {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptanceDecisionDto result = acceptanceService.accept(
            token,
            new AcceptDocumentRequest("External Signer"),
            "192.0.2.11",
            "Acceptance test agent");

        assertTrue(result.completed());
        assertEquals("completed", result.deliveryStatus());
        assertEquals("signed", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_artifact "
                + "WHERE workspace_id = ? AND delivery_id = ?",
            Integer.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(1, activityCount(fixture, "completed"));
        assertEquals(1, notificationCount(delivery.id(), "document.delivery_completed"));
        assertEquals(1, countEvents(delivery.id(), "completed"));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? "
                + "AND action = 'document_delivery.recipient_accept' "
                + "AND ip_address IS NULL AND user_agent IS NULL AND session_id IS NULL",
            Integer.class,
            workspace.getId()));

        DocumentDeliveryDto completedDelivery = deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()).getFirst();
        assertEquals(2, completedDelivery.artifacts().size());
        byte[] frozenBytes = jdbcTemplate.queryForObject(
            "SELECT content FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            fixture.document().id()).getBytes(StandardCharsets.UTF_8);
        assertEquals((long) frozenBytes.length, jdbcTemplate.queryForObject(
            "SELECT byte_length FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            Long.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(sha256Bytes(frozenBytes), jdbcTemplate.queryForObject(
            "SELECT sha256 FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND kind = 'signed_document'",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals("External Signer", jdbcTemplate.queryForObject(
            "SELECT typed_name FROM document_delivery_recipient WHERE workspace_id = ? "
                + "AND delivery_id = ? AND role = 'signer'",
            String.class,
            workspace.getId(),
            delivery.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_artifact WHERE workspace_id = ? "
                + "AND delivery_id = ? AND (byte_length <= 0 OR sha256 NOT REGEXP '^[a-f0-9]{64}$')",
            Integer.class,
            workspace.getId(),
            delivery.id()));
    }

    @Test
    void everySignerButNoViewerContributesToCompletion() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(
            fixture,
            signer("one@example.test", 1),
            viewer("viewer@example.test", 2),
            signer("two@example.test", 3));
        String first = installToken(delivery.recipients().get(0).id());
        installToken(delivery.recipients().get(1).id());
        String second = installToken(delivery.recipients().get(2).id());

        DocumentAcceptanceDecisionDto pending = acceptanceService.accept(
            first, new AcceptDocumentRequest("Signer One"), "192.0.2.12", "agent-one");
        assertFalse(pending.completed());
        assertEquals("sent", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());

        DocumentAcceptanceDecisionDto complete = acceptanceService.accept(
            second, new AcceptDocumentRequest("Signer Two"), "192.0.2.13", "agent-two");
        assertTrue(complete.completed());
        assertEquals("completed", jdbcTemplate.queryForObject(
            "SELECT status FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.recipients().get(1).id()));
    }

    @Test
    void declineIsTerminalAndAnotherSignerTokenCannotAccept() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(
            fixture,
            signer("one@example.test", 1),
            signer("two@example.test", 2));
        String first = installToken(delivery.recipients().getFirst().id());
        String second = installToken(delivery.recipients().getLast().id());

        DocumentAcceptanceDecisionDto declined = acceptanceService.decline(
            first,
            new DeclineDocumentRequest("Commercial terms were not accepted"),
            "192.0.2.14",
            "decline-agent");

        assertEquals("declined", declined.deliveryStatus());
        assertEquals("final", documentService.getOne(
            fixture.deal().getId(), fixture.document().id()).status());
        assertEquals(1, activityCount(fixture, "declined"));
        assertThrows(ResourceNotFoundException.class, () -> acceptanceService.accept(
            second, new AcceptDocumentRequest("Too Late"), "192.0.2.15", "late-agent"));
    }

    @Test
    void repeatedDecisionIsIdempotentAcrossEvidenceActivityAndNotification() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        DocumentAcceptanceDecisionDto first = acceptanceService.accept(
            token, new AcceptDocumentRequest("Signer"), "192.0.2.16", "agent");
        DocumentAcceptanceDecisionDto second = acceptanceService.accept(
            token, new AcceptDocumentRequest("Changed Name"), "198.51.100.2", "changed-agent");

        assertEquals(first, second);
        assertEquals(1, countEvents(delivery.id(), "completed"));
        assertEquals(1, activityCount(fixture, "completed"));
        assertEquals(1, notificationCount(delivery.id(), "document.delivery_completed"));
        assertEquals("Signer", jdbcTemplate.queryForObject(
            "SELECT typed_name FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.recipients().getFirst().id()));
    }

    @Test
    void voidedExpiredWrongAndUnknownTokensShareTheUnavailableResponse() {
        DocumentFixture fixture = finalDocument();
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());

        char replacement = token.endsWith("a") ? 'b' : 'a';
        String wrong = token.substring(0, token.length() - 1) + replacement;
        String unknownWorkspace = "w2147483646-" + "a".repeat(64);
        String wrongMessage = unavailableMessage(wrong);
        assertEquals(wrongMessage, unavailableMessage(unknownWorkspace));

        Workspace other = new Workspace();
        other.setOrgId(workspace.getOrgId());
        other.setName("Other acceptance workspace " + unique());
        other.setSlug("acceptance-other-" + unique());
        workspaceMapper.insert(other);
        String otherWorkspaceToken = token.replaceFirst("w\\d+-", "w" + other.getId() + "-");
        assertEquals(wrongMessage, unavailableMessage(otherWorkspaceToken));

        jdbcTemplate.update(
            "UPDATE document_delivery_recipient SET token_expires_at = ? "
                + "WHERE workspace_id = ? AND id = ?",
            LocalDateTime.now().minusMinutes(1),
            workspace.getId(),
            delivery.recipients().getFirst().id());
        assertEquals(wrongMessage, unavailableMessage(token));

        jdbcTemplate.update(
            "UPDATE document_delivery_recipient SET token_expires_at = ? "
                + "WHERE workspace_id = ? AND id = ?",
            LocalDateTime.now().plusDays(1),
            workspace.getId(),
            delivery.recipients().getFirst().id());
        deliveryService.voidDelivery(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), "Withdrawn");
        assertEquals(wrongMessage, unavailableMessage(token));
        assertNull(jdbcTemplate.queryForObject(
            "SELECT token_hash FROM document_delivery_recipient WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            delivery.recipients().getFirst().id()));
    }

    @Test
    void publicEntryPointsAreNotTransactional() throws Exception {
        List<Method> entries = List.of(
            DocumentAcceptanceService.class.getMethod("preview", String.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "accept", String.class, AcceptDocumentRequest.class, String.class, String.class),
            DocumentAcceptanceService.class.getMethod(
                "decline", String.class, DeclineDocumentRequest.class, String.class, String.class));

        for (Method method : entries) {
            assertFalse(method.isAnnotationPresent(Transactional.class), method.getName());
        }
        assertFalse(DocumentAcceptanceService.class.isAnnotationPresent(Transactional.class));
    }

    @Test
    void unknownWorkspacePrefixesConsumeTheSourceRateLimit() {
        int previousLimit = signatureProperties.getMaxRequestsPerSource();
        signatureProperties.setMaxRequestsPerSource(1);
        String source = "unknown-workspace-source-" + unique();
        try {
            assertThrows(ResourceNotFoundException.class, () -> acceptanceService.preview(
                "w2147483646-" + "b".repeat(64), source));
            assertThrows(TooManyRequestsException.class, () -> acceptanceService.preview(
                "w2147483645-" + "c".repeat(64), source));
        } finally {
            signatureProperties.setMaxRequestsPerSource(previousLimit);
        }
    }

    private String unavailableMessage(String token) {
        ResourceNotFoundException exception = assertThrows(
            ResourceNotFoundException.class,
            () -> acceptanceService.preview(token, "203.0.113.5"));
        return exception.getMessage();
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

    private static String sha256Bytes(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
