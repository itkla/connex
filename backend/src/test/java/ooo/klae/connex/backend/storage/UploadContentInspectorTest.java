package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import tools.jackson.databind.ObjectMapper;

class UploadContentInspectorTest {
    private static final byte[] VALID_WEBP = Base64.getDecoder().decode(
        "UklGRlYAAABXRUJQVlA4IDoAAADwAgCdASoBAAEAAEcIhYWIhYSIAgICdaoD+AP6Ag1NGAD+/vNYf/5gZt2KO//mBv/80F4SW6//zLwASUNNVAgAAAB0ZXN0MXgxAA==");

    private ObjectStorageProperties properties;
    private UploadContentInspector inspector;
    private BoundedImageValidationExecutor imageValidationExecutor;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        UploadPolicy policy = new UploadPolicy(properties);
        imageValidationExecutor = new BoundedImageValidationExecutor();
        ImageUploadValidator imageValidator = new ImageUploadValidator(
            properties,
            policy,
            new ImageDecodeAdmissionService(properties),
            imageValidationExecutor);
        inspector = new UploadContentInspector(policy, imageValidator, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        inspector.close();
        imageValidationExecutor.close();
    }

    @ParameterizedTest
    @MethodSource("validFormats")
    void acceptsGenuineContentForEveryAllowedFormat(
            UploadPurpose purpose,
            String fileName,
            String contentType,
            UploadFormat format,
            byte[] content) throws Exception {
        InspectedUpload inspected = inspector.inspect(
            purpose, UploadSource.from(fileName, contentType, content));

        if (Set.of(UploadFormat.JPEG, UploadFormat.PNG, UploadFormat.GIF, UploadFormat.WEBP)
                .contains(format)) {
            assertTrue(Set.of(UploadFormat.JPEG, UploadFormat.PNG).contains(inspected.format()));
            assertTrue(Set.of("image/jpeg", "image/png").contains(inspected.contentType()));
            assertFalse(java.util.Arrays.equals(content, inspected.content()));
            assertTrue(ImageIO.read(new ByteArrayInputStream(inspected.content())) != null);
        } else {
            assertEquals(format, inspected.format());
            assertEquals(contentType, inspected.contentType());
            assertArrayEquals(content, inspected.content());
        }
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(inspected.content()),
            inspected.sha256());
    }

    @ParameterizedTest
    @MethodSource("invalidFormats")
    void rejectsMalformedContentForEveryAllowedFormat(
            UploadPurpose purpose,
            String fileName,
            String contentType,
            byte[] content) {
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(
                purpose, UploadSource.from(fileName, contentType, content)));
    }

    @Test
    void rejectsExtensionAndMimeSpoofing() throws Exception {
        byte[] png = image("png");

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("payload.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("payload.pdf", "application/pdf", "alert(1)".getBytes(StandardCharsets.UTF_8))));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("payload.pdf", "application/pdf", png)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("payload.png", "application/pdf", png)));
    }

    @Test
    void rejectsGifHtmlAndPdfZipPolyglots() throws Exception {
        byte[] gifPolyglot = concatenate(
            image("gif"), "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        byte[] pdfZipPolyglot = concatenate(validPdf(), officePackage(UploadFormat.DOCX));
        byte[] jpegPolyglot = concatenate(image("jpeg"), image("jpeg"));

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.INLINE_IMAGE,
                UploadSource.from("polyglot.gif", "image/gif", gifPolyglot)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("polyglot.jpg", "image/jpeg", jpegPolyglot)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("polyglot.pdf", "application/pdf", pdfZipPolyglot)));
    }

    /** Verifies a hyperlink PDF emitted by Headless Chrome 149, including its real URI action. */
    @Test
    void acceptsChromiumPdfWithUriHyperlink() throws Exception {
        byte[] pdf = fixture("chromium-hyperlink.pdf");

        assertTrue(contains(pdf, "/URI".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(UploadFormat.PDF, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("invoice.pdf", "application/pdf", pdf)).format());
    }

    @Test
    void acceptsStaticPdfFormsFileReferencesAndIncrementalUpdates() throws Exception {
        byte[] ordinaryStructure = validPdf(
            "/AcroForm << /Fields [] >> /Names << /Dests << /Names [] >> >> "
                + "/RelatedFile << /Type /Filespec /F (terms.pdf) >>");
        ByteArrayOutputStream incrementalOutput = new ByteArrayOutputStream();
        try (PDDocument document = Loader.loadPDF(ordinaryStructure)) {
            document.getDocumentCatalog().setLanguage("en-US");
            document.saveIncremental(incrementalOutput);
        }
        byte[] incrementalPdf = incrementalOutput.toByteArray();

        assertTrue(contains(incrementalPdf, "/Prev".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(UploadFormat.PDF, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("form.pdf", "application/pdf", ordinaryStructure)).format());
        assertEquals(UploadFormat.PDF, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("signed.pdf", "application/pdf", incrementalPdf)).format());
    }

    /** Verifies ordinary fields and hyperlinks emitted by LibreOffice Writer 25.2.3.2. */
    @Test
    void acceptsLibreOfficeDocumentsWithOrdinaryFieldsAndHyperlinks() throws Exception {
        byte[] docx = fixture("libreoffice-fields-source.docx");
        byte[] odt = fixture("libreoffice-fields-source.odt");
        byte[] documentXml = zipEntry(docx, "word/document.xml");
        byte[] relationships = zipEntry(docx, "word/_rels/document.xml.rels");
        byte[] simpleFields = officePackage(
            UploadFormat.DOCX,
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:fldSimple w:instr=\" TOC \\o &quot;1-3&quot; \"/>"
                + "<w:fldSimple w:instr=\" PAGE \"/></w:body></w:document>",
            null);

        assertTrue(contains(documentXml, "w:fldChar".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(contains(documentXml, "w:instrText".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(contains(relationships, "TargetMode=\"External\""
            .getBytes(StandardCharsets.US_ASCII)));
        assertEquals(UploadFormat.DOCX, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("review.docx", docxContentType(), docx)).format());
        assertEquals(UploadFormat.ODT, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from(
                "review.odt", "application/vnd.oasis.opendocument.text", odt)).format());
        assertEquals(UploadFormat.DOCX, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("fields.docx", docxContentType(), simpleFields)).format());
    }

    @Test
    void stripsImageMetadataBeforeReturningStorageEligibleBytes() throws Exception {
        byte[] script = "<script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
        byte[] gifWithComment = gifWithComment(image("gif"), script);
        byte[] jpegWithComment = jpegWithComment(image("jpeg"), script);

        InspectedUpload gif = inspector.inspect(
            UploadPurpose.INLINE_IMAGE,
            UploadSource.from("comment.gif", "image/gif", gifWithComment));
        InspectedUpload jpeg = inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("comment.jpg", "image/jpeg", jpegWithComment));

        assertFalse(contains(gif.content(), script));
        assertFalse(contains(jpeg.content(), script));
        assertTrue(Set.of(UploadFormat.JPEG, UploadFormat.PNG).contains(gif.format()));
        assertTrue(Set.of(UploadFormat.JPEG, UploadFormat.PNG).contains(jpeg.format()));
    }

    @Test
    void rejectsTruncatedPdfAndOversizedImageMetadata() throws Exception {
        byte[] pdf = validPdf();
        byte[] truncated = java.util.Arrays.copyOf(pdf, pdf.length - 5);
        byte[] metadataHeavyPng = pngWithMetadata(1024 * 1024 + 1);

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("truncated.pdf", "application/pdf", truncated)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("metadata.png", "image/png", metadataHeavyPng)));
    }

    @Test
    void rejectsXmlEntityExpansionAndHighRatioPackage() throws Exception {
        byte[] entityPackage = officePackage(
            UploadFormat.DOCX,
            "<!DOCTYPE x [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
                + "<x>&b;</x>",
            null);
        byte[] highRatioPackage = officePackage(
            UploadFormat.DOCX,
            defaultMainXml(UploadFormat.DOCX),
            new byte[2 * 1024 * 1024]);

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("entity.docx", docxContentType(), entityPackage)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("bomb.docx", docxContentType(), highRatioPackage)));
    }

    @Test
    void rejectsActivePdfAndExternalOfficeRelationships() throws Exception {
        byte[] activePdf = validPdf("/OpenAction << /S /JavaScript /JS (alert) >>");
        byte[] networkActionPdf = validPdf(
            "/A << /S /SubmitForm /F (https://example.invalid) >>");
        byte[] encryptedPdf = validPdf("", "/Encrypt 3 0 R");
        byte[] externalPackage = officePackageWithRelationship(
            "<Relationship Id=\"rId1\" Type=\""
                + "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                + "\" Target=\"https://example.invalid/image.png\" TargetMode=\"External\"/>");
        byte[] localFileHyperlinkPackage = officePackageWithRelationship(
            "<Relationship Id=\"rId1\" Type=\""
                + "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                + "\" Target=\"file:///tmp/payload\" TargetMode=\"External\"/>");
        byte[] activeOdfReference = officePackage(
            UploadFormat.ODT,
            "<office:document-content xmlns:office=\""
                + "urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + "<office:body xlink:href=\"vnd.sun.star.script:payload\"/>"
                + "</office:document-content>",
            null);
        byte[] activeOdfScript = officePackage(
            UploadFormat.ODT,
            "<office:document-content xmlns:office=\""
                + "urn:oasis:names:tc:opendocument:xmlns:office:1.0\">"
                + "<office:scripts><office:script>payload</office:script></office:scripts>"
                + "</office:document-content>",
            null);

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("active.pdf", "application/pdf", activePdf)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("network.pdf", "application/pdf", networkActionPdf)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("encrypted.pdf", "application/pdf", encryptedPdf)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("external.docx", docxContentType(), externalPackage)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "local-file.docx", docxContentType(), localFileHyperlinkPackage)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "active-reference.odt",
                    "application/vnd.oasis.opendocument.text",
                    activeOdfReference)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "active-script.odt",
                    "application/vnd.oasis.opendocument.text",
                    activeOdfScript)));
    }

    @Test
    void rejectsExecutableEmbeddedAndAutoTriggeredPdfContent() {
        List<String> dangerousCatalogEntries = List.of(
            "/Action << /S /JavaScript /JS (alert) >>",
            "/Action << /S /Launch /F (payload.exe) >>",
            "/Payload << /Type /EmbeddedFile /Length 0 >>",
            "/Annotation << /Subtype /RichMedia >>",
            "/Annotation << /Subtype /Movie >>",
            "/Annotation << /Subtype /Sound >>",
            "/Annotation << /Subtype /3D >>",
            "/AcroForm << /Fields [] /XFA [] >>",
            "/Action << /S /SubmitForm >>",
            "/Action << /S /ImportData >>",
            "/OpenAction [3 0 R /Fit]",
            "/AA << /E << /S /Named /N /NextPage >> >>");

        for (String catalogEntry : dangerousCatalogEntries) {
            byte[] pdf = validPdf(catalogEntry);
            assertThrows(UnsupportedUploadMediaTypeException.class,
                () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                    UploadSource.from("active.pdf", "application/pdf", pdf)),
                catalogEntry);
        }
    }

    @Test
    void rejectsCorruptPdfCrossReferenceAndPackageEvidenceMisbinding() throws Exception {
        byte[] corruptCrossReference = validPdf();
        int catalogEntry = findAscii(corruptCrossReference, "0000000009 00000 n");
        corruptCrossReference[catalogEntry] = '9';
        byte[] missingPageTree = validPdf();
        int pagesReference = findAscii(missingPageTree, "/Pages 2 0 R");
        missingPageTree[pagesReference + "/Pages ".length()] = '9';
        byte[] fakeContentType = packageWithFakeContentTypeInMainPart();
        byte[] competingMainParts = packageWithCompetingMainParts();

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("corrupt.pdf", "application/pdf", corruptCrossReference)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("missing-pages.pdf", "application/pdf", missingPageTree)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("fake.docx", docxContentType(), fakeContentType)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("ambiguous.docx", docxContentType(), competingMainParts)));
    }

    @Test
    void validatesEveryNestedOfficeRelationshipTarget() throws Exception {
        byte[] safeNestedRelationship = packageWithNestedRelationship(
            "media/pixel.dat", "", true);
        byte[] implicitExternalRelationship = packageWithNestedRelationship(
            "https://example.invalid/payload", "", false);

        InspectedUpload accepted = inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("nested.docx", docxContentType(), safeNestedRelationship));

        assertEquals(UploadFormat.DOCX, accepted.format());
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "implicit-external.docx",
                    docxContentType(),
                    implicitExternalRelationship)));
    }

    @Test
    void requiresRelativeOdfReferencesToResolveInsideThePackage() throws Exception {
        String content = "<office:document-content xmlns:office=\""
            + "urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<office:body xlink:href=\"Pictures/padding.dat\"/>"
            + "</office:document-content>";
        byte[] resolved = officePackage(
            UploadFormat.ODT, content, "safe".getBytes(StandardCharsets.UTF_8));
        byte[] missing = officePackage(UploadFormat.ODT, content, null);

        assertEquals(UploadFormat.ODT, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from(
                "resolved.odt",
                "application/vnd.oasis.opendocument.text",
                resolved)).format());
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "missing.odt",
                    "application/vnd.oasis.opendocument.text",
                    missing)));
    }

    @Test
    void rejectsActiveOfficeInstructionsAndAllSpreadsheetFormulas() throws Exception {
        byte[] wordDde = officePackage(
            UploadFormat.DOCX,
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:fldSimple w:instr=\"DDEAUTO payload\"/></w:body></w:document>",
            null);
        byte[] networkFormula = officePackage(
            UploadFormat.XLSX,
            "<x:workbook xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<x:f>WEBSERVICE(&quot;https://example.invalid&quot;)</x:f></x:workbook>",
            null);
        byte[] reconstructedNetworkFormula = officePackage(
            UploadFormat.XLSX,
            "<x:workbook xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<x:f>IMAGE(CHAR(104)&amp;CHAR(116)&amp;CHAR(116)&amp;CHAR(112))</x:f>"
                + "</x:workbook>",
            null);
        byte[] calculatedColumnFormula = packageWithAdditionalEntry(
            UploadFormat.XLSX,
            "xl/tables/table1.xml",
            "<table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<calculatedColumnFormula>IMAGE(CHAR(104))</calculatedColumnFormula>"
                + "</table>");
        byte[] staticMathFraction = officePackage(
            UploadFormat.DOCX,
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\">"
                + "<w:body><m:oMath><m:f><m:num/><m:den/></m:f></m:oMath></w:body>"
                + "</w:document>",
            null);

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("dde.docx", docxContentType(), wordDde)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("network.xlsx", xlsxContentType(), networkFormula)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "reconstructed-network.xlsx",
                    xlsxContentType(),
                    reconstructedNetworkFormula)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "calculated-column.xlsx",
                    xlsxContentType(),
                    calculatedColumnFormula)));
        assertEquals(UploadFormat.DOCX, inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from(
                "math.docx",
                docxContentType(),
                staticMathFraction)).format());
    }

    @Test
    void rejectsArchiveTraversalActiveEntriesAndChecksumMismatch() throws Exception {
        byte[] traversal = packageWithAdditionalEntry("../outside.xml", "<outside/>");
        byte[] active = packageWithAdditionalEntry("word/embeddings/payload.bin", "payload");
        byte[] trailingDot = packageWithAdditionalEntry("word/payload.exe.", "payload");
        byte[] checksumMismatch = officePackage(UploadFormat.ODT);
        int mimeTypeOffset = findAscii(
            checksumMismatch, "application/vnd.oasis.opendocument.text");
        checksumMismatch[mimeTypeOffset] ^= 1;

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("traversal.docx", docxContentType(), traversal)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("active.docx", docxContentType(), active)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from("trailing-dot.docx", docxContentType(), trailingDot)));
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "checksum.odt",
                    "application/vnd.oasis.opendocument.text",
                    checksumMismatch)));
    }

    @Test
    void rejectsUploadsOverTheConfiguredSizeBeforeReading() {
        properties.setMaxUploadBytes(3);
        AtomicInteger opens = new AtomicInteger();
        UploadSource source = new UploadSource(
            "large.txt", "text/plain", 4, () -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
            });

        assertThrows(RequestBodyTooLargeException.class,
            () -> inspector.inspect(UploadPurpose.ATTACHMENT, source));
        assertEquals(0, opens.get());
    }

    @Test
    void consumesTheUntrustedSourceOnceAndReturnsImmutableBytes() {
        byte[] content = "safe text".getBytes(StandardCharsets.UTF_8);
        AtomicInteger opens = new AtomicInteger();
        UploadSource source = new UploadSource(
            "notes.txt", "text/plain", content.length, () -> {
                opens.incrementAndGet();
                return new ByteArrayInputStream(content);
            });

        InspectedUpload inspected = inspector.inspect(UploadPurpose.ATTACHMENT, source);
        byte[] callerCopy = inspected.content();
        callerCopy[0] = 'X';

        assertEquals(1, opens.get());
        assertArrayEquals(content, inspected.content());
        assertArrayEquals(content, read(inspected.source()));
    }

    @Test
    void timeoutFailsClosed() {
        UploadPolicy policy = new UploadPolicy(properties);
        BoundedImageValidationExecutor timeoutImageExecutor =
            new BoundedImageValidationExecutor();
        ImageUploadValidator imageValidator = new ImageUploadValidator(
            properties,
            policy,
            new ImageDecodeAdmissionService(properties),
            timeoutImageExecutor);
        ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("upload-timeout-test").factory());
        try (timeoutImageExecutor;
                UploadContentInspector shortTimeoutInspector = new UploadContentInspector(
                policy,
                imageValidator,
                new ObjectMapper(),
                executor,
                Duration.ofMillis(25))) {
            UploadSource source = new UploadSource(
                "slow.txt", "text/plain", 1, SlowInputStream::new);

            assertThrows(UnsupportedUploadMediaTypeException.class,
                () -> shortTimeoutInspector.inspect(UploadPurpose.ATTACHMENT, source));
        }
    }

    private static Stream<Arguments> validFormats() throws Exception {
        return Stream.of(
            Arguments.of(UploadPurpose.ATTACHMENT, "image.jpg", "image/jpeg", UploadFormat.JPEG, image("jpeg")),
            Arguments.of(UploadPurpose.ATTACHMENT, "image.png", "image/png", UploadFormat.PNG, image("png")),
            Arguments.of(UploadPurpose.INLINE_IMAGE, "image.gif", "image/gif", UploadFormat.GIF, image("gif")),
            Arguments.of(UploadPurpose.ATTACHMENT, "image.webp", "image/webp", UploadFormat.WEBP, VALID_WEBP),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.pdf", "application/pdf", UploadFormat.PDF, validPdf()),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.docx", docxContentType(), UploadFormat.DOCX, officePackage(UploadFormat.DOCX)),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.xlsx", xlsxContentType(), UploadFormat.XLSX, officePackage(UploadFormat.XLSX)),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.pptx", pptxContentType(), UploadFormat.PPTX, officePackage(UploadFormat.PPTX)),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.odt", "application/vnd.oasis.opendocument.text", UploadFormat.ODT, officePackage(UploadFormat.ODT)),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.ods", "application/vnd.oasis.opendocument.spreadsheet", UploadFormat.ODS, officePackage(UploadFormat.ODS)),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.odp", "application/vnd.oasis.opendocument.presentation", UploadFormat.ODP, officePackage(UploadFormat.ODP)),
            Arguments.of(UploadPurpose.ATTACHMENT, "notes.txt", "text/plain", UploadFormat.TEXT, "hello\nworld".getBytes(StandardCharsets.UTF_8)),
            Arguments.of(UploadPurpose.CSV_IMPORT_SOURCE, "people.csv", "text/csv", UploadFormat.CSV, "name,email\nAda,ada@example.com\n".getBytes(StandardCharsets.UTF_8)),
            Arguments.of(UploadPurpose.ASSISTANT_CONTEXT, "notes.md", "text/markdown", UploadFormat.MARKDOWN, "# Notes\nSafe".getBytes(StandardCharsets.UTF_8)),
            Arguments.of(UploadPurpose.ASSISTANT_CONTEXT, "data.json", "application/json", UploadFormat.JSON, "{\"safe\":true}".getBytes(StandardCharsets.UTF_8)));
    }

    private static Stream<Arguments> invalidFormats() {
        byte[] invalidBinary = "not the declared format".getBytes(StandardCharsets.UTF_8);
        byte[] invalidUtf8 = {(byte) 0xc3, 0x28};
        return Stream.of(
            Arguments.of(UploadPurpose.ATTACHMENT, "image.jpg", "image/jpeg", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "image.png", "image/png", invalidBinary),
            Arguments.of(UploadPurpose.INLINE_IMAGE, "image.gif", "image/gif", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "image.webp", "image/webp", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.pdf", "application/pdf", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.docx", docxContentType(), invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.xlsx", xlsxContentType(), invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.pptx", pptxContentType(), invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.odt", "application/vnd.oasis.opendocument.text", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.ods", "application/vnd.oasis.opendocument.spreadsheet", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "document.odp", "application/vnd.oasis.opendocument.presentation", invalidBinary),
            Arguments.of(UploadPurpose.ATTACHMENT, "notes.txt", "text/plain", invalidUtf8),
            Arguments.of(UploadPurpose.CSV_IMPORT_SOURCE, "people.csv", "text/csv", "\"unterminated".getBytes(StandardCharsets.UTF_8)),
            Arguments.of(UploadPurpose.ASSISTANT_CONTEXT, "notes.md", "text/markdown", invalidUtf8),
            Arguments.of(UploadPurpose.ASSISTANT_CONTEXT, "data.json", "application/json", "{".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00aa44);
        image.setRGB(1, 0, 0x112233);
        image.setRGB(0, 1, 0x445566);
        image.setRGB(1, 1, 0x778899);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Test image writer is unavailable");
        }
        return output.toByteArray();
    }

    private static byte[] validPdf() {
        return validPdf("");
    }

    private static byte[] validPdf(String catalogAddition) {
        return validPdf(catalogAddition, "");
    }

    private static byte[] validPdf(String catalogAddition, String trailerAddition) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "%PDF-1.4\n");
        int catalogOffset = output.size();
        writeAscii(output, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R " + catalogAddition + " >>\nendobj\n");
        int pagesOffset = output.size();
        writeAscii(output, "2 0 obj\n<< /Type /Pages /Count 1 /Kids [3 0 R] >>\nendobj\n");
        int pageOffset = output.size();
        writeAscii(output, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 72 72] >>\nendobj\n");
        int xrefOffset = output.size();
        writeAscii(output, "xref\n0 4\n0000000000 65535 f \n");
        writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", catalogOffset));
        writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", pagesOffset));
        writeAscii(output, String.format(Locale.ROOT, "%010d 00000 n \n", pageOffset));
        writeAscii(output, "trailer\n<< /Size 4 /Root 1 0 R " + trailerAddition
            + " >>\nstartxref\n");
        writeAscii(output, Integer.toString(xrefOffset));
        writeAscii(output, "\n%%EOF\n");
        return output.toByteArray();
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = UploadContentInspectorTest.class
                .getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return input.readAllBytes();
        }
    }

    private static byte[] zipEntry(byte[] content, String expectedName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (expectedName.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IOException("Missing ZIP entry: " + expectedName);
    }

    private static byte[] officePackage(UploadFormat format) throws IOException {
        return officePackage(format, defaultMainXml(format), null);
    }

    private static byte[] officePackage(
            UploadFormat format,
            String mainXml,
            byte[] padding) throws IOException {
        if (format == UploadFormat.ODT || format == UploadFormat.ODS || format == UploadFormat.ODP) {
            return odfPackage(format, mainXml, padding);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml(format).getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", relationshipsXml(format, "").getBytes(StandardCharsets.UTF_8));
            put(zip, mainPart(format), mainXml.getBytes(StandardCharsets.UTF_8));
            if (padding != null) {
                put(zip, "docProps/padding.dat", padding);
            }
        }
        return output.toByteArray();
    }

    private static byte[] officePackageWithRelationship(String relationship) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml(UploadFormat.DOCX).getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", relationshipsXml(UploadFormat.DOCX, relationship).getBytes(StandardCharsets.UTF_8));
            put(zip, "word/document.xml", defaultMainXml(UploadFormat.DOCX).getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static byte[] packageWithFakeContentTypeInMainPart() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", ("<Types xmlns=\""
                + "http://schemas.openxmlformats.org/package/2006/content-types\"/>")
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", relationshipsXml(UploadFormat.DOCX, "")
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "word/document.xml", (defaultMainXml(UploadFormat.DOCX)
                .replace("<w:body/>", "<w:body><Override PartName=\"/word/document.xml\" "
                    + "ContentType=\"" + mainContentType(UploadFormat.DOCX)
                    + "\"/></w:body>"))
                .getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static byte[] packageWithCompetingMainParts() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml(UploadFormat.DOCX)
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", relationshipsXml(UploadFormat.DOCX, "")
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "word/document.xml", defaultMainXml(UploadFormat.DOCX)
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "xl/workbook.xml", defaultMainXml(UploadFormat.XLSX)
                .getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static byte[] packageWithAdditionalEntry(String name, String content)
            throws IOException {
        return packageWithAdditionalEntry(UploadFormat.DOCX, name, content);
    }

    private static byte[] packageWithAdditionalEntry(
            UploadFormat format,
            String name,
            String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml(format)
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", relationshipsXml(format, "")
                .getBytes(StandardCharsets.UTF_8));
            put(zip, mainPart(format), defaultMainXml(format)
                .getBytes(StandardCharsets.UTF_8));
            put(zip, name, content.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static byte[] packageWithNestedRelationship(
            String target,
            String targetMode,
            boolean includeTarget) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypesXml(UploadFormat.DOCX)
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", relationshipsXml(UploadFormat.DOCX, "")
                .getBytes(StandardCharsets.UTF_8));
            put(zip, "word/document.xml", defaultMainXml(UploadFormat.DOCX)
                .getBytes(StandardCharsets.UTF_8));
            String mode = targetMode.isBlank()
                ? ""
                : " TargetMode=\"" + targetMode + "\"";
            String relationships = "<Relationships xmlns=\""
                + "http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdImage\" Type=\""
                + "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                + "\" Target=\"" + target + "\"" + mode + "/></Relationships>";
            put(zip, "word/_rels/document.xml.rels",
                relationships.getBytes(StandardCharsets.UTF_8));
            if (includeTarget) {
                put(zip, "word/" + target, "safe".getBytes(StandardCharsets.UTF_8));
            }
        }
        return output.toByteArray();
    }

    private static byte[] odfPackage(
            UploadFormat format,
            String mainXml,
            byte[] padding) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            byte[] mimeType = format.contentTypes().iterator().next().getBytes(StandardCharsets.US_ASCII);
            ZipEntry mimeTypeEntry = new ZipEntry("mimetype");
            CRC32 crc = new CRC32();
            crc.update(mimeType);
            mimeTypeEntry.setMethod(ZipEntry.STORED);
            mimeTypeEntry.setSize(mimeType.length);
            mimeTypeEntry.setCompressedSize(mimeType.length);
            mimeTypeEntry.setCrc(crc.getValue());
            zip.putNextEntry(mimeTypeEntry);
            zip.write(mimeType);
            zip.closeEntry();
            put(zip, "content.xml", mainXml.getBytes(StandardCharsets.UTF_8));
            put(zip, "META-INF/manifest.xml", ("<manifest:manifest xmlns:manifest=\""
                + "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"/>")
                .getBytes(StandardCharsets.UTF_8));
            if (padding != null) {
                put(zip, "Pictures/padding.dat", padding);
            }
        }
        return output.toByteArray();
    }

    private static String contentTypesXml(UploadFormat format) {
        return "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Override PartName=\"/" + mainPart(format)
            + "\" ContentType=\"" + mainContentType(format) + "\"/></Types>";
    }

    private static String defaultMainXml(UploadFormat format) {
        return switch (format) {
            case DOCX -> "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>";
            case XLSX -> "<x:workbook xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>";
            case PPTX -> "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>";
            case ODT, ODS, ODP -> "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>";
            default -> throw new IllegalArgumentException("Unsupported fixture format");
        };
    }

    private static String mainPart(UploadFormat format) {
        return switch (format) {
            case DOCX -> "word/document.xml";
            case XLSX -> "xl/workbook.xml";
            case PPTX -> "ppt/presentation.xml";
            default -> throw new IllegalArgumentException("Unsupported fixture format");
        };
    }

    private static String mainContentType(UploadFormat format) {
        return switch (format) {
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            case PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
            default -> throw new IllegalArgumentException("Unsupported fixture format");
        };
    }

    private static String docxContentType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    private static String xlsxContentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private static String pptxContentType() {
        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    }

    private static String relationshipsXml(UploadFormat format, String content) {
        return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rIdOffice\" Type=\""
            + "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
            + "\" Target=\"" + mainPart(format) + "\"/>"
            + content + "</Relationships>";
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] pngWithMetadata(int metadataLength) throws Exception {
        byte[] png = image("png");
        int iendOffset = png.length - 12;
        byte[] metadata = new byte[metadataLength];
        byte[] chunk = pngChunk("tEXt", metadata);
        return concatenate(
            java.util.Arrays.copyOf(png, iendOffset),
            chunk,
            java.util.Arrays.copyOfRange(png, iendOffset, png.length));
    }

    private static byte[] gifWithComment(byte[] gif, byte[] comment) throws IOException {
        if (comment.length > 255 || gif[gif.length - 1] != 0x3b) {
            throw new IllegalArgumentException("GIF fixture cannot carry the comment");
        }
        return concatenate(
            java.util.Arrays.copyOf(gif, gif.length - 1),
            new byte[] {0x21, (byte) 0xfe, (byte) comment.length},
            comment,
            new byte[] {0, 0x3b});
    }

    private static byte[] jpegWithComment(byte[] jpeg, byte[] comment) throws IOException {
        int segmentLength = comment.length + 2;
        return concatenate(
            java.util.Arrays.copyOf(jpeg, 2),
            new byte[] {
                (byte) 0xff,
                (byte) 0xfe,
                (byte) (segmentLength >>> 8),
                (byte) segmentLength
            },
            comment,
            java.util.Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    private static byte[] pngChunk(String type, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[] {
            (byte) (content.length >>> 24),
            (byte) (content.length >>> 16),
            (byte) (content.length >>> 8),
            (byte) content.length
        });
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.write(typeBytes);
        output.write(content);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(content);
        long value = crc.getValue();
        output.write(new byte[] {
            (byte) (value >>> 24),
            (byte) (value >>> 16),
            (byte) (value >>> 8),
            (byte) value
        });
        return output.toByteArray();
    }

    private static byte[] read(UploadSource source) {
        try (InputStream input = source.openStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] concatenate(byte[]... values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.write(value);
        }
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static int findAscii(byte[] content, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset + expected.length <= content.length; offset++) {
            boolean matched = true;
            for (int index = 0; index < expected.length; index++) {
                if (content[offset + index] != expected[index]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return offset;
            }
        }
        throw new IllegalArgumentException("Fixture content was not found");
    }

    private static boolean contains(byte[] content, byte[] expected) {
        for (int offset = 0; offset + expected.length <= content.length; offset++) {
            boolean matched = true;
            for (int index = 0; index < expected.length; index++) {
                if (content[offset + index] != expected[index]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static final class SlowInputStream extends InputStream {
        private boolean delivered;

        @Override
        public int read() {
            if (delivered) {
                return -1;
            }
            waitForTimeout();
            delivered = true;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int value = read();
            if (value < 0) {
                return -1;
            }
            buffer[offset] = (byte) value;
            return 1;
        }

        private static void waitForTimeout() {
            long expiresAt = System.nanoTime() + Duration.ofMillis(250).toNanos();
            while (System.nanoTime() - expiresAt < 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.interrupted();
                }
            }
        }
    }
}
