package ooo.klae.connex.backend.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedAiImage;
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
 * part. XML DTDs and external entities are disabled. OOXML is bound by exact main-part,
 * content-type, and relationship evidence with a Word field-command allowlist. ODF is bound by an
 * element-namespace allowlist plus an office-namespace element allowlist, with script and form
 * vocabularies excluded entirely. Parser error, timeout, ambiguous structure, active content, and
 * any exceeded bound all fail closed.
 *
 * <p>The returned {@link InspectedUpload} is authoritative: stored bytes, length, digest, response
 * metadata, migration verification, and downstream provider input must all derive from that exact
 * artifact rather than the original source.
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
    private static final long MAX_PDF_WORK_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_PDF_GRAPH_NODES = 100_000;
    private static final int MAX_PDF_GRAPH_DEPTH = 256;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> DANGEROUS_PDF_KEYS = Set.of(
        "AA", "EmbeddedFiles", "EF", "JavaScript", "JS", "OpenAction", "RichMediaContent",
        "XFA");
    private static final Set<String> DANGEROUS_PDF_ACTIONS = Set.of(
        "ImportData", "JavaScript", "Launch", "Movie", "Rendition", "RichMediaExecute",
        "Sound", "SubmitForm");
    private static final Set<String> DANGEROUS_PDF_ANNOTATIONS = Set.of(
        "3D", "Movie", "RichMedia", "Sound");
    private static final String OOXML_CONTENT_TYPES_NAMESPACE =
        "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String OOXML_RELATIONSHIPS_NAMESPACE =
        "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final Set<String> WORDPROCESSING_NAMESPACES = Set.of(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://purl.oclc.org/ooxml/wordprocessingml/main");
    private static final Set<String> OOXML_OFFICE_DOCUMENT_RELATIONSHIPS = Set.of(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
        "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument");
    private static final Set<String> OOXML_HYPERLINK_RELATIONSHIPS = Set.of(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
        "http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink");
    private static final Set<String> ACTIVE_OOXML_RELATIONSHIP_KINDS = Set.of(
        "activexcontrol", "activexcontrolbinary", "attachedtemplate", "control", "ctrlprop",
        "customui", "ddelink", "embeddedobject", "embeddedpackage", "externallink",
        "oleobject", "package", "querytable", "vbaproject");
    private static final String ODF_TEXT_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String ODF_OFFICE_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final Set<String> ODF_ELEMENT_NAMESPACES = Set.of(
        ODF_OFFICE_NAMESPACE,
        ODF_TEXT_NAMESPACE,
        "urn:oasis:names:tc:opendocument:xmlns:style:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:dr3d:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:chart:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:config:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:meta:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:animation:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0",
        "urn:oasis:names:tc:opendocument:xmlns:of:1.2",
        "urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0",
        "urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0",
        "urn:openoffice:names:experimental:ooo-ms-interop:xmlns:field:1.0",
        "http://openoffice.org/2004/office",
        "http://openoffice.org/2004/writer",
        "http://openoffice.org/2004/calc",
        "http://openoffice.org/2005/report",
        "http://openoffice.org/2009/office",
        "http://openoffice.org/2009/table",
        "http://openoffice.org/2010/draw",
        "http://purl.org/dc/elements/1.1/",
        "http://www.w3.org/1999/xlink",
        "http://www.w3.org/XML/1998/namespace",
        "http://www.w3.org/2003/g/data-view#",
        "http://www.w3.org/TR/css3-text/");
    private static final Set<String> ODF_OFFICE_ELEMENTS = Set.of(
        "annotation", "annotation-end", "automatic-styles", "binary-data", "body",
        "change-info", "chart", "document", "document-content", "document-meta",
        "document-settings", "document-styles", "drawing", "font-face-decls", "forms",
        "image", "master-styles", "meta", "presentation", "scripts", "settings",
        "spreadsheet", "styles", "text");
    private static final Set<String> ODF_EMPTY_ONLY_OFFICE_ELEMENTS = Set.of(
        "forms", "scripts");
    private static final Set<String> ACTIVE_XML_ELEMENTS = Set.of(
        "script", "event-listener", "event-listeners", "altchunk", "object",
        "oleobject", "control", "dde-source", "dde-connection", "dde-connection-decl",
        "dde-connection-decls", "dde-link", "dde-links", "object-ole", "applet",
        "plugin", "floating-frame", "execute-macro");
    private static final Set<String> SAFE_WORD_FIELD_COMMANDS = Set.of(
        "ADVANCE", "AUTHOR", "AUTONUM", "AUTONUMLGL", "AUTONUMOUT", "BIBLIOGRAPHY",
        "CITATION", "COMMENTS", "CREATEDATE", "DATE", "DOCPROPERTY", "DOCVARIABLE", "EDITTIME",
        "EQ", "FILENAME", "FILESIZE", "FORMCHECKBOX", "FORMDROPDOWN", "FORMTEXT",
        "INFO", "KEYWORDS", "LASTSAVEDBY", "MERGEFIELD", "NEXT", "NEXTIF", "NUMCHARS",
        "NUMPAGES", "NUMWORDS", "PAGE", "PAGEREF", "PRINTDATE", "QUOTE", "REF", "REVNUM",
        "SAVEDATE", "SECTION", "SECTIONPAGES", "SEQ", "SET", "SKIPIF", "STYLEREF",
        "SUBJECT", "SYMBOL", "TA", "TC", "TEMPLATE", "TIME", "TITLE", "TOA", "TOC", "XE");
    private static final int MAX_WORD_FIELD_INSTRUCTION_CHARACTERS = 4096;

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
        return runInspection(() -> inspectNow(purpose, source));
    }

    /**
     * Infers and verifies a legacy attachment without trusting historical declared media type.
     *
     * @param source bounded legacy content and best available historical filename
     * @return immutable verified metadata, content, and digest
     */
    public InspectedUpload inspectLegacyAttachment(UploadSource source) {
        return runInspection(() -> inspectLegacyNow(source));
    }

    private InspectedUpload runInspection(Supplier<InspectedUpload> inspection) {
        Future<InspectedUpload> future;
        try {
            future = executor.submit(inspection::get);
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
        uploadPolicy.validateLength(inspected.content().length);
        return new InspectedUpload(
            inspected.fileName(),
            inspected.contentType(),
            inspected.extension(),
            inspected.format(),
            inspected.content(),
            sha256(inspected.content()));
    }

    private InspectedUpload inspectLegacyNow(UploadSource source) {
        uploadPolicy.validateLength(source.contentLength());
        Deadline deadline = new Deadline(System.nanoTime(), timeout);
        byte[] content = readExact(source, deadline);
        UploadFormat format = inferLegacyFormat(source.fileName(), content, deadline);
        ValidatedUpload metadata = uploadPolicy.validateLegacyAttachment(source, format);
        InspectedContent inspected = inspectContent(
            UploadPurpose.ATTACHMENT, format, content, metadata, deadline);
        uploadPolicy.validateLength(inspected.content().length);
        return new InspectedUpload(
            inspected.fileName(),
            inspected.contentType(),
            inspected.extension(),
            inspected.format(),
            inspected.content(),
            sha256(inspected.content()));
    }

    private static UploadFormat inferLegacyFormat(
            String fileName,
            byte[] content,
            Deadline deadline) {
        deadline.check();
        if (content.length >= 3
                && unsigned(content[0]) == 0xff
                && unsigned(content[1]) == 0xd8
                && unsigned(content[2]) == 0xff) {
            return UploadFormat.JPEG;
        }
        if (startsWith(content, PNG_SIGNATURE)) {
            return UploadFormat.PNG;
        }
        if (content.length >= 6
                && (asciiEquals(content, 0, "GIF87a") || asciiEquals(content, 0, "GIF89a"))) {
            return UploadFormat.GIF;
        }
        if (content.length >= 12
                && asciiEquals(content, 0, "RIFF")
                && asciiEquals(content, 8, "WEBP")) {
            return UploadFormat.WEBP;
        }
        if (asciiEquals(content, 0, "%PDF-")) {
            return UploadFormat.PDF;
        }
        if (content.length >= 4
                && littleEndianUnsignedInt(content, 0) == 0x04034b50L) {
            return inferLegacyPackageFormat(content, deadline);
        }
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".csv")) {
            return UploadFormat.CSV;
        }
        if (normalized.endsWith(".txt")) {
            return UploadFormat.TEXT;
        }
        throw UnsupportedUploadMediaTypeException.unsupported();
    }

    private static UploadFormat inferLegacyPackageFormat(byte[] content, Deadline deadline) {
        ArchiveDirectory directory = readArchiveDirectory(content, deadline);
        Set<String> names = directory.entries().keySet();
        List<UploadFormat> ooxml = new ArrayList<>();
        if (names.contains("word/document.xml")) {
            ooxml.add(UploadFormat.DOCX);
        }
        if (names.contains("xl/workbook.xml")) {
            ooxml.add(UploadFormat.XLSX);
        }
        if (names.contains("ppt/presentation.xml")) {
            ooxml.add(UploadFormat.PPTX);
        }
        if (ooxml.size() == 1) {
            return ooxml.getFirst();
        }
        if (!ooxml.isEmpty()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        ArchiveEntry mimeType = directory.entries().get("mimetype");
        if (mimeType == null || mimeType.method() != ZipEntry.STORED) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String value = storedArchiveEntry(content, mimeType);
        return switch (value) {
            case "application/vnd.oasis.opendocument.text" -> UploadFormat.ODT;
            case "application/vnd.oasis.opendocument.spreadsheet" -> UploadFormat.ODS;
            case "application/vnd.oasis.opendocument.presentation" -> UploadFormat.ODP;
            default -> throw UnsupportedUploadMediaTypeException.unsupported();
        };
    }

    private static String storedArchiveEntry(byte[] content, ArchiveEntry entry) {
        int offset = Math.toIntExact(entry.localOffset());
        int nameLength = littleEndianUnsignedShort(content, offset + 26);
        int extraLength = littleEndianUnsignedShort(content, offset + 28);
        int dataOffset = Math.toIntExact(Math.addExact(
            entry.localOffset(), Math.addExact(30L, nameLength + (long) extraLength)));
        int dataLength = Math.toIntExact(entry.uncompressedSize());
        int dataEnd = addBounded(dataOffset, dataLength, content.length);
        return new String(content, dataOffset, dataEnd - dataOffset, StandardCharsets.US_ASCII);
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
                byte[] canonical = inspectCsv(content, deadline);
                yield new InspectedContent(
                    metadata.fileName(),
                    metadata.contentType(),
                    metadata.extension(),
                    metadata.format(),
                    canonical);
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
            if (purpose == UploadPurpose.ASSISTANT_CONTEXT) {
                ValidatedAiImage image = imageUploadValidator.validateForAi(
                    UploadSource.from(metadata.fileName(), metadata.contentType(), content));
                byte[] canonical = image.content();
                deadline.check();
                return new InspectedContent(
                    replaceExtension(metadata.fileName(), "jpg"),
                    "image/jpeg",
                    "jpg",
                    UploadFormat.JPEG,
                    canonical);
            }
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
        } catch (BadRequestException exception) {
            if (purpose == UploadPurpose.ASSISTANT_CONTEXT) {
                throw exception;
            }
            throw UnsupportedUploadMediaTypeException.unsupported();
        } catch (RequestBodyTooLargeException | ServiceUnavailableException exception) {
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

    /**
     * Strictly parses the PDF object graph and rejects executable or automatically triggered
     * behavior in the context where PDF defines it. JavaScript and JS entries, Launch actions,
     * embedded files, RichMedia, Movie, Sound, 3D, XFA, SubmitForm, ImportData, OpenAction, and AA
     * are refused because they execute, transmit, embed, or trigger without an ordinary link
     * click. Structural names such as URI, AcroForm, Prev, XRefStm, Names, Named, and FileSpec are
     * deliberately allowed because they represent ordinary links, static forms, incremental
     * revisions, hybrid cross-reference data, and file references unless an active action, XFA
     * form, or embedded payload is attached to them.
     */
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
        deadline.check();
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(content);
                PDDocument document = new PDFParser(
                    source,
                    "",
                    null,
                    null,
                    MemoryUsageSetting.setupMainMemoryOnly(MAX_PDF_WORK_BYTES).streamCache)
                    .parse(false)) {
            deadline.check();
            if (document.isEncrypted() || document.getNumberOfPages() <= 0) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            inspectPdfGraph(document.getDocumentCatalog().getCOSObject(), deadline);
        } catch (UnsupportedUploadMediaTypeException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static void inspectPdfGraph(COSBase root, Deadline deadline) {
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PdfGraphNode> pending = new ArrayList<>();
        pending.add(new PdfGraphNode(root, 0));
        while (!pending.isEmpty()) {
            deadline.check();
            PdfGraphNode node = pending.removeLast();
            COSBase value = node.value();
            if (node.depth() > MAX_PDF_GRAPH_DEPTH
                    || !visited.add(value)
                    || visited.size() > MAX_PDF_GRAPH_NODES) {
                if (node.depth() > MAX_PDF_GRAPH_DEPTH
                        || visited.size() > MAX_PDF_GRAPH_NODES) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                continue;
            }
            if (value instanceof COSObject object) {
                COSBase resolved = object.getObject();
                if (resolved == null) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                pending.add(new PdfGraphNode(resolved, node.depth() + 1));
            } else if (value instanceof COSDictionary dictionary) {
                inspectPdfDictionary(dictionary);
                for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
                    if (entry.getValue() != null) {
                        pending.add(new PdfGraphNode(entry.getValue(), node.depth() + 1));
                    }
                }
            } else if (value instanceof COSArray array) {
                for (COSBase item : array) {
                    if (item != null) {
                        pending.add(new PdfGraphNode(item, node.depth() + 1));
                    }
                }
            }
        }
    }

    private static void inspectPdfDictionary(COSDictionary dictionary) {
        for (COSName key : dictionary.keySet()) {
            if (DANGEROUS_PDF_KEYS.contains(key.getName())) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        COSName type = dictionary.getCOSName(COSName.TYPE);
        if (COSName.EMBEDDED_FILE.equals(type)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        COSName action = dictionary.getCOSName(COSName.S);
        if (action != null && DANGEROUS_PDF_ACTIONS.contains(action.getName())) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        if (COSName.URI.equals(action)
                && !safeExternalHyperlink(dictionary.getString(COSName.URI))) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        COSName annotation = dictionary.getCOSName(COSName.SUBTYPE);
        if (annotation != null && DANGEROUS_PDF_ANNOTATIONS.contains(annotation.getName())) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    /**
     * Accepts only user-activated web, email, and telephone hyperlinks. Package references that
     * can cause automatic fetching, local-file access, or application-specific execution remain
     * blocked.
     */
    private static boolean safeExternalHyperlink(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.strip();
        String lowercase = normalized.toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()
                || normalized.length() > 2048
                || normalized.contains("\\")
                || lowercase.contains("%0a")
                || lowercase.contains("%0d")) {
            return false;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getUserInfo() != null) {
                return false;
            }
            return switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http", "https" -> uri.getHost() != null && !uri.getHost().isBlank();
                case "mailto", "tel" -> uri.getSchemeSpecificPart() != null
                    && !uri.getSchemeSpecificPart().isBlank();
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void inspectDocumentPackage(
            UploadFormat format,
            byte[] content,
            Deadline deadline) {
        ArchiveDirectory directory = readArchiveDirectory(content, deadline);
        boolean odfPackage = format == UploadFormat.ODT
            || format == UploadFormat.ODS
            || format == UploadFormat.ODP;
        PackageEvidence evidence = inflateAndInspectPackage(
            content, directory, deadline, odfPackage);
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
            Deadline deadline,
            boolean odfPackage) {
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
                        zipEntry.getName(), entry.content(), deadline, odfPackage);
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
            Deadline deadline,
            boolean odfPackage) {
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
            SafeXmlHandler handler = new SafeXmlHandler(deadline, entryName, odfPackage);
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

    private static byte[] inspectCsv(byte[] content, Deadline deadline) {
        CharBuffer text = decodeUtf8(content, deadline);
        StringBuilder canonical = new StringBuilder(text.length());
        boolean quoted = false;
        boolean quoteClosed = false;
        boolean fieldStart = true;
        boolean formulaPrefix = true;
        boolean alternativeDelimiterPrefix = false;
        int first = text.length() > 0 && text.charAt(0) == '\ufeff' ? 1 : 0;
        rejectSpreadsheetDelimiterDirective(text, first);
        if (first == 1) {
            canonical.append('\ufeff');
        }
        for (int index = first; index < text.length(); index++) {
            if ((index & 0x3fff) == 0) {
                deadline.check();
            }
            char value = text.charAt(index);
            if (value == '\ufeff'
                    || value == 0
                    || value < 0x20 && value != '\t' && value != '\r' && value != '\n') {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            if (quoted) {
                if (value == '"') {
                    canonical.append(value);
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        canonical.append('"');
                        formulaPrefix = false;
                        index++;
                    } else {
                        quoted = false;
                        quoteClosed = true;
                    }
                } else if (formulaPrefix && !spreadsheetWhitespace(value)) {
                    neutralizeSpreadsheetFormula(canonical, value);
                    formulaPrefix = false;
                    canonical.append(value);
                } else {
                    canonical.append(value);
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
                formulaPrefix = true;
                alternativeDelimiterPrefix = false;
            } else if (value == '\r') {
                if (index + 1 >= text.length() || text.charAt(index + 1) != '\n') {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                index++;
                canonical.append(value).append('\n');
                fieldStart = true;
                quoteClosed = false;
                formulaPrefix = true;
                alternativeDelimiterPrefix = false;
            } else if (value == '\n') {
                fieldStart = true;
                quoteClosed = false;
                formulaPrefix = true;
                alternativeDelimiterPrefix = false;
            } else {
                if ((formulaPrefix || alternativeDelimiterPrefix)
                        && !spreadsheetWhitespace(value)) {
                    neutralizeSpreadsheetFormula(canonical, value);
                    formulaPrefix = false;
                    alternativeDelimiterPrefix = false;
                }
                if (value == ';' || value == '\t') {
                    alternativeDelimiterPrefix = true;
                }
                fieldStart = false;
            }
            if (value != '\r') {
                canonical.append(value);
            }
        }
        if (quoted) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void rejectSpreadsheetDelimiterDirective(CharBuffer text, int first) {
        if (text.length() - first >= 4
                && Character.toLowerCase(text.charAt(first)) == 's'
                && Character.toLowerCase(text.charAt(first + 1)) == 'e'
                && Character.toLowerCase(text.charAt(first + 2)) == 'p'
                && text.charAt(first + 3) == '=') {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static boolean spreadsheetWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    private static void neutralizeSpreadsheetFormula(StringBuilder canonical, char value) {
        if (value == '=' || value == '+' || value == '-' || value == '@') {
            canonical.append('\'');
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
                    ODF_OFFICE_NAMESPACE,
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

    private static boolean isPdfWhitespace(byte value) {
        return value == 0 || value == '\t' || value == '\n' || value == '\f' || value == '\r' || value == ' ';
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

    private record PdfGraphNode(COSBase value, int depth) {}

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
        private final boolean odfPackage;
        private final boolean contentTypesDocument;
        private final boolean rootRelationshipsDocument;
        private final boolean relationshipsDocument;
        private final Map<String, String> contentTypeOverrides = new HashMap<>();
        private final Set<String> officeDocumentTargets = new HashSet<>();
        private final Set<String> relationshipTargets = new HashSet<>();
        private final Set<String> relationshipIds = new HashSet<>();
        private final List<WordFieldState> wordFields = new ArrayList<>();
        private int depth;
        private int instructionTextDepth;
        private int emptyOnlyOfficeElementDepth;
        private XmlRoot root;

        private SafeXmlHandler(
                Deadline deadline,
                String entryName,
                boolean odfPackage) {
            this.deadline = deadline;
            this.entryName = entryName;
            this.odfPackage = odfPackage;
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
            if (odfPackage) {
                if (emptyOnlyOfficeElementDepth > 0) {
                    throw new SAXException("ODF office element must be empty");
                }
                if (!ODF_ELEMENT_NAMESPACES.contains(uri)) {
                    throw new SAXException("ODF element namespace is not allowed");
                }
                if (ODF_OFFICE_NAMESPACE.equals(uri)
                        && !ODF_OFFICE_ELEMENTS.contains(normalizedElement)) {
                    throw new SAXException("ODF office element is not allowed");
                }
                if (ddeName(normalizedElement)) {
                    throw new SAXException("ODF DDE content is not allowed");
                }
                if (ODF_OFFICE_NAMESPACE.equals(uri)
                        && ODF_EMPTY_ONLY_OFFICE_ELEMENTS.contains(normalizedElement)) {
                    emptyOnlyOfficeElementDepth = depth;
                }
            }
            if (ACTIVE_XML_ELEMENTS.contains(normalizedElement)) {
                throw new SAXException("Active XML content is not allowed");
            }
            inspectWordFieldStart(uri, normalizedElement, attributes);
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
                if (odfPackage
                        && (ddeName(normalizedName)
                            || "automatic-update".equals(normalizedName))) {
                    throw new SAXException("ODF active attribute is not allowed");
                }
                if (("instr".equals(normalizedName)
                            && !(WORDPROCESSING_NAMESPACES.contains(uri)
                                && "fldsimple".equals(normalizedElement)))
                        || normalizedName.contains("formula")
                        || "refersto".equals(normalizedName)) {
                    throw new SAXException("Active document instruction is not allowed");
                }
                if ("href".equalsIgnoreCase(name)) {
                    if (ODF_TEXT_NAMESPACE.equals(uri)
                            && "a".equals(normalizedElement)
                            && safeExternalHyperlink(value)) {
                        continue;
                    }
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
            String element = localName.isEmpty() ? qualifiedName : localName;
            if (WORDPROCESSING_NAMESPACES.contains(uri)
                    && "instrtext".equals(element.toLowerCase(Locale.ROOT))) {
                instructionTextDepth = 0;
            }
            if (depth == emptyOnlyOfficeElementDepth) {
                emptyOnlyOfficeElementDepth = 0;
            }
            depth--;
        }

        @Override
        public void characters(char[] characters, int start, int length) throws SAXException {
            deadline.check();
            if (instructionTextDepth > 0) {
                if (wordFields.isEmpty()) {
                    throw new SAXException("Word field instruction is malformed");
                }
                WordFieldState field = wordFields.getLast();
                if (field.instruction().length() + length
                        > MAX_WORD_FIELD_INSTRUCTION_CHARACTERS) {
                    throw new SAXException("Word field instruction exceeds safe bounds");
                }
                field.instruction().append(characters, start, length);
            }
        }

        @Override
        public void endDocument() throws SAXException {
            if (!wordFields.isEmpty() || instructionTextDepth != 0) {
                throw new SAXException("Word field instruction is malformed");
            }
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
                    || target == null) {
                throw new SAXException("Package relationship is invalid");
            }
            if (activeOoxmlRelationship(relationshipType)) {
                throw new SAXException("Active package relationship is not allowed");
            }
            if (targetMode != null && "External".equalsIgnoreCase(targetMode.trim())) {
                if (!OOXML_HYPERLINK_RELATIONSHIPS.contains(relationshipType)
                        || !safeExternalHyperlink(target)) {
                    throw new SAXException("External package relationship is not allowed");
                }
                return;
            }
            if (targetMode != null && !"Internal".equalsIgnoreCase(targetMode.trim())) {
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

        /**
         * Identifies the ODF dynamic-data-exchange element and attribute family by name token.
         *
         * <p>Matching requires the hyphenated {@code dde-} token rather than a bare {@code dde}
         * substring: legitimate ODF names such as {@code hidden-text}, {@code hidden-paragraph},
         * {@code is-hidden}, and {@code embedded} contain those three letters and must keep
         * uploading.
         *
         * @param normalizedName lower-case local element or attribute name
         * @return whether the name belongs to the DDE family
         */
        private static boolean ddeName(String normalizedName) {
            return "dde".equals(normalizedName) || normalizedName.contains("dde-");
        }

        /**
         * Rejects relationship semantics that execute, attach templates, or embed application
         * objects even when an attacker disguises the target behind an otherwise inert path.
         */
        private static boolean activeOoxmlRelationship(String relationshipType) {
            int separator = relationshipType.lastIndexOf('/');
            String kind = separator < 0
                ? relationshipType
                : relationshipType.substring(separator + 1);
            return ACTIVE_OOXML_RELATIONSHIP_KINDS.contains(kind.toLowerCase(Locale.ROOT));
        }

        private void inspectWordFieldStart(
                String uri,
                String element,
                Attributes attributes) throws SAXException {
            if (!WORDPROCESSING_NAMESPACES.contains(uri)) {
                return;
            }
            if ("fldsimple".equals(element)) {
                String instruction = attribute(attributes, "instr");
                if (instruction == null) {
                    throw new SAXException("Word field instruction is malformed");
                }
                validateWordFieldInstruction(instruction);
                return;
            }
            if ("instrtext".equals(element)) {
                if (wordFields.isEmpty()
                        || wordFields.getLast().validated()
                        || instructionTextDepth != 0) {
                    throw new SAXException("Word field instruction is malformed");
                }
                instructionTextDepth = depth;
                return;
            }
            if (!"fldchar".equals(element)) {
                return;
            }
            String fieldCharacterType = attribute(attributes, "fldCharType");
            if (fieldCharacterType == null) {
                throw new SAXException("Word field instruction is malformed");
            }
            switch (fieldCharacterType.toLowerCase(Locale.ROOT)) {
                case "begin" -> wordFields.add(new WordFieldState());
                case "separate" -> validateCurrentWordField();
                case "end" -> {
                    validateCurrentWordField();
                    wordFields.removeLast();
                }
                default -> throw new SAXException("Word field instruction is malformed");
            }
        }

        private void validateCurrentWordField() throws SAXException {
            if (wordFields.isEmpty()) {
                throw new SAXException("Word field instruction is malformed");
            }
            WordFieldState field = wordFields.getLast();
            if (!field.validated()) {
                validateWordFieldInstruction(field.instruction().toString());
                field.markValidated();
            }
        }

        /**
         * Allows inert display, numbering, metadata, merge, index, and cross-reference fields used
         * by ordinary word processors. Commands outside this reviewed set fail closed, including
         * DDE, INCLUDETEXT, INCLUDEPICTURE, LINK, DATABASE, ASK, FILLIN, MACROBUTTON, and GOTOBUTTON
         * because those commands can reach external data, prompt automatically, or invoke
         * application behavior.
         */
        private static void validateWordFieldInstruction(String instruction) throws SAXException {
            String normalized = instruction.strip();
            int commandEnd = 0;
            while (commandEnd < normalized.length()
                    && Character.isLetter(normalized.charAt(commandEnd))) {
                commandEnd++;
            }
            if (commandEnd == 0
                    || !SAFE_WORD_FIELD_COMMANDS.contains(
                        normalized.substring(0, commandEnd).toUpperCase(Locale.ROOT))) {
                throw new SAXException("Active document instruction is not allowed");
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

        private static final class WordFieldState {
            private final StringBuilder instruction = new StringBuilder();
            private boolean validated;

            private StringBuilder instruction() {
                return instruction;
            }

            private boolean validated() {
                return validated;
            }

            private void markValidated() {
                validated = true;
            }
        }

    }
}
