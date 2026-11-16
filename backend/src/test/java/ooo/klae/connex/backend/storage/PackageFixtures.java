package ooo.klae.connex.backend.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSAbsentContent;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

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
    static final String SIGNATURE_CERTIFICATE_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.digital-signature-certificate";
    static final String SIGNATURE_CERTIFICATE_RELATIONSHIP =
        "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/certificate";
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
        return ooxml(format, mainXml, extraContentTypes, extraRootRelationships,
            extraParts, storeExtraParts, "application/xml");
    }

    /**
     * Selects the XML Default declaration without emitting a duplicate Default, so a refusal
     * exercises effective member-type binding rather than declaration ambiguity.
     */
    static byte[] ooxml(
            UploadFormat format,
            String mainXml,
            String extraContentTypes,
            String extraRootRelationships,
            Map<String, byte[]> extraParts,
            boolean storeExtraParts,
            String defaultXmlContentType) throws IOException {
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
                + defaultType("xml", defaultXmlContentType)
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

    /** @return the origin and first signature declarations for certificate-package fixtures */
    static String signatureContentTypes() {
        return defaultType("sigs", SIGNATURE_ORIGIN_CONTENT_TYPE)
            + override("/_xmlsignatures/sig1.xml", SIGNATURE_CONTENT_TYPE);
    }

    /** @return the root relationship reaching the fixed signature origin */
    static String signatureOriginRelationship() {
        return relationship("rIdSigOrigin", SIGNATURE_ORIGIN_RELATIONSHIP,
            "_xmlsignatures/origin.sigs");
    }

    static String relationship(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + type + "\" Target=\""
            + target + "\"/>";
    }

    static String relationships(String entries) {
        return "<Relationships xmlns=\"" + RELATIONSHIPS_NAMESPACE + "\">"
            + entries + "</Relationships>";
    }

    /** @return a mutable signed-package member map with a signature-owned certificate reference */
    static Map<String, byte[]> signatureCertificateParts(
            UploadFormat format, String certificatePart, byte[] certificate) {
        String target = certificatePart.startsWith("_xmlsignatures/")
            ? certificatePart.substring("_xmlsignatures/".length())
            : "../" + certificatePart;
        return members(
            "_xmlsignatures/origin.sigs", new byte[0],
            "_xmlsignatures/_rels/origin.sigs.rels", ascii(relationships(
                relationship("rIdSig1", SIGNATURE_RELATIONSHIP, "sig1.xml"))),
            "_xmlsignatures/sig1.xml", ascii(officeSignatureXml(format, false, false, "")),
            "_xmlsignatures/_rels/sig1.xml.rels", ascii(relationships(
                relationship("rIdCert", SIGNATURE_CERTIFICATE_RELATIONSHIP, target))),
            certificatePart, certificate);
    }

    /** @return signed OOXML with a DER signer certificate and content types written last */
    static byte[] signedOoxmlWithCertificate(UploadFormat format, byte[] certificate)
            throws IOException {
        return ooxml(format, defaultMainXml(format), signatureContentTypes()
            + defaultType("cer", SIGNATURE_CERTIFICATE_CONTENT_TYPE),
            signatureOriginRelationship(),
            signatureCertificateParts(format, "_xmlsignatures/cert1.cer", certificate), true);
    }

    /** @return a real self-signed DER certificate with synthetic identity data */
    static byte[] signerCertificate() throws Exception {
        return signerCertificateWithPadding(0);
    }

    /** @return a valid DER certificate of exactly the requested size, using an inert extension */
    static byte[] signerCertificate(int length) throws Exception {
        int padding = Math.max(0, length - 1024);
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] certificate = signerCertificateWithPadding(padding);
            if (certificate.length == length) {
                return certificate;
            }
            padding += length - certificate.length;
            if (padding < 0) {
                break;
            }
        }
        throw new IllegalArgumentException("Cannot construct certificate at requested size");
    }

    /**
     * Retains a complete X.509 shape while replacing the inner Validity length with a BER form.
     * All enclosing lengths remain definite and minimal, so outer framing cannot detect it.
     */
    static byte[] signerCertificateWithBerValidity(boolean indefinite) throws Exception {
        ASN1Sequence certificate = ASN1Sequence.getInstance(signerCertificate());
        ASN1Sequence tbs = ASN1Sequence.getInstance(certificate.getObjectAt(0));
        ByteArrayOutputStream tbsBody = new ByteArrayOutputStream();
        for (int index = 0; index < tbs.size(); index++) {
            byte[] field = tbs.getObjectAt(index).toASN1Primitive().getEncoded(ASN1Encoding.DER);
            if (index == 4) {
                if (field[0] != 0x30 || Byte.toUnsignedInt(field[1]) >= 0x80) {
                    throw new IllegalStateException("Validity fixture requires a short SEQUENCE");
                }
                tbsBody.write(0x30);
                tbsBody.write(indefinite ? 0x80 : 0x81);
                if (!indefinite) {
                    tbsBody.write(field[1]);
                }
                tbsBody.write(field, 2, field.length - 2);
                if (indefinite) {
                    tbsBody.write(0);
                    tbsBody.write(0);
                }
            } else {
                tbsBody.write(field);
            }
        }
        ByteArrayOutputStream certificateBody = new ByteArrayOutputStream();
        certificateBody.write(derValue(0x30, tbsBody.toByteArray()));
        for (int index = 1; index < certificate.size(); index++) {
            certificateBody.write(certificate.getObjectAt(index).toASN1Primitive()
                .getEncoded(ASN1Encoding.DER));
        }
        return derValue(0x30, certificateBody.toByteArray());
    }

    /** @return supplied bytes framed with a minimal definite length, without validating contents */
    static byte[] derValue(int identifier, byte[] body) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(identifier);
        if (body.length < 0x80) {
            output.write(body.length);
        } else {
            int lengthOctets = (Integer.SIZE - Integer.numberOfLeadingZeros(body.length) + 7) / 8;
            output.write(0x80 | lengthOctets);
            for (int index = lengthOctets - 1; index >= 0; index--) {
                output.write(body.length >>> (index * 8));
            }
        }
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static byte[] signerCertificateWithPadding(int padding) throws Exception {
        X500Name subject = new X500Name("CN=Connex upload fixture");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            subject, BigInteger.ONE,
            Date.from(Instant.parse("2026-01-01T00:00:00Z")),
            Date.from(Instant.parse("2027-01-01T00:00:00Z")),
            subject, CertificateKeys.KEY_PAIR.getPublic());
        if (padding > 0) {
            builder.addExtension(new ASN1ObjectIdentifier("1.3.6.1.4.1.55555.1"),
                false, obfuscatedFont(padding));
        }
        return builder.build(certificateSigner()).getEncoded();
    }

    /** @return real DER objects that must never be accepted as a single X.509 certificate */
    static Map<String, byte[]> nonCertificateDerObjects() throws Exception {
        X500Name subject = new X500Name("CN=Connex upload fixture");
        CMSSignedDataGenerator bundle = new CMSSignedDataGenerator();
        bundle.setEncoding(ASN1Encoding.DER);
        bundle.addCertificate(new X509CertificateHolder(signerCertificate()));
        return members(
            "pkcs7", bundle.generate(new CMSAbsentContent()).getEncoded(),
            "crl", new X509v2CRLBuilder(subject,
                Date.from(Instant.parse("2026-01-01T00:00:00Z")))
                .build(certificateSigner()).getEncoded(),
            "csr", new JcaPKCS10CertificationRequestBuilder(
                subject, CertificateKeys.KEY_PAIR.getPublic())
                .build(certificateSigner()).getEncoded(),
            "private-key", CertificateKeys.KEY_PAIR.getPrivate().getEncoded());
    }

    private static ContentSigner certificateSigner() throws Exception {
        return new JcaContentSignerBuilder("SHA256withRSA")
            .build(CertificateKeys.KEY_PAIR.getPrivate());
    }

    private static final class CertificateKeys {
        private static final KeyPair KEY_PAIR = generate();

        private static KeyPair generate() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (java.security.GeneralSecurityException exception) {
                throw new IllegalStateException("Cannot generate certificate fixture key", exception);
            }
        }
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
