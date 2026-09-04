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

    @Test
    void inspectedUploadIsConstructedOnlyByTheInspector() throws IOException {
        assertEquals(
            List.of(INSPECTOR),
            sourcesContaining("new InspectedUpload("),
            "Only UploadContentInspector may mint an inspected artifact");
    }

    @Test
    void archiveAndImageParsingOfUploadsStaysInsideTheInspector() throws IOException {
        for (String parser : List.of(
                "java.util.zip.ZipInputStream",
                "java.util.zip.ZipFile",
                "ImageIO.read(")) {
            List<Path> sources = sourcesContaining(parser);
            sources.remove(INSPECTOR);
            assertTrue(
                sources.isEmpty(),
                "Untrusted upload parsing must stay inside the inspector: " + parser + " "
                    + sources);
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
