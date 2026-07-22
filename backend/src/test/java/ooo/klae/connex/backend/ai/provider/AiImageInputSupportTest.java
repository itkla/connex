package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AiImageInputSupportTest {
    private static final Set<String> BEDROCK_DIRECT_IMAGE_MODELS = Set.of(
            "anthropic.claude-3-opus-20240229-v1:0",
            "anthropic.claude-3-sonnet-20240229-v1:0",
            "anthropic.claude-3-haiku-20240307-v1:0",
            "anthropic.claude-3-5-sonnet-20240620-v1:0",
            "anthropic.claude-3-5-sonnet-20241022-v2:0",
            "anthropic.claude-3-7-sonnet-20250219-v1:0",
            "anthropic.claude-sonnet-4-20250514-v1:0",
            "anthropic.claude-opus-4-20250514-v1:0",
            "anthropic.claude-opus-4-1-20250805-v1:0",
            "anthropic.claude-sonnet-4-5-20250929-v1:0",
            "anthropic.claude-haiku-4-5-20251001-v1:0",
            "anthropic.claude-opus-4-5-20251101-v1:0",
            "anthropic.claude-sonnet-4-6",
            "anthropic.claude-opus-4-6-v1");
    private static final Map<String, Set<String>> BEDROCK_SUPPORTED_PROFILE_MODELS = Map.of(
            "us", Set.of(
                    "anthropic.claude-3-sonnet-20240229-v1:0",
                    "anthropic.claude-3-haiku-20240307-v1:0",
                    "anthropic.claude-3-5-sonnet-20240620-v1:0",
                    "anthropic.claude-3-5-sonnet-20241022-v2:0",
                    "anthropic.claude-3-7-sonnet-20250219-v1:0",
                    "anthropic.claude-opus-4-20250514-v1:0",
                    "anthropic.claude-sonnet-4-20250514-v1:0",
                    "anthropic.claude-sonnet-4-5-20250929-v1:0",
                    "anthropic.claude-haiku-4-5-20251001-v1:0",
                    "anthropic.claude-opus-4-5-20251101-v1:0",
                    "anthropic.claude-sonnet-4-6",
                    "anthropic.claude-opus-4-6-v1"),
            "eu", Set.of(
                    "anthropic.claude-3-sonnet-20240229-v1:0",
                    "anthropic.claude-3-haiku-20240307-v1:0",
                    "anthropic.claude-3-5-sonnet-20240620-v1:0",
                    "anthropic.claude-3-7-sonnet-20250219-v1:0",
                    "anthropic.claude-sonnet-4-20250514-v1:0",
                    "anthropic.claude-sonnet-4-5-20250929-v1:0",
                    "anthropic.claude-haiku-4-5-20251001-v1:0",
                    "anthropic.claude-opus-4-5-20251101-v1:0",
                    "anthropic.claude-sonnet-4-6",
                    "anthropic.claude-opus-4-6-v1"),
            "apac", Set.of("anthropic.claude-sonnet-4-20250514-v1:0"),
            "au", Set.of(
                    "anthropic.claude-sonnet-4-5-20250929-v1:0",
                    "anthropic.claude-haiku-4-5-20251001-v1:0",
                    "anthropic.claude-sonnet-4-6",
                    "anthropic.claude-opus-4-6-v1"),
            "jp", Set.of(
                    "anthropic.claude-sonnet-4-5-20250929-v1:0",
                    "anthropic.claude-haiku-4-5-20251001-v1:0",
                    "anthropic.claude-sonnet-4-6"),
            "global", Set.of(
                    "anthropic.claude-sonnet-4-20250514-v1:0",
                    "anthropic.claude-sonnet-4-5-20250929-v1:0",
                    "anthropic.claude-haiku-4-5-20251001-v1:0",
                    "anthropic.claude-opus-4-5-20251101-v1:0",
                    "anthropic.claude-sonnet-4-6",
                    "anthropic.claude-opus-4-6-v1"));
    private static final Set<String> BEDROCK_SUPPORTED_REGIONS = Set.of(
            "us-east-1",
            "us-west-2",
            "ap-northeast-1",
            "ap-southeast-1",
            "ap-southeast-2",
            "eu-central-1",
            "eu-west-1",
            "eu-west-3");
    private static final Map<String, Set<String>> BEDROCK_GEO_PROFILE_REGIONS = Map.of(
            "us", Set.of("us-east-1", "us-west-2"),
            "eu", Set.of("eu-central-1", "eu-west-1", "eu-west-3"),
            "apac", Set.of("ap-northeast-1", "ap-southeast-1", "ap-southeast-2"),
            "au", Set.of("ap-southeast-2"),
            "jp", Set.of("ap-northeast-1"));
    private static final Map<String, Set<String>> BEDROCK_GLOBAL_PROFILE_REGIONS = Map.of(
            "anthropic.claude-sonnet-4-20250514-v1:0", Set.of(
                    "us-east-1", "us-west-2", "eu-west-1", "ap-northeast-1"),
            "anthropic.claude-sonnet-4-5-20250929-v1:0", BEDROCK_SUPPORTED_REGIONS,
            "anthropic.claude-haiku-4-5-20251001-v1:0", BEDROCK_SUPPORTED_REGIONS,
            "anthropic.claude-opus-4-5-20251101-v1:0", BEDROCK_SUPPORTED_REGIONS,
            "anthropic.claude-sonnet-4-6", BEDROCK_SUPPORTED_REGIONS,
            "anthropic.claude-opus-4-6-v1", BEDROCK_SUPPORTED_REGIONS);
    private static final Map<String, Set<String>> VERTEX_SUPPORTED_REGIONS = Map.ofEntries(
            Map.entry("gemini-2.5-pro", Set.of(
                    "us-central1", "us-east1", "us-east4", "us-east5", "us-south1", "us-west1", "us-west4",
                    "northamerica-northeast1", "europe-central2", "europe-north1", "europe-southwest1",
                    "europe-west1", "europe-west4", "europe-west8", "europe-west9", "asia-northeast1")),
            Map.entry("gemini-2.5-flash", Set.of(
                    "us-central1", "us-east1", "us-east4", "us-east5", "us-south1", "us-west1", "us-west4",
                    "northamerica-northeast1", "southamerica-east1", "europe-central2", "europe-north1",
                    "europe-southwest1", "europe-west1", "europe-west2", "europe-west3", "europe-west4",
                    "europe-west8", "europe-west9", "asia-northeast1", "asia-northeast3", "asia-south1",
                    "asia-southeast1", "australia-southeast1")),
            Map.entry("gemini-2.5-flash-lite", Set.of(
                    "us-central1", "us-east1", "us-east4", "us-east5", "us-south1", "us-west1", "us-west4",
                    "europe-central2", "europe-north1", "europe-southwest1", "europe-west1", "europe-west4",
                    "europe-west8", "europe-west9")),
            Map.entry("claude-opus-4@20250514", Set.of("us-east5")),
            Map.entry("claude-opus-4-1@20250805", Set.of("us-east5")),
            Map.entry("claude-sonnet-4@20250514", Set.of("us-east5", "europe-west1")),
            Map.entry("claude-haiku-4-5@20251001", Set.of("us-east5", "europe-west1")),
            Map.entry("claude-sonnet-4-5@20250929", Set.of("us-east5", "europe-west1", "asia-southeast1")),
            Map.entry("claude-opus-4-5@20251101", Set.of("us-east5", "europe-west1", "asia-southeast1")),
            Map.entry("claude-opus-4-6", Set.of("us-east5", "europe-west1", "asia-southeast1")),
            Map.entry("claude-sonnet-4-6", Set.of("us-east5", "europe-west1", "asia-southeast1")));
    private static final Set<String> VERTEX_PLAUSIBLE_REGIONS = Set.of(
            "us-central1", "us-east1", "us-east4", "us-east5", "us-south1", "us-west1", "us-west4",
            "northamerica-northeast1", "southamerica-east1", "europe-central2", "europe-north1",
            "europe-southwest1", "europe-west1", "europe-west2", "europe-west3", "europe-west4",
            "europe-west8", "europe-west9", "asia-northeast1", "asia-northeast3", "asia-south1",
            "asia-east1", "asia-southeast1", "australia-southeast1");

    @Test
    void supportsVerifiedImageCapableFamiliesByProviderAdapter() {
        assertTrue(AiImageInputSupport.supports("bedrock",
                "anthropic.claude-3-5-sonnet-20240620-v1:0", "ap-northeast-1"));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.2", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.5", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.6", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.6-sol", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.4-2026-03-05", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.4-mini-2026-03-17", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-5.4-nano-2026-03-17", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gpt-4.1-mini", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "o1", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "o3", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "o4-mini", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "google/gemma-4-31b-it", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gemma-3-27b-it", null));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "gemma-3n-e4b-it", null));
    }

    @Test
    void supportsEveryAuditedBedrockDirectModelAndInferenceProfile() {
        for (String modelId : BEDROCK_DIRECT_IMAGE_MODELS) {
            assertTrue(AiImageInputSupport.supports("bedrock", modelId, "us-east-1"), modelId);
        }
        for (Map.Entry<String, Set<String>> profile : BEDROCK_SUPPORTED_PROFILE_MODELS.entrySet()) {
            for (String modelId : profile.getValue()) {
                String profileId = profile.getKey() + "." + modelId;
                Set<String> supportedRegions = "global".equals(profile.getKey())
                        ? BEDROCK_GLOBAL_PROFILE_REGIONS.get(modelId)
                        : BEDROCK_GEO_PROFILE_REGIONS.get(profile.getKey());
                for (String region : BEDROCK_SUPPORTED_REGIONS) {
                    String description = profileId + " in " + region;
                    if (supportedRegions.contains(region)) {
                        assertTrue(AiImageInputSupport.supports("bedrock", profileId, region), description);
                    } else {
                        assertFalse(AiImageInputSupport.supports("bedrock", profileId, region), description);
                    }
                }
            }
        }
        assertFalse(AiImageInputSupport.supports("bedrock",
                "us.anthropic.claude-sonnet-4-5-20250929-v1:0", null));
        assertFalse(AiImageInputSupport.supports("bedrock",
                "us.anthropic.claude-sonnet-4-5-20250929-v1:0", " "));
    }

    @Test
    void rejectsUnsupportedExpiredAndMalformedBedrockInferenceProfiles() {
        for (String modelId : List.of(
                "apac.anthropic.claude-sonnet-4-5-20250929-v1:0",
                "eu.anthropic.claude-3-5-sonnet-20241022-v2:0",
                "eu.anthropic.claude-opus-4-20250514-v1:0",
                "au.anthropic.claude-3-opus-20240229-v1:0",
                "jp.anthropic.claude-opus-4-6-v1",
                "global.anthropic.claude-3-7-sonnet-20250219-v1:0",
                "us.anthropic.claude-opus-4-1-20250805-v1:0",
                "unknown.anthropic.claude-sonnet-4-5-20250929-v1:0",
                "us.eu.anthropic.claude-sonnet-4-5-20250929-v1:0",
                "US.anthropic.claude-sonnet-4-5-20250929-v1:0",
                "us.anthropic.claude-99-foo",
                "us.anthropic.claude-v2:1",
                "us..anthropic.claude-sonnet-4-5-20250929-v1:0",
                "us.anthropic.claude-sonnet-4-5-20250929-v2:0",
                "us.",
                ".anthropic.claude-sonnet-4-5-20250929-v1:0")) {
            assertFalse(AiImageInputSupport.supports("bedrock", modelId, "us-east-1"), modelId);
        }
    }

    @Test
    void supportsEveryAuditedVertexModelLocationPairAndRejectsCrossRegionPairs() {
        for (Map.Entry<String, Set<String>> model : VERTEX_SUPPORTED_REGIONS.entrySet()) {
            for (String region : VERTEX_PLAUSIBLE_REGIONS) {
                boolean expected = model.getValue().contains(region);
                if (expected) {
                    assertTrue(AiImageInputSupport.supports("vertex", model.getKey(), region),
                            model.getKey() + " in " + region);
                } else {
                    assertFalse(AiImageInputSupport.supports("vertex", model.getKey(), region),
                            model.getKey() + " in " + region);
                }
            }
        }
    }

    @Test
    void rejectsUnknownTextOnlyAndAdapterIncompatibleFamilies() {
        assertFalse(AiImageInputSupport.supports("bedrock", "anthropic.claude-v2:1", null));
        assertFalse(AiImageInputSupport.supports("bedrock",
                "anthropic.claude-3-5-haiku-20241022-v1:0", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible",
                "anthropic/claude-3-5-haiku-20241022", null));
        assertFalse(AiImageInputSupport.supports("bedrock", "anthropic.claude-3-text-only", null));
        assertFalse(AiImageInputSupport.supports("bedrock", "anthropic.claude-99-foo", null));
        assertFalse(AiImageInputSupport.supports("bedrock",
                "au.anthropic.claude-3-opus-20240229-v1:0", null));
        assertFalse(AiImageInputSupport.supports("bedrock",
                "unknown.anthropic.claude-3-sonnet-20240229-v1:0", null));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-3-text-only", "us-east5"));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-99-foo", "us-east5"));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-opus-4-7", "us-east5"));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-opus-4-8", "us-east5"));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-sonnet-5", "us-east5"));
        assertFalse(AiImageInputSupport.supports("openai_compatible",
                "anthropic/claude-sonnet-4-5", null));
        assertFalse(AiImageInputSupport.supports("vertex", "gemma-4-31b-it", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-2.5-flash-preview-tts", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-1.5-pro", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-2.0-flash", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-2.0-pro", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.1-pro-preview", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.1-flash-lite", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.1-flash-preview", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.5-pro", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.5-flash", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.5-flash", "europe-west2"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-3.1-flash-tts-preview", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "gemini-2.5-flash-live", "us-central1"));
        assertFalse(AiImageInputSupport.supports("vertex", "google/gemini-2.5-pro", "us-central1"));
        assertTrue(AiImageInputSupport.supports("openai_compatible", "google/gemini-2.5-pro", null));
        assertFalse(AiImageInputSupport.supports("azure_openai", "gpt-5.2", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-4", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "o1-mini", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "o1-pro", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "o1-preview", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "o3-mini", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "o3-pro", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-4o-mini-tts", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-4o-mini-transcribe", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-4o-realtime-preview", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-4o-search-preview", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5-codex", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5-pro", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5.2-sol", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5.999", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5.6-mars", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5.4-mini-2026-03-18", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5.4-nano-2026-03-18", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-5.4-mini-latest", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "GPT-5.6", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gpt-4-turbo-preview", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "llama3.3:70b", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gemma-3-1b-it", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gemma-3-270m-it", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gemma-2-27b-it", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", "gemma-4-31B-it", null));
        assertFalse(AiImageInputSupport.supports("unknown", "gpt-5.2", null));
        assertFalse(AiImageInputSupport.supports("openai_compatible", null, null));
    }

    @Test
    void rejectsExpiredGrandfatheredAndUnroutableVertexTargets() {
        for (String modelId : Set.of(
                "claude-3-opus@20240229",
                "claude-3-sonnet@20240229",
                "claude-3-haiku@20240307",
                "claude-3-5-sonnet@20240620",
                "claude-3-5-sonnet-v2@20241022",
                "claude-3-5-haiku@20241022",
                "claude-3-7-sonnet@20250219")) {
            assertFalse(AiImageInputSupport.supports("vertex", modelId, "us-east5"), modelId);
        }
        for (String region : Set.of("global", "us", "eu", "US-EAST5", "asia-east1", "moon-west1")) {
            assertFalse(AiImageInputSupport.supports("vertex", "claude-sonnet-4@20250514", region), region);
        }
        assertFalse(AiImageInputSupport.supports("vertex", "claude-sonnet-5", "us-east5"));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-opus-4-8", "us-east5"));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-sonnet-4@20250514", null));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-sonnet-4@20250514", ""));
        assertFalse(AiImageInputSupport.supports("vertex", "claude-sonnet-4@20250514", " "));
    }
}
