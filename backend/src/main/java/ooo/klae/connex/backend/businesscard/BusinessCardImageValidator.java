package ooo.klae.connex.backend.businesscard;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnprocessableBusinessCardException;
import ooo.klae.connex.backend.exceptions.UnsupportedBusinessCardMediaTypeException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.storage.ImageDecodeAdmissionService;
import ooo.klae.connex.backend.storage.ImageDecodeAdmissionService.Lease;
import ooo.klae.connex.backend.storage.CappedImageOutputStream;
import ooo.klae.connex.backend.storage.CappedImageOutputStream.LimitExceededException;
import ooo.klae.connex.backend.storage.UploadPolicy;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;

/**
 * Verifies bounded raster inputs and emits orientation-normalized, metadata-free JPEG content.
 */
@Component
public class BusinessCardImageValidator {
    private static final float JPEG_QUALITY = 0.85f;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> GENERIC_DECLARED_TYPES = Set.of("", "application/octet-stream");

    private final BusinessCardProperties properties;
    private final ImageDecodeAdmissionService decodeAdmission;
    private final UploadPolicy uploadPolicy;

    public BusinessCardImageValidator(
            BusinessCardProperties properties,
            ImageDecodeAdmissionService decodeAdmission,
            UploadPolicy uploadPolicy) {
        this.properties = properties;
        this.decodeAdmission = decodeAdmission;
        this.uploadPolicy = uploadPolicy;
        ImageIO.setUseCache(false);
    }

    /**
     * Reads and validates one uploaded card image.
     *
     * @param image multipart image part
     * @return validated immutable metadata and sanitized bytes
     */
    public ValidatedBusinessCardImage validate(MultipartFile image) {
        try (Lease lease = decodeAdmission.tryAcquire().orElseThrow(
                () -> new TooManyRequestsException("Image processing is busy; retry shortly"))) {
            return validateAcquired(image, lease);
        }
    }

    private ValidatedBusinessCardImage validateAcquired(MultipartFile image, Lease lease) {
        if (image == null || image.isEmpty()) {
            throw new UnprocessableBusinessCardException("Business-card image is empty");
        }
        uploadPolicy.validate(UploadPurpose.BUSINESS_CARD_IMAGE, UploadSource.from(image));
        if (image.getSize() > properties.getMaxImageBytes()) {
            throw new RequestBodyTooLargeException(properties.getMaxImageBytes());
        }
        byte[] content = read(image);
        if (content.length > properties.getMaxImageBytes()) {
            throw new RequestBodyTooLargeException(properties.getMaxImageBytes());
        }
        ImageFormat format = detect(content);
        validateDeclaredType(image.getContentType(), format.contentType());
        return decode(content, format, lease);
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

    private ValidatedBusinessCardImage decode(byte[] content, ImageFormat format, Lease lease) {
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
                boolean ignoreMetadata = format != ImageFormat.JPEG;
                reader.setInput(input, false, ignoreMetadata);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateRawDimensions(width, height);
                int images = reader.getNumImages(true);
                if (images != 1) {
                    throw new UnprocessableBusinessCardException(
                            "Animated or multi-frame business-card images are not supported");
                }
                Orientation orientation = ignoreMetadata
                        ? Orientation.NORMAL
                        : orientation(reader.getImageMetadata(0));
                ImageTypeSpecifier imageType = imageType(reader);
                validateSampleModel(imageType.getSampleModel());
                long workingBytes = estimatedWorkingBytes(
                        width, height, imageType.getSampleModel(), content.length);
                if (!decodeAdmission.supports(workingBytes)) {
                    throw new UnprocessableBusinessCardException(
                            "Business-card image requires too much decode memory");
                }
                if (!lease.tryReserve(workingBytes)) {
                    throw new TooManyRequestsException("Image processing is busy; retry shortly");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw unprocessable();
                }
                BufferedImage normalized = normalize(decoded, orientation);
                validateDimensions(normalized.getWidth(), normalized.getHeight());
                byte[] encoded = encode(normalized);
                return new ValidatedBusinessCardImage(
                        encoded, "image/jpeg", "jpg", normalized.getWidth(), normalized.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (UnprocessableBusinessCardException
                | RequestBodyTooLargeException
                | TooManyRequestsException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unprocessable();
        }
    }

    private static ImageTypeSpecifier imageType(ImageReader reader) throws IOException {
        ImageTypeSpecifier type = reader.getRawImageType(0);
        if (type != null) {
            return type;
        }
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        if (!types.hasNext()) {
            throw unprocessable();
        }
        return types.next();
    }

    private static void validateSampleModel(SampleModel sampleModel) {
        int bands = sampleModel.getNumBands();
        int[] sampleSizes = sampleModel.getSampleSize();
        if (bands <= 0 || bands > 4 || sampleSizes.length != bands) {
            throw new UnprocessableBusinessCardException(
                    "Business-card image channel layout is not supported");
        }
        for (int sampleSize : sampleSizes) {
            if (sampleSize <= 0 || sampleSize > 8) {
                throw new UnprocessableBusinessCardException(
                        "Business-card image sample depth is not supported");
            }
        }
    }

    private static long estimatedWorkingBytes(
            int width,
            int height,
            SampleModel sampleModel,
            int inputBytes) {
        long pixels = Math.multiplyExact((long) width, height);
        long decodedBytes = Math.multiplyExact(pixels, Math.max(4, sampleModel.getNumBands()));
        long canonicalBytes = Math.multiplyExact(pixels, 3);
        return Math.addExact(
                inputBytes,
                Math.addExact(decodedBytes, Math.multiplyExact(canonicalBytes, 2)));
    }

    private void validateRawDimensions(int width, int height) {
        long pixels = (long) width * height;
        int maximumDimension = Math.max(properties.getMaxWidth(), properties.getMaxHeight());
        if (width <= 0 || height <= 0
                || width > maximumDimension
                || height > maximumDimension
                || pixels > properties.getMaxPixels()) {
            throw new UnprocessableBusinessCardException(
                    "Business-card image dimensions exceed the supported bounds");
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

    private byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw unprocessable();
        }
        ImageWriter writer = writers.next();
        try (CappedImageOutputStream output =
                new CappedImageOutputStream(properties.getMaxImageBytes())) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return output.toByteArray();
        } catch (LimitExceededException exception) {
            throw new RequestBodyTooLargeException(properties.getMaxImageBytes());
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage normalize(BufferedImage source, Orientation orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        int normalizedWidth = orientation.rotatesDimensions() ? height : width;
        int normalizedHeight = orientation.rotatesDimensions() ? width : height;
        BufferedImage normalized = new BufferedImage(
                normalizedWidth, normalizedHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, normalizedWidth, normalizedHeight);
            graphics.drawImage(source, orientation.transform(width, height), null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static Orientation orientation(IIOMetadata metadata) {
        if (metadata == null || !metadata.isStandardMetadataFormatSupported()) {
            return Orientation.NORMAL;
        }
        Node root = metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
        return Orientation.fromValue(orientationValue(root));
    }

    private static String orientationValue(Node node) {
        if (node == null) {
            return null;
        }
        if ("ImageOrientation".equals(node.getNodeName())) {
            NamedNodeMap attributes = node.getAttributes();
            Node value = attributes == null ? null : attributes.getNamedItem("value");
            return value == null ? null : value.getNodeValue();
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            String value = orientationValue(child);
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private enum Orientation {
        NORMAL("Normal", false),
        ROTATE_90("Rotate90", true),
        ROTATE_180("Rotate180", false),
        ROTATE_270("Rotate270", true),
        FLIP_H("FlipH", false),
        FLIP_V("FlipV", false),
        FLIP_H_ROTATE_90("FlipHRotate90", true),
        FLIP_V_ROTATE_90("FlipVRotate90", true);

        private final String value;
        private final boolean rotatesDimensions;

        Orientation(String value, boolean rotatesDimensions) {
            this.value = value;
            this.rotatesDimensions = rotatesDimensions;
        }

        boolean rotatesDimensions() {
            return rotatesDimensions;
        }

        AffineTransform transform(int width, int height) {
            return switch (this) {
                case NORMAL -> new AffineTransform();
                case ROTATE_90 -> new AffineTransform(0, -1, 1, 0, 0, width);
                case ROTATE_180 -> new AffineTransform(-1, 0, 0, -1, width, height);
                case ROTATE_270 -> new AffineTransform(0, 1, -1, 0, height, 0);
                case FLIP_H -> new AffineTransform(-1, 0, 0, 1, width, 0);
                case FLIP_V -> new AffineTransform(1, 0, 0, -1, 0, height);
                case FLIP_H_ROTATE_90 -> new AffineTransform(0, 1, 1, 0, 0, 0);
                case FLIP_V_ROTATE_90 -> new AffineTransform(0, -1, -1, 0, height, width);
            };
        }

        static Orientation fromValue(String value) {
            for (Orientation orientation : values()) {
                if (orientation.value.equals(value)) {
                    return orientation;
                }
            }
            return NORMAL;
        }
    }
}
