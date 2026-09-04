package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import tools.jackson.databind.ObjectMapper;

/**
 * Independent malicious-fixture corpus for the upload content-inspection boundary.
 *
 * <p>Every fixture is built byte by byte and pushed through the public inspection API only, so the
 * corpus compiles and runs unchanged against an older inspector. That property is what makes the
 * mutation evidence meaningful: the methods marked {@code R} below were confirmed to fail when
 * {@code UploadContentInspector} is replaced by its pre-fix version, {@code M} methods fail when
 * the named guard is removed from the current inspector, and {@code G} methods hold in both
 * states as regression guards.
 *
 * <p>Confirmed red set against the pre-fix inspector (recorded from the JUnit report of the
 * mutation run): {@code rejectsOdfPictureDeclaredSvgWhateverItsName},
 * {@code rejectsOdfPictureWhoseBytesAreSvgDespitePngNameAndType},
 * {@code rejectsOdfPictureWhoseBytesAreHtmlDespiteJpegType},
 * {@code rejectsOdfRasterWhoseManifestTypeMismatchesItsMagic},
 * {@code rejectsOdfMemberMissingFromManifest}, {@code rejectsOdfManifestEntryWithoutMember},
 * {@code rejectsOdfMetadataManifestWithDoctypeOrForeignVocabulary},
 * {@code rejectsEncryptedOdfManifest}, {@code rejectsNonEmptyConfigurations2Members},
 * {@code rejectsOdfStarViewMetafilePictures}, {@code rejectsDirectoryEntriesWithContent},
 * {@code acceptsOdfMetafileAndUncompressedRasterPictures},
 * {@code acceptsOdfLayoutCacheWithinItsBound},
 * {@code rejectsPngHtmlAndGifJavaScriptPolyglotsInMediaParts},
 * {@code rejectsSvgDeclaredAsPngInMediaPart}, {@code rejectsHtmlDeclaredAsJpegInMediaPart},
 * {@code rejectsMediaPartDeclaredSvgWhateverItsName}, {@code rejectsMediaPartWithoutContentType},
 * {@code rejectsVmlDrawingWithOleObjectOrMacroFormula},
 * {@code rejectsPresentationProgramMacroOleAndFileActions},
 * {@code rejectsAudioAndVideoMediaParts},
 * {@code acceptsPrinterSettingsPartsAndRejectsCompoundFileMagicInThem},
 * {@code acceptsObfuscatedFontsWithinBoundAndRejectsOversized},
 * {@code acceptsMetafileMediaPartsAndRejectsMismatchedDeclarations},
 * {@code acceptsDigitallySignedOoxmlPackages},
 * {@code acceptsSignedOoxmlWithSignatureLineImagesAndXades},
 * {@code acceptsLegacyInspectionOfSignedDocx}.
 */
class UploadMaliciousFixtureCorpusTest {
    private static final String SVG_PAYLOAD =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><script>alert(1)</script>"
            + "</svg>";
    private static final String HTML_PAYLOAD = "<html><script>alert(1)</script></html>";
    private static final String DRAWING_NAMESPACE =
        "http://schemas.openxmlformats.org/drawingml/2006/main";

    private UploadContentInspector inspector;
    private BoundedImageValidationExecutor imageValidationExecutor;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
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

    @Test
    void rejectsOdfPictureDeclaredSvgWhateverItsName() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/logo.dat", "image/svg+xml"),
            PackageFixtures.members("Pictures/logo.dat", PackageFixtures.png())));
    }

    @Test
    void rejectsOdfPictureWhoseBytesAreSvgDespitePngNameAndType() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/a.png", "image/png"),
            PackageFixtures.members("Pictures/a.png", PackageFixtures.ascii(SVG_PAYLOAD))));
    }

    @Test
    void rejectsOdfPictureWhoseBytesAreHtmlDespiteJpegType() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/a.jpg", "image/jpeg"),
            PackageFixtures.members("Pictures/a.jpg", PackageFixtures.ascii(HTML_PAYLOAD))));
    }

    @Test
    void rejectsOdfRasterWhoseManifestTypeMismatchesItsMagic() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/a.png", "image/jpeg"),
            PackageFixtures.members("Pictures/a.png", PackageFixtures.png())));
    }

    @Test
    void rejectsOdfMemberMissingFromManifest() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            "",
            PackageFixtures.members("Pictures/x.png", PackageFixtures.png())));
    }

    @Test
    void rejectsOdfManifestEntryWithoutMember() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/y.png", "image/png"),
            PackageFixtures.members()));
    }

    @Test
    void rejectsOdfMetadataManifestWithDoctypeOrForeignVocabulary() throws Exception {
        String doctype = "<!DOCTYPE rdf:RDF [<!ENTITY x \"y\">]>"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>";
        String foreign = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
            + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\">"
            + "<office:dde-source office:dde-application=\"cmd\"/></rdf:RDF>";

        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("manifest.rdf", "application/rdf+xml"),
            PackageFixtures.members("manifest.rdf", PackageFixtures.ascii(doctype))));
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("manifest.rdf", "application/rdf+xml"),
            PackageFixtures.members("manifest.rdf", PackageFixtures.ascii(foreign))));
    }

    @Test
    void acceptsOdfMetadataManifestWrittenByLibreOffice() throws Exception {
        String metadata = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"content.xml\">"
            + "<rdf:type rdf:resource=\"http://docs.oasis-open.org/ns/office/1.2/meta/odf#"
            + "ContentFile\"/></rdf:Description>"
            + "<rdf:Description rdf:about=\"\">"
            + "<ns0:hasPart xmlns:ns0=\"http://docs.oasis-open.org/ns/office/1.2/meta/pkg#\" "
            + "rdf:resource=\"content.xml\"/></rdf:Description></rdf:RDF>";

        assertEquals(UploadFormat.ODT, accepted(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("manifest.rdf", "application/rdf+xml"),
            PackageFixtures.members("manifest.rdf", PackageFixtures.ascii(metadata)))));
    }

    @Test
    void rejectsEncryptedOdfManifest() throws Exception {
        String encrypted = "<manifest:file-entry manifest:full-path=\"Pictures/a.png\" "
            + "manifest:media-type=\"image/png\">"
            + "<manifest:encryption-data manifest:checksum=\"aGFzaA==\">"
            + "<manifest:algorithm manifest:algorithm-name=\"AES256-CBC\"/>"
            + "</manifest:encryption-data></manifest:file-entry>";

        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            encrypted,
            PackageFixtures.members("Pictures/a.png", PackageFixtures.png())));
    }

    @Test
    void rejectsNonEmptyConfigurations2Members() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry(
                "Configurations2/images/Bitmaps/x.bmp", "application/octet-stream"),
            PackageFixtures.members(
                "Configurations2/images/Bitmaps/x.bmp", new byte[40])));
    }

    @Test
    void rejectsOdfStarViewMetafilePictures() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/x.svm", "image/x-vclgraphic"),
            PackageFixtures.members(
                "Pictures/x.svm", PackageFixtures.ascii("VCLMTF\u0000\u0000payload"))));
    }

    @Test
    void rejectsDirectoryEntriesWithContent() throws Exception {
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/", "application/octet-stream"),
            PackageFixtures.members("Pictures/", PackageFixtures.ascii("evil"))));
    }

    @Test
    void acceptsOdfDirectoryEntriesAndThumbnails() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("Configurations2/toolbar/", new byte[0]);
        members.put("Thumbnails/thumbnail.png", PackageFixtures.png());

        assertEquals(UploadFormat.ODT, accepted(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Configurations2/", "application/vnd.sun.xml.ui"
                + ".configuration")
                + PackageFixtures.manifestEntry("Thumbnails/thumbnail.png", "image/png"),
            members)));
    }

    @Test
    void rejectsOdfDdeScriptEventsAndObjectOle() throws Exception {
        String dde = "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:"
            + "xmlns:office:1.0\"><office:body><office:text>"
            + "<office:dde-source office:dde-application=\"cmd\"/>"
            + "</office:text></office:body></office:document-content>";
        String scripts = "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:"
            + "xmlns:office:1.0\"><office:scripts><office:script>payload</office:script>"
            + "</office:scripts></office:document-content>";

        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT, dde, "", PackageFixtures.members()));
        refused(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT, scripts, "", PackageFixtures.members()));
    }

    @Test
    void acceptsOdfMetafileAndUncompressedRasterPictures() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("Pictures/a.emf", PackageFixtures.emf());
        members.put("Pictures/b.wmf", PackageFixtures.wmf());
        members.put("Pictures/c.tif", PackageFixtures.tiff());
        members.put("Pictures/d.bmp", PackageFixtures.bmp());

        assertEquals(UploadFormat.ODT, accepted(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/a.emf", "image/x-emf")
                + PackageFixtures.manifestEntry("Pictures/b.wmf", "image/x-wmf")
                + PackageFixtures.manifestEntry("Pictures/c.tif", "image/tiff")
                + PackageFixtures.manifestEntry("Pictures/d.bmp", "image/bmp"),
            members)));
    }

    @Test
    void acceptsOdfLayoutCacheWithinItsBound() throws Exception {
        assertEquals(UploadFormat.ODT, accepted(UploadFormat.ODT, PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("layout-cache", "application/binary"),
            PackageFixtures.members("layout-cache", PackageFixtures.printerSettings()))));
    }

    @Test
    void rejectsPngHtmlAndGifJavaScriptPolyglotsInMediaParts() throws Exception {
        byte[] pngHtml = PackageFixtures.concatenate(
            PackageFixtures.png(), PackageFixtures.ascii(HTML_PAYLOAD));
        byte[] gifScript = PackageFixtures.concatenate(
            PackageFixtures.gif(), PackageFixtures.ascii("/*payload*/=alert(1)"));

        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("png", "image/png"),
            "",
            PackageFixtures.members("word/media/image1.png", pngHtml)));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("gif", "image/gif"),
            "",
            PackageFixtures.members("word/media/image2.gif", gifScript)));
    }

    @Test
    void rejectsSvgDeclaredAsPngInMediaPart() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.override("/word/media/image1.png", "image/png"),
            "",
            PackageFixtures.members(
                "word/media/image1.png", PackageFixtures.ascii(SVG_PAYLOAD))));
    }

    @Test
    void rejectsHtmlDeclaredAsJpegInMediaPart() throws Exception {
        refused(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            PackageFixtures.defaultType("jpeg", "image/jpeg"),
            "",
            PackageFixtures.members(
                "xl/media/image1.jpeg", PackageFixtures.ascii(HTML_PAYLOAD))));
    }

    @Test
    void rejectsMediaPartDeclaredSvgWhateverItsName() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.override("/word/media/icon.dat", "image/svg+xml"),
            "",
            PackageFixtures.members("word/media/icon.dat", PackageFixtures.png())));
    }

    @Test
    void rejectsMediaPartWithoutContentType() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "",
            PackageFixtures.members("word/media/image1.xyz", PackageFixtures.png())));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "",
            PackageFixtures.members("word/media/image1.png", PackageFixtures.png())));
    }

    @Test
    void acceptsMediaPartsBoundToTheirRealFormat() throws Exception {
        Map<String, byte[]> members = new LinkedHashMap<>();
        members.put("word/media/image1.png", PackageFixtures.png());
        members.put("word/media/image2.jpeg", PackageFixtures.jpeg());
        members.put("word/media/image3.gif", PackageFixtures.gif());

        assertEquals(UploadFormat.DOCX, accepted(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("png", "image/png")
                + PackageFixtures.defaultType("jpeg", "image/jpeg")
                + PackageFixtures.defaultType("gif", "image/gif"),
            "",
            members)));
    }

    @Test
    void rejectsVmlDrawingWithOleObjectOrMacroFormula() throws Exception {
        String ole = vml("<o:OLEObject Type=\"Embed\" ProgID=\"Package\" ShapeID=\"s1\"/>");
        String macro = vml("<x:FmlaMacro>[0]!payload</x:FmlaMacro>");

        refused(UploadFormat.XLSX, vmlPackage(ole));
        refused(UploadFormat.XLSX, vmlPackage(macro));
    }

    @Test
    void acceptsVmlDrawingForLegacyCellComments() throws Exception {
        String comment = vml("<v:shapetype id=\"_x0000_t202\" coordsize=\"21600,21600\">"
            + "<v:formulas><v:f eqn=\"sum @0 1 0\"/></v:formulas>"
            + "<v:path gradientshapeok=\"t\" o:connecttype=\"rect\"/></v:shapetype>"
            + "<v:shape id=\"s1\" type=\"#_x0000_t202\" style=\"position:absolute\">"
            + "<x:ClientData ObjectType=\"Note\"><x:MoveWithCells/><x:Row>0</x:Row>"
            + "<x:Column>0</x:Column></x:ClientData></v:shape>");

        assertEquals(UploadFormat.XLSX, accepted(UploadFormat.XLSX, vmlPackage(comment)));
    }

    @Test
    void rejectsPresentationProgramMacroOleAndFileActions() throws Exception {
        refused(UploadFormat.PPTX, presentationActionPackage("ppaction://program"));
        refused(UploadFormat.PPTX, presentationActionPackage("ppaction://macro?name=payload"));
        refused(UploadFormat.PPTX, presentationActionPackage("ppaction://ole?verb=0"));
        refused(UploadFormat.PPTX, presentationActionPackage("ppaction://hlinkfile"));
        refused(UploadFormat.PPTX, presentationActionPackage("ppaction://hlinkpres?slideindex=1"));
    }

    @Test
    void acceptsPresentationNavigationActions() throws Exception {
        assertEquals(UploadFormat.PPTX, accepted(
            UploadFormat.PPTX, presentationActionPackage("ppaction://noaction")));
        assertEquals(UploadFormat.PPTX, accepted(
            UploadFormat.PPTX,
            presentationActionPackage("ppaction://hlinkshowjump?jump=nextslide")));
        assertEquals(UploadFormat.PPTX, accepted(
            UploadFormat.PPTX, presentationActionPackage("ppaction://customshow?id=0")));
    }

    @Test
    void rejectsAudioAndVideoMediaParts() throws Exception {
        refused(UploadFormat.PPTX, PackageFixtures.ooxml(
            UploadFormat.PPTX,
            PackageFixtures.defaultType("mp4", "video/mp4"),
            "",
            PackageFixtures.members(
                "ppt/media/media1.mp4", PackageFixtures.ascii("\u0000\u0000\u0000 ftypisom"))));
        refused(UploadFormat.PPTX, PackageFixtures.ooxml(
            UploadFormat.PPTX,
            PackageFixtures.defaultType("wav", "audio/wav"),
            "",
            PackageFixtures.members("ppt/media/media1.wav", PackageFixtures.ascii("RIFFWAVE"))));
    }

    @Test
    void acceptsPrinterSettingsPartsAndRejectsCompoundFileMagicInThem() throws Exception {
        String declaration = PackageFixtures.defaultType(
            "bin",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.printerSettings");
        byte[] compoundFile = new byte[256];
        System.arraycopy(
            new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1},
            0, compoundFile, 0, 8);

        assertEquals(UploadFormat.XLSX, accepted(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            declaration,
            "",
            PackageFixtures.members(
                "xl/printerSettings/printerSettings1.bin",
                PackageFixtures.printerSettings()))));
        refused(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            declaration,
            "",
            PackageFixtures.members(
                "xl/printerSettings/printerSettings1.bin", compoundFile)));
        refused(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            declaration,
            "",
            PackageFixtures.members(
                "xl/printerSettings/settings.bin", PackageFixtures.printerSettings())));
    }

    @Test
    void acceptsObfuscatedFontsWithinBoundAndRejectsOversized() throws Exception {
        String declaration = PackageFixtures.defaultType(
            "odttf", "application/vnd.openxmlformats-officedocument.obfuscatedFont");

        assertEquals(UploadFormat.DOCX, accepted(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            declaration,
            "",
            PackageFixtures.members(
                "word/fonts/font1.odttf", PackageFixtures.obfuscatedFont(1024)))));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            declaration,
            "",
            PackageFixtures.members(
                "word/fonts/font1.odttf",
                PackageFixtures.obfuscatedFont(17 * 1024 * 1024))));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            declaration,
            "",
            PackageFixtures.members(
                "word/fonts/font1.odttf", PackageFixtures.ascii(SVG_PAYLOAD))));
    }

    @Test
    void acceptsMetafileMediaPartsAndRejectsMismatchedDeclarations() throws Exception {
        assertEquals(UploadFormat.DOCX, accepted(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("emf", "image/x-emf"),
            "",
            PackageFixtures.members("word/media/image1.emf", PackageFixtures.emf()))));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("emf", "image/png"),
            "",
            PackageFixtures.members("word/media/image1.emf", PackageFixtures.emf())));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("emf", "image/x-emf"),
            "",
            PackageFixtures.members(
                "word/media/image1.emf", PackageFixtures.ascii(HTML_PAYLOAD))));
    }

    @ParameterizedTest
    @EnumSource(value = UploadFormat.class, names = {"DOCX", "XLSX", "PPTX"})
    void acceptsDigitallySignedOoxmlPackages(UploadFormat format) throws Exception {
        assertEquals(format, accepted(format, PackageFixtures.signedOoxml(
            format, PackageFixtures.officeSignatureXml(format, false, false, ""))));
    }

    @Test
    void acceptsSignedOoxmlWithSignatureLineImagesAndXades() throws Exception {
        assertEquals(UploadFormat.DOCX, accepted(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, true, true, ""))));
    }

    @Test
    void acceptsLegacyInspectionOfSignedDocx() throws Exception {
        byte[] signed = PackageFixtures.signedOoxml(
            UploadFormat.DOCX, PackageFixtures.officeSignatureXml(
                UploadFormat.DOCX, false, false, ""));

        assertEquals(UploadFormat.DOCX, inspector.inspectLegacyAttachment(
            UploadSource.from("signed.docx", "application/octet-stream", signed)).format());
    }

    @Test
    void rejectsUnreferencedSignaturePart() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false, ""),
            false,
            true,
            "_xmlsignatures/sig1.xml",
            PackageFixtures.SIGNATURE_CONTENT_TYPE));
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false, ""),
            true,
            false,
            "_xmlsignatures/sig1.xml",
            PackageFixtures.SIGNATURE_CONTENT_TYPE));
    }

    @Test
    void rejectsSignaturePartOutsideXmlSignaturesFolder() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.override("/word/sig1.xml", PackageFixtures.SIGNATURE_CONTENT_TYPE),
            "",
            PackageFixtures.members(
                "word/sig1.xml",
                PackageFixtures.ascii(PackageFixtures.officeSignatureXml(
                    UploadFormat.DOCX, false, false, "")))));
    }

    @Test
    void rejectsSignatureDeclaredWithWrongContentType() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false, ""),
            true,
            true,
            "_xmlsignatures/sig1.xml",
            "application/xml"));
    }

    @Test
    void rejectsSignatureManifestReferencingVbaProjectOrMissingPart() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(
                "/word/vbaProject.bin?ContentType=application/vnd.ms-office.vbaProject",
                false,
                false,
                "")));
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(
                "/word/missing.xml?ContentType=application/xml", false, false, "")));
    }

    @Test
    void rejectsScriptSmuggledIntoSignaturePart() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false,
                "<Object><xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" "
                    + "version=\"1.0\"/></Object>")));
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false,
                "<Object><script>alert(1)</script></Object>")));
        refused(UploadFormat.DOCX, PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false,
                "<Object><w:object xmlns:w=\"http://schemas.openxmlformats.org/"
                    + "wordprocessingml/2006/main\"/></Object>")));
    }

    @Test
    void rejectsMacroSignaturesAndMacroEnabledPackages() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "",
            PackageFixtures.members(
                "word/vbaProject.bin", PackageFixtures.printerSettings())));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "",
            PackageFixtures.members(
                "word/vbaProjectSignature.bin", PackageFixtures.printerSettings())));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.override("/word/document.xml", "application/vnd.ms-word.document"
                + ".macroEnabled.main+xml"),
            "",
            PackageFixtures.members()));
    }

    @Test
    void rejectsDsObjectOutsideSignatureParts() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><Object xmlns=\"http://www.w3.org/2000/09/xmldsig#\"/></w:body>"
                + "</w:document>",
            "",
            "",
            PackageFixtures.members()));
    }

    @Test
    void rejectsWordDdeAutoIncludeTextAndAltChunk() throws Exception {
        refused(UploadFormat.DOCX, wordBody(
            "<w:fldSimple w:instr=\"DDEAUTO c:\\\\windows\\\\system32\\\\cmd.exe\"/>"));
        refused(UploadFormat.DOCX, wordBody(
            "<w:fldSimple w:instr=\"INCLUDETEXT https://example.invalid/payload\"/>"));
        refused(UploadFormat.DOCX, wordBody("<w:altChunk r:id=\"rId9\" xmlns:r=\"http://schemas"
            + ".openxmlformats.org/officeDocument/2006/relationships\"/>"));
    }

    @Test
    void rejectsOleEmbeddingsExternalLinksWebQueriesAndConnections() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "",
            PackageFixtures.members(
                "word/embeddings/oleObject1.bin", PackageFixtures.printerSettings())));
        refused(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            "",
            "",
            PackageFixtures.members(
                "xl/externalLinks/externalLink1.xml", PackageFixtures.ascii("<x/>"))));
        refused(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            "",
            "",
            PackageFixtures.members(
                "xl/queryTables/queryTable1.xml", PackageFixtures.ascii("<x/>"))));
        refused(UploadFormat.XLSX, PackageFixtures.ooxml(
            UploadFormat.XLSX,
            "",
            "",
            PackageFixtures.members("xl/connections.xml", PackageFixtures.ascii("<x/>"))));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "<Relationship Id=\"rIdExternal\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/image\" "
                + "Target=\"https://example.invalid/payload.png\" TargetMode=\"External\"/>",
            PackageFixtures.members()));
    }

    @Test
    void rejectsXxeInternalAndExternalDtdInAnyPart() throws Exception {
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "<!DOCTYPE w:document [<!ENTITY payload \"x\">]>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/"
                + "2006/main\"><w:body/></w:document>",
            "",
            "",
            PackageFixtures.members()));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "",
            "",
            PackageFixtures.members("word/styles.xml", PackageFixtures.ascii(
                "<!DOCTYPE styles SYSTEM \"http://127.0.0.1:9/evil.dtd\"><styles/>"))));
    }

    @Test
    void rejectsEntityExpansionCompressionAndEntryCountBombs() throws Exception {
        String entity = "<!DOCTYPE x [<!ENTITY a \"aaaaaaaaaa\">"
            + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]><x>&b;</x>";
        Map<String, byte[]> manyEntries = new LinkedHashMap<>();
        for (int index = 0; index < 600; index++) {
            manyEntries.put("word/part" + index + ".xml", PackageFixtures.ascii("<p/>"));
        }

        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX, entity, "", "", PackageFixtures.members()));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX,
            PackageFixtures.defaultType("png", "image/png"),
            "",
            PackageFixtures.members("word/media/image1.png", new byte[8 * 1024 * 1024])));
        refused(UploadFormat.DOCX, PackageFixtures.ooxml(
            UploadFormat.DOCX, "", "", manyEntries));
    }

    @Test
    void rejectsTruncatedPackages() throws Exception {
        byte[] signed = PackageFixtures.signedOoxml(
            UploadFormat.DOCX,
            PackageFixtures.officeSignatureXml(UploadFormat.DOCX, false, false, ""));
        byte[] picture = PackageFixtures.odf(
            UploadFormat.ODT,
            PackageFixtures.manifestEntry("Pictures/a.png", "image/png"),
            PackageFixtures.members("Pictures/a.png", PackageFixtures.png()));

        refused(UploadFormat.DOCX, java.util.Arrays.copyOf(signed, signed.length / 2));
        refused(UploadFormat.ODT, java.util.Arrays.copyOf(picture, picture.length / 2));
    }

    private byte[] vmlPackage(String vml) throws IOException {
        return PackageFixtures.ooxml(
            UploadFormat.XLSX,
            PackageFixtures.defaultType(
                "vml", "application/vnd.openxmlformats-officedocument.vmlDrawing"),
            "",
            PackageFixtures.members("xl/drawings/vmlDrawing1.vml", PackageFixtures.ascii(vml)));
    }

    private static String vml(String body) {
        return "<xml xmlns:v=\"urn:schemas-microsoft-com:vml\" "
            + "xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
            + "xmlns:x=\"urn:schemas-microsoft-com:office:excel\">" + body + "</xml>";
    }

    private byte[] presentationActionPackage(String action) throws IOException {
        String slide = "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/"
            + "main\" xmlns:a=\"" + DRAWING_NAMESPACE + "\"><a:hlinkClick action=\"" + action
            + "\"/></p:sld>";
        return PackageFixtures.ooxml(
            UploadFormat.PPTX,
            "",
            "",
            PackageFixtures.members("ppt/slides/slide1.xml", PackageFixtures.ascii(slide)));
    }

    private byte[] wordBody(String body) throws IOException {
        return PackageFixtures.ooxml(
            UploadFormat.DOCX,
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + body + "</w:body></w:document>",
            "",
            "",
            PackageFixtures.members());
    }

    private void refused(UploadFormat format, byte[] content) {
        assertThrows(
            UnsupportedUploadMediaTypeException.class,
            () -> inspector.inspect(
                UploadPurpose.ATTACHMENT,
                UploadSource.from(
                    "corpus." + PackageFixtures.extension(format),
                    PackageFixtures.declaredContentType(format),
                    content)));
    }

    private UploadFormat accepted(UploadFormat format, byte[] content) {
        return inspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from(
                "corpus." + PackageFixtures.extension(format),
                PackageFixtures.declaredContentType(format),
                content)).format();
    }
}
