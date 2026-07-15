package ooo.klae.connex.backend.storage;

import java.net.URI;
import java.util.Base64;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
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

    public S3ObjectStorage(ObjectStorageProperties properties) {
        this.properties = properties;
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(properties.getS3().getRegion()))
            .forcePathStyle(properties.getS3().isPathStyle());
        URI endpoint = properties.s3EndpointUri();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        client = builder.build();
    }

    @Override
    public void put(String key, UploadSource source, String contentType, byte[] sha256) {
        String validKey = ObjectStorageKey.requireValid(key);
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
        try {
            client.deleteObject(request -> request
                .bucket(properties.getS3().getBucket())
                .key(validKey));
        } catch (SdkException exception) {
            throw new ObjectStorageException("S3 object deletion failed", exception);
        }
    }

    @Override
    public boolean isReady() {
        try {
            client.headBucket(request -> request.bucket(properties.getS3().getBucket()));
            return true;
        } catch (SdkException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
