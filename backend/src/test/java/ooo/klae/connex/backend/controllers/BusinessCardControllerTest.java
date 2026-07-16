package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
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
        when(businessCardService.importCard(any(), any(), any(), any()))
                .thenReturn(new BusinessCardImportResponse(null, null, null));

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(companyAction)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact").isEmpty())
                .andExpect(jsonPath("$.attachment").isEmpty())
                .andExpect(jsonPath("$.company").isEmpty());

        ArgumentCaptor<BusinessCardContactRequest> contactCaptor =
                ArgumentCaptor.forClass(BusinessCardContactRequest.class);
        ArgumentCaptor<BusinessCardCompanyAction> actionCaptor =
                ArgumentCaptor.forClass(BusinessCardCompanyAction.class);
        verify(businessCardService).importCard(
                any(), contactCaptor.capture(), actionCaptor.capture(), org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY));
        org.junit.jupiter.api.Assertions.assertEquals("Ada Lovelace", contactCaptor.getValue().name());
        org.junit.jupiter.api.Assertions.assertEquals(
                new BusinessCardCompanyAction.Existing(7), actionCaptor.getValue());
    }

    @Test
    void importRejectsTextPlainJsonParts() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "text/plain", "{\"name\":\"Ada Lovelace\"}".getBytes());
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "text/plain", "{\"type\":\"none\"}".getBytes());

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(companyAction)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void importRequiresIdempotencyKey() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image", "card.jpg", "image/jpeg", new byte[] {1, 2, 3});
        MockMultipartFile contact = new MockMultipartFile(
                "contact", "", "application/json", "{\"name\":\"Ada Lovelace\"}".getBytes());
        MockMultipartFile companyAction = new MockMultipartFile(
                "companyAction", "", "application/json", "{\"type\":\"none\"}".getBytes());

        mockMvc.perform(multipart("/api/business-cards/import")
                        .file(image)
                        .file(contact)
                        .file(companyAction))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importStatusUsesTheOpaqueIdempotencyHeader() throws Exception {
        when(businessCardService.importStatus(IDEMPOTENCY_KEY))
            .thenReturn(new BusinessCardImportResponse(null, null, null));

        mockMvc.perform(get("/api/business-cards/import")
                .header("Idempotency-Key", IDEMPOTENCY_KEY))
            .andExpect(status().isOk());

        verify(businessCardService).importStatus(IDEMPOTENCY_KEY);
    }
}
