package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.ProductImportRequest;
import ooo.klae.connex.backend.services.ProductImportService;

@ExtendWith(MockitoExtension.class)
class ProductImportControllerTest {

    private static final String VALID_REQUEST = """
        {
          "rows":[{"SKU":"A-1","Name":"Widget","Price":"1,200.005"}],
          "mapping":[
            {"column":"SKU","field":"sku"},
            {"column":"Name","field":"name"},
            {"column":"Price","field":"unitPrice"}
          ],
          "onConflict":"skip",
          "rowDecisions":{"0":"update"}
        }
        """;

    @Mock private ProductImportService importService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
            new ProductImportController(importService)).build();
    }

    @Test
    void previewDelegatesToTheService() throws Exception {
        mockMvc.perform(post("/api/imports/products/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());

        verify(importService).previewProducts(any(ProductImportRequest.class));
    }

    @Test
    void commitDelegatesToTheService() throws Exception {
        mockMvc.perform(post("/api/imports/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk());

        verify(importService).commitProducts(any(ProductImportRequest.class));
    }

    @Test
    void rejectsInvalidBoundsBeforeCallingTheService() throws Exception {
        mockMvc.perform(post("/api/imports/products/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"rows":[],"mapping":[],"onConflict":"fill_empty",
                     "rowDecisions":{"0":"archive"},"duplicateReviewProof":"not-a-proof"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(importService);
    }
}
