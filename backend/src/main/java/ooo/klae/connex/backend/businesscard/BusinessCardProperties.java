package ooo.klae.connex.backend.businesscard;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration for local business-card OCR and image safety bounds.
 */
@Component
@ConfigurationProperties(prefix = "connex.business-cards")
public class BusinessCardProperties {
    private boolean enabled;
    private URI ocrBaseUrl;
    private String ocrServiceToken = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Duration readinessCache = Duration.ofSeconds(5);
    private int maxResponseBytes = 1_048_576;
    private long maxImageBytes = 8_388_608;
    private int maxWidth = 8_192;
    private int maxHeight = 8_192;
    private long maxPixels = 24_000_000;
    private int maxScansPerMinute = 12;
    private int maxImportsPerMinute = 12;
    private int rateLimitMaxKeys = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getOcrBaseUrl() {
        return ocrBaseUrl;
    }

    public void setOcrBaseUrl(URI ocrBaseUrl) {
        this.ocrBaseUrl = ocrBaseUrl;
    }

    public String getOcrServiceToken() {
        return ocrServiceToken;
    }

    public void setOcrServiceToken(String ocrServiceToken) {
        this.ocrServiceToken = ocrServiceToken == null ? "" : ocrServiceToken;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getReadinessCache() {
        return readinessCache;
    }

    public void setReadinessCache(Duration readinessCache) {
        this.readinessCache = readinessCache;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public int getMaxWidth() {
        return maxWidth;
    }

    public void setMaxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    public long getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    public int getMaxScansPerMinute() {
        return maxScansPerMinute;
    }

    public void setMaxScansPerMinute(int maxScansPerMinute) {
        this.maxScansPerMinute = positive(maxScansPerMinute, "maxScansPerMinute");
    }

    public int getMaxImportsPerMinute() {
        return maxImportsPerMinute;
    }

    public void setMaxImportsPerMinute(int maxImportsPerMinute) {
        this.maxImportsPerMinute = positive(maxImportsPerMinute, "maxImportsPerMinute");
    }

    public int getRateLimitMaxKeys() {
        return rateLimitMaxKeys;
    }

    public void setRateLimitMaxKeys(int rateLimitMaxKeys) {
        this.rateLimitMaxKeys = positive(rateLimitMaxKeys, "rateLimitMaxKeys");
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
