package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.ObjectStorageQuotaMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LegacyUploadMigrationIntegrationTest {
    private static final Path ROOT = temporaryRoot();
    private static final Path LEGACY_ROOT = ROOT.resolve("legacy");
    private static final Path OBJECT_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"), "connex-test-object-storage")
        .toAbsolutePath()
        .normalize();

    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private ObjectStorageQuotaMapper quotaMapper;
    @Autowired private LegacyUploadFileReader fileReader;
    @Autowired private LegacyUploadMigrationTransaction migration;
    @Autowired private ManagedObjectService managedObjectService;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.object-storage.filesystem-root", OBJECT_ROOT::toString);
        registry.add("connex.object-storage.legacy-migration.uploads-root", LEGACY_ROOT::toString);
    }

    @AfterAll
    static void removeTemporaryStorage() throws IOException {
        if (!Files.exists(ROOT)) {
            return;
        }
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void migratesAndVerifiesTenantAndControlMediaWhileRetainingSources() throws Exception {
        Files.createDirectories(LEGACY_ROOT.resolve("attachments/person"));
        Files.createDirectories(LEGACY_ROOT.resolve("profile-pictures"));
        byte[] attachmentBytes = "legacy fixture".getBytes(StandardCharsets.UTF_8);
        byte[] imageBytes = png();
        Workspace workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Legacy migration workspace");
            workspace.setSlug("default");
            workspaceMapper.insert(workspace);
        }
        User user = user(workspace.getId());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        Attachment attachment = attachment(workspace.getId(), user);
        Path attachmentSource = LEGACY_ROOT.resolve(
            attachment.getUrl().substring(1));
        Path imageSource = LEGACY_ROOT.resolve(
            "profile-pictures/user-" + user.getId() + "-1700000000000-legacy.png");
        Files.write(attachmentSource, attachmentBytes);
        Files.write(imageSource, imageBytes);
        WorkspaceObjectStorageQuota before = quotaMapper.findQuota(workspace.getId());
        long beforeBytes = before == null ? 0 : before.usedBytes();
        int beforeObjects = before == null ? 0 : before.objectCount();

        LegacyUploadRecord attachmentRecord = record(
            attachment.getId(), workspace.getId(), attachment.getUrl());
        attachmentRecord.setEntityType(attachment.getEntityType());
        attachmentRecord.setEntityId(attachment.getEntityId());
        fileReader.validateOwnership(attachmentRecord, "/attachments/");
        ResolvedLegacyUpload resolvedAttachment = fileReader.read(
            attachment.getUrl(), "/attachments/");
        migration.migrateAttachment(attachmentRecord, resolvedAttachment);

        Attachment storedAttachment = attachmentMapper.getById(
            workspace.getId(), attachment.getId());
        try (ManagedContent content = managedObjectService.openAttachment(
                workspace.getId(), storedAttachment)) {
            assertArrayEquals(attachmentBytes, content.inputStream().readAllBytes());
        }
        WorkspaceObjectStorageQuota after = quotaMapper.findQuota(workspace.getId());
        assertNotNull(after);
        assertEquals(beforeBytes + attachmentBytes.length, after.usedBytes());
        assertEquals(beforeObjects + 1, after.objectCount());

        LegacyUploadRecord userRecord = record(
            user.getId(), null, "/profile-pictures/user-" + user.getId()
                + "-1700000000000-legacy.png");
        assertEquals(1, userMapper.updateProfilePictureUrlIfCurrent(
            user.getId(), null, userRecord.getUrl()));
        migration.migrateUserImage(
            userRecord,
            fileReader.read(userRecord.getUrl(), "/profile-pictures/"));
        User storedUser = userMapper.getUserById(user.getId());
        String userToken = storedUser.getProfilePictureUrl().substring(
            storedUser.getProfilePictureUrl().lastIndexOf('/') + 1);
        try (ManagedContent content = managedObjectService.openUserImage(
                user.getId(), storedUser.getProfilePictureUrl(), userToken)) {
            byte[] storedImage = content.inputStream().readAllBytes();
            assertFalse(java.util.Arrays.equals(imageBytes, storedImage));
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(storedImage));
            assertEquals(8, decoded.getWidth());
            assertEquals(8, decoded.getHeight());
        }

        assertTrue(Files.exists(attachmentSource));
        assertTrue(Files.exists(imageSource));
    }

    private User user(int workspaceId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("legacy_" + suffix);
        user.setDisplayName("Legacy migration user");
        user.setEmail(suffix + "@example.test");
        user.setPasswordHash("hash");
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspaceId, user.getId(), "member");
        return user;
    }

    private Attachment attachment(int workspaceId, User user) {
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType("person");
        attachment.setEntityId(1);
        attachment.setFileName("legacy.txt");
        attachment.setUrl("/attachments/person/person-1-1700000000000-legacy.txt");
        attachment.setContentType("text/plain");
        attachment.setSize(1L);
        attachment.setUploadedBy(user);
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private static LegacyUploadRecord record(int id, Integer workspaceId, String url) {
        LegacyUploadRecord record = new LegacyUploadRecord();
        record.setId(id);
        record.setWorkspaceId(workspaceId);
        record.setFileName("legacy.txt");
        record.setContentType("text/plain");
        record.setUrl(url);
        return record;
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("connex-legacy-migration-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
