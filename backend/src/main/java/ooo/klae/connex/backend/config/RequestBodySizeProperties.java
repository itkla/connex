package ooo.klae.connex.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Byte limits for API request bodies read by Spring MVC.
 */
@Component
@ConfigurationProperties(prefix = "connex.request-limits")
public class RequestBodySizeProperties {
    private long maxBodyBytes = 10L * 1024L * 1024L;
    private long webauthnMaxBodyBytes = 64L * 1024L;

    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public long getWebauthnMaxBodyBytes() {
        return webauthnMaxBodyBytes;
    }

    public void setWebauthnMaxBodyBytes(long webauthnMaxBodyBytes) {
        this.webauthnMaxBodyBytes = webauthnMaxBodyBytes;
    }
}
