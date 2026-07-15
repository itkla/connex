package ooo.klae.connex.backend.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

/**
 * Fully decodes bounded, single-frame managed raster image uploads.
 */
@Component
public class ImageUploadValidator {
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> GENERIC_CONTENT_TYPES = Set.of("", "application/octet-stream");

    private final ObjectStorageProperties properties;
    private final UploadPolicy uploadPolicy;
    private final Semaphore decodePermits;

    public ImageUploadValidator(ObjectStorageProperties properties, UploadPolicy uploadPolicy) {
        this.properties = properties;
        this.uploadPolicy = uploadPolicy;
        this.decodePermits = new Semaphore(properties.getMaxConcurrentImageDecodes(), true);
        ImageIO.setUseCache(false);
    }

    public ValidatedImage validate(UploadSource source) {
        uploadPolicy.validateLength(source.contentLength());
        byte[] bytes = read(source);
        ImageFormat format = detect(bytes);
        validateDeclaredContentType(source.contentType(), format.contentType());
        if (!decodePermits.tryAcquire()) {
            throw new ServiceUnavailableException("Image validation is busy; retry shortly");
        }
        try {
            decode(bytes);
        } finally {
            decodePermits.release();
        }
        return new ValidatedImage(bytes, format.contentType(), format.extension());
    }

    private byte[] read(UploadSource source) {
        int expected = Math.toIntExact(source.contentLength());
        try (InputStream input = source.openStream()) {
            byte[] bytes = input.readNBytes(expected + 1);
            if (bytes.length != expected || input.read() != -1) {
                throw new BadRequestException("Uploaded image length is invalid");
            }
            return bytes;
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Uploaded image could not be read");
        }
    }

    private static ImageFormat detect(byte[] bytes) {
        if (startsWith(bytes, PNG_SIGNATURE)) {
            return ImageFormat.PNG;
        }
        if (bytes.length >= 3
                && Byte.toUnsignedInt(bytes[0]) == 0xff
                && Byte.toUnsignedInt(bytes[1]) == 0xd8
                && Byte.toUnsignedInt(bytes[2]) == 0xff) {
            return ImageFormat.JPEG;
        }
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
            return ImageFormat.WEBP;
        }
        throw new UnsupportedUploadMediaTypeException("Only JPEG, PNG, or WebP images are allowed");
    }

    private static void validateDeclaredContentType(String declared, String detected) {
        String normalized = declared == null
            ? ""
            : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (GENERIC_CONTENT_TYPES.contains(normalized)) {
            return;
        }
        if (normalized.equals("image/jpg")) {
            normalized = "image/jpeg";
        }
        if (!normalized.equals(detected)) {
            throw new UnsupportedUploadMediaTypeException(
                "Image content does not match its declared media type");
        }
    }

    private void decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw malformed();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw malformed();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                if (reader.getNumImages(true) != 1) {
                    throw new UnsupportedUploadMediaTypeException(
                        "Animated or multi-frame images are not supported");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw malformed();
                }
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException | UnsupportedUploadMediaTypeException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw malformed();
        }
    }

    private void validateDimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || pixels > properties.getMaxImagePixels()) {
            throw new BadRequestException("Uploaded image dimensions are invalid or too large");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] expected) {
        return bytes.length >= expected.length
            && Arrays.equals(Arrays.copyOf(bytes, expected.length), expected);
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static BadRequestException malformed() {
        return new BadRequestException("Uploaded image could not be safely decoded");
    }

    private enum ImageFormat {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp");

        private final String contentType;
        private final String extension;

        ImageFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        String contentType() {
            return contentType;
        }

        String extension() {
            return extension;
        }
    }

    /**
     * Trusted image bytes and detected metadata.
     *
     * @param content fully decoded original bytes
     * @param contentType detected media type
     * @param extension canonical extension without a leading dot
     */
    public record ValidatedImage(byte[] content, String contentType, String extension) {
        public ValidatedImage {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
