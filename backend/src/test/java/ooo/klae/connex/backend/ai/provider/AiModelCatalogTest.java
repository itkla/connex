package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog.Family;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog.ModelCapabilities;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog.Modalities;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog.Pricing;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog.Source;

class AiModelCatalogTest {

    /**
     * Pins the corpus contract itself rather than any one number.
     *
     * <p>A declared entry that outlived its citation, or whose output ceiling exceeds its own
     * context window, would not fail at declaration time — it would fail later as a rejected
     * provider request or an {@link AiProviderCapabilities} construction error, far from the line
     * that introduced it.
     */
    @Test
    void everyDeclaredEntryCarriesACitationAndAConsistentWindow() {
        List<ModelCapabilities> entries = AiModelCatalog.declaredEntries();

        assertFalse(entries.isEmpty());
        for (ModelCapabilities entry : entries) {
            assertNotNull(entry.family(), "family");
            assertNotNull(entry.matchedEntry(), "matcher");
            assertNotNull(entry.verifiedOn(), "verifiedOn for " + entry.matchedEntry());
            assertNotNull(entry.sourceUrl(), "sourceUrl for " + entry.matchedEntry());
            assertTrue(entry.sourceUrl().startsWith("https://"),
                    "citation must be an https url for " + entry.matchedEntry());
            assertTrue(entry.source() == Source.VENDOR_DOC
                            || entry.source() == Source.LEGACY_ADAPTER,
                    "declared entries are documented or preserved for " + entry.matchedEntry());
            assertTrue(entry.contextWindowTokens() >= 4_096,
                    "context window for " + entry.matchedEntry());
            assertTrue(entry.maxOutputTokens() >= 1,
                    "output ceiling for " + entry.matchedEntry());
            assertTrue(entry.maxOutputTokens() <= entry.contextWindowTokens(),
                    "output ceiling must fit the window for " + entry.matchedEntry());
            assertNotNull(entry.modalities(), "modalities for " + entry.matchedEntry());
            Pricing pricing = entry.pricing();
            if (pricing != null) {
                assertNotNull(pricing.asOf(), "priced entries are dated: " + entry.matchedEntry());
                assertNotNull(pricing.currency(), "currency for " + entry.matchedEntry());
                assertTrue(pricing.inputPerMTok().signum() > 0, entry.matchedEntry());
                assertTrue(pricing.outputPerMTok().signum() > 0, entry.matchedEntry());
            }
        }
    }

    @Test
    void partnerFamiliesCarryNoPricingRatherThanAGuessedRate() {
        for (ModelCapabilities entry : AiModelCatalog.declaredEntries()) {
            if (entry.family() == Family.BEDROCK_ANTHROPIC || entry.family() == Family.VERTEX) {
                assertNull(entry.pricing(),
                        "partner rate cards differ and are not declared: " + entry.matchedEntry());
            }
        }
    }

    @Test
    void anUnknownOrAbsentModelIdKeepsTheConservativeFallback() {
        for (Family family : Family.values()) {
            for (String modelId : List.of("", "   ", "some-unlisted-local-model")) {
                ModelCapabilities capabilities = AiModelCatalog.resolve(family, modelId, List.of());

                assertEquals(4_096, capabilities.contextWindowTokens(), family + " " + modelId);
                assertEquals(4_096, capabilities.maxOutputTokens(), family + " " + modelId);
                assertNull(capabilities.matchedEntry(), family + " " + modelId);
                assertNull(capabilities.pricing(), family + " " + modelId);
            }
            ModelCapabilities missing = AiModelCatalog.resolve(family, null, null);

            assertEquals(4_096, missing.contextWindowTokens());
            assertEquals(4_096, missing.maxOutputTokens());
        }
    }

    @Test
    void aMissingFamilyIsRejectedRatherThanDefaulted() {
        assertThrows(IllegalArgumentException.class,
                () -> AiModelCatalog.resolve(null, "claude-opus-5", List.of()));
    }

    @Test
    void firstMatchWinsSoSiblingMatchersDoNotHaveToBeDisjoint() {
        assertEquals(400_000, AiModelCatalog.resolve(
                Family.AZURE_OPENAI, "gpt-5.4-mini", List.of()).contextWindowTokens());
        assertEquals(1_050_000, AiModelCatalog.resolve(
                Family.AZURE_OPENAI, "gpt-5.4", List.of()).contextWindowTokens());
        assertEquals(128_000, AiModelCatalog.resolve(
                Family.AZURE_OPENAI, "gpt-5.2-chat", List.of()).contextWindowTokens());
        assertEquals(400_000, AiModelCatalog.resolve(
                Family.AZURE_OPENAI, "gpt-5.2", List.of()).contextWindowTokens());
    }

    @Test
    void bedrockGeoAndGlobalInferenceProfilesResolveTheSameModel() {
        for (String modelId : List.of(
                "anthropic.claude-opus-5",
                "us.anthropic.claude-opus-5",
                "eu.anthropic.claude-opus-5",
                "global.anthropic.claude-opus-5")) {
            ModelCapabilities capabilities = AiModelCatalog.resolve(
                    Family.BEDROCK_ANTHROPIC, modelId, List.of());

            assertEquals(1_000_000, capabilities.contextWindowTokens(), modelId);
            assertEquals(128_000, capabilities.maxOutputTokens(), modelId);
        }
    }

    @Test
    void openAiCompatibleIdsAreNamespaceStrippedAndCaseFolded() {
        ModelCapabilities namespaced = AiModelCatalog.resolve(
                Family.OPENAI_COMPATIBLE, "  Anthropic/Claude-Opus-5  ", List.of());

        assertEquals(1_000_000, namespaced.contextWindowTokens());
        assertEquals(128_000, namespaced.maxOutputTokens());
    }

    @Test
    void firstPartyClaudePricingIsDeclaredForTheCompatibleFamily() {
        Optional<Pricing> opus = AiModelCatalog.pricing(
                Family.OPENAI_COMPATIBLE, "claude-opus-5", List.of());
        Optional<Pricing> sonnet = AiModelCatalog.pricing(
                Family.OPENAI_COMPATIBLE, "claude-sonnet-5", List.of());
        Optional<Pricing> fable = AiModelCatalog.pricing(
                Family.OPENAI_COMPATIBLE, "claude-fable-5", List.of());
        Optional<Pricing> haiku = AiModelCatalog.pricing(
                Family.OPENAI_COMPATIBLE, "claude-haiku-4-5", List.of());

        assertEquals(new BigDecimal("5.00"), opus.orElseThrow().inputPerMTok());
        assertEquals(new BigDecimal("25.00"), opus.orElseThrow().outputPerMTok());
        assertEquals("USD", opus.orElseThrow().currency());
        assertEquals(new BigDecimal("2.00"), sonnet.orElseThrow().inputPerMTok());
        assertEquals(new BigDecimal("10.00"), sonnet.orElseThrow().outputPerMTok());
        assertEquals(new BigDecimal("10.00"), fable.orElseThrow().inputPerMTok());
        assertEquals(new BigDecimal("50.00"), fable.orElseThrow().outputPerMTok());
        assertEquals(new BigDecimal("1.00"), haiku.orElseThrow().inputPerMTok());
        assertEquals(new BigDecimal("5.00"), haiku.orElseThrow().outputPerMTok());
    }

    @Test
    void partnerDeploymentsExposeNoPricingRatherThanTheFirstPartyRate() {
        assertTrue(AiModelCatalog.pricing(
                Family.BEDROCK_ANTHROPIC, "anthropic.claude-opus-5", List.of()).isEmpty());
        assertTrue(AiModelCatalog.pricing(
                Family.VERTEX, "claude-opus-4-6", List.of()).isEmpty());
    }

    @Test
    void nonTextModelVariantsAreDeclaredWithoutTextInput() {
        Modalities imageVariant = AiModelCatalog.modalities(
                Family.OPENAI_COMPATIBLE, "gemini-2.5-flash-image", List.of());
        Modalities textModel = AiModelCatalog.modalities(
                Family.OPENAI_COMPATIBLE, "gemini-2.5-flash", List.of());

        assertFalse(imageVariant.textIn());
        assertFalse(imageVariant.imageIn());
        assertTrue(textModel.textIn());
        assertTrue(textModel.imageIn());
        assertTrue(textModel.pdfIn());
    }

    @Test
    void anOperatorOverridePatchesOnlyTheFieldsItSets() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("openai_compatible");
        override.setModelId("llama3.3:70b");
        override.setContextWindowTokens(131_072);
        override.setMaxOutputTokens(16_384);

        ModelCapabilities patched = AiModelCatalog.resolve(
                Family.OPENAI_COMPATIBLE, "llama3.3:70b", List.of(override));
        ModelCapabilities untouched = AiModelCatalog.resolve(
                Family.OPENAI_COMPATIBLE, "llama3.1:8b", List.of(override));

        assertEquals(131_072, patched.contextWindowTokens());
        assertEquals(16_384, patched.maxOutputTokens());
        assertEquals(Source.OPERATOR_OVERRIDE, patched.source());
        assertNull(patched.pricing());
        assertEquals(4_096, untouched.contextWindowTokens());
        assertEquals(4_096, untouched.maxOutputTokens());
    }

    @Test
    void anOverrideWhoseMergedOutputExceedsItsContextWindowIsRefusedAtResolution() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("openai_compatible");
        override.setModelId("llama3.3:70b");
        override.setContextWindowTokens(2_048);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> AiModelCatalog.resolve(
                        Family.OPENAI_COMPATIBLE, "llama3.3:70b", List.of(override)));

        assertTrue(refused.getMessage().contains("llama3.3:70b"));
        assertTrue(refused.getMessage().contains("context-window-tokens"));
    }

    @Test
    void anOverrideWhoseMergedPairStaysCoherentResolvesNormally() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("openai_compatible");
        override.setModelId("llama3.3:70b");
        override.setContextWindowTokens(2_048);
        override.setMaxOutputTokens(2_048);

        ModelCapabilities patched = AiModelCatalog.resolve(
                Family.OPENAI_COMPATIBLE, "llama3.3:70b", List.of(override));

        assertEquals(2_048, patched.contextWindowTokens());
        assertEquals(2_048, patched.maxOutputTokens());
    }

    @Test
    void anOperatorOverrideWinsOverADeclaredEntryAndItsPricing() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("bedrock");
        override.setModelId("anthropic.claude-opus-5");
        override.setContextWindowTokens(200_000);
        override.setInputPricePerMTok(new BigDecimal("6.00"));
        override.setOutputPricePerMTok(new BigDecimal("30.00"));
        override.setCurrency("usd");
        override.setPricingAsOf(LocalDate.of(2026, 8, 1));

        ModelCapabilities patched = AiModelCatalog.resolve(
                Family.BEDROCK_ANTHROPIC, "anthropic.claude-opus-5", List.of(override));

        assertEquals(200_000, patched.contextWindowTokens());
        assertEquals(128_000, patched.maxOutputTokens());
        assertEquals(new BigDecimal("6.00"), patched.pricing().inputPerMTok());
        assertEquals(new BigDecimal("30.00"), patched.pricing().outputPerMTok());
        assertEquals("USD", patched.pricing().currency());
        assertEquals(LocalDate.of(2026, 8, 1), patched.pricing().asOf());
        assertEquals(Source.OPERATOR_OVERRIDE, patched.pricing().source());
    }

    @Test
    void anOverrideNamingAnotherProviderOrModelIsIgnored() {
        AiProperties.ModelOverride wrongProvider = new AiProperties.ModelOverride();
        wrongProvider.setProvider("vertex");
        wrongProvider.setModelId("anthropic.claude-opus-5");
        wrongProvider.setContextWindowTokens(4_096);
        AiProperties.ModelOverride wrongModel = new AiProperties.ModelOverride();
        wrongModel.setProvider("bedrock");
        wrongModel.setModelId("anthropic.claude-opus-4-6-v1");
        wrongModel.setContextWindowTokens(4_096);

        ModelCapabilities capabilities = AiModelCatalog.resolve(
                Family.BEDROCK_ANTHROPIC,
                "anthropic.claude-opus-5",
                List.of(wrongProvider, wrongModel));

        assertEquals(1_000_000, capabilities.contextWindowTokens());
        assertEquals(Source.VENDOR_DOC, capabilities.source());
    }

    @Test
    void overrideMatchingIsExactRatherThanSubstring() {
        AiProperties.ModelOverride override = new AiProperties.ModelOverride();
        override.setProvider("bedrock");
        override.setModelId("claude-opus-5");
        override.setContextWindowTokens(4_096);

        ModelCapabilities capabilities = AiModelCatalog.resolve(
                Family.BEDROCK_ANTHROPIC, "us.anthropic.claude-opus-5", List.of(override));

        assertEquals(1_000_000, capabilities.contextWindowTokens());
    }

    @Test
    void everyCatalogedProviderIdResolvesBackToItsFamily() {
        for (Family family : Family.values()) {
            assertEquals(Optional.of(family), Family.fromProviderId(family.providerId()));
        }
        assertTrue(Family.fromProviderId("not_a_provider").isEmpty());
        assertTrue(Family.fromProviderId(null).isEmpty());
    }
}
