package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {
    @Mock S3Client client;

    private ObjectStorageProperties properties;
    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setProvider(ObjectStorageProperties.Provider.S3);
        properties.getS3().setBucket("private-objects");
        properties.getS3().setRegion("us-east-1");
        storage = new S3ObjectStorage(properties, client);
    }

    @Test
    void readinessVerifiesWriteReadIntegrityAndDeletePermissions() {
        readyBucket();
        Map<String, byte[]> probe = captureProbe(false);

        assertTrue(storage.isReady());

        verifyProbeLifecycle(probe);
    }

    @Test
    void readinessFailsAndCleansUpWhenReadBytesAreCorruptedWithoutChangingLength() {
        readyBucket();
        Map<String, byte[]> probe = captureProbe(true);

        assertFalse(storage.isReady());

        verifyProbeLifecycle(probe);
    }

    @Test
    void rejectsEnabledVersioningBeforeWriting() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder()
                .status(BucketVersioningStatus.ENABLED)
                .build());

        assertThrows(ObjectStorageException.class, () -> storage.put(
            "workspaces/1/attachments/object.pdf",
            UploadSource.from("object.pdf", "application/pdf", new byte[] {1}),
            "application/pdf",
            new byte[32]));

        verify(client, never()).putObject(
            any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void suspendedVersioningIsStillNotReady() {
        when(client.headBucket(any(HeadBucketRequest.class)))
            .thenReturn(HeadBucketResponse.builder().build());
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder()
                .status(BucketVersioningStatus.SUSPENDED)
                .build());

        assertFalse(storage.isReady());
    }

    @Test
    void readinessFailsWhenWritePermissionIsDenied() {
        readyBucket();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

        assertFalse(storage.isReady());

        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test
    void readinessFailsAndCleansUpWhenReadPermissionIsDenied() {
        readyBucket();
        when(client.getObject(any(GetObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

        assertFalse(storage.isReady());

        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void readinessFailsWhenDeletePermissionIsDenied() {
        readyBucket();
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            return response(request.key().getBytes(StandardCharsets.UTF_8));
        });
        when(client.deleteObject(any(DeleteObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());

        assertFalse(storage.isReady());

        verify(client, atLeast(2)).deleteObject(any(DeleteObjectRequest.class));
    }

    private Map<String, byte[]> captureProbe(boolean corruptRead) {
        Map<String, byte[]> probe = new HashMap<>();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenAnswer(invocation -> {
                PutObjectRequest request = invocation.getArgument(0);
                RequestBody body = invocation.getArgument(1);
                try (InputStream input = body.contentStreamProvider().newStream()) {
                    probe.put(request.key(), input.readAllBytes());
                }
                return PutObjectResponse.builder().build();
            });
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            byte[] uploaded = probe.get(request.key());
            assertNotNull(uploaded);
            byte[] returned = uploaded.clone();
            if (corruptRead) {
                returned[0] ^= 1;
            }
            return response(returned);
        });
        return probe;
    }

    private void verifyProbeLifecycle(Map<String, byte[]> probe) {
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).putObject(put.capture(), any(RequestBody.class));
        verify(client).getObject(get.capture());
        verify(client).deleteObject(delete.capture());
        assertEquals(1, probe.size());
        assertEquals("private-objects", put.getValue().bucket());
        assertEquals(put.getValue().bucket(), get.getValue().bucket());
        assertEquals(put.getValue().bucket(), delete.getValue().bucket());
        assertEquals(put.getValue().key(), get.getValue().key());
        assertEquals(put.getValue().key(), delete.getValue().key());
    }

    private void readyBucket() {
        when(client.headBucket(any(HeadBucketRequest.class)))
            .thenReturn(HeadBucketResponse.builder().build());
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder().build());
    }

    private static ResponseInputStream<GetObjectResponse> response(byte[] content) {
        return new ResponseInputStream<>(
            GetObjectResponse.builder().contentLength((long) content.length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(content)));
    }
}
