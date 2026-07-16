package ooo.klae.connex.backend.ai.provider;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative model-family policy for provider image input. Unknown and text-only model ids fail
 * closed so embedded image bytes are never sent merely because a text provider is configured.
 * Provider model availability was last audited against official provider documentation on
 * 2026-07-16. Azure deployment aliases are excluded because their configured model metadata is not
 * bound to the independently named deployment target.
 *
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/models-region-compatibility.html">Bedrock model regions</a>
 * @see <a href="https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/model-versions">Google model lifecycle</a>
 * @see <a href="https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/partner-models/claude/quotas">Claude model regions</a>
 * @see <a href="https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/deprecations/partner-models">Partner model deprecations</a>
 */
public final class AiImageInputSupport {
    private static final Set<String> BEDROCK_CLAUDE_IMAGE_MODELS = Set.of(
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
    private static final Map<String, Set<String>> BEDROCK_CLAUDE_IMAGE_PROFILE_MODELS = Map.of(
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
    private static final Set<String> BEDROCK_US_PROFILE_REGIONS = Set.of(
            "us-east-1", "us-west-2");
    private static final Set<String> BEDROCK_EU_PROFILE_REGIONS = Set.of(
            "eu-central-1", "eu-west-1", "eu-west-3");
    private static final Set<String> BEDROCK_APAC_PROFILE_REGIONS = Set.of(
            "ap-northeast-1", "ap-southeast-1", "ap-southeast-2");
    private static final Set<String> BEDROCK_ALL_PROFILE_REGIONS = Set.of(
            "us-east-1",
            "us-west-2",
            "ap-northeast-1",
            "ap-southeast-1",
            "ap-southeast-2",
            "eu-central-1",
            "eu-west-1",
            "eu-west-3");
    private static final Map<String, Set<String>> BEDROCK_GEO_PROFILE_REGIONS = Map.of(
            "us", BEDROCK_US_PROFILE_REGIONS,
            "eu", BEDROCK_EU_PROFILE_REGIONS,
            "apac", BEDROCK_APAC_PROFILE_REGIONS,
            "au", Set.of("ap-southeast-2"),
            "jp", Set.of("ap-northeast-1"));
    private static final Map<String, Set<String>> BEDROCK_GLOBAL_PROFILE_REGIONS = Map.of(
            "anthropic.claude-sonnet-4-20250514-v1:0", Set.of(
                    "us-east-1", "us-west-2", "eu-west-1", "ap-northeast-1"),
            "anthropic.claude-sonnet-4-5-20250929-v1:0", BEDROCK_ALL_PROFILE_REGIONS,
            "anthropic.claude-haiku-4-5-20251001-v1:0", BEDROCK_ALL_PROFILE_REGIONS,
            "anthropic.claude-opus-4-5-20251101-v1:0", BEDROCK_ALL_PROFILE_REGIONS,
            "anthropic.claude-sonnet-4-6", BEDROCK_ALL_PROFILE_REGIONS,
            "anthropic.claude-opus-4-6-v1", BEDROCK_ALL_PROFILE_REGIONS);
    private static final Set<String> VERTEX_GEMINI_PRO_REGIONS = Set.of(
            "us-central1",
            "us-east1",
            "us-east4",
            "us-east5",
            "us-south1",
            "us-west1",
            "us-west4",
            "northamerica-northeast1",
            "europe-central2",
            "europe-north1",
            "europe-southwest1",
            "europe-west1",
            "europe-west4",
            "europe-west8",
            "europe-west9",
            "asia-northeast1");
    private static final Set<String> VERTEX_GEMINI_FLASH_REGIONS = Set.of(
            "us-central1",
            "us-east1",
            "us-east4",
            "us-east5",
            "us-south1",
            "us-west1",
            "us-west4",
            "northamerica-northeast1",
            "southamerica-east1",
            "europe-central2",
            "europe-north1",
            "europe-southwest1",
            "europe-west1",
            "europe-west2",
            "europe-west3",
            "europe-west4",
            "europe-west8",
            "europe-west9",
            "asia-northeast1",
            "asia-northeast3",
            "asia-south1",
            "asia-southeast1",
            "australia-southeast1");
    private static final Set<String> VERTEX_GEMINI_FLASH_LITE_REGIONS = Set.of(
            "us-central1",
            "us-east1",
            "us-east4",
            "us-east5",
            "us-south1",
            "us-west1",
            "us-west4",
            "europe-central2",
            "europe-north1",
            "europe-southwest1",
            "europe-west1",
            "europe-west4",
            "europe-west8",
            "europe-west9");
    private static final Set<String> VERTEX_CLAUDE_US_ONLY_REGIONS = Set.of("us-east5");
    private static final Set<String> VERTEX_CLAUDE_US_EU_REGIONS = Set.of("us-east5", "europe-west1");
    private static final Set<String> VERTEX_CLAUDE_US_EU_ASIA_REGIONS = Set.of(
            "us-east5", "europe-west1", "asia-southeast1");
    private static final Map<String, Set<String>> VERTEX_IMAGE_MODEL_REGIONS = Map.ofEntries(
            Map.entry("gemini-2.5-pro", VERTEX_GEMINI_PRO_REGIONS),
            Map.entry("gemini-2.5-flash", VERTEX_GEMINI_FLASH_REGIONS),
            Map.entry("gemini-2.5-flash-lite", VERTEX_GEMINI_FLASH_LITE_REGIONS),
            Map.entry("claude-opus-4@20250514", VERTEX_CLAUDE_US_ONLY_REGIONS),
            Map.entry("claude-opus-4-1@20250805", VERTEX_CLAUDE_US_ONLY_REGIONS),
            Map.entry("claude-sonnet-4@20250514", VERTEX_CLAUDE_US_EU_REGIONS),
            Map.entry("claude-haiku-4-5@20251001", VERTEX_CLAUDE_US_EU_REGIONS),
            Map.entry("claude-sonnet-4-5@20250929", VERTEX_CLAUDE_US_EU_ASIA_REGIONS),
            Map.entry("claude-opus-4-5@20251101", VERTEX_CLAUDE_US_EU_ASIA_REGIONS),
            Map.entry("claude-opus-4-6", VERTEX_CLAUDE_US_EU_ASIA_REGIONS),
            Map.entry("claude-sonnet-4-6", VERTEX_CLAUDE_US_EU_ASIA_REGIONS));
    private static final Set<String> OPENAI_COMPATIBLE_GEMINI_IMAGE_MODELS = Set.of(
            "gemini-1.0-pro-vision",
            "gemini-pro-vision",
            "gemini-1.5-pro",
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b",
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash-lite-001",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash");
    private static final Set<String> OPENAI_IMAGE_MODELS = Set.of(
            "gpt-4o",
            "gpt-4o-2024-05-13",
            "gpt-4o-2024-08-06",
            "gpt-4o-2024-11-20",
            "gpt-4o-mini",
            "gpt-4o-mini-2024-07-18",
            "gpt-4.1",
            "gpt-4.1-2025-04-14",
            "gpt-4.1-mini",
            "gpt-4.1-mini-2025-04-14",
            "gpt-4.1-nano",
            "gpt-4.1-nano-2025-04-14",
            "gpt-4.5-preview",
            "gpt-4.5-preview-2025-02-27",
            "gpt-4-turbo",
            "gpt-4-turbo-2024-04-09",
            "gpt-4-vision-preview",
            "gpt-5",
            "gpt-5-2025-08-07",
            "gpt-5-mini",
            "gpt-5-mini-2025-08-07",
            "gpt-5-nano",
            "gpt-5-nano-2025-08-07",
            "gpt-5-chat-latest",
            "gpt-5.1",
            "gpt-5.1-2025-11-13",
            "gpt-5.1-chat-latest",
            "gpt-5.2",
            "gpt-5.2-2025-12-11",
            "gpt-5.2-chat-latest",
            "gpt-5.3-chat-latest",
            "gpt-5.4",
            "gpt-5.4-2026-03-05",
            "gpt-5.4-mini",
            "gpt-5.4-mini-2026-03-17",
            "gpt-5.4-nano",
            "gpt-5.4-nano-2026-03-17",
            "gpt-5.5",
            "gpt-5.5-2026-04-23",
            "gpt-5.6",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "o1",
            "o1-2024-12-17",
            "o3",
            "o3-2025-04-16",
            "o4-mini",
            "o4-mini-2025-04-16");
    private static final Pattern GEMMA_IMAGE_MODEL = Pattern.compile(
            "(?:^|/)gemma-(?:3-(?:4b|12b|27b)"
                    + "|3n-e(?:2|4)b"
                    + "|4-(?:e2b|e4b|12b|26b-a4b|31b))(?:-it)?$");

    private AiImageInputSupport() {
    }

    /**
     * Returns whether the configured provider target belongs to a verified image-capable family
     * supported by its Connex adapter.
     *
     * @param provider provider id
     * @param modelId configured model id
     * @param region configured provider region, required for Vertex and Bedrock inference profiles
     * @return whether embedded image input may be sent
     */
    public static boolean supports(String provider, String modelId, String region) {
        if (provider == null || modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalized = modelId.trim();
        return switch (provider) {
            case "bedrock" -> supportsBedrockClaudeImageInput(normalized, region);
            case "vertex" -> supportsVertexImageInput(normalized, region);
            case "azure_openai" -> false;
            case "openai_compatible" -> supportsOpenAiImageInput(normalized)
                    || supportsOpenAiCompatibleGeminiImageInput(normalized)
                    || GEMMA_IMAGE_MODEL.matcher(normalized).find();
            default -> false;
        };
    }

    private static boolean supportsVertexImageInput(String modelId, String region) {
        if (region == null || region.isBlank()) {
            return false;
        }
        Set<String> supportedRegions = VERTEX_IMAGE_MODEL_REGIONS.get(modelId);
        return supportedRegions != null && supportedRegions.contains(region);
    }

    private static boolean supportsOpenAiImageInput(String modelId) {
        return OPENAI_IMAGE_MODELS.contains(unqualifiedModelId(modelId));
    }

    private static boolean supportsOpenAiCompatibleGeminiImageInput(String modelId) {
        return OPENAI_COMPATIBLE_GEMINI_IMAGE_MODELS.contains(unqualifiedModelId(modelId));
    }

    private static boolean supportsBedrockClaudeImageInput(String modelId, String region) {
        if (BEDROCK_CLAUDE_IMAGE_MODELS.contains(modelId)) {
            return true;
        }
        if (region == null || region.isBlank()) {
            return false;
        }
        int profileSeparator = modelId.indexOf('.');
        if (profileSeparator < 1) {
            return false;
        }
        String profile = modelId.substring(0, profileSeparator);
        String baseModelId = modelId.substring(profileSeparator + 1);
        Set<String> supportedModels = BEDROCK_CLAUDE_IMAGE_PROFILE_MODELS.get(
                profile);
        if (supportedModels == null || !supportedModels.contains(baseModelId)) {
            return false;
        }
        Set<String> supportedRegions = "global".equals(profile)
                ? BEDROCK_GLOBAL_PROFILE_REGIONS.get(baseModelId)
                : BEDROCK_GEO_PROFILE_REGIONS.get(profile);
        return supportedRegions != null && supportedRegions.contains(region);
    }

    private static String unqualifiedModelId(String modelId) {
        int namespaceSeparator = modelId.lastIndexOf('/');
        return namespaceSeparator < 0
                ? modelId
                : modelId.substring(namespaceSeparator + 1);
    }
}
