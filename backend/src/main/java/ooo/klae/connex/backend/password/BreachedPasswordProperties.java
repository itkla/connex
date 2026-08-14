package ooo.klae.connex.backend.password;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Non-disableable breached-password source configuration.
 */
@Component
@ConfigurationProperties(prefix = "connex.security.breached-passwords")
public class BreachedPasswordProperties {
    private String source = "REMOTE";
    private String offlineFile = "";
    private String offlineSha256 = "";

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getOfflineFile() {
        return offlineFile;
    }

    public void setOfflineFile(String offlineFile) {
        this.offlineFile = offlineFile;
    }

    public String getOfflineSha256() {
        return offlineSha256;
    }

    public void setOfflineSha256(String offlineSha256) {
        this.offlineSha256 = offlineSha256;
    }

    public BreachedPasswordSourceType sourceType() {
        return BreachedPasswordSourceType.parse(source);
    }
}
