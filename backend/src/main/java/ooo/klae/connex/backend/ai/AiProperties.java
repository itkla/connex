package ooo.klae.connex.backend.ai;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * Instance-wide AI configuration, bound from {@code connex.ai.*} /
 * {@code CONNEX_AI_*}. The feature gate also requires a per-org configured and
 * enabled BYOP provider, so AI features fail closed.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.ai")
public class AiProperties {

    /**
     * Instance-level kill switch for all AI features. Defaults to false so a
     * deployment ships with AI dormant; the feature gate additionally requires a
     * per-org configured and enabled BYOP provider, so AI features fail closed.
     */
    private boolean enabled = false;

    /**
     * Optional per-feature switches. A feature is enabled when the master {@link #enabled} switch
     * is true and its entry is not explicitly false; an absent entry therefore defaults on while
     * the master switch still disables every feature.
     */
    private Map<AiFeature, Boolean> features = new EnumMap<>(AiFeature.class);

    /**
     * Whether organizations may point an OpenAI-compatible provider at private or internal
     * endpoint addresses. Defaults to false: on a shared multi-tenant deployment an org-supplied
     * internal endpoint is an SSRF vector, so the org-level {@code allowInternalEndpoint} flag is
     * honored only when the instance operator opts in (e.g. a self-hosted deployment running a
     * LAN inference server).
     */
    private boolean allowInternalEndpoints = false;

    /** Whether this deployment permits currently attested organizations to send unmasked data. */
    private boolean unmaskedModeEnabled = false;

    /**
     * Comma-separated RFC 6052 network-specific prefixes used by this deployment's IPv4/IPv6
     * translators. Prefixes are validated at startup and let the egress policy classify translated
     * destinations exactly, including private translations for explicitly enabled internal endpoints.
     */
    private String nat64Prefixes = "";

    /**
     * AI provider outbound TCP connect timeout in milliseconds. Kept short by default so AI
     * egress fails closed instead of pinning request threads on unreachable provider networks.
     */
    private long connectTimeoutMs = 3000;

    /**
     * AI provider outbound request timeout in milliseconds, covering response wait and body read.
     */
    private long requestTimeoutMs = 60000;

    /** Maximum provider-stream inactivity before the turn fails with a distinct terminal reason. */
    private Duration streamIdleTimeout = Duration.ofSeconds(30);

    /**
     * Maximum AI provider response body size in bytes. Oversized responses are rejected rather
     * than truncated so downstream JSON parsing never sees partial provider output.
     */
    private int maxResponseBytes = 2097152;

    /** Maximum concurrent AI requests that carry embedded media across the instance. */
    private int maxConcurrentMediaRequests = 2;

    /** Maximum concurrent embedded-media requests from one organization. */
    private int maxConcurrentMediaRequestsPerOrg = 1;

    /**
     * Shared estimated working-memory budget for embedded media, Base64/JSON expansion, request
     * bytes, and bounded provider responses.
     */
    private long maxMediaWorkingBytes = 67108864;

    /**
     * Maximum admitted cache-miss provider attempts per organization in one rolling window, per
     * JVM replica. Effective deployment-wide capacity multiplies across backend replicas.
     */
    private int invocationQuotaAttemptsPerOrg = 300;

    /** Rolling organization quota window for admitted cache-miss provider attempts. */
    private Duration invocationQuotaWindow = Duration.ofMinutes(10);

    /** Minimum interval between admitted forced refreshes for one cache identity. */
    private Duration invocationRefreshThrottle = Duration.ofSeconds(30);

    /** Maximum simultaneously live organization quota windows retained by one replica. */
    private int invocationQuotaMaxOrganizations = 10000;

    /** Maximum simultaneously live forced-refresh timestamps retained by one replica. */
    private int invocationRefreshMaxIdentities = 10000;

    /** Maximum active single-flight identities retained by one replica. */
    private int invocationMaxActiveFlights = 10000;

    /** Provider output-token cap for each Ask Connex model step. */
    @Min(1)
    private int assistantMaxOutputTokens = 16384;

    /** Whether Ask Connex requests provider reasoning for each model step. */
    private boolean assistantThinkingEnabled = true;

    /** Fixed worker count for request-detached AI generation. */
    private int generationWorkerThreads = 4;

    /** Maximum queued generation tasks beyond the fixed worker count. */
    private int generationQueueCapacity = 60;

    /** Maximum live accepted, running, and terminal polling handles. */
    private int generationMaxHandles = 2048;

    /** Maximum accepted or running tasks owned by one user in one workspace. */
    private int generationMaxActivePerUser = 4;

    /** Maximum live active and terminal handles owned by one user across workspaces. */
    private int generationMaxHandlesPerUser = 32;

    /** Maximum serialized result retained behind one polling handle. */
    private int generationMaxResultBytes = 4194304;

    /** Maximum serialized result bytes retained across all polling handles. */
    private long generationMaxRetainedResultBytes = 67108864;

    /** Maximum serialized result bytes reserved or retained by one workspace. */
    private long generationMaxRetainedResultBytesPerWorkspace = 33554432;

    /** Maximum serialized result bytes reserved or retained by one user across workspaces. */
    private long generationMaxRetainedResultBytesPerUser = 16777216;

    /** Hard non-sliding lifetime for one generation task. */
    private Duration generationMaxLifetime = Duration.ofSeconds(190);

    /** Fixed window in which the initiating client may poll a handle. */
    private Duration generationPollWindow = Duration.ofMinutes(2);

    /** Recommended client poll cadence and abandoned-handle cleanup cadence. */
    private Duration generationPollInterval = Duration.ofSeconds(2);

    /**
     * Deployment patches applied over {@link ooo.klae.connex.backend.ai.provider.AiModelCatalog}
     * declarations. Vendor limits and prices drift between Connex releases and an
     * OpenAI-compatible endpoint may serve an arbitrary model, so an operator can correct a
     * capability without waiting for a redeploy of the declared corpus.
     */
    @Valid
    private List<ModelOverride> modelOverrides = new ArrayList<>();

    /**
     * One operator-declared capability patch for an exact provider and model id.
     *
     * <p>Only the fields the operator sets are applied; every other capability keeps its declared
     * value. Matching is an exact, case-insensitive comparison against the model id as the owning
     * provider family normalizes it, because a substring override would silently capture models the
     * operator never named.
     */
    @Data
    public static class ModelOverride {

        /** Provider id this override applies to, matching the configured provider. */
        @NotBlank
        private String provider;

        /** Exact model id this override applies to. */
        @NotBlank
        private String modelId;

        /** Replacement context window in tokens. */
        @Min(1)
        private Integer contextWindowTokens;

        /** Replacement maximum generated output in tokens. */
        @Min(1)
        private Integer maxOutputTokens;

        /** Replacement text-input modality declaration. */
        private Boolean textInput;

        /** Replacement image-input modality declaration. */
        private Boolean imageInput;

        /**
         * Whether this endpoint accepts a streamed completion request.
         *
         * <p>An OpenAI-compatible endpoint may serve any model under any name, and several serve
         * one that does not accept the streamed request this client builds. There is no way to
         * discover that without asking the endpoint, so an operator declares it here after
         * verifying it; an undeclared endpoint is not streamed.
         *
         * <p>Only honoured together with {@link #endpoint}: the same model id served by two
         * gateways is two different answers to this question, and a deployment-wide declaration
         * would enable streaming for an endpoint nobody verified.
         */
        private Boolean streaming;

        /**
         * Whether this endpoint returns thought summaries when the request asks for them.
         *
         * <p>Verified by probe, never assumed: the Gemini OpenAI-compatibility layer rejects an
         * unknown request parameter outright, and an endpoint that accepts the parameter may still
         * return the thoughts somewhere this adapter does not read. An undeclared endpoint is
         * asked for nothing and parsed for nothing.
         *
         * <p>Only honoured together with {@link #endpoint}, on the same reasoning as
         * {@link #streaming}.
         */
        private Boolean thoughts;

        /**
         * Exact provider endpoint the {@link #streaming} and {@link #thoughts} declarations apply
         * to.
         *
         * <p>Scopes an endpoint-specific capability to the endpoint that was actually verified.
         * The token, modality, and pricing fields are properties of the model itself and ignore
         * it.
         */
        private String endpoint;

        /** Replacement PDF-document-input modality declaration. */
        private Boolean pdfInput;

        /** Replacement price per million input tokens. */
        private BigDecimal inputPricePerMTok;

        /** Replacement price per million output tokens. */
        private BigDecimal outputPricePerMTok;

        /** ISO 4217 currency for the replacement prices; defaults to the declared currency. */
        private String currency;

        /** Date the operator read the replacement rate card. */
        private LocalDate pricingAsOf;

        /**
         * Whether this override declares streaming for one exact configured endpoint.
         *
         * @param candidateProvider configured provider id
         * @param normalizedModelId family-normalized configured model id
         * @param candidateEndpoint configured provider endpoint
         * @return whether the declaration applies, or {@code null} when it says nothing
         */
        public Boolean streamingFor(
                String candidateProvider, String normalizedModelId, String candidateEndpoint) {
            return endpointScoped(streaming, candidateProvider, normalizedModelId,
                    candidateEndpoint);
        }

        /**
         * Whether this override declares thought summaries for one exact configured endpoint.
         *
         * @param candidateProvider configured provider id
         * @param normalizedModelId family-normalized configured model id
         * @param candidateEndpoint configured provider endpoint
         * @return whether the declaration applies, or {@code null} when it says nothing
         */
        public Boolean thoughtsFor(
                String candidateProvider, String normalizedModelId, String candidateEndpoint) {
            return endpointScoped(thoughts, candidateProvider, normalizedModelId,
                    candidateEndpoint);
        }

        private Boolean endpointScoped(
                Boolean declared,
                String candidateProvider,
                String normalizedModelId,
                String candidateEndpoint) {
            if (declared == null || endpoint == null || candidateEndpoint == null
                    || !matches(candidateProvider, normalizedModelId)) {
                return null;
            }
            return endpoint.trim().equals(candidateEndpoint.trim())
                    ? declared
                    : null;
        }

        /**
         * Returns whether this override applies to a normalized model id on a provider.
         *
         * @param candidateProvider configured provider id
         * @param normalizedModelId model id normalized by the owning provider family
         * @return true when both the provider and the model id match exactly
         */
        public boolean matches(String candidateProvider, String normalizedModelId) {
            if (provider == null || modelId == null
                    || candidateProvider == null || normalizedModelId == null) {
                return false;
            }
            return provider.trim().equalsIgnoreCase(candidateProvider)
                    && modelId.trim().toLowerCase(Locale.ROOT).equals(normalizedModelId);
        }
    }

    /**
     * Returns whether the master switch and the selected feature switch permit the feature.
     * @param feature feature to evaluate
     * @return true unless the master switch is off or the feature is explicitly disabled
     */
    public boolean isFeatureEnabled(AiFeature feature) {
        return enabled && feature != null
                && (features == null || !Boolean.FALSE.equals(features.get(feature)));
    }
}
