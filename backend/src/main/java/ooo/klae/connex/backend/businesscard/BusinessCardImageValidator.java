package ooo.klae.connex.backend.businesscard;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnprocessableBusinessCardException;
import ooo.klae.connex.backend.exceptions.UnsupportedBusinessCardMediaTypeException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Verifies business-card image signatures and fully decodes bounded JPEG, PNG, and WebP inputs.
 */
@Component
public class BusinessCardImageValidator {
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> GENERIC_DECLARED_TYPES = Set.of("", "application/octet-stream");

    private final BusinessCardProperties properties;
    private final Semaphore validation = new Semaphore(1);

    public BusinessCardImageValidator(BusinessCardProperties properties) {
        this.properties = properties;
        ImageIO.setUseCache(false);
    }

    /**
     * Reads and validates one uploaded card image.
     *
     * @param image multipart image part
     * @return validated immutable metadata and original bytes
     */
    public ValidatedBusinessCardImage validate(MultipartFile image) {
        if (!validation.tryAcquire()) {
            throw new TooManyRequestsException("Image processing is busy; retry shortly");
        }
        try {
            return validateAcquired(image);
        } finally {
            validation.release();
        }
    }

    private ValidatedBusinessCardImage validateAcquired(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new UnprocessableBusinessCardException("Business-card image is empty");
        }
        if (image.getSize() > properties.getMaxImageBytes()) {
            throw new RequestBodyTooLargeException(properties.getMaxImageBytes());
        }
        byte[] content = read(image);
        if (content.length > properties.getMaxImageBytes()) {
            throw new RequestBodyTooLargeException(properties.getMaxImageBytes());
        }
        ImageFormat format = detect(content);
        validateDeclaredType(image.getContentType(), format.contentType());
        return decode(content, format);
    }

    private static byte[] read(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (IOException exception) {
            throw new UnprocessableBusinessCardException("Business-card image could not be read");
        }
    }

    private static ImageFormat detect(byte[] content) {
        if (startsWith(content, PNG_SIGNATURE)) {
            return ImageFormat.PNG;
        }
        if (content.length >= 3
                && Byte.toUnsignedInt(content[0]) == 0xff
                && Byte.toUnsignedInt(content[1]) == 0xd8
                && Byte.toUnsignedInt(content[2]) == 0xff) {
            return ImageFormat.JPEG;
        }
        if (content.length >= 12
                && ascii(content, 0, "RIFF")
                && ascii(content, 8, "WEBP")) {
            return ImageFormat.WEBP;
        }
        throw new UnsupportedBusinessCardMediaTypeException(
                "Business-card image must be JPEG, PNG, or WebP");
    }

    private static void validateDeclaredType(String declaredType, String detectedType) {
        String normalized = declaredType == null ? "" : declaredType.trim().toLowerCase(Locale.ROOT);
        if (GENERIC_DECLARED_TYPES.contains(normalized)) {
            return;
        }
        if (normalized.equals("image/jpg")) {
            normalized = "image/jpeg";
        }
        if (!normalized.equals(detectedType)) {
            throw new UnsupportedBusinessCardMediaTypeException(
                    "Business-card image content does not match its declared media type");
        }
    }

    private ValidatedBusinessCardImage decode(byte[] content, ImageFormat format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw unprocessable();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unprocessable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                int images = reader.getNumImages(true);
                if (images != 1) {
                    throw new UnprocessableBusinessCardException(
                            "Animated or multi-frame business-card images are not supported");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw unprocessable();
                }
                return new ValidatedBusinessCardImage(
                        content, format.contentType(), format.extension(), width, height);
            } finally {
                reader.dispose();
            }
        } catch (UnprocessableBusinessCardException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unprocessable();
        }
    }

    private void validateDimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0
                || width > properties.getMaxWidth()
                || height > properties.getMaxHeight()
                || pixels > properties.getMaxPixels()) {
            throw new UnprocessableBusinessCardException(
                    "Business-card image dimensions exceed the supported bounds");
        }
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] content, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            if (content[offset + i] != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static UnprocessableBusinessCardException unprocessable() {
        return new UnprocessableBusinessCardException("Business-card image could not be safely decoded");
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
}
