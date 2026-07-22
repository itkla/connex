package ooo.klae.connex.backend.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.springframework.web.multipart.MultipartFile;

/**
 * Repeatable source for a bounded binary upload.
 *
 * @param fileName untrusted client file name
 * @param contentType untrusted client media type
 * @param contentLength known byte length
 * @param streamFactory repeatable stream factory
 */
public record UploadSource(
        String fileName,
        String contentType,
        long contentLength,
        StreamFactory streamFactory) {

    public UploadSource {
        Objects.requireNonNull(streamFactory, "streamFactory");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must not be negative");
        }
    }

    public InputStream openStream() throws IOException {
        InputStream input = streamFactory.open();
        if (input == null) {
            throw new IOException("Upload stream is unavailable");
        }
        return input;
    }

    public static UploadSource from(MultipartFile file) {
        Objects.requireNonNull(file, "file");
        return new UploadSource(
            file.getOriginalFilename(),
            file.getContentType(),
            file.getSize(),
            file::getInputStream
        );
    }

    public static UploadSource from(String fileName, String contentType, byte[] bytes) {
        byte[] value = Objects.requireNonNull(bytes, "bytes").clone();
        return new UploadSource(
            fileName,
            contentType,
            value.length,
            () -> new ByteArrayInputStream(value)
        );
    }

    /**
     * Opens a fresh stream for each validation or storage pass.
     */
    @FunctionalInterface
    public interface StreamFactory {
        InputStream open() throws IOException;
    }
}
