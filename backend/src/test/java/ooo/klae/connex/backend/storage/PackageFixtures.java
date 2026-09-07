package ooo.klae.connex.backend.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;

/**
 * Builds malicious and benign OOXML and ODF package fixtures byte by byte.
 *
 * <p>The builders emit only what a test asks for, so a corpus test can express an attack as the
 * exact package a hostile client would upload rather than as a mutation of a valid document. They
 * deliberately depend on nothing inside {@code UploadContentInspector}, which keeps the corpus
 * compilable against an unfixed inspector during mutation runs.
 */
final class PackageFixtures {
    static final String DOCX_MAIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    static final String XLSX_MAIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
    static final String PPTX_MAIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
    static final String RELATIONSHIPS_NAMESPACE =
        "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String XMLDSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#";
    static final String OPC_SIGNATURE_NAMESPACE =
        "http://schemas.openxmlformats.org/package/2006/digital-signature";
    static final String OFFICE_DIGSIG_NAMESPACE =
        "http://schemas.microsoft.com/office/2006/digsig";
    static final String SIGNATURE_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.digital-signature-xmlsignature+xml";
    static final String SIGNATURE_ORIGIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.digital-signature-origin";
    static final String SIGNATURE_ORIGIN_RELATIONSHIP =
        "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/origin";
    static final String SIGNATURE_RELATIONSHIP =
        "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/signature";
    static final String ODF_MANIFEST_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";

    private PackageFixtures() {
    }

    /** @return the main document part name for an OOXML format */
    static String mainPart(UploadFormat format) {
        return switch (format) {
            case DOCX -> "word/document.xml";
            case XLSX -> "xl/workbook.xml";
            case PPTX -> "ppt/presentation.xml";
            default -> throw new IllegalArgumentException("Unsupported package format");
        };
    }

    /** @return the main document content type for an OOXML format */
    static String mainContentType(UploadFormat format) {
        return switch (format) {
            case DOCX -> DOCX_MAIN_CONTENT_TYPE;
            case XLSX -> XLSX_MAIN_CONTENT_TYPE;
            case PPTX -> PPTX_MAIN_CONTENT_TYPE;
            default -> throw new IllegalArgumentException("Unsupported package format");
        };
    }

    /** @return the declared media type a client sends for an OOXML format */
    static String declaredContentType(UploadFormat format) {
        return switch (format) {
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case PPTX ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ODT -> "application/vnd.oasis.opendocument.text";
            case ODS -> "application/vnd.oasis.opendocument.spreadsheet";
            case ODP -> "application/vnd.oasis.opendocument.presentation";
            default -> throw new IllegalArgumentException("Unsupported package format");
        };
    }

    /** @return the canonical extension a client sends for a package format */
    static String extension(UploadFormat format) {
        return switch (format) {
            case DOCX -> "docx";
            case XLSX -> "xlsx";
            case PPTX -> "pptx";
            case ODT -> "odt";
            case ODS -> "ods";
            case ODP -> "odp";
            default -> throw new IllegalArgumentException("Unsupported package format");
        };
    }

    static String defaultMainXml(UploadFormat format) {
        return switch (format) {
            case DOCX -> "<w:document xmlns:w=\"http://schemas.openxmlformats.org/"
                + "wordprocessingml/2006/main\"><w:body/></w:document>";
            case XLSX -> "<x:workbook xmlns:x=\"http://schemas.openxmlformats.org/"
                + "spreadsheetml/2006/main\"/>";
            case PPTX -> "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/"
                + "presentationml/2006/main\"/>";
            case ODT, ODS, ODP -> "<office:document-content xmlns:office=\""
                + "urn:oasis:names:tc:opendocument:xmlns:office:1.0\"/>";
            default -> throw new IllegalArgumentException("Unsupported package format");
        };
    }

    /**
     * Builds an OOXML package with the standard content types and root relationships plus any
     * extra declarations and parts a test needs.
     *
     * @param format OOXML package format
     * @param extraContentTypes additional {@code Default} and {@code Override} markup
     * @param extraRootRelationships additional {@code Relationship} markup for {@code _rels/.rels}
     * @param extraParts additional members keyed by exact archive name
     * @return package bytes
     */
    static byte[] ooxml(
            UploadFormat format,
            String extraContentTypes,
            String extraRootRelationships,
            Map<String, byte[]> extraParts) throws IOException {
        return ooxml(
            format, defaultMainXml(format), extraContentTypes, extraRootRelationships, extraParts);
    }

    static byte[] ooxml(
            UploadFormat format,
            String mainXml,
            String extraContentTypes,
            String extraRootRelationships,
            Map<String, byte[]> extraParts) throws IOException {
        return ooxml(format, mainXml, extraContentTypes, extraRootRelationships, extraParts, false);
    }

    /**
     * Builds an OOXML package, optionally writing the extra parts STORED rather than DEFLATED so
     * that a large incompressible or highly compressible member exercises a size bound without
     * first tripping the compression-ratio bound.
     */
    static byte[] ooxml(
            UploadFormat format,
            String mainXml,
            String extraContentTypes,
            String extraRootRelationships,
            Map<String, byte[]> extraParts,
            boolean storeExtraParts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "_rels/.rels", ascii("<Relationships xmlns=\"" + RELATIONSHIPS_NAMESPACE
                + "\"><Relationship Id=\"rIdOffice\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/officeDocument\" Target=\""
                + mainPart(format) + "\"/>" + extraRootRelationships + "</Relationships>"));
            put(zip, mainPart(format), ascii(mainXml));
            for (Map.Entry<String, byte[]> part : extraParts.entrySet()) {
                if (storeExtraParts) {
                    stored(zip, part.getKey(), part.getValue());
                } else {
                    put(zip, part.getKey(), part.getValue());
                }
            }
            put(zip, "[Content_Types].xml", ascii("<Types xmlns=\"http://schemas.openxmlformats"
                + ".org/package/2006/content-types\">"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-"
                + "package.relationships+xml\"/>"
                + "<Override PartName=\"/" + mainPart(format) + "\" ContentType=\""
                + mainContentType(format) + "\"/>" + extraContentTypes + "</Types>"));
        }
        return output.toByteArray();
    }

    /**
     * Builds an ODF package whose manifest lists {@code content.xml} plus any extra entries.
     *
     * @param format ODF package format
     * @param extraManifestEntries additional {@code manifest:file-entry} markup
     * @param extraMembers additional members keyed by exact archive name
     * @return package bytes
     */
    static byte[] odf(
            UploadFormat format,
            String extraManifestEntries,
            Map<String, byte[]> extraMembers) throws IOException {
        return odf(format, defaultMainXml(format), extraManifestEntries, extraMembers);
    }

    static byte[] odf(
            UploadFormat format,
            String contentXml,
            String extraManifestEntries,
            Map<String, byte[]> extraMembers) throws IOException {
        return odf(format, contentXml, extraManifestEntries, extraMembers, manifestEntry(
            "content.xml", "text/xml"));
    }

    static byte[] odf(
            UploadFormat format,
            String contentXml,
            String extraManifestEntries,
            Map<String, byte[]> extraMembers,
            String contentManifestEntry) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            byte[] mimeType = declaredContentType(format).getBytes(StandardCharsets.US_ASCII);
            stored(zip, "mimetype", mimeType);
            put(zip, "content.xml", ascii(contentXml));
            for (Map.Entry<String, byte[]> member : extraMembers.entrySet()) {
                put(zip, member.getKey(), member.getValue());
            }
            put(zip, "META-INF/manifest.xml", ascii("<manifest:manifest xmlns:manifest=\""
                + ODF_MANIFEST_NAMESPACE + "\">" + contentManifestEntry + extraManifestEntries
                + "</manifest:manifest>"));
        }
        return output.toByteArray();
    }

    static String manifestEntry(String fullPath, String mediaType) {
        return "<manifest:file-entry manifest:full-path=\"" + fullPath
            + "\" manifest:media-type=\"" + mediaType + "\"/>";
    }

    static String override(String partName, String contentType) {
        return "<Override PartName=\"" + partName + "\" ContentType=\"" + contentType + "\"/>";
    }

    static String defaultType(String extension, String contentType) {
        return "<Default Extension=\"" + extension + "\" ContentType=\"" + contentType + "\"/>";
    }

    /**
     * Builds a digitally signed OOXML package in the shape the Open Packaging Conventions define.
     *
     * @param format OOXML package format
     * @param signatureXml signature part markup
     * @param originRelationship whether {@code _rels/.rels} declares the signature origin
     * @param signatureRelationship whether the origin relationships part declares the signature
     * @param signaturePart archive name for the signature markup
     * @param signatureContentType content type declared for the signature part
     * @return package bytes
     */
    static byte[] signedOoxml(
            UploadFormat format,
            String signatureXml,
            boolean originRelationship,
            boolean signatureRelationship,
            String signaturePart,
            String signatureContentType) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("_xmlsignatures/origin.sigs", new byte[0]);
        parts.put("_xmlsignatures/_rels/origin.sigs.rels", ascii(
            "<Relationships xmlns=\"" + RELATIONSHIPS_NAMESPACE + "\">"
                + (signatureRelationship
                    ? "<Relationship Id=\"rIdSig1\" Type=\"" + SIGNATURE_RELATIONSHIP
                        + "\" Target=\"" + signaturePart.substring(
                            signaturePart.lastIndexOf('/') + 1) + "\"/>"
                    : "")
                + "</Relationships>"));
        parts.put(signaturePart, ascii(signatureXml));
        return ooxml(
            format,
            defaultType("sigs", SIGNATURE_ORIGIN_CONTENT_TYPE)
                + override("/" + signaturePart, signatureContentType),
            originRelationship
                ? "<Relationship Id=\"rIdSigOrigin\" Type=\"" + SIGNATURE_ORIGIN_RELATIONSHIP
                    + "\" Target=\"_xmlsignatures/origin.sigs\"/>"
                : "",
            parts);
    }

    static byte[] signedOoxml(UploadFormat format, String signatureXml) throws IOException {
        return signedOoxml(
            format, signatureXml, true, true, "_xmlsignatures/sig1.xml", SIGNATURE_CONTENT_TYPE);
    }

    /**
     * Builds Office-shaped XMLDSig signature markup covering the package main part.
     *
     * @param format OOXML package format the signature covers
     * @param signatureLineImages whether printed signature-line image objects are included
     * @param xades whether XAdES qualifying properties are included
     * @param extraObject additional markup appended inside the signature
     * @return signature part markup
     */
    static String officeSignatureXml(
            UploadFormat format,
            boolean signatureLineImages,
            boolean xades,
            String extraObject) {
        return officeSignatureXml(
            "/" + mainPart(format) + "?ContentType=" + mainContentType(format),
            signatureLineImages,
            xades,
            extraObject);
    }

    static String officeSignatureXml(
            String manifestReferenceUri,
            boolean signatureLineImages,
            boolean xades,
            String extraObject) {
        String canonicalization = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
        return "<Signature xmlns=\"" + XMLDSIG_NAMESPACE + "\" Id=\"idPackageSignature\">"
            + "<SignedInfo>"
            + "<CanonicalizationMethod Algorithm=\"" + canonicalization + "\"/>"
            + "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha256\"/>"
            + "<Reference URI=\"#idPackageObject\" Type=\"" + XMLDSIG_NAMESPACE + "Object\">"
            + "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"
            + "<DigestValue>aGFzaA==</DigestValue></Reference>"
            + "<Reference URI=\"#idOfficeObject\" Type=\"" + XMLDSIG_NAMESPACE + "Object\">"
            + "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"
            + "<DigestValue>aGFzaA==</DigestValue></Reference>"
            + "</SignedInfo>"
            + "<SignatureValue>c2lnbmF0dXJl</SignatureValue>"
            + "<KeyInfo><X509Data><X509Certificate>Y2VydA==</X509Certificate></X509Data></KeyInfo>"
            + "<Object Id=\"idPackageObject\" xmlns:mdssi=\"" + OPC_SIGNATURE_NAMESPACE + "\">"
            + "<Manifest>"
            + "<Reference URI=\"" + manifestReferenceUri + "\">"
            + "<Transforms><Transform Algorithm=\"" + canonicalization + "\"/></Transforms>"
            + "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"
            + "<DigestValue>aGFzaA==</DigestValue></Reference>"
            + "<Reference URI=\"/_rels/.rels?ContentType=application/vnd.openxmlformats-package"
            + ".relationships+xml\"><Transforms>"
            + "<Transform Algorithm=\"http://schemas.openxmlformats.org/package/2006/"
            + "RelationshipTransform\"><mdssi:RelationshipReference SourceId=\"rIdOffice\"/>"
            + "<mdssi:RelationshipsGroupReference SourceType=\"http://schemas.openxmlformats.org/"
            + "officeDocument/2006/relationships/officeDocument\"/></Transform>"
            + "<Transform Algorithm=\"" + canonicalization + "\"/></Transforms>"
            + "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"
            + "<DigestValue>aGFzaA==</DigestValue></Reference>"
            + "</Manifest>"
            + "<SignatureProperties><SignatureProperty Id=\"idSignatureTime\" "
            + "Target=\"#idPackageSignature\"><mdssi:SignatureTime>"
            + "<mdssi:Format>YYYY-MM-DDThh:mm:ssTZD</mdssi:Format>"
            + "<mdssi:Value>2026-09-04T00:00:00Z</mdssi:Value>"
            + "</mdssi:SignatureTime></SignatureProperty></SignatureProperties></Object>"
            + "<Object Id=\"idOfficeObject\"><SignatureProperties>"
            + "<SignatureProperty Id=\"idOfficeV1Details\" Target=\"#idPackageSignature\">"
            + "<SignatureInfoV1 xmlns=\"" + OFFICE_DIGSIG_NAMESPACE + "\">"
            + "<SetupID>{00000000-0000-0000-0000-000000000000}</SetupID>"
            + "<SignatureText/><SignatureImage/><SignatureComments/>"
            + "<WindowsVersion>10.0</WindowsVersion><OfficeVersion>16.0</OfficeVersion>"
            + "<ApplicationVersion>16.0</ApplicationVersion><Monitors>1</Monitors>"
            + "<HorizontalResolution>1920</HorizontalResolution>"
            + "<VerticalResolution>1080</VerticalResolution><ColorDepth>32</ColorDepth>"
            + "<SignatureProviderId>{00000000-0000-0000-0000-000000000000}</SignatureProviderId>"
            + "<SignatureProviderUrl/><SignatureProviderDetails>9</SignatureProviderDetails>"
            + "<SignatureType>" + (signatureLineImages ? "2" : "1") + "</SignatureType>"
            + "<ManifestHashAlgorithm>http://www.w3.org/2001/04/xmlenc#sha256"
            + "</ManifestHashAlgorithm>"
            + "</SignatureInfoV1></SignatureProperty></SignatureProperties></Object>"
            + (signatureLineImages
                ? "<Object Id=\"idValidSigLnImg\">aW1hZ2U=</Object>"
                    + "<Object Id=\"idInvalidSigLnImg\">aW1hZ2U=</Object>"
                : "")
            + (xades
                ? "<Object><xd:QualifyingProperties xmlns:xd=\"http://uri.etsi.org/01903/v1.3.2#\" "
                    + "Target=\"#idPackageSignature\">"
                    + "<xd:SignedProperties Id=\"idSignedProperties\">"
                    + "<xd:SignedSignatureProperties>"
                    + "<xd:SigningTime>2026-09-04T00:00:00Z</xd:SigningTime>"
                    + "<xd:SigningCertificate><xd:Cert><xd:CertDigest>"
                    + "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"
                    + "<DigestValue>aGFzaA==</DigestValue></xd:CertDigest>"
                    + "<xd:IssuerSerial><X509IssuerName>CN=Test</X509IssuerName>"
                    + "<X509SerialNumber>1</X509SerialNumber></xd:IssuerSerial>"
                    + "</xd:Cert></xd:SigningCertificate>"
                    + "<xd:SignaturePolicyIdentifier><xd:SignaturePolicyImplied/>"
                    + "</xd:SignaturePolicyIdentifier></xd:SignedSignatureProperties>"
                    + "</xd:SignedProperties></xd:QualifyingProperties></Object>"
                : "")
            + extraObject
            + "</Signature>";
    }

    /**
     * Builds a structurally valid PNG of exactly the requested length by padding one IDAT chunk,
     * so a member size bound is exercised by bytes the raster walker would otherwise accept.
     *
     * @param length exact PNG length, at least 57 bytes
     * @return PNG bytes
     */
    static byte[] png(int length) {
        byte[] content = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(content);
        buffer.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        pngChunk(buffer, "IHDR", new byte[] {0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0});
        pngChunk(buffer, "IDAT", new byte[length - 57]);
        pngChunk(buffer, "IEND", new byte[0]);
        return content;
    }

    static byte[] png() throws IOException {
        return raster("png");
    }

    static byte[] jpeg() throws IOException {
        return raster("jpg");
    }

    static byte[] gif() throws IOException {
        return raster("gif");
    }

    /**
     * Builds a 1×1 GIF89a by hand with the requested number of image frames, each preceded by a
     * graphic control extension, so animated members and frameless GIFs can be exercised.
     *
     * @param frames image blocks to write
     * @return GIF bytes ending with the trailer
     */
    static byte[] animatedGif(int frames) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ascii("GIF89a"));
        output.writeBytes(new byte[] {1, 0, 1, 0, (byte) 0x80, 0, 0});
        output.writeBytes(new byte[] {0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff});
        for (int frame = 0; frame < frames; frame++) {
            output.writeBytes(new byte[] {0x21, (byte) 0xf9, 4, 0, 10, 0, 0, 0});
            output.writeBytes(new byte[] {0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0});
            output.writeBytes(new byte[] {2, 2, 0x44, 0x01, 0});
        }
        output.write(0x3b);
        return output.toByteArray();
    }

    /** @return an EMF header whose record type and signature identify an enhanced metafile */
    static byte[] emf() {
        return emf(128);
    }

    /**
     * @param claimedLength byte count written into the EMF header's file-size field
     * @return a 128-byte EMF header claiming the given metafile length
     */
    static byte[] emf(int claimedLength) {
        byte[] content = new byte[128];
        littleEndianInt(content, 0, 1);
        littleEndianInt(content, 4, content.length);
        content[40] = ' ';
        content[41] = 'E';
        content[42] = 'M';
        content[43] = 'F';
        littleEndianInt(content, 48, claimedLength);
        return content;
    }

    /** @return a placeable WMF whose metafile word count covers the bytes after the placeable header */
    static byte[] wmf() {
        return wmf(53);
    }

    /**
     * @param claimedWords 16-bit word count written into the standard WMF header
     * @return a 128-byte placeable WMF claiming the given metafile size
     */
    static byte[] wmf(int claimedWords) {
        byte[] content = new byte[128];
        content[0] = (byte) 0xd7;
        content[1] = (byte) 0xcd;
        content[2] = (byte) 0xc6;
        content[3] = (byte) 0x9a;
        content[22] = 1;
        content[24] = 9;
        littleEndianInt(content, 28, claimedWords);
        return content;
    }

    /** @return a 128-byte WMF with a standard header only, whose word count covers the member */
    static byte[] standardWmf() {
        byte[] content = new byte[128];
        content[0] = 1;
        content[2] = 9;
        littleEndianInt(content, 6, 64);
        return content;
    }

    /** @return a little-endian TIFF header whose first directory offset stays inside the file */
    static byte[] tiff() {
        byte[] content = new byte[128];
        content[0] = 'I';
        content[1] = 'I';
        content[2] = 0x2a;
        content[3] = 0x00;
        littleEndianInt(content, 4, 8);
        return content;
    }

    /** @return a BMP header whose declared file size matches the member length */
    static byte[] bmp() {
        byte[] content = new byte[128];
        content[0] = 'B';
        content[1] = 'M';
        littleEndianInt(content, 2, content.length);
        littleEndianInt(content, 10, 54);
        return content;
    }

    /** @return a DEVMODE-shaped printer settings blob with no recognisable file magic */
    static byte[] printerSettings() {
        byte[] content = new byte[256];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index % 251 + 1);
        }
        return content;
    }

    /**
     * Builds an obfuscated embedded font blob whose bytes are incompressible, so a size bound is
     * exercised without the package first tripping the compression-ratio bound.
     *
     * @param length exact blob length
     * @return font blob bytes
     */
    static byte[] obfuscatedFont(int length) {
        byte[] content = new byte[length];
        long state = 0x5deece66dL;
        for (int index = 0; index < content.length; index++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            content[index] = (byte) (state >>> 33);
        }
        return content;
    }

    /**
     * Builds an incompressible blob led by the TrueType sfnt tag and a one-table directory, so an
     * ODF embedded font member passes the sfnt sniff while a size bound is exercised.
     *
     * @param length exact blob length, at least 28 bytes
     * @param tables table count written into the sfnt directory
     * @return font blob bytes
     */
    static byte[] trueTypeFont(int length, int tables) {
        byte[] content = obfuscatedFont(length);
        ByteBuffer.wrap(content).putInt(0x00010000).putShort((short) tables);
        return content;
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concatenate(byte[] first, byte[] second) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(first);
        output.write(second);
        return output.toByteArray();
    }

    static Map<String, byte[]> members(Object... namesAndContent) {
        Map<String, byte[]> members = new LinkedHashMap<>();
        for (int index = 0; index < namesAndContent.length; index += 2) {
            members.put((String) namesAndContent[index], (byte[]) namesAndContent[index + 1]);
        }
        return members;
    }

    private static byte[] raster(String format) throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Unsupported raster fixture format: " + format);
        }
        return output.toByteArray();
    }

    private static void pngChunk(ByteBuffer buffer, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        buffer.putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue());
    }

    private static void littleEndianInt(byte[] content, int offset, int value) {
        ByteBuffer.wrap(content, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void stored(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
