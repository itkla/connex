package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardAvailabilityResponse;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportDisposition;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardImportReservationResponse;
import ooo.klae.connex.backend.dto.BusinessCardPersonAction;
import ooo.klae.connex.backend.services.BusinessCardService;

@ExtendWith(MockitoExtension.class)
class BusinessCardControllerTest {
    private static final String IDEMPOTENCY_KEY = String.join(
            "-", "02a25a23", "70af", "4f8e", "a64a", "6cfc5f8c69be");

    @Mock private BusinessCardService businessCardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BusinessCardController(businessCardService)).build();
    }

    @Test
    void availabilityIsWorkspaceResolvedAndNotCacheable() throws Exception {
        when(businessCardService.availability())
                .thenReturn(new BusinessCardAvailabilityResponse(true, true));

        mockMvc.perform(get("/api/business-cards/availability"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.scanning").value(true))
                .andExpect(jsonPath("$.importing").value(true))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist());

        verify(businessCardService).availability();
    }

    @Test
    void importAcceptsApplicationJsonMultipartBlobs() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "application/json",
                "{\"name\":\"Ada Lovelace\",\"email\":\"ada@example.test\",\"phone\":\"+12025550199\",\"title\":\"Engineer\",\"companyId\":7}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "application/json",
                "{\"type\":\"existing\",\"companyId\":7}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile personAction = new MockMultipartFile(
                "personAction", "", "application/json",
                "{\"type\":\"create\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(businessCardService.importCard(any(), any(), any(), any(), any()))
                .thenReturn(new BusinessCardImportResponse(
                    null, null, null, BusinessCardImportDisposition.CREATED));

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(personAction)
                        .file(companyAction)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact").isEmpty())
                .andExpect(jsonPath("$.attachment").isEmpty())
                .andExpect(jsonPath("$.company").isEmpty())
                .andExpect(jsonPath("$.disposition").value("created"));

        ArgumentCaptor<BusinessCardContactRequest> contactCaptor =
                ArgumentCaptor.forClass(BusinessCardContactRequest.class);
        ArgumentCaptor<BusinessCardCompanyAction> actionCaptor =
                ArgumentCaptor.forClass(BusinessCardCompanyAction.class);
        ArgumentCaptor<BusinessCardPersonAction> personActionCaptor =
                ArgumentCaptor.forClass(BusinessCardPersonAction.class);
        verify(businessCardService).importCard(
                any(), contactCaptor.capture(), personActionCaptor.capture(), actionCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY));
        org.junit.jupiter.api.Assertions.assertEquals("Ada Lovelace", contactCaptor.getValue().name());
        org.junit.jupiter.api.Assertions.assertEquals(
                new BusinessCardPersonAction.Create(), personActionCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertEquals(
                new BusinessCardCompanyAction.Existing(7), actionCaptor.getValue());
    }

    @Test
    void importDefaultsMissingLegacyPersonActionToCreate() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "application/json",
                "{\"name\":\"Ada Lovelace\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "application/json",
                "{\"type\":\"none\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(businessCardService.importCard(any(), any(), any(), any(), any()))
                .thenReturn(new BusinessCardImportResponse(
                        null, null, null, BusinessCardImportDisposition.CREATED));

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(companyAction)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("created"));

        ArgumentCaptor<BusinessCardPersonAction> actionCaptor =
                ArgumentCaptor.forClass(BusinessCardPersonAction.class);
        verify(businessCardService).importCard(
                any(), any(), actionCaptor.capture(), any(),
                org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY));
        org.junit.jupiter.api.Assertions.assertEquals(
                new BusinessCardPersonAction.Create(), actionCaptor.getValue());
    }

    @Test
    void importRejectsTextPlainJsonParts() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "text/plain", "{\"name\":\"Ada Lovelace\"}".getBytes());
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "text/plain", "{\"type\":\"none\"}".getBytes());
        MockMultipartFile personAction = new MockMultipartFile(
                "personAction", "", "text/plain", "{\"type\":\"create\"}".getBytes());

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(personAction)
                        .file(companyAction)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void importRejectsExistingPersonWithoutAValidReviewToken() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "application/json", "{\"name\":\"Ada Lovelace\"}".getBytes());
        MockMultipartFile personAction = new MockMultipartFile(
                "personAction", "", "application/json",
                "{\"type\":\"existing\",\"personId\":31}".getBytes());
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "application/json", "{\"type\":\"none\"}".getBytes());

        mockMvc.perform(multipart("/api/business-cards/import")
                .file(image)
                .file(contact)
                .file(personAction)
                .file(companyAction)
                .header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andExpect(status().isBadRequest());

        verify(businessCardService, never()).importCard(
            any(), any(), any(), any(), any());
    }

    @Test
    void importRequiresIdempotencyKey() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "application/json", "{\"name\":\"Ada Lovelace\"}".getBytes());
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "application/json", "{\"type\":\"none\"}".getBytes());
        MockMultipartFile personAction = new MockMultipartFile(
                "personAction", "", "application/json", "{\"type\":\"create\"}".getBytes());

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(personAction)
                        .file(companyAction))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importStatusUsesTheOpaqueIdempotencyHeader() throws Exception {
        when(businessCardService.importStatus(IDEMPOTENCY_KEY))
            .thenReturn(new BusinessCardImportResponse(
                null, null, null, BusinessCardImportDisposition.CREATED));

        mockMvc.perform(get("/api/business-cards/import")
                .header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andExpect(status().isOk());

        verify(businessCardService).importStatus(IDEMPOTENCY_KEY);
    }

    @Test
    void importReservationUsesTheOpaqueIdempotencyHeader() throws Exception {
        when(businessCardService.reserveImport(IDEMPOTENCY_KEY))
            .thenReturn(new BusinessCardImportReservationResponse(
                java.time.Instant.parse("2026-07-16T00:00:00Z")));

        mockMvc.perform(post("/api/business-cards/import/reservation")
                .header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresAt").value("2026-07-16T00:00:00Z"));

        verify(businessCardService).reserveImport(IDEMPOTENCY_KEY);
    }
}
