package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.services.SearchService;

class SearchControllerTest {

    private final SearchService service = mock(SearchService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
            .setControllerAdvice(new GlobalExceptionHandler(null, null))
            .build();
        when(service.search(eq("needle"), anySet())).thenReturn(new SearchResultsDto());
    }

    @Test
    void omittedTypesSelectsEveryGroup() throws Exception {
        mockMvc.perform(get("/api/search").queryParam("query", "needle"))
            .andExpect(status().isOk());

        verify(service).search("needle", SearchService.parseTypes(null));
    }

    @Test
    void commaSeparatedTypesBind() throws Exception {
        mockMvc.perform(get("/api/search").queryParam("query", "needle")
                .queryParam("types", "companies,people"))
            .andExpect(status().isOk());

        verify(service).search("needle", Set.of("companies", "people"));
    }

    @Test
    void repeatedAndMixedTypesBindAndDeduplicate() throws Exception {
        mockMvc.perform(get("/api/search").queryParam("query", "needle")
                .queryParam("types", "companies, people", "documentTemplates", "companies"))
            .andExpect(status().isOk());

        verify(service).search("needle", Set.of("companies", "people", "documentTemplates"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "companies,unknown", "Companies", "", " , "})
    void invalidTypesReturnTheExistingBadRequestShapeBeforeService(String types) throws Exception {
        mockMvc.perform(get("/api/search").queryParam("query", "needle")
                .queryParam("types", types))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").isString())
            .andExpect(jsonPath("$.message").isString());

        verifyNoInteractions(service);
    }
}
