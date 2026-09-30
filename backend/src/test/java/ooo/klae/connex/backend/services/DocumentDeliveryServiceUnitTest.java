package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.signature.DocumentSignatureEmailService;
import ooo.klae.connex.backend.signature.DocumentSignatureProviderRouter;
import ooo.klae.connex.backend.signature.RecipientDeliveryLink;
import ooo.klae.connex.backend.signature.SendOutcome;
import ooo.klae.connex.backend.signature.SendRecipientOutcome;
import ooo.klae.connex.backend.signature.SignatureProperties;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DocumentDeliveryServiceUnitTest {
    private final ObjectMapper objectMapper = JsonMapper.builder()
        .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
    private final DocumentDeliveryService deliveryService = new DocumentDeliveryService(
        mock(DocumentDeliveryMapper.class),
        mock(DealDocumentMapper.class),
        mock(DealMapper.class),
        mock(DocumentApprovalMapper.class),
        mock(PersonMapper.class),
        mock(WorkspaceService.class),
        mock(CapabilityRegistry.class),
        mock(SignatureProperties.class),
        mock(DocumentSignatureProviderRouter.class),
        mock(DocumentSignatureEmailService.class),
        mock(ManagedObjectService.class),
        mock(AuditService.class),
        objectMapper);

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
}
