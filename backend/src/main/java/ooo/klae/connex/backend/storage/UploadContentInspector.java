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
import java.util.regex.Pattern;
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
 * element-namespace allowlist plus closed office- and animation-namespace element allowlists, with
 * script and form vocabularies excluded entirely. {@code office:binary-data} is excluded because
 * inline base64 payloads are never decoded, so an inline image cannot be inspected the way a real
 * raster upload is; documents must reference package parts instead.
 *
 * <p>The single ODF part {@code META-INF/documentsignatures.xml} is instead admitted through its
 * own closed signature vocabulary so that digitally signed documents remain uploadable. That
 * exemption is deliberately keyed on the exact part name and must not be widened to every
 * signature-shaped part: an ODF package may also carry {@code META-INF/macrosignatures.xml}, and a
 * signed macro is still a macro. Macro signature parts carry no exemption, so they fall to the
 * ordinary ODF namespace allowlist and are refused. Parser error, timeout, ambiguous structure,
 * active content, and any exceeded bound all fail closed.
 *
 * <p>Every package member is classified into exactly one inspection class and is validated, never
 * re-encoded, so document bytes reach storage unchanged and any signature over them keeps
 * verifying. XML, VML, and signature parts are parsed; raster members are walked by the same
 * structural inspectors used for direct image uploads, except that a GIF member may be animated;
 * EMF, WMF, TIFF, and BMP members are bound
 * by magic, an internal length that must agree with the member length, and their own declared
 * media type; ODF {@code Fonts/*.ttf|otf|ttc} members are bound by the sfnt magic, a table
 * directory that fits the member, and a declared font type; OOXML obfuscated fonts and
 * {@code printerSettings[0-9]+.bin} are admitted as declared-opaque bytes with a negative header
 * sniff; directory and signature-origin entries must be empty. A member matching no class is
 * refused rather than stored uninspected. Members are bounded at 16 MiB each, embedded fonts at
 * 16 MiB, and printer settings and the ODF layout cache at 1 MiB, inside the unchanged 64 MiB
 * expanded-package bound.
 *
 * <p>The package's own declared media types are then bound to the members they describe: the Open
 * Packaging Conventions {@code Override} then {@code Default} rule for OOXML, and the
 * {@code manifest:file-entry} media type for ODF. A raster or metafile member whose declared type
 * disagrees with the format its bytes prove is refused, as is any member declared with an active
 * type such as {@code image/svg+xml}, so an extension dodge cannot smuggle scriptable content into
 * a document package.
 *
 * <p>OOXML signature parts under {@code _xmlsignatures/} are admitted through their own closed
 * XMLDSig, OPC, Microsoft Office, and XAdES vocabulary, bound by the signature-origin and
 * signature relationships and the OPC signature content types. That widening is narrow on purpose:
 * {@code <Object>} stays refused everywhere else, and macro signature parts and macro-enabled
 * packages stay refused.
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
    private static final long MAX_PACKAGE_MEMBER_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_OPAQUE_FONT_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_OPAQUE_SETTINGS_BYTES = 1024L * 1024L;
    private static final int OPAQUE_SNIFF_BYTES = 64;
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
        "activexcontrol", "activexcontrolbinary", "attachedtemplate", "audio", "control",
        "ctrlprop", "customui", "ddelink", "embeddedobject", "embeddedpackage",
        "externallink", "media", "oleobject", "package", "querytable", "vbaproject",
        "video");
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
        "annotation", "annotation-end", "automatic-styles", "body",
        "change-info", "chart", "document", "document-content", "document-meta",
        "document-settings", "document-styles", "drawing", "font-face-decls", "forms",
        "image", "master-styles", "meta", "presentation", "scripts", "settings",
        "spreadsheet", "styles", "text");
    private static final Set<String> ODF_EMPTY_ONLY_OFFICE_ELEMENTS = Set.of(
        "forms", "scripts");
    private static final String ODF_ANIMATION_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:animation:1.0";
    private static final Set<String> ODF_ANIMATION_ELEMENTS = Set.of(
        "animate", "animatecolor", "animatemotion", "animatetransform", "audio",
        "iterate", "par", "param", "seq", "set", "transitionfilter");
    private static final String ODF_SIGNATURE_PART = "META-INF/documentsignatures.xml";
    private static final Set<String> ODF_SIGNATURE_NAMESPACES = Set.of(
        "urn:oasis:names:tc:opendocument:xmlns:digitalsignature:1.0",
        "http://www.w3.org/2000/09/xmldsig#",
        "http://uri.etsi.org/01903/v1.1.1#",
        "http://uri.etsi.org/01903/v1.3.2#",
        "http://purl.org/dc/elements/1.1/");
    private static final Set<String> ODF_SIGNATURE_ELEMENTS = Set.of(
        "document-signatures", "signature", "signedinfo", "canonicalizationmethod",
        "signaturemethod", "reference", "transforms", "transform", "digestmethod",
        "digestvalue", "signaturevalue", "keyinfo", "keyname", "keyvalue", "rsakeyvalue",
        "modulus", "exponent", "dsakeyvalue", "eckeyvalue", "namedcurve", "publickey",
        "x509data", "x509certificate", "x509issuerserial", "x509issuername",
        "x509serialnumber", "x509subjectname", "x509ski", "x509crl", "retrievalmethod",
        "object", "manifest", "signatureproperties", "signatureproperty",
        "hmacoutputlength", "xpath", "date", "qualifyingproperties", "signedproperties",
        "signedsignatureproperties", "signingtime", "signingcertificate", "cert",
        "certdigest", "issuerserial", "signeddataobjectproperties", "unsignedproperties",
        "unsignedsignatureproperties", "signaturepolicyidentifier",
        "signaturepolicyimplied", "claimedroles", "claimedrole",
        "signatureproductionplace", "city", "stateorprovince", "postalcode",
        "countryname");
    private static final String OOXML_CONTENT_TYPES_PART = "[Content_Types].xml";
    private static final String OOXML_ROOT_RELATIONSHIPS_PART = "_rels/.rels";
    private static final String OOXML_SIGNATURE_DIRECTORY = "_xmlsignatures/";
    private static final String OOXML_SIGNATURE_ORIGIN_PART = "_xmlsignatures/origin.sigs";
    private static final String OOXML_SIGNATURE_ORIGIN_RELATIONSHIPS_PART =
        "_xmlsignatures/_rels/origin.sigs.rels";
    private static final String OOXML_SIGNATURE_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.digital-signature-xmlsignature+xml";
    private static final String OOXML_SIGNATURE_ORIGIN_CONTENT_TYPE =
        "application/vnd.openxmlformats-package.digital-signature-origin";
    private static final String OOXML_SIGNATURE_ORIGIN_RELATIONSHIP =
        "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/origin";
    private static final String OOXML_SIGNATURE_RELATIONSHIP =
        "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/signature";
    private static final Set<String> OOXML_SIGNATURE_NAMESPACES = Set.of(
        "http://www.w3.org/2000/09/xmldsig#",
        "http://www.w3.org/2009/xmldsig11#",
        "http://schemas.openxmlformats.org/package/2006/digital-signature",
        "http://schemas.microsoft.com/office/2006/digsig",
        "http://uri.etsi.org/01903/v1.1.1#",
        "http://uri.etsi.org/01903/v1.3.2#",
        "http://uri.etsi.org/01903/v1.4.1#");
    private static final Set<String> XMLDSIG_ELEMENTS = Set.of(
        "signature", "signedinfo", "signaturevalue", "keyinfo", "object",
        "canonicalizationmethod", "signaturemethod", "reference", "transforms", "transform",
        "digestmethod", "digestvalue", "keyname", "mgmtdata", "keyvalue", "dsakeyvalue",
        "rsakeyvalue", "eckeyvalue", "namedcurve", "publickey", "retrievalmethod", "x509data",
        "x509issuerserial", "x509ski", "x509subjectname", "x509certificate", "x509crl",
        "x509issuername", "x509serialnumber", "pgpdata", "pgpkeyid", "pgpkeypacket",
        "spkidata", "spkisexp", "manifest", "signatureproperties", "signatureproperty",
        "hmacoutputlength", "xpath", "p", "q", "g", "y", "j", "seed", "pgencounter",
        "modulus", "exponent");
    private static final Set<String> OPC_SIGNATURE_ELEMENTS = Set.of(
        "relationshipreference", "relationshipsgroupreference", "signaturetime", "format",
        "value");
    private static final Set<String> OFFICE_SIGNATURE_ELEMENTS = Set.of(
        "signatureinfov1", "signatureinfov2", "setupid", "signaturetext", "signatureimage",
        "signaturecomments", "windowsversion", "officeversion", "applicationversion",
        "monitors", "horizontalresolution", "verticalresolution", "colordepth",
        "signatureproviderid", "signatureproviderurl", "signatureproviderdetails",
        "signaturetype", "delegatesuggestedsigner", "delegatesuggestedsigner2",
        "delegatesuggestedsigneremail", "manifesthashalgorithm", "address1", "address2");
    private static final Set<String> XADES_ELEMENTS = Set.of(
        "alldataobjectstimestamp", "allsigneddataobjects", "any", "archivetimestamp",
        "attrauthoritiescertvalues", "attributecertificaterefs", "attributerevocationrefs",
        "attributerevocationvalues", "bykey", "byname", "cert", "certdigest",
        "certificatevalues", "certifiedrole", "certifiedroles", "certrefs", "city",
        "claimedrole", "claimedroles", "commitmenttypeid", "commitmenttypeindication",
        "commitmenttypequalifier", "commitmenttypequalifiers", "completecertificaterefs",
        "completerevocationrefs", "countersignature", "countryname", "crlidentifier",
        "crlref", "crlrefs", "crlvalues", "dataobjectformat", "description",
        "digestalgandvalue", "documentationreference", "documentationreferences",
        "encapsulatedcrlvalue", "encapsulatedocspvalue", "encapsulatedpkidata",
        "encapsulatedtimestamp", "encapsulatedx509certificate", "encoding", "explicittext",
        "identifier", "include", "individualdataobjectstimestamp", "int", "issuer",
        "issuerserial", "issuetime", "mimetype", "noticenumbers", "noticeref", "number",
        "objectidentifier", "objectreference", "ocspidentifier", "ocspref", "ocsprefs",
        "ocspvalues", "organization", "othercertificate", "otherref", "otherrefs",
        "othertimestamp", "othervalue", "othervalues", "postalcode", "producedat",
        "qualifyingproperties", "qualifyingpropertiesreference", "referenceinfo",
        "refsonlytimestamp", "responderid", "revocationvalues", "sigandrefstimestamp",
        "signaturepolicyid", "signaturepolicyidentifier", "signaturepolicyimplied",
        "signatureproductionplace", "signaturetimestamp", "signeddataobjectproperties",
        "signedproperties", "signedsignatureproperties", "signerrole", "signingcertificate",
        "signingtime", "sigpolicyhash", "sigpolicyid", "sigpolicyqualifier",
        "sigpolicyqualifiers", "spuri", "spusernotice", "stateorprovince",
        "unsigneddataobjectproperties", "unsigneddataobjectproperty", "unsignedproperties",
        "unsignedsignatureproperties", "xadestimestamp", "xmltimestamp",
        "timestampvalidationdata");
    private static final Set<String> OOXML_SIGNATURE_ELEMENTS = ooxmlSignatureElements();
    private static final String ODF_MANIFEST_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
    private static final String ODF_MANIFEST_PART = "META-INF/manifest.xml";
    private static final String ODF_METADATA_MANIFEST_PART = "manifest.rdf";
    private static final String ODF_LAYOUT_CACHE_PART = "layout-cache";
    private static final Set<String> ODF_MANIFEST_ELEMENTS = Set.of("manifest", "file-entry");
    private static final String ODF_METADATA_NAMESPACE_PREFIX =
        "http://docs.oasis-open.org/ns/office/1.2/meta/";
    private static final Set<String> ODF_METADATA_NAMESPACES = Set.of(
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "http://docs.oasis-open.org/ns/office/1.2/meta/pkg#",
        "http://docs.oasis-open.org/ns/office/1.2/meta/odf#");
    private static final Set<String> ODF_METADATA_ELEMENTS = Set.of(
        "rdf", "description", "type", "haspart", "document", "contentfile", "stylesfile",
        "metadatafile");
    private static final Set<String> VML_NAMESPACES = Set.of(
        "urn:schemas-microsoft-com:vml");
    private static final Set<String> DRAWING_NAMESPACES = Set.of(
        "http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://purl.oclc.org/ooxml/drawingml/main");
    private static final Set<String> PRESENTATION_ACTION_ELEMENTS = Set.of(
        "hlinkclick", "hlinkhover", "hlinkmouseover");
    private static final Pattern SAFE_PRESENTATION_ACTION = Pattern.compile(
        "ppaction://(noaction|media|hlinksldjump"
            + "|hlinkshowjump\\?jump=(firstslide|lastslide|nextslide|previousslide"
            + "|lastslideviewed|endshow|[0-9]+)"
            + "|customshow\\?id=[0-9]+(&return=true)?)");
    private static final Pattern EMBEDDED_FONT_MEMBER = Pattern.compile(
        "(word|xl|ppt)/fonts/[A-Za-z0-9._-]+\\.(odttf|fntdata)");
    private static final Pattern PRINTER_SETTINGS_MEMBER = Pattern.compile(
        "(word|xl|ppt)/printerSettings/printerSettings[0-9]+\\.bin");
    private static final Pattern ODF_FONT_MEMBER = Pattern.compile(
        "Fonts/[A-Za-z0-9._-]+\\.(ttf|otf|ttc)");
    private static final String SFNT_FONT_TYPE = "font/sfnt";
    private static final Set<String> RASTER_MEMBER_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "webp");
    private static final Set<String> SNIFFED_MEMBER_EXTENSIONS = Set.of(
        "emf", "wmf", "tif", "tiff", "bmp");
    private static final Set<String> EMBEDDED_FONT_CONTENT_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.obfuscatedfont",
        "application/x-fontdata");
    private static final Set<String> PRINTER_SETTINGS_CONTENT_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.printersettings",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.printersettings",
        "application/vnd.openxmlformats-officedocument.presentationml.printersettings");
    private static final Set<String> XML_MEMBER_CONTENT_TYPES = Set.of(
        "text/xml", "application/xml", "application/rdf+xml");
    private static final Set<String> ODF_OPAQUE_MEMBER_CONTENT_TYPES = Set.of(
        "application/binary", "application/octet-stream");
    private static final Set<String> SIGNATURE_URI_ELEMENTS = Set.of(
        "spuri", "signatureproviderurl");
    private static final Set<String> REFUSED_DECLARED_CONTENT_TYPES = Set.of(
        "image/svg+xml", "text/html", "application/xhtml+xml", "application/javascript",
        "text/javascript", "application/ecmascript", "application/x-msdownload",
        "application/x-msdos-program", "application/hta", "application/x-shockwave-flash",
        "application/vnd.ms-office.vbaproject",
        "application/vnd.openxmlformats-officedocument.oleobject",
        "application/vnd.openxmlformats-officedocument.package",
        "application/vnd.ms-office.activex+xml",
        "application/vnd.ms-excel.controlproperties+xml");
    private static final List<byte[]> REFUSED_OPAQUE_MAGIC = List.of(
        new byte[] {0x4d, 0x5a},
        new byte[] {0x7f, 0x45, 0x4c, 0x46},
        new byte[] {0x50, 0x4b, 0x03, 0x04},
        new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1},
        new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47},
        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
        new byte[] {0x47, 0x49, 0x46, 0x38},
        new byte[] {0x25, 0x50, 0x44, 0x46},
        new byte[] {0x42, 0x4d},
        new byte[] {0x49, 0x49, 0x2a, 0x00},
        new byte[] {0x4d, 0x4d, 0x00, 0x2a},
        new byte[] {0x43, 0x57, 0x53},
        new byte[] {0x46, 0x57, 0x53});
    private static final Set<String> REFUSED_OPAQUE_TEXT_PREFIXES = Set.of(
        "<?xml", "<svg", "<html", "<script", "<!doctype", "<!--", "#!", "{\\rtf");
    private static final Set<String> ACTIVE_XML_ELEMENTS = Set.of(
        "script", "event-listener", "event-listeners", "altchunk", "object",
        "oleobject", "control", "dde-source", "dde-connection", "dde-connection-decl",
        "dde-connection-decls", "dde-link", "dde-links", "object-ole", "applet",
        "plugin", "floating-frame", "execute-macro", "fmlamacro");
    private static final Set<String> SAFE_WORD_FIELD_COMMANDS = Set.of(
        "ADVANCE", "AUTHOR", "AUTONUM", "AUTONUMLGL", "AUTONUMOUT", "BIBLIOGRAPHY",
        "CITATION", "COMMENTS", "CREATEDATE", "DATE", "DOCPROPERTY", "DOCVARIABLE", "EDITTIME",
        "EQ", "FILENAME", "FILESIZE", "FORMCHECKBOX", "FORMDROPDOWN", "FORMTEXT",
        "INFO", "KEYWORDS", "LASTSAVEDBY", "MERGEFIELD", "NEXT", "NEXTIF", "NUMCHARS",
        "NUMPAGES", "NUMWORDS", "PAGE", "PAGEREF", "PRINTDATE", "QUOTE", "REF", "REVNUM",
        "SAVEDATE", "SECTION", "SECTIONPAGES", "SEQ", "SET", "SKIPIF", "STYLEREF",
        "SUBJECT", "SYMBOL", "TA", "TC", "TEMPLATE", "TIME", "TITLE", "TOA", "TOC", "XE");
    private static final int MAX_WORD_FIELD_INSTRUCTION_CHARACTERS = 4096;
    private static final int MAX_SIGNATURE_URI_CHARACTERS = 2048;

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
            case GIF -> inspectGif(content, deadline, true);
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

    /**
     * Walks a GIF structurally: header, colour tables, every extension and image block, and the
     * trailer, which must be the last byte.
     *
     * <p>A direct raster upload must hold exactly one image because it is canonicalised to a
     * still image afterwards; a document package member keeps its bytes, so it may carry the
     * several frames of an animated GIF as long as every frame is walked and the trailer still
     * ends the member.
     *
     * @param content exact GIF bytes
     * @param deadline shared inspection deadline
     * @param singleImage whether exactly one image block is required
     */
    private static void inspectGif(byte[] content, Deadline deadline, boolean singleImage) {
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
                if (images < 1 || singleImage && images > 1 || offset != content.length) {
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
        Map<String, String> contentTypeDefaults = new HashMap<>();
        Map<String, String> manifestMediaTypes = new HashMap<>();
        Map<String, MemberEvidence> members = new HashMap<>();
        Map<String, XmlRoot> xmlRoots = new HashMap<>();
        Set<String> officeDocumentTargets = new HashSet<>();
        Set<String> relationshipTargets = new HashSet<>();
        Set<String> signatureOriginTargets = new HashSet<>();
        Set<String> signatureTargets = new HashSet<>();
        String packageMimeType = null;
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry zipEntry;
            while ((zipEntry = zip.getNextEntry()) != null) {
                deadline.check();
                String name = zipEntry.getName();
                ArchiveEntry expected = directory.entries().get(name);
                if (expected == null || !seen.add(name)) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                PackageMemberClass memberClass = classifyMember(name, odfPackage);
                if (memberClass == PackageMemberClass.REFUSED) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                EntryContent entry = readArchiveEntry(
                    zip,
                    expected,
                    expanded,
                    memberBound(memberClass, name),
                    memberCapturePrefix(memberClass),
                    deadline);
                expanded = Math.addExact(expanded, entry.length());
                String sniffedType = null;
                switch (memberClass) {
                    case XML, SIGNATURE -> {
                        XmlEvidence xml = inspectXml(name, entry.content(), deadline, odfPackage);
                        xmlRoots.put(name, xml.root());
                        officeDocumentTargets.addAll(xml.officeDocumentTargets());
                        relationshipTargets.addAll(xml.relationshipTargets());
                        signatureOriginTargets.addAll(xml.signatureOriginTargets());
                        signatureTargets.addAll(xml.signatureTargets());
                        mergeDeclaredTypes(contentTypeOverrides, xml.contentTypeOverrides());
                        mergeDeclaredTypes(contentTypeDefaults, xml.contentTypeDefaults());
                        mergeDeclaredTypes(manifestMediaTypes, xml.manifestMediaTypes());
                    }
                    case MIMETYPE ->
                        packageMimeType = decodeUtf8(entry.content(), deadline).toString();
                    case RASTER -> sniffedType = inspectPackageRaster(entry.content(), deadline);
                    case SNIFFED_OPAQUE -> sniffedType = ODF_FONT_MEMBER.matcher(name).matches()
                        ? sniffFontMember(entry.content(), entry.length())
                        : sniffOpaqueMember(entry.content(), entry.length());
                    case DECLARED_OPAQUE -> requireInertOpaqueMember(entry.content());
                    case SIGNATURE_ORIGIN, DIRECTORY, REFUSED ->
                        requireEmptyMember(entry.length());
                }
                members.put(
                    name, new MemberEvidence(name, memberClass, sniffedType, entry.length()));
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
            Map.copyOf(contentTypeDefaults),
            Map.copyOf(manifestMediaTypes),
            Map.copyOf(members),
            Map.copyOf(xmlRoots),
            Set.copyOf(officeDocumentTargets),
            Set.copyOf(relationshipTargets),
            Set.copyOf(signatureOriginTargets),
            Set.copyOf(signatureTargets),
            packageMimeType);
    }

    private static void mergeDeclaredTypes(
            Map<String, String> merged,
            Map<String, String> captured) {
        for (Map.Entry<String, String> declaration : captured.entrySet()) {
            if (merged.putIfAbsent(declaration.getKey(), declaration.getValue()) != null) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
    }

    /**
     * Assigns every package member to the single inspection class that governs how many of its
     * bytes may be retained and which structural walker must agree with them.
     *
     * <p>The classification is closed: a member that matches no known family is refused rather
     * than stored uninspected, because an unclassified member would otherwise reach storage with
     * only its CRC and size checked.
     *
     * @param name exact archive entry name
     * @param odfPackage whether the enclosing package is ODF rather than OOXML
     * @return the inspection class that governs this member
     */
    private static PackageMemberClass classifyMember(String name, boolean odfPackage) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/")) {
            return PackageMemberClass.DIRECTORY;
        }
        if (refusedPackageEntry(name, normalized)) {
            return PackageMemberClass.REFUSED;
        }
        return odfPackage
            ? classifyOdfMember(name, normalized)
            : classifyOoxmlMember(name, normalized);
    }

    private static PackageMemberClass classifyOdfMember(String name, String normalized) {
        if ("mimetype".equals(name)) {
            return PackageMemberClass.MIMETYPE;
        }
        if (ODF_SIGNATURE_PART.equals(name)) {
            return PackageMemberClass.SIGNATURE;
        }
        if (ODF_MANIFEST_PART.equals(name)) {
            return PackageMemberClass.XML;
        }
        if (normalized.startsWith("meta-inf/")) {
            return PackageMemberClass.REFUSED;
        }
        if (ODF_METADATA_MANIFEST_PART.equals(name)) {
            return PackageMemberClass.XML;
        }
        if (normalized.endsWith(".rdf")) {
            return PackageMemberClass.REFUSED;
        }
        if (ODF_LAYOUT_CACHE_PART.equals(name)) {
            return PackageMemberClass.DECLARED_OPAQUE;
        }
        if (normalized.startsWith("configurations2/")) {
            return PackageMemberClass.REFUSED;
        }
        if (ODF_FONT_MEMBER.matcher(name).matches()) {
            return PackageMemberClass.SNIFFED_OPAQUE;
        }
        return classifyByExtension(normalized);
    }

    private static PackageMemberClass classifyOoxmlMember(String name, String normalized) {
        if (OOXML_SIGNATURE_ORIGIN_PART.equals(name)) {
            return PackageMemberClass.SIGNATURE_ORIGIN;
        }
        if (OOXML_SIGNATURE_ORIGIN_RELATIONSHIPS_PART.equals(name)) {
            return PackageMemberClass.XML;
        }
        if (normalized.startsWith(OOXML_SIGNATURE_DIRECTORY)) {
            return normalized.endsWith(".xml")
                ? PackageMemberClass.SIGNATURE
                : PackageMemberClass.REFUSED;
        }
        if (normalized.endsWith(".vml")) {
            return PackageMemberClass.XML;
        }
        if (EMBEDDED_FONT_MEMBER.matcher(name).matches()
                || PRINTER_SETTINGS_MEMBER.matcher(name).matches()) {
            return PackageMemberClass.DECLARED_OPAQUE;
        }
        return classifyByExtension(normalized);
    }

    private static PackageMemberClass classifyByExtension(String normalized) {
        if (xmlEntry(normalized)) {
            return PackageMemberClass.XML;
        }
        String extension = memberExtension(normalized);
        if (RASTER_MEMBER_EXTENSIONS.contains(extension)) {
            return PackageMemberClass.RASTER;
        }
        if (SNIFFED_MEMBER_EXTENSIONS.contains(extension)) {
            return PackageMemberClass.SNIFFED_OPAQUE;
        }
        return PackageMemberClass.REFUSED;
    }

    private static String memberExtension(String normalized) {
        int separator = normalized.lastIndexOf('/');
        String fileName = separator < 0 ? normalized : normalized.substring(separator + 1);
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }

    private static long memberBound(PackageMemberClass memberClass, String name) {
        return switch (memberClass) {
            case XML, SIGNATURE, MIMETYPE -> MAX_XML_BYTES;
            case RASTER -> MAX_PACKAGE_MEMBER_BYTES;
            case SNIFFED_OPAQUE -> ODF_FONT_MEMBER.matcher(name).matches()
                ? MAX_OPAQUE_FONT_BYTES
                : MAX_PACKAGE_MEMBER_BYTES;
            case DECLARED_OPAQUE -> EMBEDDED_FONT_MEMBER.matcher(name).matches()
                ? MAX_OPAQUE_FONT_BYTES
                : MAX_OPAQUE_SETTINGS_BYTES;
            case SIGNATURE_ORIGIN, DIRECTORY, REFUSED -> 0;
        };
    }

    private static long memberCapturePrefix(PackageMemberClass memberClass) {
        return switch (memberClass) {
            case XML, SIGNATURE, MIMETYPE -> MAX_XML_BYTES;
            case RASTER -> MAX_PACKAGE_MEMBER_BYTES;
            case SNIFFED_OPAQUE, DECLARED_OPAQUE -> OPAQUE_SNIFF_BYTES;
            case SIGNATURE_ORIGIN, DIRECTORY, REFUSED -> 0;
        };
    }

    private static void requireEmptyMember(long length) {
        if (length != 0) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    /**
     * Walks a package raster member with the same structural inspectors used for direct image
     * uploads and returns the media type its bytes actually claim.
     *
     * <p>The bytes are never decoded or re-encoded: a document package must stay byte-identical
     * so that its own digital signatures keep verifying and so that the stored artifact remains
     * the exact uploaded bytes.
     *
     * @param content exact member bytes
     * @param deadline shared inspection deadline
     * @return canonical media type proven by the member's magic and structure
     */
    private static String inspectPackageRaster(byte[] content, Deadline deadline) {
        if (startsWith(content, PNG_SIGNATURE)) {
            inspectPng(content, deadline);
            return "image/png";
        }
        if (content.length >= 3
                && unsigned(content[0]) == 0xff
                && unsigned(content[1]) == 0xd8
                && unsigned(content[2]) == 0xff) {
            inspectJpeg(content, deadline);
            return "image/jpeg";
        }
        if (asciiEquals(content, 0, "GIF87a") || asciiEquals(content, 0, "GIF89a")) {
            inspectGif(content, deadline, false);
            return "image/gif";
        }
        if (asciiEquals(content, 0, "RIFF") && asciiEquals(content, 8, "WEBP")) {
            inspectWebp(content, deadline);
            return "image/webp";
        }
        throw UnsupportedUploadMediaTypeException.unsupported();
    }

    /**
     * Identifies a metafile or uncompressed raster member from its header alone.
     *
     * <p>These formats have no in-repository structural validator, so they are admitted as opaque
     * bytes bound by magic, an internal length that must agree with the member length, and the
     * package's own declared media type: the EMF header byte count, the WMF header word count
     * (after the placeable header when present), the TIFF first-directory offset, and the BMP
     * file size. Anything whose header is not one of these families is refused.
     *
     * @param head leading member bytes
     * @param length exact member length
     * @return canonical media type proven by the member header
     */
    private static String sniffOpaqueMember(byte[] head, long length) {
        if (head.length >= 52
                && littleEndianUnsignedInt(head, 0) == 1L
                && asciiEquals(head, 40, " EMF")
                && littleEndianUnsignedInt(head, 48) == length) {
            return "image/x-emf";
        }
        if (head.length >= 40
                && unsigned(head[0]) == 0xd7
                && unsigned(head[1]) == 0xcd
                && unsigned(head[2]) == 0xc6
                && unsigned(head[3]) == 0x9a
                && wmfHeaderCoversMember(head, 22, length - 22)) {
            return "image/x-wmf";
        }
        if (head.length >= 18 && wmfHeaderCoversMember(head, 0, length)) {
            return "image/x-wmf";
        }
        if (head.length >= 8
                && asciiEquals(head, 0, "II")
                && unsigned(head[2]) == 0x2a
                && unsigned(head[3]) == 0x00
                && littleEndianUnsignedInt(head, 4) < length) {
            return "image/tiff";
        }
        if (head.length >= 8
                && asciiEquals(head, 0, "MM")
                && unsigned(head[2]) == 0x00
                && unsigned(head[3]) == 0x2a
                && bigEndianUnsignedInt(head, 4) < length) {
            return "image/tiff";
        }
        if (head.length >= 14
                && asciiEquals(head, 0, "BM")
                && littleEndianUnsignedInt(head, 2) == length
                && littleEndianUnsignedInt(head, 10) < length) {
            return "image/bmp";
        }
        throw UnsupportedUploadMediaTypeException.unsupported();
    }

    private static boolean wmfHeaderCoversMember(byte[] head, int offset, long length) {
        int type = littleEndianUnsignedShort(head, offset);
        return (type == 1 || type == 2)
            && littleEndianUnsignedShort(head, offset + 2) == 9
            && littleEndianUnsignedInt(head, offset + 6) * 2L == length;
    }

    /**
     * Proves an ODF embedded font member is an sfnt container whose directory fits the member.
     *
     * <p>LibreOffice embeds the fonts a document uses under {@code Fonts/} as TrueType, OpenType,
     * or collection files, so the member must start with one of the sfnt tags ({@code 00 01 00 00},
     * {@code OTTO}, {@code true}, or {@code ttcf}) and its table or font directory must lie inside
     * the member; the tables themselves are not parsed. Anything else is refused.
     *
     * @param head leading member bytes
     * @param length exact member length
     * @return the canonical sfnt media type
     */
    private static String sniffFontMember(byte[] head, long length) {
        if (head.length < 12) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        long directoryEnd;
        if (asciiEquals(head, 0, "ttcf")) {
            directoryEnd = 12L + 4L * bigEndianUnsignedInt(head, 8);
        } else if (bigEndianUnsignedInt(head, 0) == 0x00010000L
                || asciiEquals(head, 0, "OTTO")
                || asciiEquals(head, 0, "true")) {
            directoryEnd = 12L + 16L * bigEndianUnsignedShort(head, 4);
        } else {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        if (directoryEnd <= 12L || directoryEnd > length) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return SFNT_FONT_TYPE;
    }

    /**
     * Refuses a declared-opaque member whose leading bytes look like an executable, an archive, a
     * compound file, a document, or markup rather than the inert blob its declared type promises.
     *
     * @param head leading member bytes
     */
    private static void requireInertOpaqueMember(byte[] head) {
        for (byte[] magic : REFUSED_OPAQUE_MAGIC) {
            if (startsWith(head, magic)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        int offset = 0;
        while (offset < head.length && (head[offset] == ' ' || head[offset] == '\t'
                || head[offset] == '\r' || head[offset] == '\n' || head[offset] == (byte) 0xef
                || head[offset] == (byte) 0xbb || head[offset] == (byte) 0xbf)) {
            offset++;
        }
        String prefix = ascii(head, offset, head.length - offset).toLowerCase(Locale.ROOT);
        for (String marker : REFUSED_OPAQUE_TEXT_PREFIXES) {
            if (prefix.startsWith(marker)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
    }

    private static EntryContent readArchiveEntry(
            ZipInputStream zip,
            ArchiveEntry expected,
            long expandedBefore,
            long maxBytes,
            long capturePrefix,
            Deadline deadline) throws IOException {
        if (expected.uncompressedSize() > MAX_ARCHIVE_EXPANDED_BYTES - expandedBefore
                || expected.uncompressedSize() > maxBytes) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int captureLength = Math.toIntExact(
            Math.min(expected.uncompressedSize(), capturePrefix));
        byte[] captured = new byte[captureLength];
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
            if (read < captureLength) {
                System.arraycopy(
                    buffer,
                    0,
                    captured,
                    Math.toIntExact(read),
                    Math.toIntExact(Math.min(count, captureLength - read)));
            }
            read += count;
        }
        if (read != expected.uncompressedSize() || crc.getValue() != expected.crc()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return new EntryContent(read, captured);
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
        requireInertDeclaredTypes(evidence.contentTypeOverrides().values());
        requireInertDeclaredTypes(evidence.contentTypeDefaults().values());
        bindOoxmlMembers(evidence);
        requireOoxmlSignatures(evidence);
    }

    /**
     * Binds every inspected OOXML member to the content type the package itself declares for it.
     *
     * <p>Resolution follows the Open Packaging Conventions rule: the {@code Override} whose
     * {@code PartName} matches wins, otherwise the {@code Default} for the member extension. A
     * non-XML member with no declared type is refused, and a raster or metafile member whose
     * declared type disagrees with the type its bytes prove is refused, so an
     * {@code image/svg+xml} payload is refused whatever the member is called.
     *
     * @param evidence evidence gathered during the single package pass
     */
    private static void bindOoxmlMembers(PackageEvidence evidence) {
        for (MemberEvidence member : evidence.members().values()) {
            if (boundExemptMember(member.memberClass())) {
                continue;
            }
            String declared = evidence.contentTypeOverrides().get("/" + member.name());
            if (declared == null) {
                declared = evidence.contentTypeDefaults()
                    .get(memberExtension(member.name().toLowerCase(Locale.ROOT)));
            }
            requireDeclaredType(member, declared, EMBEDDED_FONT_MEMBER
                .matcher(member.name()).matches()
                ? EMBEDDED_FONT_CONTENT_TYPES
                : PRINTER_SETTINGS_CONTENT_TYPES);
        }
    }

    /**
     * Admits digitally signed OOXML packages through the narrow shape the Open Packaging
     * Conventions define, and refuses every other arrangement of signature markup.
     *
     * <p>Signed contracts are ordinary attachments in this product's market, and before this
     * binding a signed {@code .docx} was refused outright because XMLDSig uses {@code <Object>}.
     * The widening is deliberately narrow: signature markup is admitted only inside
     * {@code _xmlsignatures/}, only when the package declares the OPC signature content types,
     * and only when the root relationships part points at the signature origin part. Macro
     * signatures and {@code <Object>} outside a signature part stay refused.
     *
     * @param evidence evidence gathered during the single package pass
     */
    private static void requireOoxmlSignatures(PackageEvidence evidence) {
        Set<String> signatureParts = new HashSet<>();
        for (MemberEvidence member : evidence.members().values()) {
            if (member.memberClass() == PackageMemberClass.SIGNATURE) {
                signatureParts.add(member.name());
            }
        }
        for (Map.Entry<String, String> override : evidence.contentTypeOverrides().entrySet()) {
            if (OOXML_SIGNATURE_CONTENT_TYPE.equalsIgnoreCase(override.getValue())
                    && !signatureParts.contains(override.getKey().substring(1))) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        if (!signatureParts.containsAll(evidence.signatureTargets())) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        if (signatureParts.isEmpty() && evidence.signatureOriginTargets().isEmpty()) {
            return;
        }
        MemberEvidence origin = evidence.members().get(OOXML_SIGNATURE_ORIGIN_PART);
        if (!signatureParts.equals(evidence.signatureTargets())
                || !Set.of(OOXML_SIGNATURE_ORIGIN_PART).equals(evidence.signatureOriginTargets())
                || origin == null
                || origin.memberClass() != PackageMemberClass.SIGNATURE_ORIGIN) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static boolean boundExemptMember(PackageMemberClass memberClass) {
        return memberClass == PackageMemberClass.XML
            || memberClass == PackageMemberClass.MIMETYPE
            || memberClass == PackageMemberClass.DIRECTORY;
    }

    private static void requireDeclaredType(
            MemberEvidence member,
            String declared,
            Set<String> opaqueContentTypes) {
        if (declared == null) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String normalized = canonicalDeclaredType(declared);
        boolean bound = switch (member.memberClass()) {
            case RASTER, SNIFFED_OPAQUE -> normalized.equals(member.sniffedType());
            case DECLARED_OPAQUE -> opaqueContentTypes.contains(normalized);
            case SIGNATURE -> OOXML_SIGNATURE_CONTENT_TYPE.equals(normalized);
            case SIGNATURE_ORIGIN -> OOXML_SIGNATURE_ORIGIN_CONTENT_TYPE.equals(normalized);
            case XML -> XML_MEMBER_CONTENT_TYPES.contains(normalized);
            case MIMETYPE, DIRECTORY, REFUSED -> false;
        };
        if (!bound) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private static String canonicalDeclaredType(String declared) {
        String normalized = declared.trim().toLowerCase(Locale.ROOT);
        int parameter = normalized.indexOf(';');
        if (parameter >= 0) {
            normalized = normalized.substring(0, parameter).trim();
        }
        return switch (normalized) {
            case "image/jpg", "image/pjpeg" -> "image/jpeg";
            case "image/emf" -> "image/x-emf";
            case "image/wmf" -> "image/x-wmf";
            case "image/x-tiff" -> "image/tiff";
            case "image/x-ms-bmp" -> "image/bmp";
            case "application/x-font-ttf", "application/x-font-otf", "font/ttf", "font/otf",
                "application/vnd.ms-opentype" -> SFNT_FONT_TYPE;
            default -> normalized;
        };
    }

    private static void requireInertDeclaredTypes(java.util.Collection<String> declaredTypes) {
        for (String declared : declaredTypes) {
            String normalized = canonicalDeclaredType(declared);
            if (REFUSED_DECLARED_CONTENT_TYPES.contains(normalized)
                    || normalized.startsWith("audio/")
                    || normalized.startsWith("video/")
                    || normalized.contains("macroenabled")
                    || normalized.contains("vba")) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
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
                    ODF_MANIFEST_NAMESPACE,
                    "manifest").equals(manifestRoot)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        requireInertDeclaredTypes(evidence.manifestMediaTypes().values());
        bindOdfMembers(evidence);
    }

    /**
     * Binds every inspected ODF member to the media type its own package manifest declares.
     *
     * <p>ODF 1.3 Part 2 requires exactly one {@code manifest:file-entry} for every member other
     * than {@code mimetype} and the {@code META-INF/} parts, so a member missing from the
     * manifest and a manifest entry naming a member that is not present are both refused. A
     * picture whose declared media type disagrees with the format its bytes prove is refused
     * whatever the member is called.
     *
     * @param evidence evidence gathered during the single package pass
     */
    private static void bindOdfMembers(PackageEvidence evidence) {
        for (MemberEvidence member : evidence.members().values()) {
            if (member.memberClass() == PackageMemberClass.DIRECTORY
                    || member.memberClass() == PackageMemberClass.MIMETYPE
                    || member.name().startsWith("META-INF/")) {
                continue;
            }
            requireDeclaredType(
                member,
                evidence.manifestMediaTypes().get(member.name()),
                ODF_OPAQUE_MEMBER_CONTENT_TYPES);
        }
        for (String declaredMember : evidence.manifestMediaTypes().keySet()) {
            if (!"/".equals(declaredMember)
                    && !declaredMember.endsWith("/")
                    && !evidence.names().contains(declaredMember)) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
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

    /**
     * Refuses package members by name family before any of their bytes are retained.
     *
     * <p>The blanket {@code .bin} refusal is deliberately widened for
     * {@code (word|xl|ppt)/printerSettings/printerSettingsN.bin} only: Excel on Windows writes
     * that part into most saved workbooks, and refusing it rejected ordinary business documents.
     * Those parts are admitted as declared-opaque bytes bounded at 1 MiB with a negative header
     * sniff, so a compound file, executable, or archive smuggled under that exact name is still
     * refused. Every other {@code .bin} member, including {@code vbaProjectSignature*.bin},
     * remains refused outright.
     *
     * @param name exact archive entry name
     * @param normalized lower-case archive entry name
     * @return whether the member is refused on its name alone
     */
    private static boolean refusedPackageEntry(String name, String normalized) {
        if (normalized.endsWith(".bin") && PRINTER_SETTINGS_MEMBER.matcher(name).matches()) {
            return false;
        }
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
            || normalized.startsWith("dialogs/")
            || normalized.contains("/dialogs/")
            || normalized.startsWith("[trash]/")
            || normalized.contains("afchunk")
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
            || normalized.endsWith(".swf")
            || normalized.endsWith(".svm")
            || normalized.endsWith(".wdp")
            || normalized.endsWith(".hdp")
            || normalized.endsWith(".mht")
            || normalized.endsWith(".mhtml")
            || normalized.endsWith(".xhtml")
            || normalized.endsWith(".vbs")
            || normalized.endsWith(".wsf")
            || normalized.endsWith(".lnk");
    }

    /**
     * Builds the closed element vocabulary admitted inside an OOXML package signature part.
     *
     * <p>The names are the union of the XMLDSig core schema, the OPC digital-signature markup,
     * the Microsoft Office signature-information schema, and the XAdES 1.3.2 and 1.4.1 schemas.
     * Anything outside the union is refused by absence, which is the same fail-closed posture the
     * ODF signature part already uses.
     *
     * @return lower-case element local names admitted inside a signature part
     */
    private static Set<String> ooxmlSignatureElements() {
        Set<String> elements = new HashSet<>(ODF_SIGNATURE_ELEMENTS);
        elements.addAll(XMLDSIG_ELEMENTS);
        elements.addAll(OPC_SIGNATURE_ELEMENTS);
        elements.addAll(OFFICE_SIGNATURE_ELEMENTS);
        elements.addAll(XADES_ELEMENTS);
        return Set.copyOf(elements);
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

    private enum PackageMemberClass {
        XML,
        SIGNATURE,
        SIGNATURE_ORIGIN,
        MIMETYPE,
        RASTER,
        SNIFFED_OPAQUE,
        DECLARED_OPAQUE,
        DIRECTORY,
        REFUSED
    }

    private record MemberEvidence(
            String name,
            PackageMemberClass memberClass,
            String sniffedType,
            long length) {}

    private record PackageEvidence(
            Set<String> names,
            Map<String, String> contentTypeOverrides,
            Map<String, String> contentTypeDefaults,
            Map<String, String> manifestMediaTypes,
            Map<String, MemberEvidence> members,
            Map<String, XmlRoot> xmlRoots,
            Set<String> officeDocumentTargets,
            Set<String> relationshipTargets,
            Set<String> signatureOriginTargets,
            Set<String> signatureTargets,
            String packageMimeType) {}

    private record XmlEvidence(
            Map<String, String> contentTypeOverrides,
            Map<String, String> contentTypeDefaults,
            Map<String, String> manifestMediaTypes,
            Set<String> officeDocumentTargets,
            Set<String> relationshipTargets,
            Set<String> signatureOriginTargets,
            Set<String> signatureTargets,
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
        private final boolean odfSignaturePart;
        private final boolean odfManifestPart;
        private final boolean odfMetadataPart;
        private final boolean ooxmlSignaturePart;
        private final boolean signaturePart;
        private final boolean contentTypesDocument;
        private final boolean rootRelationshipsDocument;
        private final boolean relationshipsDocument;
        private final Map<String, String> contentTypeOverrides = new HashMap<>();
        private final Map<String, String> contentTypeDefaults = new HashMap<>();
        private final Map<String, String> manifestMediaTypes = new HashMap<>();
        private final Set<String> officeDocumentTargets = new HashSet<>();
        private final Set<String> relationshipTargets = new HashSet<>();
        private final Set<String> signatureOriginTargets = new HashSet<>();
        private final Set<String> signatureTargets = new HashSet<>();
        private final Set<String> relationshipIds = new HashSet<>();
        private final List<WordFieldState> wordFields = new ArrayList<>();
        private final StringBuilder signatureUriText = new StringBuilder();
        private int depth;
        private int instructionTextDepth;
        private int signatureUriTextDepth;
        private int emptyOnlyOfficeElementDepth;
        private XmlRoot root;

        private SafeXmlHandler(
                Deadline deadline,
                String entryName,
                boolean odfPackage) {
            this.deadline = deadline;
            this.entryName = entryName;
            this.odfPackage = odfPackage;
            odfSignaturePart = odfPackage && ODF_SIGNATURE_PART.equals(entryName);
            odfManifestPart = odfPackage && ODF_MANIFEST_PART.equals(entryName);
            odfMetadataPart = odfPackage && ODF_METADATA_MANIFEST_PART.equals(entryName);
            ooxmlSignaturePart = !odfPackage
                && entryName.startsWith(OOXML_SIGNATURE_DIRECTORY)
                && entryName.toLowerCase(Locale.ROOT).endsWith(".xml");
            signaturePart = odfSignaturePart || ooxmlSignaturePart;
            contentTypesDocument = OOXML_CONTENT_TYPES_PART.equals(entryName);
            rootRelationshipsDocument = OOXML_ROOT_RELATIONSHIPS_PART.equals(entryName);
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
            if (odfSignaturePart) {
                if (!ODF_SIGNATURE_NAMESPACES.contains(uri)
                        || !ODF_SIGNATURE_ELEMENTS.contains(normalizedElement)) {
                    throw new SAXException("ODF signature content is not allowed");
                }
            } else if (ooxmlSignaturePart) {
                if (!OOXML_SIGNATURE_NAMESPACES.contains(uri)
                        || !OOXML_SIGNATURE_ELEMENTS.contains(normalizedElement)) {
                    throw new SAXException("Package signature content is not allowed");
                }
            } else if (odfMetadataPart) {
                if (!ODF_METADATA_NAMESPACES.contains(uri)
                        || !ODF_METADATA_ELEMENTS.contains(normalizedElement)) {
                    throw new SAXException("ODF metadata manifest content is not allowed");
                }
            } else if (odfManifestPart) {
                if (!ODF_MANIFEST_NAMESPACE.equals(uri)
                        || !ODF_MANIFEST_ELEMENTS.contains(normalizedElement)) {
                    throw new SAXException("ODF manifest content is not allowed");
                }
            } else if (odfPackage) {
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
                if (ODF_ANIMATION_NAMESPACE.equals(uri)
                        && !ODF_ANIMATION_ELEMENTS.contains(normalizedElement)) {
                    throw new SAXException("ODF animation element is not allowed");
                }
                if (ddeName(normalizedElement)) {
                    throw new SAXException("ODF DDE content is not allowed");
                }
                if (ODF_OFFICE_NAMESPACE.equals(uri)
                        && ODF_EMPTY_ONLY_OFFICE_ELEMENTS.contains(normalizedElement)) {
                    emptyOnlyOfficeElementDepth = depth;
                }
            }
            if (!signaturePart && ACTIVE_XML_ELEMENTS.contains(normalizedElement)) {
                throw new SAXException("Active XML content is not allowed");
            }
            if (ooxmlSignaturePart
                    && ("reference".equals(normalizedElement)
                        || "retrievalmethod".equals(normalizedElement))) {
                String referenced = signatureReferenceTarget(attribute(attributes, "URI"));
                if (referenced != null) {
                    relationshipTargets.add(referenced);
                }
            }
            if (ooxmlSignaturePart && SIGNATURE_URI_ELEMENTS.contains(normalizedElement)) {
                if (signatureUriTextDepth != 0) {
                    throw new SAXException("Package signature reference is invalid");
                }
                signatureUriTextDepth = depth;
                signatureUriText.setLength(0);
            }
            if (odfManifestPart && "file-entry".equals(normalizedElement)) {
                inspectManifestFileEntry(attributes);
            }
            inspectWordFieldStart(uri, normalizedElement, attributes);
            if (formulaElement(uri, normalizedElement)) {
                throw new SAXException("Spreadsheet formulas are not allowed");
            }
            if (contentTypesDocument && "Default".equals(element)) {
                if (depth != 2 || !OOXML_CONTENT_TYPES_NAMESPACE.equals(uri)) {
                    throw new SAXException("Package content types are invalid");
                }
                String extension = attribute(attributes, "Extension");
                String contentType = attribute(attributes, "ContentType");
                if (extension == null
                        || contentType == null
                        || extension.isBlank()
                        || contentTypeDefaults.putIfAbsent(
                            extension.toLowerCase(Locale.ROOT), contentType) != null) {
                    throw new SAXException("Package content types are ambiguous");
                }
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
                if (odfMetadataPart
                        && ("about".equals(normalizedName)
                            || "resource".equals(normalizedName))) {
                    inspectMetadataReference(value);
                }
                if ("action".equals(normalizedName)
                        && DRAWING_NAMESPACES.contains(uri)
                        && PRESENTATION_ACTION_ELEMENTS.contains(normalizedElement)
                        && !SAFE_PRESENTATION_ACTION.matcher(value.trim()).matches()) {
                    throw new SAXException("Presentation action is not allowed");
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
        public void endElement(String uri, String localName, String qualifiedName)
                throws SAXException {
            deadline.check();
            String element = localName.isEmpty() ? qualifiedName : localName;
            if (WORDPROCESSING_NAMESPACES.contains(uri)
                    && "instrtext".equals(element.toLowerCase(Locale.ROOT))) {
                instructionTextDepth = 0;
            }
            if (depth == signatureUriTextDepth) {
                inspectSignatureUriText();
                signatureUriTextDepth = 0;
            }
            if (depth == emptyOnlyOfficeElementDepth) {
                emptyOnlyOfficeElementDepth = 0;
            }
            depth--;
        }

        @Override
        public void characters(char[] characters, int start, int length) throws SAXException {
            deadline.check();
            if (signatureUriTextDepth > 0) {
                if (signatureUriText.length() + length > MAX_SIGNATURE_URI_CHARACTERS) {
                    throw new SAXException("Package signature reference exceeds safe bounds");
                }
                signatureUriText.append(characters, start, length);
            }
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
            if (signatureUriTextDepth != 0) {
                throw new SAXException("Package signature reference is invalid");
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
                Map.copyOf(contentTypeDefaults),
                Map.copyOf(manifestMediaTypes),
                Set.copyOf(officeDocumentTargets),
                Set.copyOf(relationshipTargets),
                Set.copyOf(signatureOriginTargets),
                Set.copyOf(signatureTargets),
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
            if (OOXML_SIGNATURE_ORIGIN_RELATIONSHIP.equals(relationshipType)) {
                if (!rootRelationshipsDocument) {
                    throw new SAXException("Package signature origin relationship is misplaced");
                }
                signatureOriginTargets.add(normalizedTarget);
            }
            if (OOXML_SIGNATURE_RELATIONSHIP.equals(relationshipType)) {
                if (!OOXML_SIGNATURE_ORIGIN_RELATIONSHIPS_PART.equals(entryName)) {
                    throw new SAXException("Package signature relationship is misplaced");
                }
                signatureTargets.add(normalizedTarget);
            }
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

        private void inspectManifestFileEntry(Attributes attributes) throws SAXException {
            if (depth != 2) {
                throw new SAXException("ODF manifest entry is misplaced");
            }
            String fullPath = attribute(attributes, "full-path");
            String mediaType = attribute(attributes, "media-type");
            if (fullPath == null
                    || mediaType == null
                    || !safeManifestPath(fullPath)
                    || manifestMediaTypes.putIfAbsent(fullPath, mediaType) != null) {
                throw new SAXException("ODF manifest entry is ambiguous");
            }
        }

        private static boolean safeManifestPath(String value) {
            if ("/".equals(value)) {
                return true;
            }
            return value.endsWith("/")
                ? safeArchivePath(value.substring(0, value.length() - 1))
                : safeArchivePath(value);
        }

        /**
         * Binds URL-bearing signature text such as XAdES {@code SPURI} and Office
         * {@code SignatureProviderUrl} to the same rule as signature references, except that an
         * ordinary web, mail, or telephone hyperlink is also admitted because those values are
         * shown to a person rather than dereferenced by the package consumer.
         */
        private void inspectSignatureUriText() throws SAXException {
            String value = signatureUriText.toString().trim();
            if (value.isEmpty() || safeExternalHyperlink(value)) {
                return;
            }
            String referenced = signatureReferenceTarget(value);
            if (referenced != null) {
                relationshipTargets.add(referenced);
            }
        }

        /**
         * Binds an ODF metadata-manifest subject or object to the package it lives in.
         *
         * <p>An {@code rdf:about} or {@code rdf:resource} value may be empty (the package
         * itself), an ODF metadata vocabulary URI, or a reference to a member of this package,
         * which is recorded as a relationship target so that a reference to an absent member
         * refuses. Any other URI, including every external one, refuses.
         */
        private void inspectMetadataReference(String value) throws SAXException {
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.startsWith(ODF_METADATA_NAMESPACE_PREFIX)) {
                return;
            }
            String referenced = normalizePackageReference(normalized);
            if (referenced != null) {
                relationshipTargets.add(referenced);
            }
        }

        /**
         * Validates a signature reference and returns the package part it covers.
         *
         * <p>A {@code Reference}, a {@code RetrievalMethod}, or URL-bearing signature text may
         * only address a fragment inside the signature itself or a part of this package written
         * as {@code /part?ContentType=type}. The returned part is recorded as a relationship
         * target so that a signature covering a part that is not present refuses, and a reference
         * whose declared content type names macro, OLE, ActiveX, control, or embedded-package
         * content refuses outright.
         *
         * @param value raw reference value
         * @return the referenced archive path, or {@code null} for an in-signature reference
         */
        private static String signatureReferenceTarget(String value) throws SAXException {
            if (value == null) {
                throw new SAXException("Package signature reference is invalid");
            }
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                return null;
            }
            if (normalized.startsWith("#")) {
                if (!safeFragment(normalized.substring(1))) {
                    throw new SAXException("Package signature reference is invalid");
                }
                return null;
            }
            if (!normalized.startsWith("/")) {
                throw new SAXException("Package signature reference is invalid");
            }
            int query = normalized.indexOf('?');
            String path = query < 0
                ? normalized.substring(1)
                : normalized.substring(1, query);
            if (!safeArchivePath(path)) {
                throw new SAXException("Package signature reference is invalid");
            }
            if (query >= 0) {
                String parameters = normalized.substring(query + 1).toLowerCase(Locale.ROOT);
                if (!parameters.startsWith("contenttype=")
                        || parameters.contains("vba")
                        || parameters.contains("macroenabled")
                        || parameters.contains("oleobject")
                        || parameters.contains("activex")
                        || parameters.contains("controlproperties")
                        || parameters.contains("officedocument.package")) {
                    throw new SAXException("Package signature reference is invalid");
                }
            }
            return path;
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

        /**
         * Refuses spreadsheet and chart formula vocabularies wherever they appear.
         *
         * <p>The VML namespace is exempt because {@code v:formulas} and {@code v:f} there are
         * static shape-geometry adjustments, not calculated expressions; legacy cell comments and
         * Word watermarks would otherwise stop uploading now that VML parts are parsed.
         */
        private static boolean formulaElement(String uri, String localName) {
            if (VML_NAMESPACES.contains(uri)) {
                return false;
            }
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
