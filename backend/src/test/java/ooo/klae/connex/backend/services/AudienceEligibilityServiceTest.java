package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.CampaignMapper;

@ExtendWith(MockitoExtension.class)
class AudienceEligibilityServiceTest {

    private static final int WORKSPACE = 7;
    private static final String CHANNEL = "email";
    private static final String PURPOSE = "marketing";

    @Mock private CampaignMapper campaignMapper;
    @InjectMocks private AudienceEligibilityService service;

    @Test
    void classifyAppliesRestrictedThenSuppressedThenConsentMissingPrecedence() {
        List<Integer> candidates = List.of(1, 2, 3, 4);
        when(campaignMapper.restrictedPersonIds(eq(WORKSPACE), anyList())).thenReturn(List.of(1));
        when(campaignMapper.suppressedPersonIds(eq(WORKSPACE), anyList(), eq(CHANNEL))).thenReturn(List.of(2));
        when(campaignMapper.grantedConsentPersonIds(eq(WORKSPACE), anyList(), eq(CHANNEL), eq(PURPOSE)))
                .thenReturn(List.of(4));

        AudienceEligibilityService.AudienceClassification result =
                service.classify(WORKSPACE, candidates, CHANNEL, PURPOSE);

        assertEquals(Set.of(1), result.restricted());
        assertEquals(Set.of(2), result.suppressed());
        assertEquals(Set.of(3), result.consentMissing());
        assertEquals(List.of(4), result.includedIds());
        assertEquals("restricted", result.reasonFor(1));
        assertEquals("suppressed", result.reasonFor(2));
        assertEquals("consent_missing", result.reasonFor(3));
        assertNull(result.reasonFor(4));
    }

    @Test
    void restrictionTakesPrecedenceSoSuppressionQueryNeverSeesRestrictedIds() {
        List<Integer> candidates = List.of(10, 11);
        when(campaignMapper.restrictedPersonIds(eq(WORKSPACE), anyList())).thenReturn(List.of(10, 11));

        AudienceEligibilityService.AudienceClassification result =
                service.classify(WORKSPACE, candidates, CHANNEL, PURPOSE);

        assertEquals(Set.of(10, 11), result.restricted());
        assertTrue(result.includedIds().isEmpty());
        assertTrue(result.suppressed().isEmpty());
        assertTrue(result.consentMissing().isEmpty());
    }
}
