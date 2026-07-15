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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

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
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.CompanyCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.FieldCandidate;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.Fields;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class BusinessCardServiceTest {
    private static final String IDEMPOTENCY_KEY = "02a25a23-70af-4f8e-a64a-6cfc5f8c69be";

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

    private BusinessCardProperties properties;
    private BusinessCardService service;
    private MockMultipartFile image;
    private ValidatedBusinessCardImage validated;

    @BeforeEach
    void setUp() throws Exception {
        properties = new BusinessCardProperties();
        properties.setEnabled(true);
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
                rateLimiter);
        image = new MockMultipartFile("image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        validated = new ValidatedBusinessCardImage(
                image.getBytes(), "image/jpeg", "jpg", 120, 70);
        lenient().when(binaryStore.isReady()).thenReturn(true);
        lenient().when(importRequestMapper.claim(anyInt(), anyString(), any())).thenReturn(1);
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
        when(companyService.findVisibleByNormalizedName("Analytical Labs")).thenReturn(List.of(company));

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
        when(companyService.findVisibleByNormalizedName("Analytical Labs")).thenReturn(List.of(
                company(17, "Analytical Labs"), company(18, "Analytical Labs")));

        BusinessCardScanResponse response = service.scan(image);

        assertNull(response.company().matchedCompanyId());
        assertTrue(response.warnings().contains("company_match_ambiguous"));
    }

    @Test
    void scanRejectsConcurrentBusinessCardProcessing() throws Exception {
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        when(imageValidator.validate(image)).thenAnswer(invocation -> {
            validationStarted.countDown();
            try {
                if (!releaseValidation.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted", exception);
            }
            return validated;
        });
        when(ocrClient.recognize(validated)).thenReturn(List.of());
        when(extractor.extract(List.of())).thenReturn(draft(null));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = Thread.startVirtualThread(() -> {
            try {
                service.scan(image);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        assertTrue(validationStarted.await(5, TimeUnit.SECONDS));

        try {
            assertThrows(TooManyRequestsException.class, () -> service.scan(image));
        } finally {
            releaseValidation.countDown();
            first.join(5_000);
        }

        assertNull(failure.get());
        assertTrue(!first.isAlive());
        verify(imageValidator).validate(image);
    }

    @Test
    void importUsesReviewedValuesAndExplicitExistingCompany() {
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
        verify(importRequestMapper).complete(5, IDEMPOTENCY_KEY, 31, 41, 17);
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
    void importDeletesStoredBinaryWhenMetadataWriteFailsOutsideTransactionProxy() {
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
        when(authService.getCurrentUser()).thenReturn(new User());
        when(attachmentService.createManaged(any())).thenThrow(new ServiceUnavailableException("failed"));

        assertThrows(ServiceUnavailableException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(binaryStore).delete(5, "/attachments/person/card-31.jpg");
    }

    @Test
    void importDeletesStorageWriteWithUnexpectedSize() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(imageValidator.validate(image)).thenReturn(validated);
        when(personService.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(binaryStore.store(eq(5), any(), any(), any()))
                .thenReturn(new BusinessCardBinaryStore.StoredBusinessCard(
                        "/attachments/person/card.jpg", validated.content().length - 1));

        assertThrows(ServiceUnavailableException.class, () -> service.importCard(
                image, contact, new BusinessCardCompanyAction.None(), IDEMPOTENCY_KEY));

        verify(binaryStore).delete(5, "/attachments/person/card.jpg");
        verify(attachmentService, never()).createManaged(any());
    }

    @Test
    void unavailableStorageFailsClosedBeforeImageProcessing() {
        when(binaryStore.isReady()).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, () -> service.scan(image));

        verify(imageValidator, never()).validate(image);
        verify(ocrClient, never()).recognize(any());
    }

    @Test
    void importReplaysCompletedResultForEquivalentNormalizedRequest() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "  Ada   Lovelace  ", null, null, null, null);
        AtomicReference<byte[]> fingerprint = new AtomicReference<>();
        when(imageValidator.validate(image)).thenReturn(validated);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.claim(eq(5), eq(IDEMPOTENCY_KEY), any()))
                .thenAnswer(invocation -> {
                    fingerprint.compareAndSet(
                            null,
                            invocation.getArgument(2, byte[].class).clone());
                    return 0;
                });
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY)).thenAnswer(invocation ->
                new BusinessCardImportRecord(fingerprint.get(), 31, 41, null));
        Person person = new Person();
        person.setId(31);
        person.setName("Ada Lovelace");
        Attachment attachment = new Attachment();
        attachment.setId(41);
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
        verify(personService, never()).create(any());
        verify(binaryStore, never()).store(anyInt(), any(), any(), any());
    }

    @Test
    void importRejectsIdempotencyKeyReuseWithDifferentRequest() {
        BusinessCardContactRequest contact = new BusinessCardContactRequest(
                "Ada Lovelace", null, null, null, null);
        when(imageValidator.validate(image)).thenReturn(validated);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(importRequestMapper.claim(eq(5), eq(IDEMPOTENCY_KEY), any())).thenReturn(0);
        when(importRequestMapper.get(5, IDEMPOTENCY_KEY))
                .thenReturn(new BusinessCardImportRecord(new byte[32], 31, 41, null));

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
        verify(importRequestMapper, never()).claim(anyInt(), anyString(), any());
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
}
