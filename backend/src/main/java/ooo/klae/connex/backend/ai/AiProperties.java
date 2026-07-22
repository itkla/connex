package ooo.klae.connex.backend.ai;

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
}
