package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.businesscard.BusinessCardAiExtractionService;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.businesscard.BusinessCardExtractor;
import ooo.klae.connex.backend.businesscard.BusinessCardImageValidator;
import ooo.klae.connex.backend.businesscard.BusinessCardOcrClient;
import ooo.klae.connex.backend.businesscard.BusinessCardProperties;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.businesscard.OcrLine;
import ooo.klae.connex.backend.businesscard.ValidatedBusinessCardImage;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportDisposition;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardPersonAction;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.CompanyCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.ExtractionOrigin;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.FieldCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.Fields;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusinessCardPersistenceReplayTest extends AbstractServiceTest {
    private static final Path OBJECT_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"), "connex-test-object-storage")
        .toAbsolutePath()
        .normalize();

    @Autowired private BusinessCardService service;
    @Autowired private PersonService personService;
    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private ScoringService scoringService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BusinessCardProperties properties;
    @Autowired private BusinessCardImageValidator imageValidator;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private ManagedObjectService managedObjectService;
    @MockitoBean private BusinessCardOcrClient ocrClient;
    @MockitoBean private BusinessCardExtractor extractor;
    @MockitoBean private BusinessCardAiExtractionService aiExtractionService;
    @MockitoBean private CapabilityEntitlement capabilityEntitlement;
    @MockitoBean private BusinessCardRateLimiter rateLimiter;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private boolean scanningWasEnabled;
    private String idempotencyKey;
    private Integer persistedPersonId;
    private Integer persistedTagId;
    private String persistedAttachmentUrl;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.object-storage.filesystem-root", OBJECT_ROOT::toString);
    }

    @AfterAll
    static void removeTemporaryStorage() throws IOException {
        if (!Files.exists(OBJECT_ROOT)) {
            return;
        }
        try (var paths = Files.walk(OBJECT_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @BeforeEach
    void enableScanning() {
        scanningWasEnabled = properties.isEnabled();
        properties.setEnabled(true);
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        properties.setEnabled(scanningWasEnabled);
        if (workspace != null && persistedAttachmentUrl != null) {
            managedObjectService.deleteAttachmentAfterCommit(
                workspace.getId(), persistedAttachmentUrl);
        }
        if (workspace != null && idempotencyKey != null) {
            jdbcTemplate.update(
                "DELETE FROM business_card_import_request "
                    + "WHERE workspace_id = ? AND idempotency_key = ?",
                workspace.getId(),
                idempotencyKey);
        }
        if (workspace != null && persistedPersonId != null) {
            jdbcTemplate.update(
                "DELETE FROM activity WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                persistedPersonId);
            jdbcTemplate.update(
                "DELETE FROM task WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                persistedPersonId);
            jdbcTemplate.update(
                "DELETE FROM attachment WHERE workspace_id = ? "
                    + "AND entity_type = 'person' AND entity_id = ?",
                workspace.getId(),
                persistedPersonId);
            jdbcTemplate.update(
                "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedPersonId);
        }
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM notification WHERE workspace_id = ? AND recipient_id = ?",
                workspace.getId(),
                currentUser.getId());
        }
        if (workspace != null && persistedTagId != null) {
            jdbcTemplate.update(
                "DELETE FROM tag WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedTagId);
        }
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(),
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
    }

    @Test
    void persistedBusinessCardThreeCycleReplayKeepsCanonicalPersonAndProvenance() throws Exception {
        byte[] content = png();
        MockMultipartFile image = new MockMultipartFile(
            "image", "card.png", "image/png", content);
        ValidatedBusinessCardImage validated = imageValidator.validate(image);
        assertFalse(java.util.Arrays.equals(content, validated.content()));
        when(ocrClient.isReady()).thenReturn(true);
        when(ocrClient.isReadyForScan()).thenReturn(true);
        when(capabilityEntitlement.isEntitled(any())).thenReturn(true);

        String name = "Persisted OCR replay";
        String email = "persisted-ocr-" + unique() + "@example.test";
        String phone = "+81 90 8765 4321";
        List<OcrLine> ocrLines = List.of(
            new OcrLine(name, 0.99, 0, 0, 100, 20),
            new OcrLine(email, 0.99, 0, 21, 100, 40),
            new OcrLine(phone, 0.99, 0, 41, 100, 60));
        BusinessCardScanResponse scanDraft = new BusinessCardScanResponse(
            new Fields(
                new FieldCandidate(name, 0.99, ExtractionOrigin.OCR),
                new FieldCandidate(email, 0.99, ExtractionOrigin.OCR),
                new FieldCandidate(phone, 0.99, ExtractionOrigin.OCR),
                new FieldCandidate("Engineer", 0.99, ExtractionOrigin.OCR)),
            new CompanyCandidate(null, null, null, null),
            List.of());
        when(ocrClient.recognize(any(ValidatedBusinessCardImage.class))).thenReturn(ocrLines);
        when(extractor.extract(ocrLines)).thenReturn(scanDraft);
        BusinessCardScanResponse firstScan = service.scan(image);
        PersonDuplicatePreflightRequest duplicateRequest = duplicateRequest(firstScan);
        DuplicatePreflightResponse initialReview =
            duplicatePreflightService.preflightPerson(duplicateRequest);
        BusinessCardContactRequest contact = contact(
            firstScan, initialReview.reviewToken());
        idempotencyKey = UUID.randomUUID().toString();
        service.reserveImport(idempotencyKey);

        BusinessCardImportResponse first = service.importCard(
            image,
            contact,
            new BusinessCardPersonAction.Create(),
            new BusinessCardCompanyAction.None(),
            idempotencyKey);
        persistedAttachmentUrl = first.attachment().getUrl();
        Attachment storedCard = attachmentMapper.getById(
            workspace.getId(), first.attachment().getId());
        try (ManagedContent storedContent = managedObjectService.openAttachment(
                workspace.getId(), storedCard)) {
            assertEquals(validated.content().length, storedContent.contentLength());
            assertArrayEquals(validated.content(), storedContent.inputStream().readAllBytes());
        }
        int personId = first.contact().getId();
        persistedPersonId = personId;
        Tag tag = newTag();
        persistedTagId = tag.getId();
        personService.addTag(personId, tag.getId());
        Person person = personMapper.getPersonById(workspace.getId(), personId);
        newActivity(currentUser, person, null);
        newTask(currentUser, person, null);
        newNotification(workspace.getId(), currentUser.getId());
        OcrReplayState afterFirst = replayState(personId, idempotencyKey);

        BusinessCardScanResponse secondScan = service.scan(image);
        BusinessCardImportResponse second = service.importCard(
            image,
            contact,
            new BusinessCardPersonAction.Create(),
            new BusinessCardCompanyAction.None(),
            idempotencyKey);
        OcrReplayState afterSecond = replayState(personId, idempotencyKey);

        BusinessCardScanResponse thirdScan = service.scan(image);
        BusinessCardImportResponse third = service.importCard(
            image,
            contact,
            new BusinessCardPersonAction.Create(),
            new BusinessCardCompanyAction.None(),
            idempotencyKey);

        assertEquals(BusinessCardImportDisposition.CREATED, first.disposition());
        assertEquals(BusinessCardImportDisposition.CREATED, second.disposition());
        assertEquals(BusinessCardImportDisposition.CREATED, third.disposition());
        assertEquals(firstScan, secondScan);
        assertEquals(firstScan, thirdScan);
        assertEquals(personId, second.contact().getId());
        assertEquals(personId, third.contact().getId());
        assertEquals(1, afterFirst.artifacts().activities());
        assertEquals(1, afterFirst.artifacts().tasks());
        assertTrue(afterFirst.artifacts().tags() >= 2);
        assertTrue(afterFirst.artifacts().notifications() >= 1);
        assertEquals(2, afterFirst.artifacts().relationshipEvidenceEvents());
        assertEquals(afterFirst, afterSecond);
        assertEquals(afterFirst, replayState(personId, idempotencyKey));
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND email = ?",
                workspace.getId(),
                email));
        assertEquals(
            2,
            rowCount(
                "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ? AND person_id = ? "
                    + "AND source_system = 'business_card' AND source_row_ref = ? "
                    + "AND acquired_at IS NOT NULL AND superseded_at IS NULL",
                workspace.getId(),
                personId,
                "business-card:" + idempotencyKey));
        assertEquals(
            "1,1,1,1",
            jdbcTemplate.queryForObject(
                "SELECT CONCAT(COUNT(*), ',', COUNT(DISTINCT person_id), ',', "
                    + "COUNT(DISTINCT attachment_id), ',', SUM(completed_at IS NOT NULL)) "
                    + "FROM business_card_import_request WHERE workspace_id = ? "
                    + "AND idempotency_key = ?",
                String.class,
                workspace.getId(),
                idempotencyKey));
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(*) FROM attachment WHERE workspace_id = ? AND entity_type = 'person' "
                    + "AND entity_id = ? AND file_name = 'business-card.jpg'",
                workspace.getId(),
                personId));
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(DISTINCT url) FROM attachment WHERE workspace_id = ? "
                    + "AND entity_type = 'person' AND entity_id = ?",
                workspace.getId(),
                personId));
        assertNotNull(first.attachment());
        assertNotNull(second.attachment());
        assertNotNull(third.attachment());
        verify(ocrClient, times(3)).recognize(any(ValidatedBusinessCardImage.class));
        verify(extractor, times(3)).extract(ocrLines);
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(120, 70, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("Connex business card", 8, 24);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(8, 36, 96, 18);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("connex-business-card-storage-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static PersonDuplicatePreflightRequest duplicateRequest(
            BusinessCardScanResponse scan) {
        return new PersonDuplicatePreflightRequest(
            scan.fields().name().value(),
            List.of(scan.fields().email().value()),
            List.of(scan.fields().phone().value()));
    }

    private static BusinessCardContactRequest contact(
            BusinessCardScanResponse scan,
            String reviewToken) {
        return new BusinessCardContactRequest(
            scan.fields().name().value(),
            scan.fields().email().value(),
            scan.fields().phone().value(),
            scan.fields().title().value(),
            null,
            reviewToken);
    }

    private OcrReplayState replayState(int personId, String idempotencyKey) {
        return new OcrReplayState(
            replayArtifacts(personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, owner_id, name, email, phone, company_id, title, "
                    + "image_url, created_at, updated_at FROM person "
                    + "WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, type, subject, notes, person_id, deal_id, "
                    + "created_by_id, timestamp FROM activity "
                    + "WHERE workspace_id = ? AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, description, completed, status, position, due_date, "
                    + "assigned_to_id, person_id, deal_id, created_at, updated_at FROM task "
                    + "WHERE workspace_id = ? AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT t.id, t.workspace_id, t.name, t.color, pt.person_id "
                    + "FROM tag t JOIN person_tag pt ON pt.tag_id = t.id "
                    + "WHERE t.workspace_id = ? AND pt.person_id = ? ORDER BY t.id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, person_id, company_id, company_name, title, "
                    + "started_at, ended_at, created_at FROM person_employment "
                    + "WHERE workspace_id = ? AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT id, workspace_id, recipient_id, type, category, severity, "
                    + "template_version, title, body, actor_id, actor_label, source_type, "
                    + "source_id, source_label, context_type, context_id, context_label, "
                    + "action_url, data, dedupe_key, triggered_at, read_at, dismissed_at, "
                    + "resolved_at, created_at, updated_at FROM notification "
                    + "WHERE workspace_id = ? AND recipient_id = ? ORDER BY id",
                workspace.getId(),
                currentUser.getId()),
            jdbcTemplate.queryForList(
                "SELECT id, kind, `value`, normalized_value, source_system, source_channel, "
                    + "source_row_ref, acquired_at, superseded_at FROM person_identity "
                    + "WHERE workspace_id = ? AND person_id = ? ORDER BY id",
                workspace.getId(),
                personId),
            jdbcTemplate.queryForList(
                "SELECT idempotency_key, HEX(request_fingerprint) AS request_fingerprint, "
                    + "person_id, attachment_id, company_id, created_at, completed_at, expires_at, "
                    + "created_by_user_id, submission_expires_at, reservation_slot "
                    + "FROM business_card_import_request WHERE workspace_id = ? "
                    + "AND idempotency_key = ?",
                workspace.getId(),
                idempotencyKey),
            jdbcTemplate.queryForList(
                "SELECT id, entity_type, entity_id, file_name, url, content_type, size, "
                    + "uploaded_by_id, created_at, updated_at FROM attachment "
                    + "WHERE workspace_id = ? AND entity_type = 'person' AND entity_id = ? "
                    + "ORDER BY id",
                workspace.getId(),
                personId));
    }

    private ReplayArtifacts replayArtifacts(int personId) {
        int records = rowCount(
                "SELECT COUNT(*) FROM person WHERE workspace_id = ?", workspace.getId())
            + rowCount(
                "SELECT COUNT(*) FROM company WHERE workspace_id = ?", workspace.getId())
            + rowCount(
                "SELECT COUNT(*) FROM deal WHERE workspace_id = ?", workspace.getId());
        int tags = rowCount(
                "SELECT COUNT(*) FROM tag WHERE workspace_id = ?", workspace.getId())
            + rowCount("SELECT COUNT(*) FROM person_tag WHERE person_id = ?", personId);
        int relationships = rowCount(
                "SELECT COUNT(*) FROM person_employment WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId)
            + rowCount(
                "SELECT COUNT(*) FROM deal_person dp JOIN deal d ON d.id = dp.deal_id "
                    + "WHERE d.workspace_id = ? AND dp.person_id = ?",
                workspace.getId(),
                personId);
        return new ReplayArtifacts(
            records,
            rowCount(
                "SELECT COUNT(*) FROM activity WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            rowCount(
                "SELECT COUNT(*) FROM task WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            tags,
            relationships,
            rowCount(
                "SELECT COUNT(*) FROM notification WHERE workspace_id = ?",
                workspace.getId()),
            scoringService.contactEvidence(
                workspace.getId(), personId, currentUser.getId())
                .totals()
                .contributorCount());
    }

    private int rowCount(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private record ReplayArtifacts(
        int records,
        int activities,
        int tasks,
        int tags,
        int relationships,
        int notifications,
        int relationshipEvidenceEvents
    ) {}

    private record OcrReplayState(
        ReplayArtifacts artifacts,
        List<Map<String, Object>> people,
        List<Map<String, Object>> activities,
        List<Map<String, Object>> tasks,
        List<Map<String, Object>> tags,
        List<Map<String, Object>> employmentRelationships,
        List<Map<String, Object>> notifications,
        List<Map<String, Object>> identities,
        List<Map<String, Object>> importRequests,
        List<Map<String, Object>> attachments
    ) {}
}
