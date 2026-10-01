package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DocumentDeliveryLifecycleServiceUnitTest {
    private final ObjectMapper objectMapper = JsonMapper.builder()
        .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
    private final DocumentDeliveryLifecycleService lifecycleService = new DocumentDeliveryLifecycleService(
        mock(DocumentDeliveryMapper.class),
        mock(DealDocumentMapper.class),
        mock(DocumentApprovalMapper.class),
        mock(ManagedObjectService.class),
        mock(ActivityService.class),
        mock(AutomationExecutor.class),
        mock(SystemActor.class),
        mock(NotificationDelivery.class),
        mock(NotificationPreferenceService.class),
        mock(WorkspaceService.class),
        mock(AuditService.class),
        objectMapper);

    @Test
    void certificateRetainsAppliedPolicyIdAfterTheLiveForeignKeyIsCleared() {
        int workspaceId = 7;
        Deal deal = new Deal();
        deal.setId(11);
        DealDocument document = new DealDocument();
        document.setId(13);
        document.setVersion(1);
        document.setType("proposal");
        DocumentDelivery persisted = new DocumentDelivery();
        persisted.setId(17);
        persisted.setProvider("in_app");
        persisted.setProviderEnvelopeId("envelope-17");
        LocalDateTime completedAt = LocalDateTime.of(2026, 9, 2, 12, 0);
        persisted.setSentAt(completedAt.minusDays(1));
        DocumentDeliveryRecipient recipient = new DocumentDeliveryRecipient();
        recipient.setId(19);
        recipient.setRecipientOrder(1);
        recipient.setEmail("policy-snapshot@example.test");
        recipient.setRole("signer");
        recipient.setStatus("pending");
        List<DocumentDeliveryRecipient> recipients = List.of(recipient);
        DocumentApproval approval =
            new DocumentApproval();
        approval.setId(51);
        approval.setStatus("approved");
        approval.setPolicyId(null);
        approval.setPolicyIdSnapshot(37);
        approval.setPolicyBinding("applied");

        byte[] certificateBytes = lifecycleService.certificateBytes(
            workspaceId,
            deal,
            document,
            approval,
            persisted,
            recipients,
            completedAt,
            "a".repeat(64));

        JsonNode certificate = objectMapper.readTree(certificateBytes);
        assertEquals(51, certificate.path("approvalRequestId").asInt());
        assertEquals("approved", certificate.path("approvalOutcome").asString());
        assertEquals(37, certificate.path("approvalPolicyId").asInt());

        approval.setPolicyIdSnapshot(null);
        approval.setPolicyBinding("unknown_legacy");
        assertThrows(IllegalStateException.class, () -> lifecycleService.certificateBytes(
            workspaceId,
            deal,
            document,
            approval,
            persisted,
            recipients,
            completedAt,
            "a".repeat(64)));
    }
}
