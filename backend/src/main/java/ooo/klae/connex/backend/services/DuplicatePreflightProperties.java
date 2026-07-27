package ooo.klae.connex.backend.services;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounded admission settings for duplicate probing.
 */
@Component
@ConfigurationProperties(prefix = "connex.duplicate-preflight")
public class DuplicatePreflightProperties {
    private int maxRequestsPerMinute = 60;
    private int maxGlobalRequestsPerMinute = 1000;
    private int maxRateLimitKeys = 10_000;

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    public int getMaxGlobalRequestsPerMinute() {
        return maxGlobalRequestsPerMinute;
    }

    public void setMaxGlobalRequestsPerMinute(int maxGlobalRequestsPerMinute) {
        this.maxGlobalRequestsPerMinute = maxGlobalRequestsPerMinute;
    }

    public int getMaxRateLimitKeys() {
        return maxRateLimitKeys;
    }

    public void setMaxRateLimitKeys(int maxRateLimitKeys) {
        this.maxRateLimitKeys = maxRateLimitKeys;
    }

    @PostConstruct
    void validate() {
        if (maxRequestsPerMinute < 1
                || maxGlobalRequestsPerMinute < maxRequestsPerMinute
                || maxRateLimitKeys < 1) {
            throw new IllegalArgumentException("Duplicate-preflight rate limits are invalid");
        }
    }
}
