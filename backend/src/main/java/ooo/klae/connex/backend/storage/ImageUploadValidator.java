package ooo.klae.connex.backend.storage;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
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
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ImageDecodeAdmissionService.Lease;

/**
 * Fully decodes bounded managed raster uploads and emits metadata-free canonical bytes.
 */
@Component
public class ImageUploadValidator {
    private static final float JPEG_QUALITY = 0.9f;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> GENERIC_CONTENT_TYPES = Set.of("", "application/octet-stream");

    private final ObjectStorageProperties properties;
    private final UploadPolicy uploadPolicy;
    private final ImageDecodeAdmissionService decodeAdmission;

    public ImageUploadValidator(
            ObjectStorageProperties properties,
            UploadPolicy uploadPolicy,
            ImageDecodeAdmissionService decodeAdmission) {
        this.properties = properties;
        this.uploadPolicy = uploadPolicy;
        this.decodeAdmission = decodeAdmission;
        ImageIO.setUseCache(false);
    }

    public ValidatedImage validate(UploadSource source) {
        uploadPolicy.validateLength(source.contentLength());
        try (Lease lease = decodeAdmission.tryAcquire().orElseThrow(
                () -> new ServiceUnavailableException("Image validation is busy; retry shortly"))) {
            byte[] bytes = read(source);
            ImageFormat format = detect(bytes);
            validateDeclaredContentType(source.contentType(), format.contentType());
            return decode(bytes, format, lease);
        }
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

    private ValidatedImage decode(byte[] bytes, ImageFormat format, Lease lease) {
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
                boolean ignoreMetadata = format != ImageFormat.JPEG;
                reader.setInput(input, false, ignoreMetadata);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                if (reader.getNumImages(true) != 1) {
                    throw new UnsupportedUploadMediaTypeException(
                        "Animated or multi-frame images are not supported");
                }
                Orientation orientation = ignoreMetadata
                    ? Orientation.NORMAL
                    : orientation(reader.getImageMetadata(0));
                ImageTypeSpecifier imageType = imageType(reader);
                validateSampleModel(imageType.getSampleModel());
                boolean alpha = imageType.getColorModel().hasAlpha();
                long workingBytes = estimatedWorkingBytes(
                    width, height, imageType.getSampleModel(), alpha, bytes.length);
                if (!decodeAdmission.supports(workingBytes)) {
                    throw new BadRequestException("Uploaded image requires too much decode memory");
                }
                if (!lease.tryReserve(workingBytes)) {
                    throw new ServiceUnavailableException("Image validation is busy; retry shortly");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw malformed();
                }
                BufferedImage normalized = normalize(decoded, orientation, alpha);
                EncodedImage encoded = encode(normalized, alpha);
                return new ValidatedImage(
                    encoded.content(), encoded.contentType(), encoded.extension());
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException
                | RequestBodyTooLargeException
                | ServiceUnavailableException
                | UnsupportedUploadMediaTypeException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw malformed();
        }
    }

    private static ImageTypeSpecifier imageType(ImageReader reader) throws IOException {
        ImageTypeSpecifier type = reader.getRawImageType(0);
        if (type != null) {
            return type;
        }
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        if (!types.hasNext()) {
            throw malformed();
        }
        return types.next();
    }

    private static void validateSampleModel(SampleModel sampleModel) {
        int bands = sampleModel.getNumBands();
        int[] sampleSizes = sampleModel.getSampleSize();
        if (bands <= 0 || bands > 4 || sampleSizes.length != bands) {
            throw new BadRequestException("Uploaded image channel layout is not supported");
        }
        for (int sampleSize : sampleSizes) {
            if (sampleSize <= 0 || sampleSize > 8) {
                throw new BadRequestException("Uploaded image sample depth is not supported");
            }
        }
    }

    private static long estimatedWorkingBytes(
            int width,
            int height,
            SampleModel sampleModel,
            boolean alpha,
            int inputBytes) {
        long pixels = Math.multiplyExact((long) width, height);
        long decodedBytes = Math.multiplyExact(pixels, Math.max(4, sampleModel.getNumBands()));
        int canonicalBytesPerPixel = alpha ? 4 : 3;
        long canonicalBytes = Math.multiplyExact(pixels, canonicalBytesPerPixel);
        return Math.addExact(
            inputBytes,
            Math.addExact(decodedBytes, Math.multiplyExact(canonicalBytes, 2)));
    }

    private static BufferedImage normalize(
            BufferedImage source,
            Orientation orientation,
            boolean alpha) {
        int width = source.getWidth();
        int height = source.getHeight();
        int normalizedWidth = orientation.rotatesDimensions() ? height : width;
        int normalizedHeight = orientation.rotatesDimensions() ? width : height;
        BufferedImage normalized = new BufferedImage(
            normalizedWidth,
            normalizedHeight,
            alpha ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = normalized.createGraphics();
        try {
            if (alpha) {
                graphics.setComposite(AlphaComposite.Src);
            } else {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, normalizedWidth, normalizedHeight);
            }
            graphics.drawImage(source, orientation.transform(width, height), null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private EncodedImage encode(BufferedImage image, boolean alpha) throws IOException {
        String format = alpha ? "png" : "jpeg";
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw malformed();
        }
        ImageWriter writer = writers.next();
        try (CappedImageOutputStream output =
                new CappedImageOutputStream(properties.getMaxUploadBytes())) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (!alpha && parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return new EncodedImage(
                output.toByteArray(), alpha ? "image/png" : "image/jpeg", alpha ? "png" : "jpg");
        } catch (CappedImageOutputStream.LimitExceededException exception) {
            throw new RequestBodyTooLargeException(properties.getMaxUploadBytes());
        } finally {
            writer.dispose();
        }
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
        JPEG("image/jpeg"),
        PNG("image/png"),
        WEBP("image/webp");

        private final String contentType;

        ImageFormat(String contentType) {
            this.contentType = contentType;
        }

        String contentType() {
            return contentType;
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

    private record EncodedImage(byte[] content, String contentType, String extension) {
    }

    /**
     * Canonical metadata-free image bytes and detected response metadata.
     *
     * @param content canonical image bytes
     * @param contentType canonical media type
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
