package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Private S3-compatible object storage using the operator's default AWS credential chain.
 */
@Component
@ConditionalOnProperty(prefix = "connex.object-storage", name = "provider", havingValue = "s3")
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {
    private final ObjectStorageProperties properties;
    private final S3Client client;
    private final String readinessProbeKey =
        "connex-readiness/" + UUID.randomUUID() + ".probe";

    public S3ObjectStorage(ObjectStorageProperties properties, Environment environment) {
        this(properties, buildClient(properties, environment));
    }

    S3ObjectStorage(ObjectStorageProperties properties, S3Client client) {
        this.properties = properties;
        this.client = client;
    }

    private static S3Client buildClient(
            ObjectStorageProperties properties,
            Environment environment) {
        ObjectStorageTransportStartupValidator.validate(properties, environment);
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(properties.getS3().getRegion()))
            .forcePathStyle(properties.getS3().isPathStyle())
            .overrideConfiguration(configuration -> configuration
                .apiCallTimeout(properties.getS3().getApiCallTimeout())
                .apiCallAttemptTimeout(properties.getS3().getApiCallAttemptTimeout()));
        URI endpoint = properties.s3EndpointUri();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    @Override
    public void put(String key, UploadSource source, String contentType, byte[] sha256) {
        String validKey = ObjectStorageKey.requireValid(key);
        requireUnversionedBucket();
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(properties.getS3().getBucket())
            .key(validKey)
            .contentLength(source.contentLength())
            .contentType(contentType)
            .checksumSHA256(Base64.getEncoder().encodeToString(sha256))
            .build();
        try (var input = source.openStream()) {
            client.putObject(request, RequestBody.fromInputStream(input, source.contentLength()));
        } catch (Exception exception) {
            throw new ObjectStorageException("S3 object write failed", exception);
        }
    }

    @Override
    public StoredObject get(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            ResponseInputStream<GetObjectResponse> response = client.getObject(GetObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(validKey)
                .build());
            return new StoredObject(response, response.response().contentLength());
        } catch (NoSuchKeyException exception) {
            throw new ObjectStorageNotFoundException("Managed object was not found", exception);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ObjectStorageNotFoundException("Managed object was not found", exception);
            }
            throw new ObjectStorageException("S3 object read failed", exception);
        } catch (SdkException exception) {
            throw new ObjectStorageException("S3 object read failed", exception);
        }
    }

    @Override
    public void delete(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        requireUnversionedBucket();
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(validKey)
                .build());
        } catch (SdkException exception) {
            throw new ObjectStorageException("S3 object deletion failed", exception);
        }
    }

    @Override
    public boolean isReady() {
        boolean probeWritten = false;
        try {
            client.headBucket(HeadBucketRequest.builder()
                .bucket(properties.getS3().getBucket())
                .build());
            requireUnversionedBucket();
            byte[] content = readinessProbeKey.getBytes(StandardCharsets.UTF_8);
            put(
                readinessProbeKey,
                UploadSource.from("readiness.probe", "application/octet-stream", content),
                "application/octet-stream",
                sha256(content));
            probeWritten = true;
            boolean matches;
            try (StoredObject stored = get(readinessProbeKey);
                    DigestInputStream input = new DigestInputStream(
                        stored.inputStream(), sha256Digest())) {
                long copied = input.transferTo(OutputStream.nullOutputStream());
                matches = stored.contentLength() == content.length
                    && copied == content.length
                    && MessageDigest.isEqual(sha256(content), input.getMessageDigest().digest());
            }
            if (!matches) {
                return false;
            }
            delete(readinessProbeKey);
            probeWritten = false;
            return true;
        } catch (IOException | ObjectStorageException | SdkException exception) {
            return false;
        } finally {
            if (probeWritten) {
                deleteProbeBestEffort();
            }
        }
    }

    private void deleteProbeBestEffort() {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(readinessProbeKey)
                .build());
        } catch (SdkException exception) {
            return;
        }
    }

    private static byte[] sha256(byte[] content) {
        return sha256Digest().digest(content);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireUnversionedBucket() {
        try {
            var response = client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(properties.getS3().getBucket())
                .build());
            if (response.status() != null) {
                throw new ObjectStorageException(
                    "S3 bucket versioning must never have been enabled for managed objects");
            }
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (SdkException exception) {
            throw new ObjectStorageException("S3 bucket versioning could not be verified", exception);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
