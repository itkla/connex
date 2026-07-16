package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyUploadFileReaderTest {
    @TempDir Path temporaryDirectory;

    private Path uploadsRoot;
    private LegacyUploadFileReader reader;

    @BeforeEach
    void setUp() throws Exception {
        uploadsRoot = temporaryDirectory.resolve("public");
        Files.createDirectories(uploadsRoot);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.getLegacyMigration().setUploadsRoot(uploadsRoot.toString());
        properties.setMaxUploadBytes(1_024);
        reader = new LegacyUploadFileReader(properties);
    }

    @Test
    void readsKnownPathBeneathConfiguredRoot() throws Exception {
        Path source = uploadsRoot.resolve("attachments/person/card.txt");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {1, 2, 3});

        ResolvedLegacyUpload resolved = reader.read(
            "/attachments/person/card.txt", "/attachments/");

        assertEquals("card.txt", resolved.fileName());
        assertArrayEquals(new byte[] {1, 2, 3}, resolved.content());
    }

    @Test
    void rejectsUnknownPrefixesTraversalAndEscapingLinks() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.bin");
        Files.write(outside, new byte[] {1});
        Path link = uploadsRoot.resolve("attachments/person/escape.bin");
        Path inside = uploadsRoot.resolve("attachments/person/inside.bin");
        Path insideLink = uploadsRoot.resolve("attachments/person/inside-link.bin");
        Files.createDirectories(link.getParent());
        Files.createDirectories(inside.getParent());
        Files.write(inside, new byte[] {2});
        try {
            Files.createSymbolicLink(link, outside);
            Files.createSymbolicLink(insideLink, inside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(IllegalStateException.class,
            () -> reader.read("/other/file.bin", "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/attachments/../../outside.bin", "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/attachments/person/escape.bin", "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read(
                "/attachments/person/inside-link.bin", "/attachments/"));
    }

    @Test
    void rejectsCrossPrefixEncodingAndNonCanonicalPaths() {
        assertThrows(IllegalStateException.class,
            () -> reader.read(
                "/attachments/../profile-pictures/x.png", "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/attachments/person/%2e%2e", "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/attachments/person\\file.pdf", "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/contact-pictures/nested/file.png", "/contact-pictures/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/company-logos/file.png?download=1", "/company-logos/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/profile-pictures/file\u0000.png", "/profile-pictures/"));
    }

    @Test
    void rejectsMissingEmptyAndOversizedSources() throws Exception {
        Path empty = uploadsRoot.resolve("profile-pictures/empty.png");
        Path large = uploadsRoot.resolve("profile-pictures/large.png");
        Files.createDirectories(empty.getParent());
        Files.write(empty, new byte[0]);
        Files.write(large, new byte[1_025]);

        assertThrows(IllegalStateException.class,
            () -> reader.read("/profile-pictures/missing.png", "/profile-pictures/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/profile-pictures/empty.png", "/profile-pictures/"));
        assertThrows(IllegalStateException.class,
            () -> reader.read("/profile-pictures/large.png", "/profile-pictures/"));
    }

    @Test
    void validatesCanonicalOwnerBoundLegacyNames() {
        LegacyUploadRecord attachment = record(
            41, 7, "person", 19, "/attachments/person/person-19-1700000000000-card.pdf");
        LegacyUploadRecord person = record(
            19, 7, null, null, "/contact-pictures/contact-19-1700000000000-card.png");
        LegacyUploadRecord company = record(
            23, 7, null, null, "/company-logos/company-23-1700000000000-logo.png");
        LegacyUploadRecord user = record(
            29, null, null, null, "/profile-pictures/user-29-1700000000000-photo.png");

        reader.validateOwnership(attachment, "/attachments/");
        reader.validateOwnership(person, "/contact-pictures/");
        reader.validateOwnership(company, "/company-logos/");
        reader.validateOwnership(user, "/profile-pictures/");
    }

    @Test
    void rejectsLegacyNamesOwnedByDifferentRows() {
        LegacyUploadRecord attachment = record(
            41, 7, "person", 19, "/attachments/person/person-91-1700000000000-card.pdf");
        LegacyUploadRecord person = record(
            19, 7, null, null, "/contact-pictures/contact-91-1700000000000-card.png");
        LegacyUploadRecord company = record(
            23, 7, null, null, "/company-logos/company-91-1700000000000-logo.png");
        LegacyUploadRecord user = record(
            29, null, null, null, "/profile-pictures/user-91-1700000000000-photo.png");

        assertThrows(IllegalStateException.class,
            () -> reader.validateOwnership(attachment, "/attachments/"));
        assertThrows(IllegalStateException.class,
            () -> reader.validateOwnership(person, "/contact-pictures/"));
        assertThrows(IllegalStateException.class,
            () -> reader.validateOwnership(company, "/company-logos/"));
        assertThrows(IllegalStateException.class,
            () -> reader.validateOwnership(user, "/profile-pictures/"));
    }

    @Test
    void rejectsOverlappingLegacyAndPrivateStorageRoots() throws Exception {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.getLegacyMigration().setUploadsRoot(uploadsRoot.toString());
        properties.setFilesystemRoot(uploadsRoot.resolve("managed").toString());

        LegacyUploadFileReader overlappingReader = new LegacyUploadFileReader(properties);

        assertThrows(IllegalStateException.class, overlappingReader::validateConfiguration);
    }

    private static LegacyUploadRecord record(
            int id,
            Integer workspaceId,
            String entityType,
            Integer entityId,
            String url) {
        LegacyUploadRecord record = new LegacyUploadRecord();
        record.setId(id);
        record.setWorkspaceId(workspaceId);
        record.setEntityType(entityType);
        record.setEntityId(entityId);
        record.setUrl(url);
        return record;
    }
}
