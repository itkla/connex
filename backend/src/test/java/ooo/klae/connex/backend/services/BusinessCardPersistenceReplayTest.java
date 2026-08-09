package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.businesscard.BusinessCardAiExtractionService;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.businesscard.BusinessCardBinaryStore;
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
import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;

@Transactional(isolation = Isolation.READ_COMMITTED)
class BusinessCardPersistenceReplayTest extends AbstractServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    @Autowired private CompanyService companyService;
    @Autowired private PersonService personService;
    @Autowired private AttachmentService attachmentService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AuthService authService;
    @Autowired private BusinessCardImportRequestMapper importRequestMapper;
    @Autowired private DuplicateDecisionLockService duplicateDecisionLockService;
    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private ScoringService scoringService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistedBusinessCardThreeCycleReplayKeepsCanonicalPersonAndProvenance() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        BusinessCardImageValidator imageValidator = mock(BusinessCardImageValidator.class);
        BusinessCardOcrClient ocrClient = mock(BusinessCardOcrClient.class);
        BusinessCardExtractor extractor = mock(BusinessCardExtractor.class);
        BusinessCardBinaryStore binaryStore = mock(BusinessCardBinaryStore.class);
        CapabilityEntitlement capabilityEntitlement = mock(CapabilityEntitlement.class);
        BusinessCardRateLimiter rateLimiter = mock(BusinessCardRateLimiter.class);
        BusinessCardProperties properties = new BusinessCardProperties();
        properties.setEnabled(true);
        BusinessCardService service = new BusinessCardService(
            properties,
            imageValidator,
            ocrClient,
            extractor,
            mock(BusinessCardAiExtractionService.class),
            binaryStore,
            companyService,
            personService,
            attachmentService,
            workspaceService,
            authService,
            importRequestMapper,
            rateLimiter,
            capabilityEntitlement,
            duplicateDecisionLockService,
            clock);
        byte[] content = {1, 2, 3};
        MockMultipartFile image = new MockMultipartFile(
            "image", "card.jpg", "image/jpeg", content);
        ValidatedBusinessCardImage validated = new ValidatedBusinessCardImage(
            content, "image/jpeg", "jpg", 120, 70);
        AtomicInteger storedCards = new AtomicInteger();
        when(imageValidator.validate(image)).thenReturn(validated);
        when(ocrClient.isReady()).thenReturn(true);
        when(ocrClient.isReadyForScan()).thenReturn(true);
        when(binaryStore.isReady()).thenReturn(true);
        when(binaryStore.store(anyInt(), anyString(), anyString(), any(byte[].class)))
            .thenAnswer(invocation -> new BusinessCardBinaryStore.StoredBusinessCard(
                "/api/attachments/content/replay-" + storedCards.incrementAndGet(),
                content.length));
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
        when(ocrClient.recognize(validated)).thenReturn(ocrLines);
        when(extractor.extract(ocrLines)).thenReturn(scanDraft);
        BusinessCardScanResponse firstScan = service.scan(image);
        PersonDuplicatePreflightRequest duplicateRequest = duplicateRequest(firstScan);
        DuplicatePreflightResponse initialReview =
            duplicatePreflightService.preflightPerson(duplicateRequest);
        BusinessCardContactRequest contact = contact(
            firstScan, initialReview.reviewToken());
        String firstKey = reserveImport();

        BusinessCardImportResponse first = service.importCard(
            image,
            contact,
            new BusinessCardPersonAction.Create(),
            new BusinessCardCompanyAction.None(),
            firstKey);
        int personId = first.contact().getId();
        Tag tag = newTag();
        personService.addTag(personId, tag.getId());
        Person person = personMapper.getPersonById(workspace.getId(), personId);
        newActivity(currentUser, person, null);
        newTask(currentUser, person, null);
        newNotification(workspace.getId(), currentUser.getId());
        OcrReplayState afterFirst = replayState(personId, firstKey);

        BusinessCardScanResponse secondScan = service.scan(image);
        BusinessCardImportResponse second = service.importCard(
            image,
            contact,
            new BusinessCardPersonAction.Create(),
            new BusinessCardCompanyAction.None(),
            firstKey);
        OcrReplayState afterSecond = replayState(personId, firstKey);

        BusinessCardScanResponse thirdScan = service.scan(image);
        BusinessCardImportResponse third = service.importCard(
            image,
            contact,
            new BusinessCardPersonAction.Create(),
            new BusinessCardCompanyAction.None(),
            firstKey);

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
        assertEquals(afterFirst, replayState(personId, firstKey));
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
                "business-card:" + firstKey));
        assertEquals(
            "1,1,1,1",
            jdbcTemplate.queryForObject(
                "SELECT CONCAT(COUNT(*), ',', COUNT(DISTINCT person_id), ',', "
                    + "COUNT(DISTINCT attachment_id), ',', SUM(completed_at IS NOT NULL)) "
                    + "FROM business_card_import_request WHERE workspace_id = ? "
                    + "AND idempotency_key = ?",
                String.class,
                workspace.getId(),
                firstKey));
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
        verify(ocrClient, times(3)).recognize(validated);
        verify(extractor, times(3)).extract(ocrLines);
        verify(binaryStore).store(
            anyInt(), anyString(), anyString(), any(byte[].class));
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

    private String reserveImport() {
        String idempotencyKey = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertEquals(
            1,
            importRequestMapper.reserve(
                workspace.getId(),
                currentUser.getId(),
                idempotencyKey,
                1,
                now.plusMinutes(2),
                now.plusDays(1)));
        return idempotencyKey;
    }

    private OcrReplayState replayState(int personId, String idempotencyKey) {
        return new OcrReplayState(
            replayArtifacts(personId),
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
        List<Map<String, Object>> identities,
        List<Map<String, Object>> importRequests,
        List<Map<String, Object>> attachments
    ) {}
}
