package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.businesscard.BusinessCardBinaryStore;
import ooo.klae.connex.backend.businesscard.BusinessCardExtractor;
import ooo.klae.connex.backend.businesscard.BusinessCardImageValidator;
import ooo.klae.connex.backend.businesscard.BusinessCardImportRecord;
import ooo.klae.connex.backend.businesscard.BusinessCardOcrClient;
import ooo.klae.connex.backend.businesscard.BusinessCardProperties;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.businesscard.OcrLine;
import ooo.klae.connex.backend.businesscard.ValidatedBusinessCardImage;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardImportReservationResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.CompanyCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.FieldCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.Fields;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.BusinessCardImportResultGoneException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;
import ooo.klae.connex.backend.services.CompanyService.NormalizedCompanyMatches;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class BusinessCardServiceTest {
    private static final String IDEMPOTENCY_KEY = String.join(
            "-", "02a25a23", "70af", "4f8e", "a64a", "6cfc5f8c69be");
    private static final LocalDateTime EXPIRY = LocalDateTime.parse("2026-07-16T00:00:00");

    @Mock private BusinessCardImageValidator imageValidator;
    @Mock private BusinessCardOcrClient ocrClient;
    @Mock private BusinessCardExtractor extractor;
    @Mock private BusinessCardBinaryStore binaryStore;
    @Mock private CompanyService companyService;
    @Mock private PersonService personService;
    @Mock private AttachmentService attachmentService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private BusinessCardImportRequestMapper importRequestMapper;
    @Mock private BusinessCardRateLimiter rateLimiter;
    @Mock private CapabilityEntitlement capabilityEntitlement;

    private BusinessCardProperties properties;
    private BusinessCardService service;
    private MockMultipartFile image;
    private ValidatedBusinessCardImage validated;
    private Clock clock;

    @BeforeEach
    void setUp() throws Exception {
        properties = new BusinessCardProperties();
        properties.setEnabled(true);
        clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
        service = new BusinessCardService(
                properties,
                imageValidator,
                ocrClient,
                extractor,
                binaryStore,
                companyService,
                personService,
                attachmentService,
                workspaceService,
                authService,
                importRequestMapper,
                rateLimiter,
                capabilityEntitlement,
                clock);
        image = new MockMultipartFile("image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        validated = new ValidatedBusinessCardImage(
                image.getBytes(), "image/jpeg", "jpg", 120, 70);
        lenient().when(binaryStore.isReady()).thenReturn(true);
        lenient().when(ocrClient.isReady()).thenReturn(true);
        User currentUser = new User();
        currentUser.setId(9);
        lenient().when(authService.getCurrentUser()).thenReturn(currentUser);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        lenient().when(capabilityEntitlement.isEntitled(any())).thenReturn(true);
        BusinessCardImportRecord reservation = activeReservation();
        lenient().when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(reservation);
        lenient().when(importRequestMapper.getForUpdate(5, IDEMPOTENCY_KEY)).thenReturn(reservation);
        lenient().when(importRequestMapper.bindReservation(
                eq(5), eq(9), eq(IDEMPOTENCY_KEY), any(), any(), any())).thenReturn(1);
        lenient().when(importRequestMapper.complete(anyInt(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(1);
    }

    @Test
    void scanAddsUniqueVisibleCompanyMatch() {
        BusinessCardScanResponse draft = draft("Analytical Labs");
        Company company = company(17, "Analytical Labs");
        List<OcrLine> lines = List.of(new OcrLine("Ada Lovelace", 0.99, 1, 1, 10, 10));
        when(imageValidator.validate(image)).thenReturn(validated);
        when(ocrClient.recognize(validated)).thenReturn(lines);
        when(extractor.extract(lines)).thenReturn(draft);
        when(companyService.findVisibleByNormalizedName("Analytical Labs"))
                .thenReturn(new NormalizedCompanyMatches(List.of(company), false));

        BusinessCardScanResponse response = service.scan(image);

        assertEquals(17, response.company().matchedCompanyId());
        assertTrue(response.warnings().isEmpty());
        verify(workspaceService).requirePermission(Permission.ATTACHMENT_CREATE);
        verify(rateLimiter).requireScanAllowed();
    }

    @Test
    void scanNeverAutoBindsAmbiguousCompanyMatch() {
        BusinessCardScanResponse draft = draft("Analytical Labs");
        when(imageValidator.validate(image)).thenReturn(validated);
        when(ocrClient.recognize(validated)).thenReturn(List.of());
        when(extractor.extract(List.of())).thenReturn(draft);
        when(companyService.findVisibleByNormalizedName("Analytical Labs"))
                .thenReturn(new NormalizedCompanyMatches(List.of(
                        company(17, "Analytical Labs"), company(18, "Analytical Labs")), false));

        BusinessCardScanResponse response = service.scan(image);

        assertNull(response.company().matchedCompanyId());
        assertTrue(response.warnings().contains("company_match_ambiguous"));
    }

    @Test
    void importUsesReviewedValuesWhenScanningIsDisabled() {
        properties.setEnabled(false);
        Company company = company(17, "Analytical Labs");
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "  Ada   Lovelace  ", "ADA@EXAMPLE.TEST", "+1 202 555 0199", "Engineer", 17);
        when(imageValidator.validate(image)).thenReturn(validated);
        when(companyService.getCompanyById(17)).thenReturn(company);
        when(personService.create(any())).thenAnswer(invocation -> {
            Person person = invocation.getArgument(0);
            person.setId(31);
            return person;
        });
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(binaryStore.store(5, "business-card.jpg", "image/jpeg", validated.content()))
                .thenReturn(new BusinessCardBinaryStore.StoredBusinessCard(
                        "/attachments/person/card-31.jpg", validated.content().length));
        User user = new User();
        user.setId(9);
        when(authService.getCurrentUser()).thenReturn(user);
        when(attachmentService.createManaged(any())).thenAnswer(invocation -> {
            Attachment attachment = invocation.getArgument(0);
            attachment.setId(41);
            return attachment;
        });

        BusinessCardImportResponse response = service.importCard(
                image, contact, new BusinessCardCompanyAction.Existing(17), IDEMPOTENCY_KEY);

        assertTrue(service.isImportAvailable());
        ArgumentCaptor<Person> personCaptor = ArgumentCaptor.forClass(Person.class);
        verify(personService).create(personCaptor.capture());
        assertEquals("Ada Lovelace", personCaptor.getValue().getName());
        assertEquals("ADA@EXAMPLE.TEST", personCaptor.getValue().getEmail());
        assertEquals(company, personCaptor.getValue().getCompany());
        ArgumentCaptor<Attachment> attachmentCaptor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentService).createManaged(attachmentCaptor.capture());
        assertEquals("person", attachmentCaptor.getValue().getEntityType());
        assertEquals(31, attachmentCaptor.getValue().getEntityId());
        assertEquals("business-card.jpg", attachmentCaptor.getValue().getFileName());
        assertEquals(9, attachmentCaptor.getValue().getUploadedBy().getId());
        assertEquals(31, response.contact().getId());
        assertEquals(41, response.attachment().getId());
        assertEquals(17, response.company().getId());
        InOrder persistenceOrder = inOrder(
            binaryStore, companyService, personService, attachmentService);
        persistenceOrder.verify(binaryStore).store(
            5, "business-card.jpg", "image/jpeg", validated.content());
        persistenceOrder.verify(companyService).getCompanyById(17);
        persistenceOrder.verify(personService).create(any());
        persistenceOrder.verify(attachmentService).createManaged(any());
        verify(importRequestMapper).complete(5, IDEMPOTENCY_KEY, 31, 41, 17);
        verify(rateLimiter).requireImportAllowed();
    }

    @Test
    void importRequiresAReservationBeforeProcessingPrivateMultipartData() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(null);

        assertThrows(ConflictException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(imageValidator, never()).validate(image);
        verify(rateLimiter).requireImportAllowed();
        verify(importRequestMapper, never()).getForUpdate(5, IDEMPOTENCY_KEY);
        InOrder order = inOrder(rateLimiter, importRequestMapper);
        order.verify(rateLimiter).requireImportAllowed();
        order.verify(importRequestMapper).get(5, IDEMPOTENCY_KEY);
    }

    @Test
    void importBindsAReservedKeyWithoutCreatingAnotherClaim() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        BusinessCardImportRecord reservation = new BusinessCardImportRecord(
                null,
                null,
                null,
                null,
                EXPIRY,
                9,
                LocalDateTime.parse("2026-07-15T00:02:00"),
                1);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(reservation);
        when(importRequestMapper.getForUpdate(5, IDEMPOTENCY_KEY)).thenReturn(reservation);
        when(importRequestMapper.bindReservation(
                eq(5), eq(9), eq(IDEMPOTENCY_KEY), any(), any(), any()))
                .thenReturn(1);
        when(imageValidator.validate(image)).thenReturn(validated);
        when(binaryStore.isReady()).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(rateLimiter).requireImportAllowed();
        verify(importRequestMapper).bindReservation(
                eq(5),
                eq(9),
                eq(IDEMPOTENCY_KEY),
                any(),
                any(),
                eq(LocalDateTime.parse("2026-07-15T00:00:00")));
        verify(personService, never()).create(any());
    }

    @Test
    void reservationPersistsBeforeReturningItsServerExpiry() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(null);
        when(importRequestMapper.reserve(
                5,
                9,
                IDEMPOTENCY_KEY,
                1,
                LocalDateTime.parse("2026-07-15T00:02:00"),
                EXPIRY)).thenReturn(1);

        BusinessCardImportReservationResponse response = service.reserveImport(IDEMPOTENCY_KEY);

        assertEquals(Instant.parse("2026-07-16T00:00:00Z"), response.expiresAt());
        verify(importRequestMapper).reserve(
            5,
            9,
            IDEMPOTENCY_KEY,
            1,
            LocalDateTime.parse("2026-07-15T00:02:00"),
            EXPIRY);
    }

    @Test
    void reservationUsesAnIndependentReadCommittedTransaction() throws Exception {
        Transactional transaction = BusinessCardService.class
            .getMethod("reserveImport", String.class)
            .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
    }

    @Test
    void existingReservationIsIdempotentAfterAdmissionAndWithoutGapLocking() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(new BusinessCardImportRecord(
                        null,
                        null,
                        null,
                        null,
                        EXPIRY,
                        9,
                        LocalDateTime.parse("2026-07-15T00:01:00"),
                        1));
        when(importRequestMapper.renewReservation(
                5,
                9,
                IDEMPOTENCY_KEY,
                LocalDateTime.parse("2026-07-15T00:02:00"),
                LocalDateTime.parse("2026-07-15T00:00:00"))).thenReturn(1);

        BusinessCardImportReservationResponse response = service.reserveImport(IDEMPOTENCY_KEY);

        assertEquals(Instant.parse("2026-07-16T00:00:00Z"), response.expiresAt());
        verify(importRequestMapper, never()).getForUpdate(anyInt(), anyString());
        verify(importRequestMapper, never()).reserve(
                anyInt(), anyInt(), anyString(), anyInt(), any(), any());
    }

    @Test
    void reservationReclaimsExpiredPendingRowsBeforeAllocatingASlot() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(null);
        when(importRequestMapper.reserve(
                5,
                9,
                IDEMPOTENCY_KEY,
                1,
                LocalDateTime.parse("2026-07-15T00:02:00"),
                EXPIRY)).thenReturn(1);

        service.reserveImport(IDEMPOTENCY_KEY);

        verify(importRequestMapper).deleteAbandonedReservations(
                5, 9, LocalDateTime.parse("2026-07-15T00:00:00"));
    }

    @Test
    void reservationRejectsWhenEveryPendingSlotIsOccupied() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(null);

        assertThrows(TooManyRequestsException.class,
                () -> service.reserveImport(IDEMPOTENCY_KEY));

        verify(importRequestMapper, times(4)).reserve(
                eq(5), eq(9), eq(IDEMPOTENCY_KEY), anyInt(), any(), eq(EXPIRY));
    }

    @Test
    void reservationResolvesAConcurrentDuplicateInsertWithoutGapLocking() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
            .thenReturn(null, activeReservation());
        when(importRequestMapper.renewReservation(
            5,
            9,
            IDEMPOTENCY_KEY,
            LocalDateTime.parse("2026-07-15T00:02:00"),
            LocalDateTime.parse("2026-07-15T00:00:00"))).thenReturn(1);

        BusinessCardImportReservationResponse response = service.reserveImport(IDEMPOTENCY_KEY);

        assertEquals(Instant.parse("2026-07-16T00:00:00Z"), response.expiresAt());
        verify(importRequestMapper, times(4)).reserve(
            eq(5), eq(9), eq(IDEMPOTENCY_KEY), anyInt(), any(), eq(EXPIRY));
        verify(importRequestMapper, times(2)).get(5, IDEMPOTENCY_KEY);
        verify(importRequestMapper, never()).getForUpdate(anyInt(), anyString());
    }

    @Test
    void reservationFailsClosedForAnUnownedPendingState() {
        BusinessCardImportRecord legacyReservation = new BusinessCardImportRecord(
                null, null, null, null, EXPIRY);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(legacyReservation);

        assertThrows(ResourceNotFoundException.class,
                () -> service.reserveImport(IDEMPOTENCY_KEY));

        verify(importRequestMapper, never()).renewReservation(
                anyInt(), anyInt(), anyString(), any(), any());
    }

    @Test
    void importRejectsAnotherUsersReservationBeforeImageProcessing() {
        BusinessCardImportRecord anotherUsersReservation = new BusinessCardImportRecord(
                null,
                null,
                null,
                null,
                EXPIRY,
                10,
                LocalDateTime.parse("2026-07-15T00:02:00"),
                1);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(anotherUsersReservation);
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);

        assertThrows(ResourceNotFoundException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(imageValidator, never()).validate(image);
        verify(rateLimiter).requireImportAllowed();
    }

    @Test
    void importRejectsExpiredSubmissionLeaseBeforeImageProcessing() {
        BusinessCardImportRecord expiredReservation = new BusinessCardImportRecord(
                null,
                null,
                null,
                null,
                EXPIRY,
                9,
                LocalDateTime.parse("2026-07-14T23:59:59"),
                1);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(expiredReservation);
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);

        assertThrows(BusinessCardImportResultGoneException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(imageValidator, never()).validate(image);
        verify(rateLimiter).requireImportAllowed();
    }

    @Test
    void importRejectsConflictingCompanyIdentifiersBeforeSideEffects() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, 17);
        when(imageValidator.validate(image)).thenReturn(validated);

        assertThrows(BadRequestException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.Existing(18), IDEMPOTENCY_KEY));

        verify(companyService, never()).getCompanyById(18);
        verify(personService, never()).create(any());
        verify(binaryStore, never()).store(anyInt(), any(), any(), any());
    }

    @Test
    void importRejectsCompanyNameThatBecomesBlankAfterNormalization() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(imageValidator.validate(image)).thenReturn(validated);

        assertThrows(BadRequestException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.Create("　"), IDEMPOTENCY_KEY));

        verify(companyService, never()).createCompany(any());
        verify(personService, never()).create(any());
    }

    @Test
    void importPropagatesMetadataFailureWithoutStartingACompetingCleanupTransaction() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(imageValidator.validate(image)).thenReturn(validated);
        when(personService.create(any())).thenAnswer(invocation -> {
            Person person = invocation.getArgument(0);
            person.setId(31);
            return person;
        });
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(binaryStore.store(eq(5), any(), any(), any()))
                .thenReturn(new BusinessCardBinaryStore.StoredBusinessCard(
                        "/attachments/person/card-31.jpg", validated.content().length));
        when(attachmentService.createManaged(any())).thenThrow(new ServiceUnavailableException("failed"));

        assertThrows(ServiceUnavailableException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(binaryStore).store(eq(5), any(), any(), any());
    }

    @Test
    void importRejectsStorageWriteWithUnexpectedSize() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(imageValidator.validate(image)).thenReturn(validated);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(binaryStore.store(eq(5), any(), any(), any()))
                .thenReturn(new BusinessCardBinaryStore.StoredBusinessCard(
                        "/attachments/person/card.jpg", validated.content().length - 1));

        assertThrows(ServiceUnavailableException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(binaryStore).store(eq(5), any(), any(), any());
        verify(attachmentService, never()).createManaged(any());
    }

    @Test
    void unavailableStorageFailsClosedBeforeImageProcessing() {
        when(binaryStore.isReady()).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, () -> service.scan(image));

        verify(rateLimiter).requireScanAllowed();
        verify(imageValidator, never()).validate(image);
        verify(ocrClient, never()).recognize(any());
    }

    @Test
    void unavailableScannerFailsClosedBeforeImageProcessing() {
        when(ocrClient.isReady()).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, () -> service.scan(image));

        verify(rateLimiter).requireScanAllowed();
        verify(imageValidator, never()).validate(image);
        verify(ocrClient, never()).recognize(any());
    }

    @Test
    void importReplaysCompletedResultForEquivalentNormalizedRequest() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "  Ada   Lovelace  ", null, null, null, null);
        AtomicReference<byte[]> fingerprint = new AtomicReference<>();
        BusinessCardImportRecord reservation = activeReservation();
        when(imageValidator.validate(image)).thenReturn(validated);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(reservation)
                .thenAnswer(invocation -> completedImport(fingerprint.get()));
        when(importRequestMapper.getForUpdate(5, IDEMPOTENCY_KEY))
                .thenReturn(reservation)
                .thenAnswer(invocation -> completedImport(fingerprint.get()));
        when(importRequestMapper.bindReservation(
                eq(5), eq(9), eq(IDEMPOTENCY_KEY), any(), any(), any()))
                .thenAnswer(invocation -> {
                    fingerprint.set(invocation.getArgument(3, byte[].class).clone());
                    return 1;
                });
        Person person = new Person();
        person.setId(31);
        person.setName("Ada Lovelace");
        Attachment attachment = new Attachment();
        attachment.setId(41);
        when(personService.create(any())).thenReturn(person);
        when(binaryStore.store(5, "business-card.jpg", "image/jpeg", validated.content()))
                .thenReturn(new BusinessCardBinaryStore.StoredBusinessCard(
                        "/attachments/person/card-31.jpg", validated.content().length));
        when(attachmentService.createManaged(any())).thenReturn(attachment);
        when(personService.getPersonById(31)).thenReturn(person);
        when(attachmentService.getById(41)).thenReturn(attachment);

        BusinessCardImportResponse response = service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY);
        BusinessCardImportResponse normalizedResponse = service.importCard(
                image,
                new BusinessCardContactRequest("Ada Lovelace", null, null, null, null),
                new BusinessCardCompanyAction.None(),
                IDEMPOTENCY_KEY);

        assertEquals(31, response.contact().getId());
        assertEquals(41, response.attachment().getId());
        assertNull(response.company());
        assertEquals(31, normalizedResponse.contact().getId());
        verify(rateLimiter, times(2)).requireImportAllowed();
        verify(personService).create(any());
        verify(binaryStore).store(5, "business-card.jpg", "image/jpeg", validated.content());
    }

    @Test
    void importRejectsIdempotencyKeyReuseWithDifferentRequest() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(imageValidator.validate(image)).thenReturn(validated);
        BusinessCardImportRecord completed = new BusinessCardImportRecord(
                new byte[32], 31, 41, null, EXPIRY, 9, null, null);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenReturn(completed);
        when(importRequestMapper.getForUpdate(5, IDEMPOTENCY_KEY))
                .thenReturn(completed);

        assertThrows(ConflictException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(personService, never()).getPersonById(anyInt());
        verify(personService, never()).create(any());
        verify(binaryStore, never()).store(anyInt(), any(), any(), any());
    }

    @Test
    void importRejectsMalformedIdempotencyKeyBeforeImageProcessing() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);

        assertThrows(BadRequestException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), "not-a-uuid"));

        verify(imageValidator, never()).validate(image);
        verify(importRequestMapper, never()).get(anyInt(), anyString());
    }

    @Test
    void scanningEntitlementDenialPrecedesPermissionsAndImageProcessing() {
        when(capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_SCANNING))
            .thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.scan(image));

        verify(workspaceService, never()).requirePermission(any());
        verify(rateLimiter, never()).requireScanAllowed();
        verifyNoInteractions(imageValidator);
    }

    @Test
    void importEntitlementDenialPrecedesAdmissionAndPersistenceForEveryImportOperation() {
        when(capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_IMPORT))
            .thenReturn(false);
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
            "Ada Lovelace", null, null, null, null);

        assertThrows(ForbiddenException.class, () -> service.reserveImport(IDEMPOTENCY_KEY));
        assertThrows(ForbiddenException.class, () -> service.importCard(
            image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));
        assertThrows(ForbiddenException.class, () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(rateLimiter, never()).requireImportAllowed();
        verifyNoInteractions(importRequestMapper, imageValidator);
    }

    @Test
    void importStatusReturnsTheCompletedTenantScopedResult() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
            .thenReturn(new BusinessCardImportRecord(
                new byte[32], 31, 41, null, EXPIRY, 9, null, null));
        Person person = new Person();
        person.setId(31);
        Attachment attachment = new Attachment();
        attachment.setId(41);
        when(personService.getPersonById(31)).thenReturn(person);
        when(attachmentService.getById(41)).thenReturn(attachment);

        BusinessCardImportResponse response = service.importStatus(IDEMPOTENCY_KEY);

        assertEquals(31, response.contact().getId());
        assertEquals(41, response.attachment().getId());
        verify(workspaceService).requirePermission(Permission.ATTACHMENT_CREATE);
        verify(importRequestMapper).get(5, IDEMPOTENCY_KEY);
        verify(importRequestMapper, never()).getForUpdate(anyInt(), anyString());
    }

    @Test
    void importStatusDoesNotExposeAnotherWorkspace() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
        when(importRequestMapper.get(9, IDEMPOTENCY_KEY)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
            () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(importRequestMapper).get(9, IDEMPOTENCY_KEY);
        verify(personService, never()).getPersonById(anyInt());
    }

    @Test
    void importStatusReportsAnIncompleteClaimAsInProgress() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
            .thenReturn(new BusinessCardImportRecord(
                new byte[32], null, null, null, EXPIRY, 9, null, null));

        assertThrows(ConflictException.class,
            () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(importRequestMapper).get(5, IDEMPOTENCY_KEY);
        verify(personService, never()).getPersonById(anyInt());
    }

    @Test
    void importStatusReportsAnActiveReservationAsInProgress() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(activeReservation());

        assertThrows(ConflictException.class,
                () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(personService, never()).getPersonById(anyInt());
    }

    @Test
    void importStatusReportsAnExpiredReservationAsTerminallyGone() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(new BusinessCardImportRecord(
                        null,
                        null,
                        null,
                        null,
                        EXPIRY,
                        9,
                        LocalDateTime.parse("2026-07-15T00:00:00"),
                        1));

        assertThrows(BusinessCardImportResultGoneException.class,
                () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(personService, never()).getPersonById(anyInt());
    }

    @Test
    void importStatusDoesNotExposeAnotherUsersResult() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(new BusinessCardImportRecord(
                        new byte[32], 31, 41, null, EXPIRY, 10, null, null));

        assertThrows(ResourceNotFoundException.class,
                () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(personService, never()).getPersonById(anyInt());
    }

    @Test
    void importStatusFailsClosedForLegacyNullOwnership() {
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
            .thenReturn(new BusinessCardImportRecord(
                new byte[32], 31, 41, null, EXPIRY));

        assertThrows(ResourceNotFoundException.class,
            () -> service.importStatus(IDEMPOTENCY_KEY));

        verify(personService, never()).getPersonById(anyInt());
    }

    @Test
    void importStatusReportsADeletedMutableResultAsTerminallyGone() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(new BusinessCardImportRecord(
                    new byte[32], 31, 41, null, EXPIRY, 9, null, null));
        Person person = new Person();
        person.setId(31);
        when(personService.getPersonById(31)).thenReturn(person);
        when(attachmentService.getById(41))
                .thenThrow(new ResourceNotFoundException("Attachment not found"));

        assertThrows(BusinessCardImportResultGoneException.class,
                () -> service.importStatus(IDEMPOTENCY_KEY));
    }

    private static BusinessCardScanResponse draft(String company) {
        FieldCandidate name = new FieldCandidate("Ada Lovelace", 0.99);
        return new BusinessCardScanResponse(
                new Fields(name, FieldCandidate.empty(), FieldCandidate.empty(), FieldCandidate.empty()),
                new CompanyCandidate(company, 0.92, null),
                List.of());
    }

    private static Company company(int id, String name) {
        Company company = new Company();
        company.setId(id);
        company.setName(name);
        return company;
    }

    private static BusinessCardImportRecord activeReservation() {
        return new BusinessCardImportRecord(
                null,
                null,
                null,
                null,
                EXPIRY,
                9,
                LocalDateTime.parse("2026-07-15T00:02:00"),
                1);
    }

    private static BusinessCardImportRecord completedImport(byte[] fingerprint) {
        return new BusinessCardImportRecord(fingerprint, 31, 41, null, EXPIRY, 9, null, null);
    }
}
