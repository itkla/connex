package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;

class UploadPolicyTest {
    private UploadPolicy policy;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxUploadBytes(8);
        policy = new UploadPolicy(properties);
    }

    @ParameterizedTest
    @MethodSource("attachmentFormats")
    void allowsTheExplicitCrmAttachmentSet(
            String fileName,
            String contentType,
            UploadFormat format) {
        ValidatedUpload upload = policy.validate(
            UploadPurpose.ATTACHMENT,
            UploadSource.from(fileName, contentType, new byte[] {1}));

        assertEquals(format, upload.format());
    }

    @Test
    void sanitizesPathsAndAcceptsUppercaseExtensions() {
        ValidatedUpload upload = policy.validate(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("../../顧客報告.PDF", "Application/PDF; charset=binary", new byte[] {1}));

        assertEquals("顧客報告.PDF", upload.fileName());
        assertEquals("application/pdf", upload.contentType());
        assertEquals("pdf", upload.extension());
    }

    @ParameterizedTest
    @MethodSource("unsafeMetadata")
    void rejectsUnknownAmbiguousActiveAndDisguisedTypes(String fileName, String contentType) {
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> policy.validate(
                UploadPurpose.ATTACHMENT,
                UploadSource.from(fileName, contentType, new byte[] {1})));
    }

    @Test
    void enforcesPurposeSpecificSets() {
        UploadSource image = UploadSource.from("photo.gif", "image/gif", new byte[] {1});
        UploadSource document = UploadSource.from("brief.pdf", "application/pdf", new byte[] {1});
        UploadSource csv = UploadSource.from("contacts.csv", "text/csv", new byte[] {1});

        assertDoesNotThrow(() -> policy.validate(UploadPurpose.INLINE_IMAGE, image));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> policy.validate(UploadPurpose.INLINE_IMAGE, document));
        assertDoesNotThrow(() -> policy.validate(UploadPurpose.CSV_IMPORT_SOURCE, csv));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> policy.validate(UploadPurpose.CSV_IMPORT_SOURCE, image));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> policy.validate(UploadPurpose.BUSINESS_CARD_IMAGE, image));
    }

    @Test
    void rejectsConfiguredOversize() {
        assertThrows(
            RequestBodyTooLargeException.class,
            () -> policy.validate(
                UploadPurpose.ATTACHMENT,
                UploadSource.from("large.pdf", "application/pdf", new byte[9])));
    }

    @Test
    void defaultPolicyAllowsGenericAttachmentAboveScannerLimit() {
        ObjectStorageProperties defaults = new ObjectStorageProperties();
        UploadPolicy defaultPolicy = new UploadPolicy(defaults);
        UploadSource attachment = new UploadSource(
            "large.pdf",
            "application/pdf",
            8 * 1024 * 1024 + 1,
            InputStream::nullInputStream
        );

        assertDoesNotThrow(() -> defaultPolicy.validate(UploadPurpose.ATTACHMENT, attachment));
    }

    private static Stream<Arguments> attachmentFormats() {
        return Stream.of(
            Arguments.of("photo.jpg", "image/jpeg", UploadFormat.JPEG),
            Arguments.of("photo.png", "image/png", UploadFormat.PNG),
            Arguments.of("photo.gif", "image/gif", UploadFormat.GIF),
            Arguments.of("photo.webp", "image/webp", UploadFormat.WEBP),
            Arguments.of("brief.pdf", "application/pdf", UploadFormat.PDF),
            Arguments.of("brief.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                UploadFormat.DOCX),
            Arguments.of("budget.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                UploadFormat.XLSX),
            Arguments.of("deck.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                UploadFormat.PPTX),
            Arguments.of("brief.odt", "application/vnd.oasis.opendocument.text", UploadFormat.ODT),
            Arguments.of("budget.ods", "application/vnd.oasis.opendocument.spreadsheet", UploadFormat.ODS),
            Arguments.of("deck.odp", "application/vnd.oasis.opendocument.presentation", UploadFormat.ODP),
            Arguments.of("notes.txt", "text/plain", UploadFormat.TEXT),
            Arguments.of("contacts.csv", "text/csv", UploadFormat.CSV));
    }

    private static Stream<Arguments> unsafeMetadata() {
        return Stream.of(
            Arguments.of("program.exe", "application/octet-stream"),
            Arguments.of("archive.zip", "application/zip"),
            Arguments.of("macros.docm", "application/vnd.ms-word.document.macroenabled.12"),
            Arguments.of("page.html", "text/html"),
            Arguments.of("graphic.svg", "image/svg+xml"),
            Arguments.of("invoice.pdf.exe", "application/pdf"),
            Arguments.of("x.png.svg", "image/png"),
            Arguments.of("photo.png", "image/jpeg"),
            Arguments.of("brief.pdf", "application/octet-stream"),
            Arguments.of("brief.pdf", ""),
            Arguments.of("brief", "application/pdf"),
            Arguments.of(".pdf", "application/pdf"),
            Arguments.of("brief.pdf.", "application/pdf"),
            Arguments.of("brief.pdf ", "application/pdf"),
            Arguments.of("brief\uFF0Epdf", "application/pdf"),
            Arguments.of("brief\u202E.pdf", "application/pdf"));
    }
}
