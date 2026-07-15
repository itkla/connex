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
    private long importMaxBodyBytes = 64L * 1024L * 1024L;
    private long uploadMaxBodyBytes = 27L * 1024L * 1024L;
    private long webauthnMaxBodyBytes = 64L * 1024L;
    private long formMaxBodyBytes = 1L * 1024L * 1024L;

    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public long getImportMaxBodyBytes() {
        return importMaxBodyBytes;
    }

    public void setImportMaxBodyBytes(long importMaxBodyBytes) {
        this.importMaxBodyBytes = importMaxBodyBytes;
    }

    public long getWebauthnMaxBodyBytes() {
        return webauthnMaxBodyBytes;
    }

    public long getUploadMaxBodyBytes() {
        return uploadMaxBodyBytes;
    }

    public void setUploadMaxBodyBytes(long uploadMaxBodyBytes) {
        this.uploadMaxBodyBytes = uploadMaxBodyBytes;
    }

    public void setWebauthnMaxBodyBytes(long webauthnMaxBodyBytes) {
        this.webauthnMaxBodyBytes = webauthnMaxBodyBytes;
    }

    public long getFormMaxBodyBytes() {
        return formMaxBodyBytes;
    }

    public void setFormMaxBodyBytes(long formMaxBodyBytes) {
        this.formMaxBodyBytes = formMaxBodyBytes;
    }

    public long getLargestBodyLimit() {
        return Math.max(Math.max(maxBodyBytes, importMaxBodyBytes),
            Math.max(uploadMaxBodyBytes, webauthnMaxBodyBytes));
    }
}
