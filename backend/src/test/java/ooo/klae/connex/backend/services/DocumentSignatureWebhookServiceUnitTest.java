package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;

class DocumentSignatureWebhookServiceUnitTest {
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
}
