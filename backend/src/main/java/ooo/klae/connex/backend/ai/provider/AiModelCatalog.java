package ooo.klae.connex.backend.ai.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.ai.AiProperties;

/**
 * The declare-once corpus of provider model capabilities: context window, output ceiling, input
 * modalities, and dated pricing.
 *
 * <p>Before this catalog existed the same knowledge lived as four divergent substring heuristics,
 * one per provider adapter, and they disagreed with each other and with current vendor
 * documentation. Every budget in Ask Connex — tool-result allocations, history compaction bounds,
 * the assistant context floor, and the pre-egress prompt admission ceiling — is derived from these
 * two numbers, so a stale entry does not fail loudly; it silently truncates answers or asks a
 * provider for more output tokens than the model will produce.
 *
 * <h2>The corpus contract</h2>
 *
 * <ol>
 *   <li><b>Dated sources.</b> Every entry carries {@code verifiedOn} and {@code sourceUrl} naming
 *   the official vendor page the numbers were read from. An entry without a citation is not an
 *   entry.</li>
 *   <li><b>Verify, never recall.</b> Numbers are transcribed from vendor documentation at the time
 *   the entry is written or revised. A model whose limits cannot be read from an official page does
 *   not get a declared entry.</li>
 *   <li><b>Conservative unknowns.</b> An unmatched model id resolves to its family's conservative
 *   fallback — the same value that family's adapter returned before delegation — so widening is
 *   always deliberate and never a side effect of a matcher that happens to be loose.</li>
 *   <li><b>First match wins.</b> Entries are ordered most specific first within a family, which is
 *   what lets {@code gpt-5.4-mini} and {@code gpt-5.4} carry different context windows without the
 *   matchers having to be mutually exclusive.</li>
 * </ol>
 *
 * <p>Entries whose source is {@link Source#LEGACY_ADAPTER} preserve, byte for byte, the value the
 * owning adapter returned before this catalog was introduced. They are declared rather than deleted
 * so that migrating to the catalog changed behaviour only where a vendor citation justified it.
 *
 * <h2>Partner pricing caveat</h2>
 *
 * <p>Pricing is populated only where a dated first-party rate card was read. Amazon Bedrock and
 * Google Vertex AI are partner-operated and bill Claude at their own rates, which differ from
 * Anthropic's first-party rates; those families therefore carry no pricing rather than a plausible
 * guess. Consumers must treat a {@code null} pricing block as "unknown", never as "free".
 *
 * <h2>Models API hydration seam</h2>
 *
 * <p>Anthropic's Models API ({@code GET /v1/models/{id}}) returns {@code max_input_tokens},
 * {@code max_tokens}, and a {@code capabilities} tree, so a later increment can refresh entries from
 * the vendor itself at provider-configuration time — cached, never per turn, and failing back to the
 * declared value. {@link Source#LIVE} is reserved for capabilities produced that way; nothing in
 * this class performs network I/O today.
 *
 * @see <a href="https://platform.claude.com/docs/en/about-claude/models/overview">Claude model overview</a>
 * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html">Bedrock model cards</a>
 * @see <a href="https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure">Azure OpenAI model limits</a>
 * @see <a href="https://ai.google.dev/gemini-api/docs/models">Gemini model limits</a>
 * @see <a href="https://developers.openai.com/api/docs/models">OpenAI model limits</a>
 */
public final class AiModelCatalog {

    /** Provider adapter family a declared entry belongs to. */
    public enum Family {
        /** Anthropic Claude published through Amazon Bedrock. */
        BEDROCK_ANTHROPIC("bedrock"),
        /** OpenAI models published through Azure, addressed by operator-named deployment. */
        AZURE_OPENAI("azure_openai"),
        /** Gemini and Claude publisher models on Google Vertex AI. */
        VERTEX("vertex"),
        /** Any endpoint speaking the OpenAI chat-completions protocol. */
        OPENAI_COMPATIBLE("openai_compatible");

        private final String providerId;

        Family(String providerId) {
            this.providerId = providerId;
        }

        /** @return the {@link AiProviderTarget#provider()} value this family serves */
        public String providerId() {
            return providerId;
        }

        /**
         * Resolves the family serving a configured provider id.
         *
         * @param providerId configured provider id
         * @return the matching family, or empty when the provider is not catalogued
         */
        public static Optional<Family> fromProviderId(String providerId) {
            if (providerId == null) {
                return Optional.empty();
            }
            for (Family family : values()) {
                if (family.providerId.equals(providerId)) {
                    return Optional.of(family);
                }
            }
            return Optional.empty();
        }

        private String normalize(String modelId) {
            if (modelId == null || modelId.isBlank()) {
                return null;
            }
            String lowered = modelId.trim().toLowerCase(Locale.ROOT);
            if (this != OPENAI_COMPATIBLE) {
                return lowered;
            }
            int namespaceSeparator = lowered.lastIndexOf('/');
            return namespaceSeparator < 0 ? lowered : lowered.substring(namespaceSeparator + 1);
        }
    }

    /** Where a capability value came from. */
    public enum Source {
        /** Transcribed from official vendor documentation on {@code verifiedOn}. */
        VENDOR_DOC,
        /** Preserved verbatim from the adapter heuristic that predated this catalog. */
        LEGACY_ADAPTER,
        /** Patched by deployment configuration. */
        OPERATOR_OVERRIDE,
        /** Hydrated from a vendor capability API. Reserved; not produced by this build. */
        LIVE
    }

    /** How an entry's matcher is applied to the family-normalized model id. */
    public enum MatchKind {
        /** The normalized id contains the matcher text. */
        CONTAINS,
        /** The normalized id starts with the matcher text. */
        STARTS_WITH,
        /** The normalized id matches the matcher regular expression in full. */
        REGEX
    }

    /**
     * Declared input modalities.
     *
     * @param textIn whether the model accepts text input
     * @param imageIn whether the model accepts embedded image input
     * @param pdfIn whether the model accepts PDF document input
     */
    public record Modalities(boolean textIn, boolean imageIn, boolean pdfIn) {
    }

    /**
     * A dated price for one million tokens.
     *
     * @param inputPerMTok price per million input tokens
     * @param outputPerMTok price per million output tokens
     * @param currency ISO 4217 currency code
     * @param asOf date the rate card was read
     * @param source provenance of the rate
     * @param note operator-facing caveat, never null
     */
    public record Pricing(
            BigDecimal inputPerMTok,
            BigDecimal outputPerMTok,
            String currency,
            LocalDate asOf,
            Source source,
            String note) {
    }

    /**
     * Resolved capabilities for one configured model id.
     *
     * @param family provider family that resolved the id
     * @param matchedEntry human-readable matcher that produced this result, or {@code null} for a
     * family fallback
     * @param contextWindowTokens total context window in tokens
     * @param maxOutputTokens maximum generated output in tokens
     * @param modalities declared input modalities
     * @param pricing dated pricing, or {@code null} when unknown for this family
     * @param source provenance of the numbers
     * @param verifiedOn date the entry was last verified, or {@code null} for a fallback
     * @param sourceUrl citation for the entry, or {@code null} for a fallback
     */
    public record ModelCapabilities(
            Family family,
            String matchedEntry,
            int contextWindowTokens,
            int maxOutputTokens,
            Modalities modalities,
            Pricing pricing,
            Source source,
            LocalDate verifiedOn,
            String sourceUrl) {
    }

    private record Entry(
            Family family,
            MatchKind matchKind,
            String matcher,
            int contextWindowTokens,
            int maxOutputTokens,
            Modalities modalities,
            Pricing pricing,
            Source source,
            LocalDate verifiedOn,
            String sourceUrl,
            Pattern compiled) {

        Entry(
                Family family,
                MatchKind matchKind,
                String matcher,
                int contextWindowTokens,
                int maxOutputTokens,
                Modalities modalities,
                Pricing pricing,
                Source source,
                LocalDate verifiedOn,
                String sourceUrl) {
            this(family, matchKind, matcher, contextWindowTokens, maxOutputTokens, modalities,
                    pricing, source, verifiedOn, sourceUrl,
                    matchKind == MatchKind.REGEX ? Pattern.compile(matcher) : null);
        }

        boolean matches(String normalizedModelId) {
            return switch (matchKind) {
                case CONTAINS -> normalizedModelId.contains(matcher);
                case STARTS_WITH -> normalizedModelId.startsWith(matcher);
                case REGEX -> compiled.matcher(normalizedModelId).matches();
            };
        }
    }

    private static final int CONSERVATIVE_TOKEN_FALLBACK = 4_096;
    private static final String CURRENCY_USD = "USD";
    private static final LocalDate ANTHROPIC_VERIFIED_ON = LocalDate.of(2026, 6, 24);
    private static final LocalDate VENDOR_VERIFIED_ON = LocalDate.of(2026, 8, 25);
    private static final LocalDate LEGACY_VERIFIED_ON = LocalDate.of(2026, 7, 16);

    private static final String ANTHROPIC_OVERVIEW_URL =
            "https://platform.claude.com/docs/en/about-claude/models/overview";
    private static final String ANTHROPIC_PRICING_URL =
            "https://platform.claude.com/docs/en/about-claude/pricing";
    private static final String BEDROCK_MODEL_CARDS_URL =
            "https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html";
    private static final String AZURE_MODELS_URL =
            "https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/models-sold-directly-by-azure";
    private static final String GEMINI_MODELS_URL =
            "https://ai.google.dev/gemini-api/docs/models";
    private static final String GEMINI_THREE_URL =
            "https://ai.google.dev/gemini-api/docs/gemini-3";
    private static final String OPENAI_MODELS_URL =
            "https://developers.openai.com/api/docs/models";
    private static final String VERTEX_PARTNER_CLAUDE_URL =
            "https://cloud.google.com/vertex-ai/generative-ai/docs/partner-models/claude/overview";

    private static final String FIRST_PARTY_PRICING_NOTE =
            "First-party vendor API rate.";
    private static final String OPERATOR_PRICING_NOTE =
            "Operator-configured rate.";

    private static final Modalities TEXT_ONLY = new Modalities(true, false, false);
    private static final Modalities TEXT_AND_IMAGE = new Modalities(true, true, false);
    private static final Modalities TEXT_IMAGE_AND_PDF = new Modalities(true, true, true);
    private static final Modalities NOT_TEXT_CAPABLE = new Modalities(false, false, false);

    private static final Modalities FALLBACK_MODALITIES = TEXT_ONLY;

    private static final Pattern OPENAI_COMPATIBLE_NON_TEXT_VARIANT = Pattern.compile(
            ".*-(?:image|live|tts|audio|native-audio|embedding|exp-image)(?:[-.@].*)?$");

    private static final List<Entry> ENTRIES = List.of(
            bedrock("claude-opus-5", 1_000_000, 128_000),
            bedrock("claude-sonnet-5", 1_000_000, 128_000),
            bedrock("claude-fable", 1_000_000, 128_000),
            bedrock("claude-mythos", 1_000_000, 128_000),
            bedrock("claude-opus-4-8", 1_000_000, 128_000),
            bedrock("claude-opus-4-7", 1_000_000, 128_000),
            bedrock("claude-opus-4-6", 1_000_000, 128_000),
            bedrock("claude-sonnet-4-6", 1_000_000, 64_000),
            bedrock("claude-haiku-4-5", 200_000, 64_000),
            bedrockLegacy("claude-opus-4-5", 200_000, 65_536),
            bedrockLegacy("claude-sonnet-4", 200_000, 65_536),
            bedrockLegacy("claude-haiku-4", 200_000, 65_536),
            bedrockLegacy("claude-opus-4", 200_000, 32_768),
            bedrockLegacy("claude-3-7", 200_000, 65_536),
            bedrockLegacy("claude-3-5", 200_000, 8_192),
            bedrockLegacy("claude-3", 200_000, 4_096),

            new Entry(Family.AZURE_OPENAI, MatchKind.REGEX, ".*gpt-5(?:\\.\\d+)?-chat.*",
                    128_000, 16_384, TEXT_AND_IMAGE, null,
                    Source.VENDOR_DOC, VENDOR_VERIFIED_ON, AZURE_MODELS_URL),
            azure("gpt-5.6", 1_050_000, 128_000),
            azure("gpt-5.5", 1_050_000, 128_000),
            azure("gpt-5.4-mini", 400_000, 128_000),
            azure("gpt-5.4-nano", 400_000, 128_000),
            azure("gpt-5.4", 1_050_000, 128_000),
            azure("gpt-5", 400_000, 128_000),
            azure("o3", 200_000, 100_000),
            azure("o4", 200_000, 100_000),
            azure("gpt-4.1", 300_000, 32_768),
            azure("gpt-4o", 128_000, 16_384),

            vertexLegacy(MatchKind.STARTS_WITH, "gemini-2.5-flash-image", 32_768, 32_768),
            new Entry(Family.VERTEX, MatchKind.STARTS_WITH, "gemini-3-pro-image",
                    65_536, 32_768, TEXT_AND_IMAGE, null,
                    Source.VENDOR_DOC, VENDOR_VERIFIED_ON, GEMINI_THREE_URL),
            new Entry(Family.VERTEX, MatchKind.REGEX,
                    "^gemini-(?:2\\.5|3(?:\\.\\d+)?)-(?:flash|pro)(?:[-.@].*)?$",
                    1_048_576, 65_536, TEXT_IMAGE_AND_PDF, null,
                    Source.VENDOR_DOC, VENDOR_VERIFIED_ON, GEMINI_MODELS_URL),
            vertexLegacy(MatchKind.REGEX, "^gemini-(?:1\\.5|2\\.0)-(?:flash|pro)(?:[-.@].*)?$",
                    128_000, 8_192),
            vertexLegacy(MatchKind.REGEX, "^gemini-\\d+(?:\\.\\d+)?-(?:flash|pro)(?:[-.@].*)?$",
                    128_000, 65_536),
            vertexLegacy(MatchKind.CONTAINS, "claude-mythos", 200_000, 131_072),
            vertexLegacy(MatchKind.CONTAINS, "claude-fable", 200_000, 131_072),
            vertexLegacy(MatchKind.CONTAINS, "claude-opus-4-6", 200_000, 131_072),
            vertexLegacy(MatchKind.CONTAINS, "claude-opus-4-7", 200_000, 131_072),
            vertexLegacy(MatchKind.CONTAINS, "claude-opus-4-8", 200_000, 131_072),
            vertexLegacy(MatchKind.CONTAINS, "claude-sonnet-4-6", 200_000, 131_072),
            vertexLegacy(MatchKind.CONTAINS, "claude-3-7", 200_000, 65_536),
            vertexLegacy(MatchKind.CONTAINS, "claude-sonnet-4", 200_000, 65_536),
            vertexLegacy(MatchKind.CONTAINS, "claude-haiku-4", 200_000, 65_536),
            vertexLegacy(MatchKind.CONTAINS, "claude-opus-4-5", 200_000, 65_536),
            vertexLegacy(MatchKind.CONTAINS, "claude-opus-4", 200_000, 32_768),
            vertexLegacy(MatchKind.CONTAINS, "claude-3-5", 200_000, 8_192),
            vertexLegacy(MatchKind.CONTAINS, "claude-3", 200_000, 4_096),

            new Entry(Family.OPENAI_COMPATIBLE, MatchKind.REGEX,
                    OPENAI_COMPATIBLE_NON_TEXT_VARIANT.pattern(),
                    CONSERVATIVE_TOKEN_FALLBACK, CONSERVATIVE_TOKEN_FALLBACK,
                    NOT_TEXT_CAPABLE, null,
                    Source.LEGACY_ADAPTER, LEGACY_VERIFIED_ON, GEMINI_MODELS_URL),
            gemini("^gemini-(?:2\\.5|3(?:\\.\\d+)?)-(?:flash|pro)(?:[-.@].*)?$",
                    1_048_576, 65_536),
            gemini("^gemini-1\\.5-pro(?:[-.@].*)?$", 2_097_152, 8_192),
            gemini("^gemini-(?:1\\.5|2\\.0)-flash(?:-(?:8b|lite))?(?:[-.@].*)?$",
                    1_048_576, 8_192),
            openAi("^o[134](?:-(?:mini|pro))?(?:-\\d{4}-\\d{2}-\\d{2})?$", 200_000, 100_000, null),
            openAi("^gpt-4\\.5(?:-preview)?(?:-\\d{4}-\\d{2}-\\d{2})?$", 128_000, 16_384, null),
            openAi("^gpt-5(?:\\.[123])?-chat-latest$", 128_000, 16_384, null),
            openAi("^gpt-5\\.2(?:-\\d{4}-\\d{2}-\\d{2})?$", 400_000, 128_000,
                    new Pricing(new BigDecimal("1.75"), new BigDecimal("14.00"), CURRENCY_USD,
                            VENDOR_VERIFIED_ON, Source.VENDOR_DOC, FIRST_PARTY_PRICING_NOTE)),
            openAi("^gpt-5(?:\\.(?:1|2))?(?:-(?:mini|nano|pro))?(?:-\\d{4}-\\d{2}-\\d{2})?$"
                    + "|^gpt-5\\.4-(?:mini|nano)(?:-\\d{4}-\\d{2}-\\d{2})?$", 400_000, 128_000, null),
            openAi("^gpt-5\\.(?:4|5)(?:-pro)?(?:-\\d{4}-\\d{2}-\\d{2})?$"
                    + "|^gpt-5\\.6(?:-(?:sol|terra|luna))?$", 1_050_000, 128_000, null),
            openAi("^gpt-4\\.1(?:-(?:mini|nano))?(?:-\\d{4}-\\d{2}-\\d{2})?$", 1_047_576, 32_768, null),
            openAi("^gpt-4o(?:-mini)?(?:-\\d{4}-\\d{2}-\\d{2})?$", 128_000, 16_384, null),
            claude("claude-fable-5", 1_000_000, 128_000, "10.00", "50.00"),
            claude("claude-mythos-5", 1_000_000, 128_000, "10.00", "50.00"),
            claudeLegacy("claude-mythos-preview", 1_000_000, 65_536),
            claude("claude-opus-5", 1_000_000, 128_000, "5.00", "25.00"),
            claude("claude-opus-4-8", 1_000_000, 128_000, "5.00", "25.00"),
            claude("claude-opus-4-7", 1_000_000, 128_000, "5.00", "25.00"),
            claude("claude-opus-4-6", 1_000_000, 128_000, "5.00", "25.00"),
            claude("claude-sonnet-5", 1_000_000, 128_000, "2.00", "10.00"),
            claude("claude-sonnet-4-6", 1_000_000, 128_000, "3.00", "15.00"),
            claude("claude-haiku-4-5", 200_000, 64_000, "1.00", "5.00"),
            claudeLegacy("claude-opus-4-5", 200_000, 65_536),
            claudeLegacy("claude-sonnet-4", 200_000, 65_536),
            claudeLegacy("claude-haiku-4", 200_000, 65_536),
            claudeLegacy("claude-opus-4", 200_000, 32_768),
            claudeLegacy("claude-3-7", 200_000, 65_536),
            claudeLegacy("claude-3-5", 200_000, 8_192),
            claudeLegacy("claude-3", 200_000, 4_096),
            gemma(MatchKind.STARTS_WITH, "gemma-4", 128_000),
            gemma(MatchKind.REGEX, "^gemma-3-(?:4b|12b|27b)(?:[-.@].*)?$", 128_000),
            gemma(MatchKind.STARTS_WITH, "gemma-3", 32_768));

    private AiModelCatalog() {
    }

    /**
     * Resolves declared capabilities for a configured model id, applying operator overrides last.
     *
     * @param family provider family owning the model id
     * @param modelId configured model id, may be {@code null} or blank
     * @param overrides deployment overrides, may be {@code null}
     * @return declared capabilities, or the family's conservative fallback when nothing matches
     */
    public static ModelCapabilities resolve(
            Family family,
            String modelId,
            List<AiProperties.ModelOverride> overrides) {
        if (family == null) {
            throw new IllegalArgumentException("AI model family is required");
        }
        String normalized = family.normalize(modelId);
        ModelCapabilities declared = normalized == null
                ? fallback(family)
                : declaredFor(family, normalized);
        return normalized == null
                ? declared
                : applyOverrides(declared, family, normalized, overrides);
    }

    /**
     * Resolves the context window for a configured target.
     *
     * @param family provider family owning the target
     * @param target configured provider target, may be {@code null}
     * @param overrides deployment overrides, may be {@code null}
     * @return context window in tokens, never below the conservative fallback
     */
    public static int contextWindowTokens(
            Family family, AiProviderTarget target, List<AiProperties.ModelOverride> overrides) {
        return resolve(family, modelIdOf(target), overrides).contextWindowTokens();
    }

    /**
     * Resolves the maximum generated output for a configured target.
     *
     * @param family provider family owning the target
     * @param target configured provider target, may be {@code null}
     * @param overrides deployment overrides, may be {@code null}
     * @return maximum output tokens, never below the conservative fallback
     */
    public static int maxOutputTokens(
            Family family, AiProviderTarget target, List<AiProperties.ModelOverride> overrides) {
        return resolve(family, modelIdOf(target), overrides).maxOutputTokens();
    }

    /**
     * Resolves dated pricing for a configured model id.
     *
     * @param family provider family owning the model id
     * @param modelId configured model id, may be {@code null}
     * @param overrides deployment overrides, may be {@code null}
     * @return the declared or overridden rate, or empty when this deployment's rate is unknown
     */
    public static Optional<Pricing> pricing(
            Family family, String modelId, List<AiProperties.ModelOverride> overrides) {
        return Optional.ofNullable(resolve(family, modelId, overrides).pricing());
    }

    /**
     * Resolves declared input modalities for a configured model id.
     *
     * <p>Modalities are declared data. Embedded-image egress remains gated by
     * {@link AiImageInputSupport}, which additionally enforces per-region partner availability that
     * a model-id-keyed corpus cannot express.
     *
     * @param family provider family owning the model id
     * @param modelId configured model id, may be {@code null}
     * @param overrides deployment overrides, may be {@code null}
     * @return declared input modalities
     */
    public static Modalities modalities(
            Family family, String modelId, List<AiProperties.ModelOverride> overrides) {
        return resolve(family, modelId, overrides).modalities();
    }

    /** @return every declared entry as an immutable ordered corpus view for verification */
    public static List<ModelCapabilities> declaredEntries() {
        return ENTRIES.stream()
                .map(entry -> new ModelCapabilities(
                        entry.family(),
                        entry.matcher(),
                        entry.contextWindowTokens(),
                        entry.maxOutputTokens(),
                        entry.modalities(),
                        entry.pricing(),
                        entry.source(),
                        entry.verifiedOn(),
                        entry.sourceUrl()))
                .toList();
    }

    private static String modelIdOf(AiProviderTarget target) {
        return target == null ? null : target.modelId();
    }

    private static ModelCapabilities declaredFor(Family family, String normalizedModelId) {
        for (Entry entry : ENTRIES) {
            if (entry.family() == family && entry.matches(normalizedModelId)) {
                return new ModelCapabilities(
                        family,
                        entry.matcher(),
                        entry.contextWindowTokens(),
                        entry.maxOutputTokens(),
                        entry.modalities(),
                        entry.pricing(),
                        entry.source(),
                        entry.verifiedOn(),
                        entry.sourceUrl());
            }
        }
        return fallback(family);
    }

    private static ModelCapabilities fallback(Family family) {
        return new ModelCapabilities(
                family,
                null,
                CONSERVATIVE_TOKEN_FALLBACK,
                CONSERVATIVE_TOKEN_FALLBACK,
                FALLBACK_MODALITIES,
                null,
                Source.LEGACY_ADAPTER,
                null,
                null);
    }

    private static ModelCapabilities applyOverrides(
            ModelCapabilities declared,
            Family family,
            String normalizedModelId,
            List<AiProperties.ModelOverride> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return declared;
        }
        ModelCapabilities patched = declared;
        for (AiProperties.ModelOverride override : overrides) {
            if (override == null || !override.matches(family.providerId(), normalizedModelId)) {
                continue;
            }
            patched = patch(patched, override);
        }
        return patched;
    }

    private static ModelCapabilities patch(
            ModelCapabilities current, AiProperties.ModelOverride override) {
        int contextWindowTokens = override.getContextWindowTokens() == null
                ? current.contextWindowTokens()
                : override.getContextWindowTokens();
        int maxOutputTokens = override.getMaxOutputTokens() == null
                ? current.maxOutputTokens()
                : override.getMaxOutputTokens();
        Modalities modalities = new Modalities(
                override.getTextInput() == null
                        ? current.modalities().textIn() : override.getTextInput(),
                override.getImageInput() == null
                        ? current.modalities().imageIn() : override.getImageInput(),
                override.getPdfInput() == null
                        ? current.modalities().pdfIn() : override.getPdfInput());
        return new ModelCapabilities(
                current.family(),
                current.matchedEntry(),
                contextWindowTokens,
                maxOutputTokens,
                modalities,
                overriddenPricing(current.pricing(), override),
                Source.OPERATOR_OVERRIDE,
                current.verifiedOn(),
                current.sourceUrl());
    }

    private static Pricing overriddenPricing(
            Pricing current, AiProperties.ModelOverride override) {
        if (override.getInputPricePerMTok() == null && override.getOutputPricePerMTok() == null) {
            return current;
        }
        BigDecimal input = override.getInputPricePerMTok() == null
                ? current == null ? null : current.inputPerMTok()
                : override.getInputPricePerMTok();
        BigDecimal output = override.getOutputPricePerMTok() == null
                ? current == null ? null : current.outputPerMTok()
                : override.getOutputPricePerMTok();
        if (input == null || output == null) {
            return current;
        }
        String currency = override.getCurrency() == null || override.getCurrency().isBlank()
                ? current == null ? CURRENCY_USD : current.currency()
                : override.getCurrency().trim().toUpperCase(Locale.ROOT);
        return new Pricing(
                input,
                output,
                currency,
                override.getPricingAsOf(),
                Source.OPERATOR_OVERRIDE,
                OPERATOR_PRICING_NOTE);
    }

    private static Entry bedrock(String matcher, int contextWindowTokens, int maxOutputTokens) {
        return new Entry(
                Family.BEDROCK_ANTHROPIC, MatchKind.CONTAINS, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_AND_IMAGE,
                null, Source.VENDOR_DOC, VENDOR_VERIFIED_ON, BEDROCK_MODEL_CARDS_URL);
    }

    private static Entry bedrockLegacy(
            String matcher, int contextWindowTokens, int maxOutputTokens) {
        return new Entry(
                Family.BEDROCK_ANTHROPIC, MatchKind.CONTAINS, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_AND_IMAGE,
                null, Source.LEGACY_ADAPTER, LEGACY_VERIFIED_ON, BEDROCK_MODEL_CARDS_URL);
    }

    private static Entry azure(String matcher, int contextWindowTokens, int maxOutputTokens) {
        return new Entry(
                Family.AZURE_OPENAI, MatchKind.CONTAINS, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_AND_IMAGE,
                null, Source.VENDOR_DOC, VENDOR_VERIFIED_ON, AZURE_MODELS_URL);
    }

    private static Entry vertexLegacy(
            MatchKind matchKind, String matcher, int contextWindowTokens, int maxOutputTokens) {
        return new Entry(
                Family.VERTEX, matchKind, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_AND_IMAGE,
                null, Source.LEGACY_ADAPTER, LEGACY_VERIFIED_ON, VERTEX_PARTNER_CLAUDE_URL);
    }

    private static Entry gemini(String matcher, int contextWindowTokens, int maxOutputTokens) {
        return new Entry(
                Family.OPENAI_COMPATIBLE, MatchKind.REGEX, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_IMAGE_AND_PDF,
                null, Source.VENDOR_DOC, VENDOR_VERIFIED_ON, GEMINI_MODELS_URL);
    }

    private static Entry openAi(
            String matcher, int contextWindowTokens, int maxOutputTokens, Pricing pricing) {
        return new Entry(
                Family.OPENAI_COMPATIBLE, MatchKind.REGEX, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_AND_IMAGE,
                pricing, Source.VENDOR_DOC, VENDOR_VERIFIED_ON, OPENAI_MODELS_URL);
    }

    private static Entry claude(
            String matcher,
            int contextWindowTokens,
            int maxOutputTokens,
            String inputPerMTok,
            String outputPerMTok) {
        return new Entry(
                Family.OPENAI_COMPATIBLE, MatchKind.STARTS_WITH, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_IMAGE_AND_PDF,
                new Pricing(
                        new BigDecimal(inputPerMTok),
                        new BigDecimal(outputPerMTok),
                        CURRENCY_USD,
                        ANTHROPIC_VERIFIED_ON,
                        Source.VENDOR_DOC,
                        FIRST_PARTY_PRICING_NOTE),
                Source.VENDOR_DOC, ANTHROPIC_VERIFIED_ON, ANTHROPIC_OVERVIEW_URL);
    }

    private static Entry claudeLegacy(
            String matcher, int contextWindowTokens, int maxOutputTokens) {
        return new Entry(
                Family.OPENAI_COMPATIBLE, MatchKind.STARTS_WITH, matcher,
                contextWindowTokens, maxOutputTokens, TEXT_IMAGE_AND_PDF,
                null, Source.LEGACY_ADAPTER, LEGACY_VERIFIED_ON, ANTHROPIC_PRICING_URL);
    }

    private static Entry gemma(MatchKind matchKind, String matcher, int tokens) {
        return new Entry(
                Family.OPENAI_COMPATIBLE, matchKind, matcher,
                tokens, tokens, TEXT_AND_IMAGE,
                null, Source.LEGACY_ADAPTER, LEGACY_VERIFIED_ON, GEMINI_MODELS_URL);
    }
}
