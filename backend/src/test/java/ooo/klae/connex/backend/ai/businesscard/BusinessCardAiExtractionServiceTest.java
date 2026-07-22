package ooo.klae.connex.backend.ai.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.businesscard.ValidatedBusinessCardImage;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;

@ExtendWith(MockitoExtension.class)
class BusinessCardAiExtractionServiceTest {
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private AiInvocationService aiInvocationService;

    private BusinessCardAiExtractionService service;
    private ValidatedBusinessCardImage validated;

    @BeforeEach
    void setUp() {
        service = new BusinessCardAiExtractionService(aiFeatureGate, aiInvocationService);
        validated = validated(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3}, 1200, 700);
    }

    @Test
    void extractSendsOneSanitizedImageAndReturnsBoundedReviewFields() {
        when(aiFeatureGate.isAiImageUsable()).thenReturn(true);
        when(aiInvocationService.completeStructured(any(), eq(BusinessCardAiExtraction.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new BusinessCardAiExtraction(
                                "  Ada   Lovelace ",
                                "ADA@Example.TEST",
                                "+1 (202) 555-0199",
                                "Engineer",
                                "Analytical Labs"),
                        0,
                        12,
                        20,
                        "end_turn"));

        Optional<BusinessCardScanResponse> outcome = service.extract(validated);

        assertTrue(outcome.isPresent());
        BusinessCardScanResponse response = outcome.orElseThrow();
        assertEquals("Ada Lovelace", response.fields().name().value());
        assertEquals("ADA@example.test", response.fields().email().value());
        assertEquals("+12025550199", response.fields().phone().value());
        assertEquals("Engineer", response.fields().title().value());
        assertEquals("Analytical Labs", response.company().value());
        assertNull(response.fields().name().confidence());
        assertTrue(response.warnings().contains("ai_extraction_requires_review"));

        ArgumentCaptor<AiInvocation> invocationCaptor = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).completeStructured(
                invocationCaptor.capture(), eq(BusinessCardAiExtraction.class));
        AiInvocation invocation = invocationCaptor.getValue();
        assertEquals("business_card.scan", invocation.feature());
        assertEquals(1, invocation.images().size());
        assertEquals(validated.content().length, invocation.images().getFirst().size());
        assertTrue(invocation.prompt().getSystemPrompt().contains("untrusted data"));
        assertTrue(invocation.prompt().getMessages().getFirst().getContent().contains("Ignore addresses"));
    }

    @Test
    void extractReturnsEmptyWhenAiIsNotAuthorized() {
        when(aiFeatureGate.isAiImageUsable()).thenReturn(false);

        assertTrue(service.extract(validated).isEmpty());

        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void extractRejectsImagesOutsideTheSharedProviderEnvelope() {
        when(aiFeatureGate.isAiImageUsable()).thenReturn(true);
        byte[] oversized = new byte[AiInputImage.MAX_BYTES + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;

        assertTrue(service.extract(validated(oversized, 1200, 700)).isEmpty());
        assertTrue(service.extract(validated(new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, AiInputImage.MAX_DIMENSION + 1, 700)).isEmpty());

        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void extractRejectsMalformedOrUnboundedProviderFields() {
        when(aiFeatureGate.isAiImageUsable()).thenReturn(true);
        when(aiInvocationService.completeStructured(any(), eq(BusinessCardAiExtraction.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new BusinessCardAiExtraction("Ada Lovelace", "not-an-email", null, null, null),
                        0,
                        1,
                        1,
                        "end_turn"));

        assertTrue(service.extract(validated).isEmpty());
    }

    @Test
    void extractDegradesWhenTheConfiguredModelRejectsImageInput() {
        when(aiFeatureGate.isAiImageUsable()).thenReturn(true);
        when(aiInvocationService.completeStructured(any(), eq(BusinessCardAiExtraction.class)))
                .thenThrow(new AiProviderException("Configured model does not accept images"));

        assertTrue(service.extract(validated).isEmpty());
    }

    @Test
    void extractKeepsEmptyResultsReviewableWithoutInventingValues() {
        when(aiFeatureGate.isAiImageUsable()).thenReturn(true);
        when(aiInvocationService.completeStructured(any(), eq(BusinessCardAiExtraction.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new BusinessCardAiExtraction(null, null, null, null, null),
                        0,
                        1,
                        1,
                        "end_turn"));

        Optional<BusinessCardScanResponse> outcome = service.extract(validated);

        assertFalse(outcome.isEmpty());
        assertNull(outcome.orElseThrow().fields().name().value());
        assertTrue(outcome.orElseThrow().warnings().contains("no_recognizable_fields"));
    }

    private static ValidatedBusinessCardImage validated(byte[] content, int width, int height) {
        return new ValidatedBusinessCardImage(content, "image/jpeg", "jpg", width, height);
    }
}
