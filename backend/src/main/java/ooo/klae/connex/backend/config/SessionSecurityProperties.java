package ooo.klae.connex.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Session lifetime and recent-authentication windows for account-sensitive operations.
 */
@Component
@ConfigurationProperties(prefix = "connex.security.session")
public class SessionSecurityProperties {
    private Duration absoluteTimeout = Duration.ofHours(12);
    private Duration recentAuthenticationWindow = Duration.ofMinutes(10);

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    public Duration getRecentAuthenticationWindow() {
        return recentAuthenticationWindow;
    }

    public void setRecentAuthenticationWindow(Duration recentAuthenticationWindow) {
        this.recentAuthenticationWindow = recentAuthenticationWindow;
    }
}
