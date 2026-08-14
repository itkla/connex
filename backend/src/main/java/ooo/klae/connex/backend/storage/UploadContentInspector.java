package ooo.klae.connex.backend.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import jakarta.annotation.PreDestroy;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes an untrusted upload once and returns an immutable artifact only after bounded real-format
 * inspection agrees with its server-selected purpose, extension, and declared media type.
 *
 * <p>Inspection runs in a bounded executor with a five-second deadline. ZIP document packages are
 * limited to 512 entries, 64 MiB expanded content, a 100:1 compression ratio, and 4 MiB per XML
 * part. XML DTDs and external entities are disabled. Parser error, timeout, ambiguous structure,
 * active content, and any exceeded bound all fail closed.
 */
@Component
public class UploadContentInspector implements AutoCloseable {
    private static final Duration INSPECTION_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CONCURRENT_INSPECTIONS = 4;
    private static final int MAX_QUEUED_INSPECTIONS = 8;
    private static final int MAX_ARCHIVE_ENTRIES = 512;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_COMPRESSION_RATIO = 100;
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final int MAX_XML_DEPTH = 128;
    private static final int MAX_XML_ATTRIBUTES = 256;
    private static final int MAX_IMAGE_METADATA_BYTES = 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> FORBIDDEN_PDF_NAMES = Set.of(
        "/javascript", "/js", "/openaction", "/aa", "/launch", "/embeddedfile",
        "/richmedia", "/xfa", "/acroform", "/encrypt", "/xrefstm", "/prev",
        "/submitform", "/importdata", "/gotor", "/gotoe", "/uri", "/rendition",
        "/sound", "/movie", "/resetform", "/hide", "/setocgstate", "/named",
        "/filespec", "/collection", "/3d");
    private static final Pattern PDF_SIZE = Pattern.compile("/size\\s+(\\d+)");
    private static final Pattern PDF_ROOT = Pattern.compile("/root\\s+(\\d+)\\s+(\\d+)\\s+r\\b");
    private static final Pattern PDF_CATALOG = Pattern.compile("/type\\s*/catalog\\b");
    private static final Pattern PDF_PAGES_REFERENCE =
        Pattern.compile("/pages\\s+(\\d+)\\s+(\\d+)\\s+r\\b");
    private static final Pattern PDF_PAGE_TREE = Pattern.compile("/type\\s*/pages\\b");
    private static final Pattern PDF_ACTION_DICTIONARY =
        Pattern.compile("/a\\s*<<[^>]{0,4096}/s\\s*/[a-z0-9]+", Pattern.DOTALL);
    private static final Set<Integer> ZIP_SIGNATURE_SUFFIXES = Set.of(0x0304, 0x0102, 0x0506);
    private static final String OOXML_CONTENT_TYPES_NAMESPACE =
        "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String OOXML_RELATIONSHIPS_NAMESPACE =
        "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final Set<String> OOXML_OFFICE_DOCUMENT_RELATIONSHIPS = Set.of(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
        "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument");
    private static final Set<String> ACTIVE_XML_ELEMENTS = Set.of(
        "script", "scripts", "event-listener", "event-listeners", "fldsimple",
        "fldchar", "instrtext", "altchunk", "object", "oleobject", "control");

    private final UploadPolicy uploadPolicy;
    private final ImageUploadValidator imageUploadValidator;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Duration timeout;

    @Autowired
    public UploadContentInspector(
            UploadPolicy uploadPolicy,
            ImageUploadValidator imageUploadValidator,
            ObjectMapper objectMapper) {
        this(
            uploadPolicy,
            imageUploadValidator,
            objectMapper,
            new ThreadPoolExecutor(
                MAX_CONCURRENT_INSPECTIONS,
                MAX_CONCURRENT_INSPECTIONS,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_INSPECTIONS),
                Thread.ofPlatform().daemon().name("upload-inspection-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()),
            INSPECTION_TIMEOUT);
    }

    UploadContentInspector(
            UploadPolicy uploadPolicy,
            ImageUploadValidator imageUploadValidator,
            ObjectMapper objectMapper,
            ExecutorService executor,
            Duration timeout) {
        this.uploadPolicy = Objects.requireNonNull(uploadPolicy, "uploadPolicy");
        this.imageUploadValidator = Objects.requireNonNull(imageUploadValidator, "imageUploadValidator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Validates metadata and real content before producing the only bytes eligible for storage.
     *
     * @param purpose server-selected reason for the upload
     * @param source untrusted upload source
     * @return immutable verified metadata, content, and digest
     */
    public InspectedUpload inspect(UploadPurpose purpose, UploadSource source) {
        Future<InspectedUpload> future;
        try {
            future = executor.submit(() -> inspectNow(purpose, source));
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException("Upload validation is busy; retry shortly");
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw UnsupportedUploadMediaTypeException.unsupported();
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw UnsupportedUploadMediaTypeException.unsupported();
        } catch (ExecutionException exception) {
            throw inspectedFailure(exception.getCause());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private InspectedUpload inspectNow(UploadPurpose purpose, UploadSource source) {
        ValidatedUpload metadata = uploadPolicy.validate(purpose, source);
        Deadline deadline = new Deadline(System.nanoTime(), timeout);
        byte[] content = readExact(source, deadline);
        InspectedContent inspected = inspectContent(
            purpose, metadata.format(), content, metadata, deadline);
        return new InspectedUpload(
            inspected.fileName(),
            inspected.contentType(),
            inspected.extension(),
            inspected.format(),
            inspected.content(),
            sha256(inspected.content()));
    }

    private InspectedContent inspectContent(
            UploadPurpose purpose,
            UploadFormat format,
            byte[] content,
            ValidatedUpload metadata,
            Deadline deadline) {
        deadline.check();
        InspectedContent inspected = switch (format) {
            case JPEG, PNG, GIF, WEBP ->
                inspectImage(purpose, format, content, metadata, deadline);
            case PDF -> {
                inspectPdf(content, deadline);
                yield InspectedContent.original(metadata, content);
            }
            case DOCX, XLSX, PPTX, ODT, ODS, ODP -> {
                inspectDocumentPackage(format, content, deadline);
                yield InspectedContent.original(metadata, content);
            }
            case TEXT, MARKDOWN -> {
                inspectText(content, deadline);
                yield InspectedContent.original(metadata, content);
            }
            case CSV -> {
                inspectCsv(content, deadline);
                yield InspectedContent.original(metadata, content);
            }
            case JSON -> {
                inspectJson(content, deadline);
                yield InspectedContent.original(metadata, content);
            }
        };
        deadline.check();
        return inspected;
    }

    private InspectedContent inspectImage(
            UploadPurpose purpose,
            UploadFormat format,
            byte[] content,
            ValidatedUpload metadata,
            Deadline deadline) {
        switch (format) {
            case JPEG -> inspectJpeg(content, deadline);
            case PNG -> inspectPng(content, deadline);
            case GIF -> inspectGif(content, deadline);
            case WEBP -> inspectWebp(content, deadline);
            default -> throw UnsupportedUploadMediaTypeException.unsupported();
        }
        try {
            ValidatedImage image = imageUploadValidator.validate(
                UploadSource.from(metadata.fileName(), metadata.contentType(), content), purpose);
            UploadFormat canonicalFormat = switch (image.extension()) {
                case "jpg" -> UploadFormat.JPEG;
                case "png" -> UploadFormat.PNG;
                default -> throw UnsupportedUploadMediaTypeException.unsupported();
            };
            deadline.check();
            return new InspectedContent(
                replaceExtension(metadata.fileName(), image.extension()),
                image.contentType(),
                image.extension(),
                canonicalFormat,
                image.content());
        } catch (ServiceUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void inspectJpeg(byte[] content, Deadline deadline) {
        if (content.length < 4
                || unsigned(content[0]) != 0xff
                || unsigned(content[1]) != 0xd8
                || unsigned(content[content.length - 2]) != 0xff
                || unsigned(content[content.length - 1]) != 0xd9) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int offset = 2;
        boolean scanFound = false;
        int metadataBytes = 0;
        while (offset < content.length - 2) {
            deadline.check();
            if (unsigned(content[offset]) != 0xff) {
                if (!scanFound) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                offset++;
                continue;
            }
            while (offset < content.length - 2 && unsigned(content[offset]) == 0xff) {
                offset++;
            }
            int marker = unsigned(content[offset++]);
            if (marker == 0x00) {
                continue;
            }
            if (marker == 0xd8 || marker == 0xd9) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (marker >= 0xd0 && marker <= 0xd7) {
                continue;
            }
            if (offset + 2 > content.length - 2) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            int length = bigEndianUnsignedShort(content, offset);
            if (length < 2 || offset + length > content.length - 2) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (marker == 0xda) {
                scanFound = true;
            }
            if (marker >= 0xe0 && marker <= 0xef || marker == 0xfe) {
                metadataBytes = Math.addExact(metadataBytes, length - 2);
                if (metadataBytes > MAX_IMAGE_METADATA_BYTES) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
            }
            offset += length;
        }
        if (!scanFound || offset != content.length - 2) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void inspectPng(byte[] content, Deadline deadline) {
        if (!startsWith(content, PNG_SIGNATURE)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int offset = PNG_SIGNATURE.length;
        boolean headerFound = false;
        boolean imageDataFound = false;
        int metadataBytes = 0;
        while (offset < content.length) {
            deadline.check();
            if (offset + 12 > content.length) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            long length = bigEndianUnsignedInt(content, offset);
            if (length > Integer.MAX_VALUE) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            int dataLength = (int) length;
            int end = Math.addExact(offset, Math.addExact(12, dataLength));
            if (end > content.length) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            String type = ascii(content, offset + 4, 4);
            CRC32 crc = new CRC32();
            crc.update(content, offset + 4, dataLength + 4);
            if (crc.getValue() != bigEndianUnsignedInt(content, offset + 8 + dataLength)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (!headerFound) {
                if (!"IHDR".equals(type) || dataLength != 13) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                headerFound = true;
            } else if ("IHDR".equals(type)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if ("IDAT".equals(type)) {
                imageDataFound = true;
            }
            if (!"IHDR".equals(type)
                    && !"PLTE".equals(type)
                    && !"IDAT".equals(type)
                    && !"IEND".equals(type)) {
                metadataBytes = Math.addExact(metadataBytes, dataLength);
                if (metadataBytes > MAX_IMAGE_METADATA_BYTES) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
            }
            if ("IEND".equals(type)) {
                if (dataLength != 0 || !imageDataFound || end != content.length) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                return;
            }
            if (type.charAt(0) >= 'A'
                    && type.charAt(0) <= 'Z'
                    && !Set.of("IHDR", "PLTE", "IDAT").contains(type)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            offset = end;
        }
        throw UnsupportedUploadMediaTypeException.unsupported();
    }

    private static void inspectGif(byte[] content, Deadline deadline) {
        if (content.length < 14
                || !(startsWith(content, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(content, "GIF89a".getBytes(StandardCharsets.US_ASCII)))) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int offset = 13;
        int packed = unsigned(content[10]);
        int metadataBytes = 0;
        if ((packed & 0x80) != 0) {
            offset = addBounded(offset, 3 * (1 << ((packed & 0x07) + 1)), content.length);
        }
        int images = 0;
        while (offset < content.length) {
            deadline.check();
            int introducer = unsigned(content[offset++]);
            if (introducer == 0x3b) {
                if (images != 1 || offset != content.length) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                return;
            }
            if (introducer == 0x21) {
                offset = addBounded(offset, 1, content.length);
                int metadataStart = offset;
                offset = skipGifSubBlocks(content, offset, deadline);
                metadataBytes = Math.addExact(metadataBytes, offset - metadataStart);
                if (metadataBytes > MAX_IMAGE_METADATA_BYTES) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                continue;
            }
            if (introducer != 0x2c) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            images++;
            offset = addBounded(offset, 9, content.length);
            int imagePacked = unsigned(content[offset - 1]);
            if ((imagePacked & 0x80) != 0) {
                offset = addBounded(
                    offset, 3 * (1 << ((imagePacked & 0x07) + 1)), content.length);
            }
            offset = addBounded(offset, 1, content.length);
            offset = skipGifSubBlocks(content, offset, deadline);
        }
        throw UnsupportedUploadMediaTypeException.unsupported();
    }

    private static int skipGifSubBlocks(byte[] content, int offset, Deadline deadline) {
        while (offset < content.length) {
            deadline.check();
            int length = unsigned(content[offset++]);
            if (length == 0) {
                return offset;
            }
            offset = addBounded(offset, length, content.length);
        }
        throw UnsupportedUploadMediaTypeException.unsupported();
    }

    private static void inspectWebp(byte[] content, Deadline deadline) {
        if (content.length < 20
                || !asciiEquals(content, 0, "RIFF")
                || !asciiEquals(content, 8, "WEBP")
                || littleEndianUnsignedInt(content, 4) != content.length - 8L) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int offset = 12;
        int metadataBytes = 0;
        int imageChunks = 0;
        while (offset < content.length) {
            deadline.check();
            if (offset + 8 > content.length) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            long chunkLength = littleEndianUnsignedInt(content, offset + 4);
            if (chunkLength > Integer.MAX_VALUE) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            int paddedLength = Math.addExact((int) chunkLength, (int) (chunkLength & 1));
            String chunkType = ascii(content, offset, 4);
            if (!Set.of("VP8 ", "VP8L", "VP8X", "ALPH", "ICCP", "EXIF", "XMP ", "ICMT")
                    .contains(chunkType)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if ("VP8 ".equals(chunkType) || "VP8L".equals(chunkType)) {
                imageChunks++;
            }
            if (Set.of("EXIF", "XMP ", "ICCP", "ICMT").contains(chunkType)) {
                metadataBytes = Math.addExact(metadataBytes, (int) chunkLength);
                if (metadataBytes > MAX_IMAGE_METADATA_BYTES) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
            }
            offset = addBounded(offset, Math.addExact(8, paddedLength), content.length);
        }
        if (offset != content.length || imageChunks != 1) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void inspectPdf(byte[] content, Deadline deadline) {
        if (content.length < 32
                || !asciiEquals(content, 0, "%PDF-1.")
                || content[7] < '0'
                || content[7] > '9') {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int end = content.length;
        while (end > 0 && isPdfWhitespace(content[end - 1])) {
            end--;
        }
        if (end < 5 || !asciiEquals(content, end - 5, "%%EOF")) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int eofOffset = end - 5;
        int startXref = lastIndexOf(
            content,
            "startxref".getBytes(StandardCharsets.US_ASCII),
            eofOffset,
            Math.max(8, eofOffset - 8192),
            deadline);
        if (startXref < 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int numberStart = startXref + "startxref".length();
        while (numberStart < end && isPdfWhitespace(content[numberStart])) {
            numberStart++;
        }
        int numberEnd = numberStart;
        while (numberEnd < end && content[numberEnd] >= '0' && content[numberEnd] <= '9') {
            numberEnd++;
        }
        if (numberStart == numberEnd) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int trailing = numberEnd;
        while (trailing < eofOffset && isPdfWhitespace(content[trailing])) {
            trailing++;
        }
        if (trailing != eofOffset || containsZipSignature(content, deadline)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int xrefOffset = parsePdfInteger(content, numberStart, numberEnd);
        if (xrefOffset < 8 || xrefOffset >= startXref || !asciiEquals(content, xrefOffset, "xref")) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        PdfCrossReference crossReference = inspectPdfCrossReference(
            content, xrefOffset, startXref, deadline);
        String normalized = normalizedPdfNames(content, deadline);
        for (String forbidden : FORBIDDEN_PDF_NAMES) {
            if (normalized.contains(forbidden)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        if (PDF_ACTION_DICTIONARY.matcher(normalized).find()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        validatePdfObjects(content, xrefOffset, crossReference, deadline);
    }

    private static PdfCrossReference inspectPdfCrossReference(
            byte[] content,
            int xrefOffset,
            int startXref,
            Deadline deadline) {
        PdfCursor cursor = new PdfCursor(content, xrefOffset, startXref, deadline);
        cursor.requireToken("xref");
        Map<Integer, PdfXrefEntry> entries = new HashMap<>();
        int trailerOffset = -1;
        while (cursor.hasRemaining()) {
            cursor.skipWhitespace();
            if (cursor.startsWith("trailer")) {
                trailerOffset = cursor.position();
                cursor.requireToken("trailer");
                break;
            }
            int firstObject = cursor.readInteger();
            cursor.requireWhitespace();
            int count = cursor.readInteger();
            if (count <= 0 || firstObject < 0 || firstObject + (long) count > 100_000) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            for (int index = 0; index < count; index++) {
                cursor.requireWhitespace();
                int objectOffset = cursor.readInteger();
                cursor.requireWhitespace();
                int generation = cursor.readInteger();
                cursor.requireWhitespace();
                char status = cursor.readAsciiCharacter();
                if ((status != 'n' && status != 'f')
                        || entries.putIfAbsent(
                            firstObject + index,
                            new PdfXrefEntry(objectOffset, generation, status == 'n')) != null) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
            }
        }
        if (trailerOffset < 0 || entries.isEmpty()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int dictionaryStart = indexOf(content, "<<", cursor.position(), startXref, deadline);
        if (dictionaryStart < 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int dictionaryEnd = indexOf(content, ">>", dictionaryStart + 2, startXref, deadline);
        if (dictionaryEnd < 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int afterDictionary = dictionaryEnd + 2;
        while (afterDictionary < startXref && isPdfWhitespace(content[afterDictionary])) {
            afterDictionary++;
        }
        if (afterDictionary != startXref) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String trailer = normalizedPdfNames(
            content, dictionaryStart, afterDictionary, deadline);
        for (String forbidden : Set.of("/encrypt", "/xrefstm", "/prev")) {
            if (trailer.contains(forbidden)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        Matcher sizeMatcher = PDF_SIZE.matcher(trailer);
        Matcher rootMatcher = PDF_ROOT.matcher(trailer);
        if (!sizeMatcher.find() || !rootMatcher.find()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int size = parsePositivePdfInteger(sizeMatcher.group(1));
        int rootObject = parsePositivePdfInteger(rootMatcher.group(1));
        int rootGeneration = parsePdfInteger(rootMatcher.group(2));
        if (size <= 0 || size > 100_000 || rootObject >= size) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        PdfXrefEntry zero = entries.get(0);
        if (zero == null
                || zero.inUse()
                || entries.keySet().stream().anyMatch(objectNumber -> objectNumber >= size)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        PdfXrefEntry root = entries.get(rootObject);
        if (root == null || !root.inUse() || root.generation() != rootGeneration) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return new PdfCrossReference(Map.copyOf(entries), rootObject);
    }

    private static void validatePdfObjects(
            byte[] content,
            int xrefOffset,
            PdfCrossReference crossReference,
            Deadline deadline) {
        Set<Integer> offsets = new HashSet<>();
        List<Map.Entry<Integer, PdfXrefEntry>> inUseEntries = crossReference.entries().entrySet()
            .stream()
            .filter(item -> item.getValue().inUse())
            .sorted(java.util.Comparator.comparingInt(item -> item.getValue().offset()))
            .toList();
        for (int index = 0; index < inUseEntries.size(); index++) {
            deadline.check();
            Map.Entry<Integer, PdfXrefEntry> item = inUseEntries.get(index);
            PdfXrefEntry entry = item.getValue();
            if (entry.offset() < 8
                    || entry.offset() >= xrefOffset
                    || !offsets.add(entry.offset())) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            PdfCursor object = new PdfCursor(content, entry.offset(), xrefOffset, deadline);
            int objectNumber = object.readInteger();
            object.requireWhitespace();
            int generation = object.readInteger();
            object.requireWhitespace();
            object.requireToken("obj");
            if (objectNumber != item.getKey() || generation != entry.generation()) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            int objectLimit = index + 1 < inUseEntries.size()
                ? inUseEntries.get(index + 1).getValue().offset()
                : xrefOffset;
            int objectEnd = indexOf(content, "endobj", object.position(), objectLimit, deadline);
            if (objectEnd < 0
                    || !onlyPdfWhitespace(content, objectEnd + "endobj".length(), objectLimit)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        PdfXrefEntry root = crossReference.entries().get(crossReference.rootObject());
        int rootLimit = inUseEntries.stream()
            .map(Map.Entry::getValue)
            .filter(entry -> entry.offset() > root.offset())
            .mapToInt(PdfXrefEntry::offset)
            .min()
            .orElse(xrefOffset);
        int rootEnd = indexOf(content, "endobj", root.offset(), rootLimit, deadline);
        if (rootEnd < 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String rootObject = normalizedPdfNames(content, root.offset(), rootEnd, deadline);
        Matcher pagesMatcher = PDF_PAGES_REFERENCE.matcher(rootObject);
        if (!PDF_CATALOG.matcher(rootObject).find() || !pagesMatcher.find()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int pagesObject = parsePositivePdfInteger(pagesMatcher.group(1));
        int pagesGeneration = parsePdfInteger(pagesMatcher.group(2));
        PdfXrefEntry pages = crossReference.entries().get(pagesObject);
        if (pages == null || !pages.inUse() || pages.generation() != pagesGeneration) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int pagesLimit = inUseEntries.stream()
            .map(Map.Entry::getValue)
            .filter(entry -> entry.offset() > pages.offset())
            .mapToInt(PdfXrefEntry::offset)
            .min()
            .orElse(xrefOffset);
        int pagesEnd = indexOf(content, "endobj", pages.offset(), pagesLimit, deadline);
        if (pagesEnd < 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String pagesContent = normalizedPdfNames(content, pages.offset(), pagesEnd, deadline);
        if (!PDF_PAGE_TREE.matcher(pagesContent).find()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static boolean onlyPdfWhitespace(byte[] content, int from, int to) {
        for (int index = from; index < to; index++) {
            if (!isPdfWhitespace(content[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsZipSignature(byte[] content, Deadline deadline) {
        for (int offset = 0; offset + 4 <= content.length; offset++) {
            if ((offset & 0x3fff) == 0) {
                deadline.check();
            }
            if (content[offset] == 'P'
                    && content[offset + 1] == 'K'
                    && ZIP_SIGNATURE_SUFFIXES.contains(
                        unsigned(content[offset + 2]) << 8 | unsigned(content[offset + 3]))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedPdfNames(byte[] content, Deadline deadline) {
        return normalizedPdfNames(content, 0, content.length, deadline);
    }

    private static String normalizedPdfNames(
            byte[] content,
            int from,
            int to,
            Deadline deadline) {
        StringBuilder normalized = new StringBuilder(to - from);
        for (int index = from; index < to; index++) {
            if ((index & 0x3fff) == 0) {
                deadline.check();
            }
            int value = unsigned(content[index]);
            if (value == '#' && index + 2 < to) {
                int high = Character.digit((char) content[index + 1], 16);
                int low = Character.digit((char) content[index + 2], 16);
                if (high >= 0 && low >= 0) {
                    normalized.append(Character.toLowerCase((char) ((high << 4) | low)));
                    index += 2;
                    continue;
                }
            }
            normalized.append(value < 128 ? Character.toLowerCase((char) value) : ' ');
        }
        return normalized.toString();
    }

    private void inspectDocumentPackage(
            UploadFormat format,
            byte[] content,
            Deadline deadline) {
        ArchiveDirectory directory = readArchiveDirectory(content, deadline);
        PackageEvidence evidence = inflateAndInspectPackage(content, directory, deadline);
        switch (format) {
            case DOCX -> requireOoxml(
                evidence,
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                "document",
                Set.of(
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                    "http://purl.oclc.org/ooxml/wordprocessingml/main"));
            case XLSX -> requireOoxml(
                evidence,
                "xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                "workbook",
                Set.of(
                    "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
                    "http://purl.oclc.org/ooxml/spreadsheetml/main"));
            case PPTX -> requireOoxml(
                evidence,
                "ppt/presentation.xml",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                "presentation",
                Set.of(
                    "http://schemas.openxmlformats.org/presentationml/2006/main",
                    "http://purl.oclc.org/ooxml/presentationml/main"));
            case ODT, ODS, ODP -> requireOdf(format, evidence, directory);
            default -> throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static ArchiveDirectory readArchiveDirectory(byte[] content, Deadline deadline) {
        if (content.length < 22 || littleEndianUnsignedInt(content, content.length - 22) != 0x06054b50L) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int eocd = content.length - 22;
        if (littleEndianUnsignedShort(content, eocd + 4) != 0
                || littleEndianUnsignedShort(content, eocd + 6) != 0
                || littleEndianUnsignedShort(content, eocd + 20) != 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int entryCount = littleEndianUnsignedShort(content, eocd + 10);
        if (entryCount == 0
                || entryCount != littleEndianUnsignedShort(content, eocd + 8)
                || entryCount > MAX_ARCHIVE_ENTRIES) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        long directorySize = littleEndianUnsignedInt(content, eocd + 12);
        long directoryOffset = littleEndianUnsignedInt(content, eocd + 16);
        if (directoryOffset + directorySize != eocd || directoryOffset > Integer.MAX_VALUE) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int offset = (int) directoryOffset;
        Map<String, ArchiveEntry> entries = new HashMap<>();
        Set<Long> localOffsets = new HashSet<>();
        long expanded = 0;
        long compressed = 0;
        for (int index = 0; index < entryCount; index++) {
            deadline.check();
            if (offset + 46 > eocd || littleEndianUnsignedInt(content, offset) != 0x02014b50L) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            int flags = littleEndianUnsignedShort(content, offset + 8);
            int method = littleEndianUnsignedShort(content, offset + 10);
            long crc = littleEndianUnsignedInt(content, offset + 16);
            long compressedSize = littleEndianUnsignedInt(content, offset + 20);
            long uncompressedSize = littleEndianUnsignedInt(content, offset + 24);
            int nameLength = littleEndianUnsignedShort(content, offset + 28);
            int extraLength = littleEndianUnsignedShort(content, offset + 30);
            int commentLength = littleEndianUnsignedShort(content, offset + 32);
            int disk = littleEndianUnsignedShort(content, offset + 34);
            long localOffset = littleEndianUnsignedInt(content, offset + 42);
            int entryEnd = addBounded(
                offset, Math.addExact(46, Math.addExact(nameLength, Math.addExact(extraLength, commentLength))), eocd);
            if ((flags & ~(0x08 | 0x800)) != 0
                    || (method != ZipEntry.STORED && method != ZipEntry.DEFLATED)
                    || disk != 0
                    || compressedSize == 0xffffffffL
                    || uncompressedSize == 0xffffffffL
                    || localOffset == 0xffffffffL) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            String name = archiveName(content, offset + 46, nameLength);
            validateArchiveName(name);
            ArchiveEntry entry = new ArchiveEntry(
                name, method, flags, crc, compressedSize, uncompressedSize, localOffset);
            if (entries.putIfAbsent(name, entry) != null || !localOffsets.add(localOffset)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            validateLocalEntry(content, entry, directoryOffset);
            expanded = Math.addExact(expanded, uncompressedSize);
            compressed = Math.addExact(compressed, compressedSize);
            requireArchiveBounds(uncompressedSize, compressedSize);
            offset = entryEnd;
        }
        if (offset != eocd || expanded > MAX_ARCHIVE_EXPANDED_BYTES) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        requireArchiveBounds(expanded, compressed);
        validateLocalLayout(content, entries.values(), directoryOffset);
        return new ArchiveDirectory(Map.copyOf(entries));
    }

    private PackageEvidence inflateAndInspectPackage(
            byte[] content,
            ArchiveDirectory directory,
            Deadline deadline) {
        Set<String> seen = new HashSet<>();
        Map<String, String> contentTypeOverrides = new HashMap<>();
        Map<String, XmlRoot> xmlRoots = new HashMap<>();
        Set<String> officeDocumentTargets = new HashSet<>();
        Set<String> relationshipTargets = new HashSet<>();
        String packageMimeType = null;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry zipEntry;
            while ((zipEntry = zip.getNextEntry()) != null) {
                deadline.check();
                ArchiveEntry expected = directory.entries().get(zipEntry.getName());
                if (expected == null || !seen.add(zipEntry.getName())) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                int captureLimit = xmlEntry(zipEntry.getName()) || "mimetype".equals(zipEntry.getName())
                    ? MAX_XML_BYTES
                    : 0;
                EntryContent entry = readArchiveEntry(zip, expected, expanded, captureLimit, deadline);
                expanded = Math.addExact(expanded, entry.length());
                if (activePackageEntry(zipEntry.getName())) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                if (xmlEntry(zipEntry.getName())) {
                    XmlEvidence xml = inspectXml(
                        zipEntry.getName(), entry.content(), deadline);
                    xmlRoots.put(zipEntry.getName(), xml.root());
                    officeDocumentTargets.addAll(xml.officeDocumentTargets());
                    relationshipTargets.addAll(xml.relationshipTargets());
                    for (Map.Entry<String, String> override : xml.contentTypeOverrides().entrySet()) {
                        if (contentTypeOverrides.putIfAbsent(
                                override.getKey(), override.getValue()) != null) {
                            throw UnsupportedUploadMediaTypeException.unsupported();
                        }
                    }
                } else if ("mimetype".equals(zipEntry.getName())) {
                    packageMimeType = decodeUtf8(entry.content(), deadline).toString();
                }
                zip.closeEntry();
            }
        } catch (UnsupportedUploadMediaTypeException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        if (!seen.equals(directory.entries().keySet())
                || !seen.containsAll(relationshipTargets)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return new PackageEvidence(
            Set.copyOf(seen),
            Map.copyOf(contentTypeOverrides),
            Map.copyOf(xmlRoots),
            Set.copyOf(officeDocumentTargets),
            Set.copyOf(relationshipTargets),
            packageMimeType);
    }

    private static EntryContent readArchiveEntry(
            ZipInputStream zip,
            ArchiveEntry expected,
            long expandedBefore,
            int captureLimit,
            Deadline deadline) throws IOException {
        if (expected.uncompressedSize() > MAX_ARCHIVE_EXPANDED_BYTES - expandedBefore
                || captureLimit > 0 && expected.uncompressedSize() > captureLimit) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        byte[] captured = captureLimit > 0 ? new byte[Math.toIntExact(expected.uncompressedSize())] : null;
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        long read = 0;
        int count;
        while ((count = zip.read(buffer)) != -1) {
            deadline.check();
            if (read + count > expected.uncompressedSize()
                    || expandedBefore + read + count > MAX_ARCHIVE_EXPANDED_BYTES) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            crc.update(buffer, 0, count);
            if (captured != null) {
                System.arraycopy(buffer, 0, captured, Math.toIntExact(read), count);
            }
            read += count;
        }
        if (read != expected.uncompressedSize() || crc.getValue() != expected.crc()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return new EntryContent(read, captured == null ? new byte[0] : captured);
    }

    private static XmlEvidence inspectXml(
            String entryName,
            byte[] content,
            Deadline deadline) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            SafeXmlHandler handler = new SafeXmlHandler(deadline, entryName);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new ByteArrayInputStream(content)));
            return handler.evidence();
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private void inspectJson(byte[] content, Deadline deadline) {
        inspectText(content, deadline);
        try (JsonParser parser = objectMapper.createParser(content)) {
            if (parser.nextToken() == null
                    || parser.readValueAsTree() == null
                    || parser.nextToken() != null) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        } catch (RuntimeException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        deadline.check();
    }

    private static void inspectText(byte[] content, Deadline deadline) {
        CharBuffer text = decodeUtf8(content, deadline);
        for (int index = 0; index < text.length(); index++) {
            if ((index & 0x3fff) == 0) {
                deadline.check();
            }
            char value = text.charAt(index);
            if (value == 0 || value < 0x20 && value != '\t' && value != '\r' && value != '\n') {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
    }

    private static void inspectCsv(byte[] content, Deadline deadline) {
        CharBuffer text = decodeUtf8(content, deadline);
        boolean quoted = false;
        boolean quoteClosed = false;
        boolean fieldStart = true;
        for (int index = 0; index < text.length(); index++) {
            if ((index & 0x3fff) == 0) {
                deadline.check();
            }
            char value = text.charAt(index);
            if (value == 0 || value < 0x20 && value != '\t' && value != '\r' && value != '\n') {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (quoted) {
                if (value == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        index++;
                    } else {
                        quoted = false;
                        quoteClosed = true;
                    }
                }
                continue;
            }
            if (quoteClosed && value != ',' && value != '\r' && value != '\n') {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (value == '"') {
                if (!fieldStart) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                quoted = true;
                fieldStart = false;
            } else if (value == ',') {
                fieldStart = true;
                quoteClosed = false;
            } else if (value == '\r') {
                if (index + 1 >= text.length() || text.charAt(index + 1) != '\n') {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                index++;
                fieldStart = true;
                quoteClosed = false;
            } else if (value == '\n') {
                fieldStart = true;
                quoteClosed = false;
            } else {
                fieldStart = false;
            }
        }
        if (quoted) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static CharBuffer decodeUtf8(byte[] content, Deadline deadline) {
        deadline.check();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void requireOoxml(
            PackageEvidence evidence,
            String mainPart,
            String mainContentType,
            String rootName,
            Set<String> rootNamespaces) {
        XmlRoot contentTypesRoot = evidence.xmlRoots().get("[Content_Types].xml");
        XmlRoot relationshipsRoot = evidence.xmlRoots().get("_rels/.rels");
        XmlRoot mainRoot = evidence.xmlRoots().get(mainPart);
        long mainParts = Set.of(
                "word/document.xml", "xl/workbook.xml", "ppt/presentation.xml")
            .stream()
            .filter(evidence.names()::contains)
            .count();
        if (!evidence.names().contains("[Content_Types].xml")
                || !evidence.names().contains("_rels/.rels")
                || !evidence.names().contains(mainPart)
                || mainParts != 1
                || !mainContentType.equals(
                    evidence.contentTypeOverrides().get("/" + mainPart))
                || !Set.of(mainPart).equals(evidence.officeDocumentTargets())
                || !new XmlRoot(
                    OOXML_CONTENT_TYPES_NAMESPACE, "Types")
                    .equals(contentTypesRoot)
                || !new XmlRoot(
                    OOXML_RELATIONSHIPS_NAMESPACE, "Relationships")
                    .equals(relationshipsRoot)
                || mainRoot == null
                || !rootName.equals(mainRoot.localName())
                || !rootNamespaces.contains(mainRoot.namespaceUri())) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void requireOdf(
            UploadFormat format,
            PackageEvidence evidence,
            ArchiveDirectory directory) {
        String expectedMimeType = format.contentTypes().iterator().next();
        ArchiveEntry mimeTypeEntry = directory.entries().get("mimetype");
        XmlRoot contentRoot = evidence.xmlRoots().get("content.xml");
        XmlRoot manifestRoot = evidence.xmlRoots().get("META-INF/manifest.xml");
        if (!expectedMimeType.equals(evidence.packageMimeType())
                || mimeTypeEntry == null
                || mimeTypeEntry.method() != ZipEntry.STORED
                || mimeTypeEntry.localOffset() != 0
                || !evidence.names().contains("content.xml")
                || !evidence.names().contains("META-INF/manifest.xml")
                || !new XmlRoot(
                    "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
                    "document-content").equals(contentRoot)
                || !new XmlRoot(
                    "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0",
                    "manifest").equals(manifestRoot)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static String archiveName(byte[] content, int offset, int length) {
        if (length == 0 || length > 512) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        for (int index = 0; index < length; index++) {
            int value = unsigned(content[offset + index]);
            if (value == 0 || value >= 0x80) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        return new String(content, offset, length, StandardCharsets.US_ASCII);
    }

    private static void validateArchiveName(String name) {
        if (name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String[] segments = name.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() && index != segments.length - 1
                    || ".".equals(segment)
                    || "..".equals(segment)
                    || segment.endsWith(".")
                    || segment.endsWith(" ")) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            for (int character = 0; character < segment.length(); character++) {
                if (segment.charAt(character) < 0x20) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
            }
        }
    }

    private static void validateLocalEntry(byte[] content, ArchiveEntry entry, long directoryOffset) {
        if (entry.localOffset() > Integer.MAX_VALUE) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int offset = Math.toIntExact(entry.localOffset());
        if (offset + 30 > directoryOffset || littleEndianUnsignedInt(content, offset) != 0x04034b50L) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int flags = littleEndianUnsignedShort(content, offset + 6);
        int method = littleEndianUnsignedShort(content, offset + 8);
        int nameLength = littleEndianUnsignedShort(content, offset + 26);
        int extraLength = littleEndianUnsignedShort(content, offset + 28);
        int dataOffset = addBounded(offset, Math.addExact(30, Math.addExact(nameLength, extraLength)), content.length);
        if (flags != entry.flags()
                || (flags & 0x01) != 0
                || method != entry.method()
                || nameLength != entry.name().length()
                || !entry.name().equals(archiveName(content, offset + 30, nameLength))
                || dataOffset + entry.compressedSize() > directoryOffset) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void validateLocalLayout(
            byte[] content,
            java.util.Collection<ArchiveEntry> entries,
            long directoryOffset) {
        List<ArchiveEntry> ordered = new ArrayList<>(entries);
        ordered.sort(java.util.Comparator.comparingLong(ArchiveEntry::localOffset));
        long expectedOffset = 0;
        for (ArchiveEntry entry : ordered) {
            if (entry.localOffset() != expectedOffset) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            int offset = Math.toIntExact(entry.localOffset());
            int nameLength = littleEndianUnsignedShort(content, offset + 26);
            int extraLength = littleEndianUnsignedShort(content, offset + 28);
            long dataOffset = Math.addExact(
                entry.localOffset(), Math.addExact(30L, nameLength + (long) extraLength));
            long dataEnd = Math.addExact(dataOffset, entry.compressedSize());
            if ((entry.flags() & 0x08) != 0) {
                if (dataEnd + 16 > directoryOffset
                        || littleEndianUnsignedInt(content, Math.toIntExact(dataEnd)) != 0x08074b50L
                        || littleEndianUnsignedInt(content, Math.toIntExact(dataEnd + 4)) != entry.crc()
                        || littleEndianUnsignedInt(content, Math.toIntExact(dataEnd + 8)) != entry.compressedSize()
                        || littleEndianUnsignedInt(content, Math.toIntExact(dataEnd + 12)) != entry.uncompressedSize()) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                dataEnd += 16;
            }
            expectedOffset = dataEnd;
        }
        if (expectedOffset != directoryOffset) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static boolean activePackageEntry(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith("vbaproject.bin")
            || normalized.contains("/activex/")
            || normalized.contains("/embeddings/")
            || normalized.contains("/oleobject")
            || normalized.contains("/customui/")
            || normalized.startsWith("customui/")
            || normalized.contains("/externallinks/")
            || normalized.contains("/ddelinks/")
            || normalized.contains("/olelinks/")
            || normalized.contains("/querytables/")
            || normalized.endsWith("/connections.xml")
            || normalized.startsWith("scripts/")
            || normalized.startsWith("basic/")
            || normalized.startsWith("object ")
            || normalized.contains("/object ")
            || normalized.startsWith("objectreplacements/")
            || normalized.endsWith(".bin")
            || normalized.endsWith(".svg")
            || normalized.endsWith(".html")
            || normalized.endsWith(".htm")
            || normalized.endsWith(".js")
            || normalized.endsWith(".exe")
            || normalized.endsWith(".dll")
            || normalized.endsWith(".com")
            || normalized.endsWith(".scr")
            || normalized.endsWith(".msi")
            || normalized.endsWith(".jar")
            || normalized.endsWith(".class")
            || normalized.endsWith(".ps1")
            || normalized.endsWith(".sh")
            || normalized.endsWith(".bat")
            || normalized.endsWith(".cmd")
            || normalized.endsWith(".hta")
            || normalized.endsWith(".swf");
    }

    private static boolean xmlEntry(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".xml") || normalized.endsWith(".rels");
    }

    private static void requireArchiveBounds(long expanded, long compressed) {
        if (expanded > MAX_ARCHIVE_EXPANDED_BYTES
                || expanded > Math.multiplyExact(
                    Math.max(1, compressed), MAX_COMPRESSION_RATIO)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static byte[] readExact(UploadSource source, Deadline deadline) {
        int expected = Math.toIntExact(source.contentLength());
        try (InputStream input = source.openStream()) {
            byte[] content = new byte[expected];
            int offset = 0;
            while (offset < expected) {
                deadline.check();
                int read = input.read(content, offset, Math.min(8192, expected - offset));
                if (read < 0) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                offset += read;
            }
            deadline.check();
            if (input.read() != -1) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            return content;
        } catch (UnsupportedUploadMediaTypeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Uploaded file could not be read");
        }
    }

    private static RuntimeException inspectedFailure(Throwable cause) {
        if (cause instanceof UnsupportedUploadMediaTypeException exception) {
            return exception;
        }
        if (cause instanceof BadRequestException exception) {
            return exception;
        }
        if (cause instanceof RequestBodyTooLargeException exception) {
            return exception;
        }
        if (cause instanceof ServiceUnavailableException exception) {
            return exception;
        }
        return UnsupportedUploadMediaTypeException.unsupported();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int addBounded(int offset, int length, long bound) {
        int result;
        try {
            result = Math.addExact(offset, length);
        } catch (ArithmeticException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        if (length < 0 || result > bound) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return result;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static int bigEndianUnsignedShort(byte[] content, int offset) {
        return unsigned(content[offset]) << 8 | unsigned(content[offset + 1]);
    }

    private static long bigEndianUnsignedInt(byte[] content, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(content, offset, 4).getInt());
    }

    private static int littleEndianUnsignedShort(byte[] content, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(content, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static long littleEndianUnsignedInt(byte[] content, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(content, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static boolean startsWith(byte[] content, byte[] expected) {
        return content.length >= expected.length
            && Arrays.equals(Arrays.copyOf(content, expected.length), expected);
    }

    private static boolean asciiEquals(byte[] content, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > content.length) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (content[offset + index] != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] content, int offset, int length) {
        return new String(content, offset, length, StandardCharsets.US_ASCII);
    }

    private static String replaceExtension(String fileName, String extension) {
        int dot = fileName.lastIndexOf('.');
        String base = dot <= 0 ? fileName : fileName.substring(0, dot);
        return base + "." + extension;
    }

    private static int lastIndexOf(
            byte[] content,
            byte[] target,
            int before,
            int minimum,
            Deadline deadline) {
        for (int offset = before - target.length; offset >= minimum; offset--) {
            if ((offset & 0x3ff) == 0) {
                deadline.check();
            }
            boolean matched = true;
            for (int index = 0; index < target.length; index++) {
                if (content[offset + index] != target[index]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return offset;
            }
        }
        return -1;
    }

    private static int indexOf(
            byte[] content,
            String target,
            int from,
            int to,
            Deadline deadline) {
        for (int offset = Math.max(0, from); offset + target.length() <= to; offset++) {
            if ((offset & 0x3fff) == 0) {
                deadline.check();
            }
            if (asciiEquals(content, offset, target)) {
                return offset;
            }
        }
        return -1;
    }

    private static int parsePdfInteger(byte[] content, int from, int to) {
        long value = 0;
        if (from >= to) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        for (int index = from; index < to; index++) {
            byte digit = content[index];
            if (digit < '0' || digit > '9') {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            value = value * 10 + digit - '0';
            if (value > Integer.MAX_VALUE) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        return (int) value;
    }

    private static int parsePositivePdfInteger(String value) {
        int parsed = parsePdfInteger(value);
        if (parsed <= 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return parsed;
    }

    private static int parsePdfInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static boolean isPdfWhitespace(byte value) {
        return value == 0 || value == '\t' || value == '\n' || value == '\f' || value == '\r' || value == ' ';
    }

    private static boolean isPdfDelimiter(byte value) {
        return value == '(' || value == ')' || value == '<' || value == '>'
            || value == '[' || value == ']' || value == '{' || value == '}'
            || value == '/' || value == '%';
    }

    /**
     * Immutable content admitted for the storage boundary and later malware inspection.
     *
     * @param fileName sanitized display file name
     * @param contentType normalized declared media type
     * @param extension normalized extension
     * @param format structurally verified real format
     * @param content exact verified bytes
     * @param sha256 SHA-256 of the verified bytes
     */
    public record InspectedUpload(
            String fileName,
            String contentType,
            String extension,
            UploadFormat format,
            byte[] content,
            byte[] sha256) {
        public InspectedUpload {
            content = content.clone();
            sha256 = sha256.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        /** @return a repeatable source backed only by the verified immutable bytes */
        public UploadSource source() {
            return UploadSource.from(fileName, contentType, content);
        }

        /** @return exact verified byte count */
        public long contentLength() {
            return content.length;
        }
    }

    private record ArchiveDirectory(Map<String, ArchiveEntry> entries) {}

    private record ArchiveEntry(
            String name,
            int method,
            int flags,
            long crc,
            long compressedSize,
            long uncompressedSize,
            long localOffset) {}

    private record EntryContent(long length, byte[] content) {}

    private record InspectedContent(
            String fileName,
            String contentType,
            String extension,
            UploadFormat format,
            byte[] content) {
        private static InspectedContent original(ValidatedUpload metadata, byte[] content) {
            return new InspectedContent(
                metadata.fileName(),
                metadata.contentType(),
                metadata.extension(),
                metadata.format(),
                content);
        }
    }

    private record PackageEvidence(
            Set<String> names,
            Map<String, String> contentTypeOverrides,
            Map<String, XmlRoot> xmlRoots,
            Set<String> officeDocumentTargets,
            Set<String> relationshipTargets,
            String packageMimeType) {}

    private record XmlEvidence(
            Map<String, String> contentTypeOverrides,
            Set<String> officeDocumentTargets,
            Set<String> relationshipTargets,
            XmlRoot root) {}

    private record XmlRoot(String namespaceUri, String localName) {}

    private record PdfCrossReference(
            Map<Integer, PdfXrefEntry> entries,
            int rootObject) {}

    private record PdfXrefEntry(int offset, int generation, boolean inUse) {}

    private static final class PdfCursor {
        private final byte[] content;
        private final int limit;
        private final Deadline deadline;
        private int position;

        private PdfCursor(byte[] content, int position, int limit, Deadline deadline) {
            this.content = content;
            this.position = position;
            this.limit = limit;
            this.deadline = deadline;
        }

        private boolean hasRemaining() {
            return position < limit;
        }

        private int position() {
            return position;
        }

        private boolean startsWith(String value) {
            deadline.check();
            return position + value.length() <= limit
                && asciiEquals(content, position, value);
        }

        private void requireToken(String value) {
            if (!startsWith(value)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            position += value.length();
            if (position < limit && !isPdfWhitespace(content[position])
                    && !isPdfDelimiter(content[position])) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }

        private void skipWhitespace() {
            while (position < limit && isPdfWhitespace(content[position])) {
                position++;
            }
            deadline.check();
        }

        private void requireWhitespace() {
            if (position >= limit || !isPdfWhitespace(content[position])) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            skipWhitespace();
        }

        private int readInteger() {
            deadline.check();
            int start = position;
            while (position < limit
                    && content[position] >= '0'
                    && content[position] <= '9') {
                position++;
            }
            return parsePdfInteger(content, start, position);
        }

        private char readAsciiCharacter() {
            if (position >= limit || unsigned(content[position]) >= 128) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            return (char) content[position++];
        }
    }

    private static final class Deadline {
        private final long expiresAt;

        private Deadline(long startedAt, Duration timeout) {
            expiresAt = Math.addExact(startedAt, timeout.toNanos());
        }

        private void check() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - expiresAt >= 0) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
    }

    private static final class SafeXmlHandler extends DefaultHandler {
        private final Deadline deadline;
        private final String entryName;
        private final boolean contentTypesDocument;
        private final boolean rootRelationshipsDocument;
        private final boolean relationshipsDocument;
        private final Map<String, String> contentTypeOverrides = new HashMap<>();
        private final Set<String> officeDocumentTargets = new HashSet<>();
        private final Set<String> relationshipTargets = new HashSet<>();
        private final Set<String> relationshipIds = new HashSet<>();
        private int depth;
        private XmlRoot root;

        private SafeXmlHandler(Deadline deadline, String entryName) {
            this.deadline = deadline;
            this.entryName = entryName;
            contentTypesDocument = "[Content_Types].xml".equals(entryName);
            rootRelationshipsDocument = "_rels/.rels".equals(entryName);
            relationshipsDocument = entryName.toLowerCase(Locale.ROOT).endsWith(".rels");
        }

        @Override
        public void startElement(
                String uri,
                String localName,
                String qualifiedName,
                Attributes attributes) throws SAXException {
            deadline.check();
            depth++;
            if (depth > MAX_XML_DEPTH || attributes.getLength() > MAX_XML_ATTRIBUTES) {
                throw new SAXException("XML structure exceeds safe bounds");
            }
            String element = localName.isEmpty() ? qualifiedName : localName;
            if (depth == 1) {
                root = new XmlRoot(uri, element);
            }
            String normalizedElement = element.toLowerCase(Locale.ROOT);
            if (ACTIVE_XML_ELEMENTS.contains(normalizedElement)) {
                throw new SAXException("Active XML content is not allowed");
            }
            if (formulaElement(uri, normalizedElement)) {
                throw new SAXException("Spreadsheet formulas are not allowed");
            }
            if (contentTypesDocument && "Override".equals(element)) {
                if (depth != 2 || !OOXML_CONTENT_TYPES_NAMESPACE.equals(uri)) {
                    throw new SAXException("Package content types are invalid");
                }
                String partName = attribute(attributes, "PartName");
                String contentType = attribute(attributes, "ContentType");
                if (partName == null
                        || contentType == null
                        || !safePartName(partName)
                        || contentTypeOverrides.putIfAbsent(partName, contentType) != null) {
                    throw new SAXException("Package content types are ambiguous");
                }
            }
            if (relationshipsDocument && "Relationship".equals(element)) {
                inspectRelationship(uri, attributes);
            }
            for (int index = 0; index < attributes.getLength(); index++) {
                String name = attributes.getLocalName(index).isEmpty()
                    ? attributes.getQName(index)
                    : attributes.getLocalName(index);
                String value = attributes.getValue(index);
                if ("ContentType".equalsIgnoreCase(name)) {
                    String normalized = value.toLowerCase(Locale.ROOT);
                    if (normalized.contains("macroenabled") || normalized.contains("vba")) {
                        throw new SAXException("Active package content is not allowed");
                    }
                }
                String normalizedName = name.toLowerCase(Locale.ROOT);
                if ("instr".equals(normalizedName)
                        || normalizedName.contains("formula")
                        || "refersto".equals(normalizedName)) {
                    throw new SAXException("Active document instruction is not allowed");
                }
                if ("TargetMode".equalsIgnoreCase(name) && "External".equalsIgnoreCase(value)) {
                    throw new SAXException("External package relationship is not allowed");
                }
                if ("href".equalsIgnoreCase(name)) {
                    String referencedTarget = normalizePackageReference(value);
                    if (referencedTarget != null) {
                        relationshipTargets.add(referencedTarget);
                    }
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qualifiedName) {
            deadline.check();
            depth--;
        }

        @Override
        public void characters(char[] characters, int start, int length) {
            deadline.check();
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            throw new SAXException("XML processing instructions are not allowed");
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            throw new SAXException("XML entities are not allowed");
        }

        private XmlEvidence evidence() {
            if (root == null) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (relationshipsDocument
                    && (!entryName.endsWith(".rels")
                        || !new XmlRoot(OOXML_RELATIONSHIPS_NAMESPACE, "Relationships")
                            .equals(root))) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            return new XmlEvidence(
                Map.copyOf(contentTypeOverrides),
                Set.copyOf(officeDocumentTargets),
                Set.copyOf(relationshipTargets),
                root);
        }

        private void inspectRelationship(String uri, Attributes attributes)
                throws SAXException {
            if (depth != 2 || !OOXML_RELATIONSHIPS_NAMESPACE.equals(uri)) {
                throw new SAXException("Package relationship is invalid");
            }
            String id = attribute(attributes, "Id");
            String relationshipType = attribute(attributes, "Type");
            String target = attribute(attributes, "Target");
            String targetMode = attribute(attributes, "TargetMode");
            if (id == null
                    || id.isBlank()
                    || !relationshipIds.add(id)
                    || relationshipType == null
                    || relationshipType.isBlank()
                    || target == null
                    || targetMode != null && !"Internal".equalsIgnoreCase(targetMode.trim())) {
                throw new SAXException("Package relationship is invalid");
            }
            String normalizedTarget = normalizeRelationshipTarget(entryName, target);
            relationshipTargets.add(normalizedTarget);
            if (rootRelationshipsDocument
                    && OOXML_OFFICE_DOCUMENT_RELATIONSHIPS.contains(relationshipType)
                    && !officeDocumentTargets.add(normalizedTarget)) {
                throw new SAXException("Package office document relationship is ambiguous");
            }
        }

        private static String normalizeRelationshipTarget(String entryName, String target)
                throws SAXException {
            String normalized = target.trim();
            if (normalized.isEmpty()
                    || normalized.startsWith("/")
                    || normalized.endsWith("/")
                    || normalized.contains("\\")
                    || normalized.contains(":")
                    || normalized.contains("%")
                    || normalized.contains("?")
                    || normalized.contains("#")) {
                throw new SAXException("Package relationship target is invalid");
            }
            List<String> path = new ArrayList<>();
            if (!rootRelationshipName(entryName)) {
                int relationshipDirectory = entryName.lastIndexOf("/_rels/");
                if (relationshipDirectory < 0 || !entryName.endsWith(".rels")) {
                    throw new SAXException("Package relationship location is invalid");
                }
                String base = entryName.substring(0, relationshipDirectory);
                if (!base.isEmpty()) {
                    path.addAll(List.of(base.split("/")));
                }
            }
            for (String segment : normalized.split("/", -1)) {
                if (segment.isEmpty()) {
                    throw new SAXException("Package relationship target is invalid");
                }
                if (".".equals(segment)) {
                    continue;
                }
                if ("..".equals(segment)) {
                    if (path.isEmpty()) {
                        throw new SAXException("Package relationship target escapes the archive");
                    }
                    path.removeLast();
                    continue;
                }
                if (!safeArchiveSegment(segment)) {
                    throw new SAXException("Package relationship target is invalid");
                }
                path.add(segment);
            }
            if (path.isEmpty()) {
                throw new SAXException("Package relationship target is invalid");
            }
            return String.join("/", path);
        }

        private static boolean rootRelationshipName(String entryName) {
            return "_rels/.rels".equals(entryName);
        }

        private static String attribute(Attributes attributes, String expectedName) {
            for (int index = 0; index < attributes.getLength(); index++) {
                String name = attributes.getLocalName(index).isEmpty()
                    ? attributes.getQName(index)
                    : attributes.getLocalName(index);
                if (expectedName.equals(name)) {
                    return attributes.getValue(index);
                }
            }
            return null;
        }

        private static boolean safePartName(String value) {
            return value.startsWith("/") && safeArchivePath(value.substring(1));
        }

        private static boolean safeArchivePath(String value) {
            if (value.isBlank()
                    || value.startsWith("/")
                    || value.endsWith("/")
                    || value.contains("\\")
                    || value.contains(":")
                    || value.contains("%")
                    || value.contains("?")
                    || value.contains("#")) {
                return false;
            }
            for (String segment : value.split("/", -1)) {
                if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                    return false;
                }
                if (!safeArchiveSegment(segment)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean safeArchiveSegment(String segment) {
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character < 0x21 || character > 0x7e) {
                    return false;
                }
            }
            return true;
        }

        private String normalizePackageReference(String value) throws SAXException {
            String normalized = value.trim();
            if (normalized.startsWith("#")) {
                if (!safeFragment(normalized.substring(1))) {
                    throw new SAXException("Package reference is invalid");
                }
                return null;
            }
            int fragmentOffset = normalized.indexOf('#');
            String path = fragmentOffset < 0
                ? normalized
                : normalized.substring(0, fragmentOffset);
            String fragment = fragmentOffset < 0
                ? null
                : normalized.substring(fragmentOffset + 1);
            if (!safeArchivePath(path) || fragment != null && !safeFragment(fragment)) {
                throw new SAXException("Package reference is invalid");
            }
            int directoryEnd = entryName.lastIndexOf('/');
            return directoryEnd < 0
                ? path
                : entryName.substring(0, directoryEnd + 1) + path;
        }

        private static boolean safeFragment(String value) {
            if (value.isBlank()) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < 0x21 || character > 0x7e || character == '\\') {
                    return false;
                }
            }
            return true;
        }

        private static boolean formulaElement(String uri, String localName) {
            if (localName.contains("formula")
                    || "definedname".equals(localName)
                    || "refersto".equals(localName)) {
                return true;
            }
            String normalizedUri = uri.toLowerCase(Locale.ROOT);
            return "f".equals(localName)
                && (normalizedUri.contains("spreadsheet")
                    || normalizedUri.contains("/excel/")
                    || normalizedUri.contains("/chart"));
        }

    }
}
