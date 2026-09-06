package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CampaignDeliveryFailureReasonTest {

    @Test
    void mapsInternalProviderDetailsToBoundedReasonCodes() {
        assertEquals(
                CampaignDeliveryFailureReason.DEADLINE_AMBIGUOUS,
                CampaignDeliveryFailureReason.classify("deadline exceeded after DATA", true));
        assertEquals(
                CampaignDeliveryFailureReason.PROVIDER_TIMEOUT,
                CampaignDeliveryFailureReason.classify("provider timed out", false));
        assertEquals(
                CampaignDeliveryFailureReason.PROVIDER_REJECTED,
                CampaignDeliveryFailureReason.classify("provider rejected recipient", false));
        assertEquals(
                CampaignDeliveryFailureReason.PROVIDER_REJECTED,
                CampaignDeliveryFailureReason.classify("provider returned status 429", false));
        assertEquals(
                CampaignDeliveryFailureReason.RELAY_ERROR,
                CampaignDeliveryFailureReason.classify(
                        "relay diagnostics for private@example.com", false));
    }
}
