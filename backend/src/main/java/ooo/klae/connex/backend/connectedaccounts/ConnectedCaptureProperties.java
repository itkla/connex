package ooo.klae.connex.backend.connectedaccounts;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Fail-closed operator authorization and runtime bounds for connected capture.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.connected-capture")
public class ConnectedCaptureProperties {
    private boolean schedulingEnabled;
    private Provider google = new Provider();
    private Provider microsoft = new Provider();
    private int pageSize = 100;
    private int schedulerBatchSize = 50;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration requestTimeout = Duration.ofSeconds(20);
    private Duration syncInterval = Duration.ofMinutes(15);
    private Duration retryBase = Duration.ofMinutes(1);
    private int interventionFailureCount = 8;

    /** One provider's separate ingestion authorization. */
    @Data
    public static class Provider {
        private boolean enabled;
    }

    /** Whether ingestion is authorized for the named provider. */
    public boolean isProviderEnabled(String provider) {
        return switch (provider) {
            case ConnectedAccountProviders.GOOGLE -> google.isEnabled();
            case ConnectedAccountProviders.MICROSOFT -> microsoft.isEnabled();
            default -> false;
        };
    }

    /** Whether both scheduling and provider-specific ingestion are authorized. */
    public boolean isCaptureEnabled(String provider) {
        return schedulingEnabled && isProviderEnabled(provider);
    }
}
