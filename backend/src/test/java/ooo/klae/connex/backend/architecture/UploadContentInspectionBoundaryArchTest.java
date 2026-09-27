package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.storage.UploadPolicy;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;

/**
 * Pins the upload content-inspection boundary that future ingress pipelines must reuse.
 *
 * <p>The contract these assertions defend is written up in
 * {@code docs/UPLOAD_CONTENT_INSPECTION.md}. New upload surfaces — folders, standalone uploads,
 * external file requests, provider-backed references — must route bytes through
 * {@code UploadContentInspector} rather than growing a second, weaker path, so the pins here fail
 * the build if inspection artifacts, archive parsing, or object writes leak out of the storage
 * package.
 *
 * <p>Image decoding is pinned to the surface the repository actually uses, the
 * {@code javax.imageio} reader and stream API rather than an {@code ImageIO.read} idiom no
 * production class calls. The sanctioned decoders are the inspector's own
 * {@code ImageUploadValidator} and the business-card ingress's {@code BusinessCardImageValidator},
 * which decodes and re-encodes card scans to a canonical JPEG before they are stored; any other
 * class that starts decoding uploaded bytes fails this pin.
 */
class UploadContentInspectionBoundaryArchTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path REPOSITORY_ROOT = Path.of("..");
    private static final Path BOUNDARY_DOCUMENT =
        REPOSITORY_ROOT.resolve("docs/UPLOAD_CONTENT_INSPECTION.md");
    private static final Path INSPECTOR = SOURCE_ROOT.resolve(
        "ooo/klae/connex/backend/storage/UploadContentInspector.java");
    private static final Path MANAGED_OBJECT_SERVICE = SOURCE_ROOT.resolve(
        "ooo/klae/connex/backend/storage/ManagedObjectService.java");
    private static final Path IMAGE_UPLOAD_VALIDATOR = SOURCE_ROOT.resolve(
        "ooo/klae/connex/backend/storage/ImageUploadValidator.java");
    private static final Path BUSINESS_CARD_IMAGE_VALIDATOR = SOURCE_ROOT.resolve(
        "ooo/klae/connex/backend/businesscard/BusinessCardImageValidator.java");
    private static final Set<Path> SANCTIONED_IMAGE_DECODERS = Set.of(
        IMAGE_UPLOAD_VALIDATOR, BUSINESS_CARD_IMAGE_VALIDATOR);
    private static final Map<String, Set<Path>> UNTRUSTED_PARSERS = Map.of(
        "java.util.zip.ZipInputStream", Set.of(INSPECTOR),
        "java.util.zip.ZipFile", Set.of(INSPECTOR),
        "javax.imageio.ImageIO", SANCTIONED_IMAGE_DECODERS,
        "ImageReader", SANCTIONED_IMAGE_DECODERS,
        "ImageInputStream", SANCTIONED_IMAGE_DECODERS);

    @Test
    void inspectedUploadIsConstructedOnlyByTheInspector() throws IOException {
        assertEquals(
            List.of(INSPECTOR),
            sourcesContaining("new InspectedUpload("),
            "Only UploadContentInspector may mint an inspected artifact");
    }

    @Test
    void archiveAndImageParsingOfUploadsStaysInsideTheSanctionedDecoders() throws IOException {
        for (Map.Entry<String, Set<Path>> parser : UNTRUSTED_PARSERS.entrySet()) {
            List<Path> sources = sourcesContaining(parser.getKey());
            sources.removeAll(parser.getValue());
            assertTrue(
                sources.isEmpty(),
                "Untrusted upload parsing must stay inside the sanctioned decoders: "
                    + parser.getKey() + " " + sources);
        }
    }

    @Test
    void objectStorageWritesHaveASingleCallSite() throws IOException {
        assertEquals(
            List.of(MANAGED_OBJECT_SERVICE),
            sourcesContaining("objectStorage.put("),
            "Stored bytes must flow through the single ManagedObjectService writer");
    }

    @Test
    void uploadPurposeFormatCeilingIsFixed() throws NoSuchFieldException {
        Field formats = UploadPolicy.class.getDeclaredField("FORMATS_BY_PURPOSE");

        assertTrue(Modifier.isPrivate(formats.getModifiers()));
        assertTrue(Modifier.isStatic(formats.getModifiers()));
        assertTrue(Modifier.isFinal(formats.getModifiers()));

        List<String> mutators = new ArrayList<>();
        for (Method method : UploadPolicy.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getName().startsWith("set")
                    || Set.class.isAssignableFrom(method.getReturnType())
                    || java.util.Map.class.isAssignableFrom(method.getReturnType())) {
                mutators.add(method.getName());
            }
        }
        assertTrue(
            mutators.isEmpty(),
            "UploadPolicy must not expose or widen its format ceiling: " + mutators);
    }

    @Test
    void boundaryDocumentListsEveryUploadPurposeAndFormat() throws IOException {
        String document = Files.readString(BOUNDARY_DOCUMENT, StandardCharsets.UTF_8);

        for (UploadPurpose purpose : UploadPurpose.values()) {
            assertTrue(
                document.contains(purpose.name()),
                "docs/UPLOAD_CONTENT_INSPECTION.md must document " + purpose.name());
        }
        for (UploadFormat format : UploadFormat.values()) {
            assertTrue(
                document.contains(format.name()),
                "docs/UPLOAD_CONTENT_INSPECTION.md must document " + format.name());
        }
    }

    private static List<Path> sourcesContaining(String token) throws IOException {
        try (var sources = Files.walk(SOURCE_ROOT)) {
            return new ArrayList<>(sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> read(path).contains(token))
                .sorted()
                .toList());
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
