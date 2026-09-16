package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.ObjectStorageQuotaMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.storage.AttachmentScanWorker;
import ooo.klae.connex.backend.storage.ObjectStorage;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;
import ooo.klae.connex.backend.storage.malware.MalwareScannerClient;
import ooo.klae.connex.backend.storage.malware.MalwareScanVerdict;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises upload admission, revocation races and queue poisoning through the real HTTP and MySQL boundaries.
 *
 * <p>The cross-workspace sharing fixture enrolls a passkey before exercising {@code SHARE_MANAGE}
 * so the real unshare request satisfies privileged-MFA enforcement.
 */
@SpringBootTest(properties = {
    "connex.malware-scanning.enabled=true",
    "connex.malware-scan.sweep-delay-ms=3600000"
})
class AttachmentUploadSecurityIntegrationTest {
    private static final String PASSWORD = "Attachment-Upload-Pw1!";
    private static final long RACE_SECONDS = 120;
    private static final long RACE_MILLIS = RACE_SECONDS * 1000;
    private static final long BLOCKED_MILLIS = 250;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter securityFilter;
    @Autowired private OrganizationMapper organizations;
    @MockitoSpyBean private WorkspaceMapper workspaces;
    @MockitoSpyBean private RoleMapper roles;
    @MockitoSpyBean private UserMapper users;
    @Autowired private CompanyMapper companies;
    @Autowired private PersonMapper people;
    @Autowired private ShareMapper shares;
    @MockitoSpyBean private NoteMapper notes;
    @MockitoSpyBean private ObjectStorageQuotaMapper quotas;
    @Autowired private AttachmentMapper attachments;
    @Autowired private AttachmentScanMapper scans;
    @Autowired private AttachmentScanWorker worker;
    @Autowired private TenantWorkScope workScope;
    @Autowired private TenantContext tenantContext;
    @Autowired private PasswordEncoder passwords;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessions;
    @MockitoBean private MalwareScannerClient scanner;
    @MockitoSpyBean private ObjectStorage storage;

    private MockMvc mvc;
    private MockHttpSession session;
    private Organization organization;
    private Workspace workspace;
    private Workspace sourceWorkspace;
    private User actor;
    private User target;
    private Company company;
    private WorkspaceRole role;
    private String storedKey;

    @BeforeEach
    void setUp() throws Exception {
        clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilter).build();
        organization = new Organization();
        organization.setName("Upload security " + unique());
        organization.setSlug("upload-security-" + unique());
        organizations.insert(organization);
        workspace = new Workspace();
        workspace.setName("Upload security " + unique());
        workspace.setSlug("upload-security-" + unique());
        workspace.setOrgId(organization.getId());
        workspaces.insert(workspace);
        actor = member();
        target = member();
        role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName("Upload only " + unique());
        roles.insertRole(role);
        roles.insertPermissions(workspace.getId(), role.getId(), List.of(Permission.ATTACHMENT_CREATE.name()));
        workspaces.setMemberCustomRole(workspace.getId(), actor.getId(), role.getId());
        company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName("Upload target " + unique());
        companies.insert(company);
        when(scanner.scan(any(byte[].class))).thenReturn(cleanReport());
        MvcResult login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", actor.getUsername(), "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        assertTrue(login.getRequest().getSession(false) instanceof MockHttpSession);
        session = (MockHttpSession) login.getRequest().getSession(false);
        clearContext();
    }

    @AfterEach
    void tearDown() {
        clearContext();
        if (storedKey != null) {
            storage.delete(storedKey);
        }
        for (Workspace fixture : new Workspace[] {workspace, sourceWorkspace}) {
            if (fixture != null) {
                for (String table : List.of("object_deletion_queue", "managed_object_usage", "object_storage_quota",
                        "attachment", "note", "person", "company", "workspace_member", "workspace_role")) {
                    jdbc.update("DELETE FROM " + table + " WHERE workspace_id = ?", fixture.getId());
                }
                jdbc.update("DELETE FROM workspace WHERE id = ?", fixture.getId());
            }
        }
        for (User user : new User[] {actor, target}) {
            if (user != null) {
                jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", user.getUsername());
                jdbc.update("DELETE FROM app_user WHERE id = ?", user.getId());
            }
        }
        if (organization != null) {
            jdbc.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void caseVariantJsonReferenceCannotPoisonSingleObjectSweepsUnderCaseInsensitiveCollation() throws Exception {
        String collation = jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attachment' AND COLUMN_NAME = 'url'", String.class);
        assertNotNull(collation);
        assertTrue(collation.endsWith("_ci"), "Regression fixture must exercise a case-insensitive URL column");
        String poisonUrl = "/API/attachments/content/" + UUID.randomUUID() + ".txt";
        assertEquals(400, postReference(poisonUrl).getResponse().getStatus());
        Attachment poison = pendingReference(poisonUrl);
        clearContext();
        Attachment valid = new Attachment();
        valid.setWorkspaceId(workspace.getId());
        valid.setEntityType("company");
        valid.setEntityId(company.getId());
        valid.setFileName("valid.txt");
        String token = UUID.randomUUID() + ".txt";
        valid.setUrl("/api/attachments/content/" + token);
        valid.setContentType("text/plain");
        byte[] content = "valid queued upload".getBytes(StandardCharsets.UTF_8);
        valid.setSize((long) content.length);
        attachments.insert(valid);
        storedKey = "workspaces/" + workspace.getId() + "/attachments/" + token;
        storage.put(storedKey, UploadSource.from("valid.txt", "text/plain", content), "text/plain",
            MessageDigest.getInstance("SHA-256").digest(content));

        workScope.inWorkspace(workspace.getId(), () -> {
            for (int i = 0; i < 3; i++) {
                worker.sweepWorkspace(workspace.getId(), actor.getId(), 1);
            }
        });

        assertEquals("clean", scans.getById(workspace.getId(), valid.getId()).getScanState());
        assertEquals(1, scans.getById(workspace.getId(), valid.getId()).getScanAttempts());
        assertEquals("pending", scans.getById(workspace.getId(), poison.getId()).getScanState());
        assertTrue(scans.findDue(workspace.getId(), 1).isEmpty());
        verify(scanner, times(1)).scan(any(byte[].class));
    }

    @Test
    void legacyUppercaseExternalUrlStillRejectsLowercaseSubmissionInAnotherWorkspace() throws Exception {
        sourceWorkspace = newSourceWorkspace();
        String path = "/" + unique() + "/file.pdf";
        Attachment legacy = new Attachment();
        legacy.setWorkspaceId(sourceWorkspace.getId());
        legacy.setEntityType("user");
        legacy.setEntityId(target.getId());
        legacy.setFileName("legacy.pdf");
        legacy.setUrl("HTTPS://EXAMPLE.COM" + path);
        legacy.setContentType("application/pdf");
        legacy.setSize(1L);
        attachments.insert(legacy);

        assertEquals(400, postReference("https://example.com" + path).getResponse().getStatus());

        assertEquals("HTTPS://EXAMPLE.COM" + path,
            attachments.getMetadataById(sourceWorkspace.getId(), legacy.getId()).getUrl());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
    }

    @Test
    void caseVariantOfCleanObjectIsRejectedAsDuplicateAndOriginalRemainsDownloadable() throws Exception {
        String url = uploadCompanyImage();
        String alias = "/API/attachments/content/" + url.substring(url.lastIndexOf('/') + 1);

        assertEquals(409, postReference(alias).getResponse().getStatus());
        assertEquals(400, postReference(alias.substring(0, alias.lastIndexOf('/') + 1)
            + alias.substring(alias.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT)).getResponse().getStatus());
        assertEquals(1, attachments.countUrl(workspace.getId(), url));
        assertEquals(0, attachments.countUrl(workspace.getId(), alias));
        assertDownload(url);
        sweepThreeTimes();
        assertDownload(url);
        assertTrue(scans.findDue(workspace.getId(), 1).isEmpty());
        verify(scanner, times(1)).scan(any(byte[].class));
    }

    @Test
    void pendingCaseAliasesCannotShadowCleanObjectAcrossSweepsOrScanTransitions() throws Exception {
        String url = uploadCompanyImage();
        Attachment clean = attachments.getMetadataByUrl(workspace.getId(), url);
        assertNotNull(clean);
        Attachment routeAlias = pendingReference(
            "/API/attachments/content/" + url.substring(url.lastIndexOf('/') + 1));
        Attachment tokenAlias = pendingReference(
            "/api/attachments/content/" + url.substring(url.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT));

        assertDownload(url);
        assertEquals(List.of(tokenAlias.getId()), scans.findDue(workspace.getId(), 1));
        sweepThreeTimes();
        assertDownload(url);
        assertEquals("clean", scans.getById(workspace.getId(), clean.getId()).getScanState());
        assertEquals(1, scans.getById(workspace.getId(), clean.getId()).getScanAttempts());
        assertEquals("pending", scans.getById(workspace.getId(), routeAlias.getId()).getScanState());
        assertEquals("unscannable", scans.getById(workspace.getId(), tokenAlias.getId()).getScanState());
        assertTrue(scans.findDue(workspace.getId(), 1).isEmpty());
        assertEquals(1, attachments.countUrl(workspace.getId(), url));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            assertEquals(List.of(clean.getId()), attachments.lockIdsByUrl(workspace.getId(), url)));

        scans.quarantine(workspace.getId(), tokenAlias.getId());
        assertDownload(url);
        scans.enqueue(workspace.getId(), clean.getId());
        assertEquals(List.of(clean.getId()), scans.findDue(workspace.getId(), 1));
        sweepThreeTimes();
        assertDownload(url);
        assertEquals("quarantined", scans.getById(workspace.getId(), tokenAlias.getId()).getScanState());
        assertEquals("pending", scans.getById(workspace.getId(), routeAlias.getId()).getScanState());
        verify(scanner, times(2)).scan(any(byte[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"upload", "upload-image"})
    void authorizedUploadPersistsBothRoutes(String route) throws Exception {
        MvcResult result = upload(route, "company", company.getId());
        assertEquals(200, result.getResponse().getStatus());
        String url = json.readTree(result.getResponse().getContentAsString()).get("url").asString();
        storedKey = "workspaces/" + workspace.getId() + "/attachments/" + url.substring(url.lastIndexOf('/') + 1);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT object_count FROM object_storage_quota WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
        verify(scanner, times(1)).scan(any(byte[].class));
        verify(storage, times(1)).put(anyString(), any(UploadSource.class), anyString(), any(byte[].class));
    }

    @ParameterizedTest
    @CsvSource({"upload,membership", "upload-image,membership", "upload,permission", "upload-image,permission"})
    void revocationCommittedDuringScanDeniesBothUploadRoutes(String route, String revocation) throws Exception {
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        pauseScanner(scanning, releaseScan);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> upload(route, "company", company.getId()));
            try {
                assertTrue(scanning.await(RACE_SECONDS, TimeUnit.SECONDS));
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> revoke(revocation));
            } finally {
                releaseScan.countDown();
            }
            assertEquals(403, result.get(RACE_SECONDS, TimeUnit.SECONDS).getResponse().getStatus());
        }
        assertNoPersistence();
    }

    @ParameterizedTest
    @CsvSource({"upload,membership", "upload-image,membership", "upload,permission", "upload-image,permission"})
    void uploadContendsOnAuthorizationRowAndReadsTheCommittedRevocation(String route, String revocation)
            throws Exception {
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        CountDownLatch revocationLocked = new CountDownLatch(1);
        CountDownLatch commitRevocation = new CountDownLatch(1);
        CountDownLatch uploadLockAttempted = new CountDownLatch(1);
        pauseScanner(scanning, releaseScan);
        signalAuthorizationLock(revocation, uploadLockAttempted);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var result = executor.submit(() -> upload(route, "company", company.getId()));
            assertTrue(scanning.await(RACE_SECONDS, TimeUnit.SECONDS));
            var removal = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if ("permission".equals(revocation)) {
                    sqlSessions.getMapper(RoleMapper.class).lockRole(workspace.getId(), role.getId());
                }
                revoke(revocation);
                revocationLocked.countDown();
                await(commitRevocation);
            }));
            try {
                assertTrue(revocationLocked.await(RACE_SECONDS, TimeUnit.SECONDS));
                releaseScan.countDown();
                assertTrue(uploadLockAttempted.await(RACE_SECONDS, TimeUnit.SECONDS), "Upload must reach the contested locking statement");
                assertThrows(TimeoutException.class, () -> result.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
            } finally {
                releaseScan.countDown();
                commitRevocation.countDown();
            }
            removal.get(RACE_SECONDS, TimeUnit.SECONDS);
            assertEquals(403, result.get(RACE_SECONDS, TimeUnit.SECONDS).getResponse().getStatus());
        } finally {
            releaseScan.countDown();
            commitRevocation.countDown();
        }
        assertNoPersistence();
    }

    @ParameterizedTest
    @CsvSource({"upload,note", "upload-image,note", "upload,user", "upload-image,user"})
    void targetVisibilityRevokedDuringScanDeniesBothUploadRoutes(String route, String type) throws Exception {
        int targetId;
        if ("note".equals(type)) {
            Note note = new Note();
            note.setWorkspaceId(workspace.getId());
            note.setAuthor(target);
            note.setVisibility("workspace");
            note.setContent("Visible before scan");
            notes.insert(note);
            targetId = note.getId();
        } else {
            targetId = target.getId();
        }
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        pauseScanner(scanning, releaseScan);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> upload(route, type, targetId));
            try {
                assertTrue(scanning.await(RACE_SECONDS, TimeUnit.SECONDS));
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    if ("note".equals(type)) {
                        jdbc.update("UPDATE note SET visibility = 'private' WHERE workspace_id = ? AND id = ?",
                            workspace.getId(), targetId);
                    } else {
                        workspaces.removeMember(workspace.getId(), targetId);
                    }
                });
            } finally {
                releaseScan.countDown();
            }
            assertEquals(404, result.get(RACE_SECONDS, TimeUnit.SECONDS).getResponse().getStatus());
        }
        assertNoPersistence();
    }

    /**
     * Pauses the upload inside its admitted-authority snapshot, before the target's own rows are locked, so
     * an actual departure can complete. The pause is keyed on the actor-scoped account check: the departure
     * path itself reads the departing member's account rows, including through audit-integrity capture, so a
     * target-scoped pause would hold the departure inside the same fixture and the upload would sit in a
     * database lock wait for the whole offboarding sweep.
     */
    @ParameterizedTest
    @ValueSource(strings = {"upload", "upload-image"})
    void departureAfterPreliminaryValidationDeniesUploadAtAdmittedAuthority(String route) throws Exception {
        assertTrue(actor.getId() < target.getId(),
            "The paused account check must precede the target's own authorization locks");
        MockHttpSession departingSession = login(target);
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch releaseAdmittedAuthority = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            admitted.countDown();
            await(releaseAdmittedAuthority);
            return sqlSessions.getMapper(UserMapper.class).isAccountDeletionReservedForShare(actor.getId());
        }).when(users).isAccountDeletionReservedForShare(actor.getId());

        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var result = executor.submit(() -> upload(route, "user", target.getId()));
                assertTrue(admitted.await(RACE_SECONDS, TimeUnit.SECONDS));
                verify(workspaces).isMember(workspace.getId(), target.getId());
                MvcResult departure = mvc.perform(post("/api/workspaces/{id}/leave", workspace.getId())
                        .session(departingSession).with(csrf().asHeader())
                        .header("X-Workspace-Id", workspace.getId()))
                    .andReturn();
                clearContext();
                assertEquals(200, departure.getResponse().getStatus(), failureDetail(departure));
                assertFalse(workspaces.isMember(workspace.getId(), target.getId()));
                assertThrows(TimeoutException.class, () -> result.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
                releaseAdmittedAuthority.countDown();
                MvcResult denied = result.get(RACE_SECONDS, TimeUnit.SECONDS);
                assertEquals(403, denied.getResponse().getStatus(), failureDetail(denied));
            } finally {
                releaseAdmittedAuthority.countDown();
            }
        }
        assertFalse(workspaces.isMember(workspace.getId(), target.getId()));
        assertNoPersistence();
    }

    @ParameterizedTest
    @CsvSource({"upload,company", "upload-image,company", "upload,person", "upload-image,person"})
    void unshareDuringQuotaAdmissionDeniesUploadAndLeavesNoReadableObject(String route, String type)
            throws Exception {
        int recordId = sharedTarget(type);
        MockHttpSession sharingSession = login(target);
        sqlSessions.getMapper(ObjectStorageQuotaMapper.class).ensureQuota(workspace.getId());
        CountDownLatch quotaLocked = new CountDownLatch(1);
        CountDownLatch releaseQuota = new CountDownLatch(1);
        CountDownLatch admissionAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            admissionAttempted.countDown();
            return sqlSessions.getMapper(ObjectStorageQuotaMapper.class).ensureQuota(workspace.getId());
        }).when(quotas).ensureQuota(workspace.getId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var blocker = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        assertNotNull(sqlSessions.getMapper(ObjectStorageQuotaMapper.class).lockQuota(workspace.getId()));
                        quotaLocked.countDown();
                        await(releaseQuota);
                    }));
                assertTrue(quotaLocked.await(RACE_SECONDS, TimeUnit.SECONDS));
                var result = executor.submit(() -> upload(route, type, recordId));
                assertTrue(admissionAttempted.await(RACE_SECONDS, TimeUnit.SECONDS),
                    "Upload must reach quota contention after preliminary target validation");
                assertThrows(TimeoutException.class, () -> result.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
                MvcResult unshared = mvc.perform(
                        delete("/api/shares/{type}/{id}/{workspaceId}", type, recordId, workspace.getId())
                            .session(sharingSession).with(csrf().asHeader())
                            .header("X-Workspace-Id", sourceWorkspace.getId()))
                    .andReturn();
                clearContext();
                assertEquals(204, unshared.getResponse().getStatus(), failureDetail(unshared));
                assertFalse("company".equals(type)
                    ? shares.companyShareExists(recordId, sourceWorkspace.getId(), workspace.getId())
                    : shares.personShareExists(recordId, sourceWorkspace.getId(), workspace.getId()));
                releaseQuota.countDown();
                blocker.get(RACE_SECONDS, TimeUnit.SECONDS);
                assertEquals(403, result.get(RACE_SECONDS, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                releaseQuota.countDown();
            }
        }
        assertUnpublishedStorageRollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"upload", "upload-image"})
    void privacyCommittedWhileQuotaAdmissionWaitsDeniesUploadAndLeavesNoReadableObject(String route)
            throws Exception {
        Note note = visibleNote();
        sqlSessions.getMapper(ObjectStorageQuotaMapper.class).ensureQuota(workspace.getId());
        CountDownLatch quotaLocked = new CountDownLatch(1);
        CountDownLatch releaseQuota = new CountDownLatch(1);
        CountDownLatch admissionAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            admissionAttempted.countDown();
            return sqlSessions.getMapper(ObjectStorageQuotaMapper.class).ensureQuota(workspace.getId());
        }).when(quotas).ensureQuota(workspace.getId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var blocker = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        assertNotNull(sqlSessions.getMapper(ObjectStorageQuotaMapper.class).lockQuota(workspace.getId()));
                        quotaLocked.countDown();
                        await(releaseQuota);
                    }));
                assertTrue(quotaLocked.await(RACE_SECONDS, TimeUnit.SECONDS));
                var result = executor.submit(() -> upload(route, "note", note.getId()));
                assertTrue(admissionAttempted.await(RACE_SECONDS, TimeUnit.SECONDS),
                    "Upload must reach the INSERT IGNORE that contends on the locked quota row");
                verify(notes, times(2)).getVisibleNoteById(workspace.getId(), note.getId(), actor.getId());
                assertThrows(TimeoutException.class, () -> result.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    note.setVisibility("private");
                    assertEquals(1, notes.update(note));
                });
                releaseQuota.countDown();
                blocker.get(RACE_SECONDS, TimeUnit.SECONDS);
                assertEquals(403, result.get(RACE_SECONDS, TimeUnit.SECONDS).getResponse().getStatus());
            } finally {
                releaseQuota.countDown();
            }
        }

        assertUnpublishedStorageRollback();
    }

    @ParameterizedTest
    @ValueSource(strings = {"upload", "upload-image"})
    void privacyUpdateAfterFinalVisibilityLockWaitsForAttachmentCommit(String route) throws Exception {
        Note note = visibleNote();
        CountDownLatch noteLocked = new CountDownLatch(1);
        CountDownLatch releaseUpload = new CountDownLatch(1);
        CountDownLatch privacyWriteAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Note locked = sqlSessions.getMapper(NoteMapper.class)
                .getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), actor.getId());
            assertNotNull(locked);
            noteLocked.countDown();
            await(releaseUpload);
            return locked;
        }).when(notes).getVisibleNoteByIdForUpdate(workspace.getId(), note.getId(), actor.getId());
        doAnswer(invocation -> {
            privacyWriteAttempted.countDown();
            return sqlSessions.getMapper(NoteMapper.class).update(note);
        }).when(notes).update(eq(note));

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var result = executor.submit(() -> upload(route, "note", note.getId()));
                assertTrue(noteLocked.await(RACE_SECONDS, TimeUnit.SECONDS));
                var privacy = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        note.setVisibility("private");
                        assertEquals(1, notes.update(note));
                        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM attachment"
                            + " WHERE workspace_id = ? AND entity_type = 'note' AND entity_id = ?",
                            Integer.class, workspace.getId(), note.getId()),
                            "The privacy write may finish only after attachment metadata commits");
                    }));
                assertTrue(privacyWriteAttempted.await(RACE_SECONDS, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> privacy.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
                releaseUpload.countDown();
                MvcResult uploaded = result.get(RACE_SECONDS, TimeUnit.SECONDS);
                assertEquals(200, uploaded.getResponse().getStatus());
                String url = json.readTree(uploaded.getResponse().getContentAsString()).get("url").asString();
                storedKey = "workspaces/" + workspace.getId() + "/attachments/"
                    + url.substring(url.lastIndexOf('/') + 1);
                privacy.get(RACE_SECONDS, TimeUnit.SECONDS);
                mvc.perform(get(url).session(session).header("X-Workspace-Id", workspace.getId()))
                    .andExpect(status().isNotFound());
                clearContext();
            } finally {
                releaseUpload.countDown();
            }
        }
    }

    private void assertUnpublishedStorageRollback() throws Exception {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), any(UploadSource.class), anyString(), any(byte[].class));
        storedKey = key.getValue();
        String url = "/api/attachments/content/" + storedKey.substring(storedKey.lastIndexOf('/') + 1);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM managed_object_usage WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
        assertEquals(0L, jdbc.queryForObject("SELECT used_bytes + object_count FROM object_storage_quota"
            + " WHERE workspace_id = ?", Long.class, workspace.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM object_deletion_queue"
            + " WHERE workspace_id = ? AND object_key = ?", Integer.class, workspace.getId(), storedKey));
        assertFalse(scans.isReadable(workspace.getId(), url, false));
        mvc.perform(get(url).session(session).header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isNotFound());
        clearContext();
    }

    private Workspace newSourceWorkspace() {
        Workspace source = new Workspace();
        source.setName("Upload source " + unique());
        source.setSlug("upload-source-" + unique());
        source.setOrgId(organization.getId());
        workspaces.insert(source);
        workspaces.addMember(source.getId(), target.getId(), "member");
        return source;
    }

    private int sharedTarget(String type) {
        sourceWorkspace = newSourceWorkspace();
        WorkspaceRole sharingRole = new WorkspaceRole();
        sharingRole.setWorkspaceId(sourceWorkspace.getId());
        sharingRole.setName("Sharing only " + unique());
        roles.insertRole(sharingRole);
        roles.insertPermissions(sourceWorkspace.getId(), sharingRole.getId(), List.of(Permission.SHARE_MANAGE.name()));
        workspaces.setMemberCustomRole(sourceWorkspace.getId(), target.getId(), sharingRole.getId());
        PublicApiTestSecuritySupport.enrollPasskey(jdbc, target);
        if ("company".equals(type)) {
            Company shared = new Company();
            shared.setWorkspaceId(sourceWorkspace.getId());
            shared.setName("Shared upload target " + unique());
            companies.insert(shared);
            assertEquals(1, shares.shareCompany(
                shared.getId(), sourceWorkspace.getId(), workspace.getId(), target.getId(), false));
            return shared.getId();
        }
        Person shared = new Person();
        shared.setWorkspaceId(sourceWorkspace.getId());
        shared.setName("Shared upload target " + unique());
        people.insert(shared);
        assertEquals(1, shares.sharePerson(
            shared.getId(), sourceWorkspace.getId(), workspace.getId(), target.getId(), false));
        return shared.getId();
    }

    private MockHttpSession login(User user) throws Exception {
        try {
            MvcResult result = mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", user.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
            assertTrue(result.getRequest().getSession(false) instanceof MockHttpSession);
            return (MockHttpSession) result.getRequest().getSession(false);
        } finally {
            clearContext();
        }
    }

    private Note visibleNote() {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setAuthor(target);
        note.setVisibility("workspace");
        note.setContent("Visible before admission");
        notes.insert(note);
        return note;
    }

    private MvcResult postReference(String url) throws Exception {
        try {
            return mvc.perform(post("/api/attachments").session(session).with(csrf().asHeader())
                    .header("X-Workspace-Id", workspace.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("entityType", "company", "entityId", company.getId(),
                        "fileName", "queue.txt", "url", url, "contentType", "text/plain", "size", 1))))
                .andReturn();
        } finally {
            clearContext();
        }
    }

    private Attachment pendingReference(String url) {
        Attachment reference = new Attachment();
        reference.setWorkspaceId(workspace.getId());
        reference.setEntityType("company");
        reference.setEntityId(company.getId());
        reference.setFileName("alias.png");
        reference.setUrl(url);
        reference.setContentType("image/png");
        reference.setSize(1L);
        attachments.insert(reference);
        return reference;
    }

    private String uploadCompanyImage() throws Exception {
        String collation = jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attachment' AND COLUMN_NAME = 'url'", String.class);
        assertNotNull(collation);
        assertTrue(collation.endsWith("_ci"), "Regression requires a case-insensitive URL column");
        MvcResult result = upload("upload", "company", company.getId());
        assertEquals(200, result.getResponse().getStatus());
        String url = json.readTree(result.getResponse().getContentAsString()).get("url").asString();
        storedKey = "workspaces/" + workspace.getId() + "/attachments/" + url.substring(url.lastIndexOf('/') + 1);
        return url;
    }

    private void assertDownload(String url) throws Exception {
        try {
            MvcResult response = mvc.perform(get(url).session(session).header("X-Workspace-Id", workspace.getId()))
                .andReturn();
            assertEquals(200, response.getResponse().getStatus(), failureDetail(response));
            response.getAsyncResult(RACE_MILLIS);
            MvcResult completed = mvc.perform(asyncDispatch(response)).andReturn();
            assertEquals(200, completed.getResponse().getStatus(), failureDetail(completed));
            try (var stored = storage.get(storedKey)) {
                assertNotNull(stored);
                assertArrayEquals(stored.inputStream().readAllBytes(), completed.getResponse().getContentAsByteArray());
            }
        } finally {
            clearContext();
        }
    }

    private void sweepThreeTimes() {
        workScope.inWorkspace(workspace.getId(), () -> {
            for (int i = 0; i < 3; i++) {
                worker.sweepWorkspace(workspace.getId(), actor.getId(), 1);
            }
        });
    }

    private void signalAuthorizationLock(String revocation, CountDownLatch attempted) {
        if ("membership".equals(revocation)) {
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                attempted.countDown();
                return sqlSessions.getMapper(WorkspaceMapper.class)
                    .lockAuthorizationMembershipForShare(workspace.getId(), actor.getId());
            }).when(workspaces).lockAuthorizationMembershipForShare(workspace.getId(), actor.getId());
        } else {
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                attempted.countDown();
                return sqlSessions.getMapper(RoleMapper.class).lockRole(workspace.getId(), role.getId());
            }).when(roles).lockRole(workspace.getId(), role.getId());
        }
    }

    private void pauseScanner(CountDownLatch scanning, CountDownLatch release) {
        when(scanner.scan(any(byte[].class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            scanning.countDown();
            await(release);
            return cleanReport();
        });
    }

    private void revoke(String revocation) {
        if ("membership".equals(revocation)) {
            assertEquals(1, workspaces.removeMember(workspace.getId(), actor.getId()));
        } else {
            assertEquals(1, roles.clearPermissions(workspace.getId(), role.getId()));
        }
    }

    private MvcResult upload(String route, String type, int id) throws Exception {
        try {
            return mvc.perform(multipart("/api/attachments/" + route)
                    .file(new MockMultipartFile("file", "image.png", "image/png", imageBytes()))
                    .param("entityType", type).param("entityId", Integer.toString(id))
                    .session(session).with(csrf().asHeader()).header("X-Workspace-Id", workspace.getId()))
                .andReturn();
        } finally {
            clearContext();
        }
    }

    private static byte[] imageBytes() throws Exception {
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image));
        return image.toByteArray();
    }

    private void assertNoPersistence() {
        verify(scanner, times(1)).scan(any(byte[].class));
        verify(storage, never()).put(anyString(), any(UploadSource.class), anyString(), any(byte[].class));
        for (String table : List.of("attachment", "managed_object_usage", "object_deletion_queue")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
                Integer.class, workspace.getId()), table);
        }
        assertEquals(0L, jdbc.queryForObject("SELECT COALESCE(SUM(used_bytes + object_count), 0)"
            + " FROM object_storage_quota WHERE workspace_id = ?", Long.class, workspace.getId()));
    }

    private User member() {
        User user = new User();
        user.setUsername("upload_security_" + unique());
        user.setDisplayName("Upload security member");
        user.setEmail(unique() + "@example.com");
        user.setTimezone("UTC");
        user.setPasswordHash(passwords.encode(PASSWORD));
        users.insert(user);
        workspaces.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private static MalwareScanReport cleanReport() {
        return new MalwareScanReport(MalwareScanVerdict.CLEAN, null, null, "daily-upload-fixture", false,
            Instant.now().plusSeconds(3600));
    }

    /** Reports the refusing control behind an unexpected status so a rerun on this fixture is diagnosable. */
    private static String failureDetail(MvcResult result) {
        Throwable resolved = result.getResolvedException();
        return "status=" + result.getResponse().getStatus()
            + " exception=" + (resolved == null ? "none" : resolved.toString());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(RACE_SECONDS, TimeUnit.SECONDS), "Upload race fixture timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Upload race fixture interrupted", exception);
        }
    }

    private void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
