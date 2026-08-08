package ooo.klae.connex.backend.ai;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Instance-wide AI configuration, bound from {@code connex.ai.*} /
 * {@code CONNEX_AI_*}. The feature gate also requires a per-org configured and
 * enabled BYOP provider, so AI features fail closed.
 */
@Data
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
    private Duration generationMaxLifetime = Duration.ofSeconds(75);

    /** Fixed window in which the initiating client may poll a handle. */
    private Duration generationPollWindow = Duration.ofMinutes(2);

    /** Recommended client poll cadence and abandoned-handle cleanup cadence. */
    private Duration generationPollInterval = Duration.ofSeconds(2);

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
