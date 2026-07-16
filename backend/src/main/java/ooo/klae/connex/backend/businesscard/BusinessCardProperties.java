package ooo.klae.connex.backend.businesscard;

import java.net.URI;
import java.time.Duration;

import jakarta.annotation.PostConstruct;

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
    private String plainHttpPrivateHost = "";
    private String ocrServiceToken = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Duration readinessCache = Duration.ofSeconds(5);
    private int maxResponseBytes = 1_048_576;
    private long maxImageBytes = 8_388_608;
    private int maxWidth = 8_192;
    private int maxHeight = 8_192;
    private long maxPixels = 24_000_000;
    private int maxScansPerMinute = 3;
    private int maxGlobalScansPerMinute = 5;
    private int maxImportsPerMinute = 12;
    private int rateLimitMaxKeys = 10_000;
    private Duration idempotencyRetention = Duration.ofHours(24);
    private Duration reservationLease = Duration.ofMinutes(2);
    private int maxOutstandingReservations = 4;
    private int idempotencyCleanupBatchSize = 1_000;

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

    public String getPlainHttpPrivateHost() {
        return plainHttpPrivateHost;
    }

    public void setPlainHttpPrivateHost(String plainHttpPrivateHost) {
        this.plainHttpPrivateHost = plainHttpPrivateHost == null ? "" : plainHttpPrivateHost.trim();
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
        this.connectTimeout = positive(connectTimeout, "connectTimeout");
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
    }

    public Duration getReadinessCache() {
        return readinessCache;
    }

    public void setReadinessCache(Duration readinessCache) {
        this.readinessCache = positive(readinessCache, "readinessCache");
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = positive(maxResponseBytes, "maxResponseBytes");
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = positive(maxImageBytes, "maxImageBytes");
    }

    public int getMaxWidth() {
        return maxWidth;
    }

    public void setMaxWidth(int maxWidth) {
        this.maxWidth = positive(maxWidth, "maxWidth");
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = positive(maxHeight, "maxHeight");
    }

    public long getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(long maxPixels) {
        this.maxPixels = positive(maxPixels, "maxPixels");
    }

    public int getMaxScansPerMinute() {
        return maxScansPerMinute;
    }

    public void setMaxScansPerMinute(int maxScansPerMinute) {
        this.maxScansPerMinute = positive(maxScansPerMinute, "maxScansPerMinute");
    }

    public int getMaxGlobalScansPerMinute() {
        return maxGlobalScansPerMinute;
    }

    public void setMaxGlobalScansPerMinute(int maxGlobalScansPerMinute) {
        this.maxGlobalScansPerMinute = positive(
                maxGlobalScansPerMinute, "maxGlobalScansPerMinute");
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

    public Duration getIdempotencyRetention() {
        return idempotencyRetention;
    }

    public void setIdempotencyRetention(Duration idempotencyRetention) {
        this.idempotencyRetention = positive(idempotencyRetention, "idempotencyRetention");
    }

    public Duration getReservationLease() {
        return reservationLease;
    }

    public void setReservationLease(Duration reservationLease) {
        this.reservationLease = positive(reservationLease, "reservationLease");
    }

    public int getMaxOutstandingReservations() {
        return maxOutstandingReservations;
    }

    public void setMaxOutstandingReservations(int maxOutstandingReservations) {
        if (maxOutstandingReservations <= 0 || maxOutstandingReservations > 32) {
            throw new IllegalArgumentException(
                    "maxOutstandingReservations must be between 1 and 32");
        }
        this.maxOutstandingReservations = maxOutstandingReservations;
    }

    public int getIdempotencyCleanupBatchSize() {
        return idempotencyCleanupBatchSize;
    }

    public void setIdempotencyCleanupBatchSize(int idempotencyCleanupBatchSize) {
        this.idempotencyCleanupBatchSize = positive(
                idempotencyCleanupBatchSize, "idempotencyCleanupBatchSize");
    }

    @PostConstruct
    void validateRetentionWindows() {
        if (reservationLease.compareTo(idempotencyRetention) >= 0) {
            throw new IllegalArgumentException(
                    "reservationLease must be shorter than idempotencyRetention");
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
