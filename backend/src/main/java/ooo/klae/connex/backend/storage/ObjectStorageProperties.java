package ooo.klae.connex.backend.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Instance configuration for private filesystem or S3-compatible object storage.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.object-storage")
public class ObjectStorageProperties {
    private Provider provider = Provider.FILESYSTEM;
    private String filesystemRoot = Path.of(System.getProperty("user.home"), ".connex", "object-storage").toString();

    @Min(0)
    private long filesystemMinFreeBytes = 1024L * 1024L * 1024L;

    @NotNull
    private Duration filesystemTempRetention = Duration.ofHours(1);

    @Min(1_000)
    @Max(86_400_000)
    private long filesystemTempCleanupDelayMs = 60_000;

    @Min(1)
    @Max(104_857_600)
    private long maxUploadBytes = 25L * 1024L * 1024L;

    @Min(1)
    private long maxImagePixels = 40_000_000L;

    @Min(1)
    @Max(2_147_483_647)
    private long maxImageWorkingBytes = 256L * 1024L * 1024L;

    @Min(1)
    @Max(32)
    private int maxConcurrentImageDecodes = 2;

    @Min(1)
    @Max(64)
    private int maxConcurrentWrites = 4;

    @Min(1)
    @Max(256)
    private int maxConcurrentReads = 32;

    @Min(1)
    @Max(64)
    private int maxConcurrentReadsPerUser = 4;

    @Min(1_000)
    @Max(300_000)
    private long readTimeoutMs = 30_000;

    @Min(1)
    private long maxWorkspaceBytes = 10L * 1024L * 1024L * 1024L;

    @Min(1)
    @Max(1_000_000)
    private int maxWorkspaceObjects = 10_000;

    @Min(1)
    @Max(100_000)
    private int deleteRetryWarningEntries = 1_000;

    @Min(1)
    @Max(100_000)
    private int maxPendingTenantAmbiguousWriteCleanups = 100;

    @Min(1)
    @Max(100)
    private int maxPendingUserImageDeletions = 2;

    @Min(1)
    @Max(1_000)
    private int maxUserImageReplacementsPerHour = 12;

    @Min(1)
    @Max(100_000)
    private int userImageRateLimitMaxKeys = 10_000;

    @Min(1)
    @Max(10_000)
    private int deleteRetryBatchSize = 100;

    @Min(1_000)
    @Max(86_400_000)
    private long deleteRetryDelayMs = 60_000;

    @Min(1_000)
    @Max(86_400_000)
    private long ambiguousWriteCleanupDelayMs = 60_000;

    @Min(1_000)
    @Max(300_000)
    private long readinessCacheTtlMs = 30_000;

    @Valid
    private S3 s3 = new S3();

    @Valid
    private LegacyMigration legacyMigration = new LegacyMigration();

    public enum Provider {
        FILESYSTEM,
        S3
    }

    public enum LegacyMigrationMode {
        OFF,
        DRY_RUN,
        MIGRATE
    }

    @Data
    public static class S3 {
        private String bucket;
        private String region;
        private String endpoint;
        private boolean pathStyle;

        @NotNull
        private Duration apiCallTimeout = Duration.ofSeconds(15);

        @NotNull
        private Duration apiCallAttemptTimeout = Duration.ofSeconds(5);

        @AssertTrue(message = "S3 API timeouts must be positive and the attempt timeout must not exceed the call timeout")
        public boolean isTimeoutConfigurationValid() {
            return apiCallTimeout != null
                && apiCallAttemptTimeout != null
                && !apiCallTimeout.isZero()
                && !apiCallTimeout.isNegative()
                && !apiCallAttemptTimeout.isZero()
                && !apiCallAttemptTimeout.isNegative()
                && apiCallAttemptTimeout.compareTo(apiCallTimeout) <= 0;
        }
    }

    @Data
    public static class LegacyMigration {
        public static final String APPLY_CONFIRMATION = "MIGRATE_LEGACY_UPLOADS";

        @NotNull
        private LegacyMigrationMode mode = LegacyMigrationMode.OFF;

        private String uploadsRoot = "";

        private String applyConfirmation = "";

        @Min(1)
        @Max(10_000)
        private int batchSize = 100;

        @AssertTrue(message = "legacy upload migration requires its source root and explicit apply confirmation")
        public boolean isConfigurationValid() {
            if (mode == LegacyMigrationMode.OFF) {
                return true;
            }
            return uploadsRoot != null
                && !uploadsRoot.isBlank()
                && (mode != LegacyMigrationMode.MIGRATE
                    || APPLY_CONFIRMATION.equals(applyConfirmation));
        }

        public Path uploadsRootPath() {
            if (uploadsRoot == null || uploadsRoot.isBlank()) {
                throw new IllegalStateException("Legacy upload migration root is unavailable");
            }
            return Path.of(uploadsRoot).toAbsolutePath().normalize();
        }
    }

    public Path filesystemRootPath() {
        return Path.of(filesystemRoot).toAbsolutePath().normalize();
    }

    public URI s3EndpointUri() {
        if (s3.endpoint == null || s3.endpoint.isBlank()) {
            return null;
        }
        try {
            return new URI(s3.endpoint.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("connex.object-storage.s3.endpoint is invalid", exception);
        }
    }

    @AssertTrue(message = "filesystem object storage requires a root directory")
    public boolean isFilesystemConfigurationValid() {
        return provider != Provider.FILESYSTEM
            || (filesystemRoot != null
                && !filesystemRoot.isBlank()
                && filesystemTempRetention != null
                && !filesystemTempRetention.isZero()
                && !filesystemTempRetention.isNegative());
    }

    @AssertTrue(message = "per-user object reads must not exceed the global read limit")
    public boolean isReadConcurrencyConfigurationValid() {
        return maxConcurrentReadsPerUser <= maxConcurrentReads;
    }

    @AssertTrue(message = "S3 object storage requires a bucket, region, and valid optional endpoint")
    public boolean isS3ConfigurationValid() {
        if (provider != Provider.S3) {
            return true;
        }
        if (s3.bucket == null || s3.bucket.isBlank() || s3.region == null || s3.region.isBlank()) {
            return false;
        }
        URI endpoint;
        try {
            endpoint = s3EndpointUri();
        } catch (IllegalStateException exception) {
            return false;
        }
        return endpoint == null
            || (("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                && endpoint.getHost() != null
                && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null
                && endpoint.getFragment() == null);
    }

    @AssertTrue(message = "ambiguous S3 write cleanup delay must exceed the total API-call timeout")
    public boolean isAmbiguousWriteCleanupDelayValid() {
        return provider != Provider.S3
            || (s3 != null
                && s3.apiCallTimeout != null
                && Duration.ofMillis(ambiguousWriteCleanupDelayMs).compareTo(s3.apiCallTimeout) > 0);
    }
}
