package ooo.klae.connex.backend.storage;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

import ooo.klae.connex.backend.storage.ObjectStorageProperties.Provider;

/**
 * Immutable coordinates of the private object-storage backend selected for an installation.
 *
 * @param provider configured storage provider
 * @param filesystemRoot normalized absolute filesystem root for filesystem storage
 * @param s3Bucket normalized S3 bucket for S3 storage
 * @param s3Region normalized S3 region for S3 storage
 * @param s3Endpoint normalized optional S3-compatible endpoint
 * @param s3PathStyle S3 path-style addressing mode
 */
public record ObjectStorageBackendIdentity(
        Provider provider,
        String filesystemRoot,
        String s3Bucket,
        String s3Region,
        String s3Endpoint,
        Boolean s3PathStyle) {

    public ObjectStorageBackendIdentity {
        Objects.requireNonNull(provider, "provider");
        if (provider == Provider.FILESYSTEM) {
            requireText(filesystemRoot, "filesystemRoot");
            requireNull(s3Bucket, "s3Bucket");
            requireNull(s3Region, "s3Region");
            requireNull(s3Endpoint, "s3Endpoint");
            requireNull(s3PathStyle, "s3PathStyle");
        } else {
            requireNull(filesystemRoot, "filesystemRoot");
            requireText(s3Bucket, "s3Bucket");
            requireText(s3Region, "s3Region");
            Objects.requireNonNull(s3PathStyle, "s3PathStyle");
        }
    }

    /**
     * Builds the canonical identity represented by the current instance configuration.
     *
     * @param properties validated object-storage properties
     * @return normalized immutable backend coordinates
     */
    public static ObjectStorageBackendIdentity configured(ObjectStorageProperties properties) {
        Objects.requireNonNull(properties, "properties");
        if (properties.getProvider() == Provider.FILESYSTEM) {
            return new ObjectStorageBackendIdentity(
                Provider.FILESYSTEM,
                properties.filesystemRootPath().toString(),
                null,
                null,
                null,
                null);
        }
        return new ObjectStorageBackendIdentity(
            Provider.S3,
            null,
            properties.getS3().getBucket().trim().toLowerCase(Locale.ROOT),
            properties.getS3().getRegion().trim().toLowerCase(Locale.ROOT),
            normalizedEndpoint(properties.s3EndpointUri()),
            properties.getS3().isPathStyle());
    }

    private static String normalizedEndpoint(URI endpoint) {
        if (endpoint == null) {
            return null;
        }
        URI normalized = endpoint.normalize();
        String scheme = normalized.getScheme().toLowerCase(Locale.ROOT);
        String host = IDN.toASCII(normalized.getHost()).toLowerCase(Locale.ROOT);
        int port = normalized.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        String path = normalized.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = null;
        } else {
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        try {
            return new URI(scheme, null, host, port, path, null, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("S3 endpoint could not be normalized", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be present");
        }
    }

    private static void requireNull(Object value, String name) {
        if (value != null) {
            throw new IllegalArgumentException(name + " is not valid for the configured provider");
        }
    }
}
