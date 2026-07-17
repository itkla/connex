package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void classifyAppliesRestrictedThenSuppressedThenRevokedConsentPrecedence() {
        List<Integer> candidates = List.of(1, 2, 3, 4);
        when(campaignMapper.restrictedPersonIds(eq(WORKSPACE), anyList())).thenReturn(List.of(1));
        when(campaignMapper.suppressedPersonIds(eq(WORKSPACE), anyList(), eq(CHANNEL))).thenReturn(List.of(2));
        when(campaignMapper.revokedConsentPersonIds(eq(WORKSPACE), anyList(), eq(CHANNEL), eq(PURPOSE)))
                .thenReturn(List.of(3));

        AudienceEligibilityService.AudienceClassification result =
                service.classify(WORKSPACE, candidates, CHANNEL, PURPOSE);

        assertEquals(Set.of(1), result.restricted());
        assertEquals(Set.of(2), result.suppressed());
        assertEquals(Set.of(3), result.consentBlocked());
        assertEquals(List.of(4), result.includedIds());
        assertEquals("restricted", result.reasonFor(1));
        assertEquals("suppressed", result.reasonFor(2));
        assertEquals("consent_revoked", result.reasonFor(3));
        assertNull(result.reasonFor(4));
    }

    @Test
    void classifyUnderOptOutIncludesPeopleWithNoConsentRecordAtAll() {
        when(campaignMapper.restrictedPersonIds(eq(WORKSPACE), anyList())).thenReturn(List.of());
        when(campaignMapper.suppressedPersonIds(eq(WORKSPACE), anyList(), eq(CHANNEL))).thenReturn(List.of());
        when(campaignMapper.revokedConsentPersonIds(eq(WORKSPACE), anyList(), eq(CHANNEL), eq(PURPOSE)))
                .thenReturn(List.of());

        AudienceEligibilityService.AudienceClassification result =
                service.classify(WORKSPACE, List.of(1, 2), CHANNEL, PURPOSE);

        assertEquals(ConsentPolicy.OPT_OUT, AudienceEligibilityService.CONSENT_POLICY);
        assertEquals(List.of(1, 2), result.includedIds());
        assertTrue(result.consentBlocked().isEmpty());
        assertNull(result.reasonFor(1));
        verify(campaignMapper, never())
                .grantedConsentPersonIds(anyInt(), anyList(), anyString(), anyString());
    }

    @Test
    void consentBlocksTreatsADeliveryWithNoPersonLinkAsAllowedUnderOptOut() {
        assertFalse(service.consentBlocks(WORKSPACE, null, CHANNEL, PURPOSE));
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
        assertTrue(result.consentBlocked().isEmpty());
    }
}
