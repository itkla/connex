package ooo.klae.connex.backend.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Min(1)
    @Max(104_857_600)
    private long maxUploadBytes = 25L * 1024L * 1024L;

    @Min(1)
    private long maxImagePixels = 40_000_000L;

    @Min(1)
    @Max(32)
    private int maxConcurrentImageDecodes = 2;

    @Min(1)
    private long maxWorkspaceBytes = 10L * 1024L * 1024L * 1024L;

    @Min(1)
    @Max(1_000_000)
    private int maxWorkspaceObjects = 10_000;

    @Min(1)
    @Max(100_000)
    private int deleteRetryWarningEntries = 1_000;

    @Min(1)
    @Max(10_000)
    private int deleteRetryBatchSize = 100;

    @Min(1_000)
    @Max(86_400_000)
    private long deleteRetryDelayMs = 60_000;

    @Min(1_000)
    @Max(300_000)
    private long readinessCacheTtlMs = 30_000;

    @Valid
    private S3 s3 = new S3();

    public enum Provider {
        FILESYSTEM,
        S3
    }

    @Data
    public static class S3 {
        private String bucket;
        private String region;
        private String endpoint;
        private boolean pathStyle;
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
            || (filesystemRoot != null && !filesystemRoot.isBlank());
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
}
