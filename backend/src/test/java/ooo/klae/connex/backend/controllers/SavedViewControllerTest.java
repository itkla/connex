package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.dto.SavedViewCreateRequest;
import ooo.klae.connex.backend.dto.SavedViewUpdateRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.SavedViewService;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SavedViewControllerTest {

    @Mock private SavedViewService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SavedViewController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void pinsLiteralRoutesToPinsHandler() throws Exception {
        when(service.listPins()).thenReturn(List.of(view()));

        mockMvc.perform(get("/api/saved-views/pins"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(42));

        verify(service).listPins();
        verify(service, never()).getById(anyInt());
    }

    @Test
    void defaultsLiteralRoutesToDefaultHandler() throws Exception {
        when(service.getDefault("company")).thenReturn(null);

        mockMvc.perform(get("/api/saved-views/defaults/company"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"view\":null}"));

        verify(service).getDefault("company");
        verify(service, never()).getById(anyInt());
    }

    @Test
    void createReturnsCanonicalBodyLocationAndCreatedStatus() throws Exception {
        when(service.create(any(SavedViewCreateRequest.class))).thenReturn(view());

        mockMvc.perform(post("/api/saved-views")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recordType":"company",
                      "name":"Warm prospects",
                      "visibility":"workspace",
                      "config":{"version":1,"filters":{},"query":""},
                      "position":2
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/saved-views/42"))
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.workspaceId").value(9))
            .andExpect(jsonPath("$.ownerUserId").value(7))
            .andExpect(jsonPath("$.ownedByCurrentUser").value(false))
            .andExpect(jsonPath("$.visibility").value("workspace"))
            .andExpect(jsonPath("$.config.version").value(1))
            .andExpect(jsonPath("$.pinned").value(true))
            .andExpect(jsonPath("$.pinPosition").value(0))
            .andExpect(jsonPath("$.default").value(true));
    }

    @Test
    void createQuotaReturnsClearBadRequest() throws Exception {
        when(service.create(any(SavedViewCreateRequest.class))).thenThrow(new BadRequestException(
            "A user cannot have more than 100 saved views per record type in a workspace"));

        mockMvc.perform(post("/api/saved-views")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recordType":"company",
                      "name":"Over quota",
                      "visibility":"workspace",
                      "config":{"version":1}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(
                "A user cannot have more than 100 saved views per record type in a workspace"));
    }

    @Test
    void canonicalResponseIncludesNullPinPositionWhenUnpinned() throws Exception {
        SavedView unpinned = view();
        unpinned.setPinned(false);
        unpinned.setPinPosition(null);
        when(service.getById(42)).thenReturn(unpinned);

        mockMvc.perform(get("/api/saved-views/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(false))
            .andExpect(jsonPath("$.pinPosition").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updatePinDefaultAndDeleteMappingsReturnContractStatuses() throws Exception {
        when(service.update(anyInt(), any(SavedViewUpdateRequest.class))).thenReturn(view());
        when(service.pin(42, 3)).thenReturn(view());
        when(service.setDefault("company", 42)).thenReturn(view());

        mockMvc.perform(put("/api/saved-views/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"company","name":"Warm prospects","config":{"version":1}}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42));
        mockMvc.perform(put("/api/saved-views/42/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":3}"))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/saved-views/defaults/company")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"savedViewId\":42}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.view.id").value(42));
        mockMvc.perform(delete("/api/saved-views/42/pin"))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/saved-views/defaults/company"))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/saved-views/42"))
            .andExpect(status().isNoContent());
    }

    @Test
    void beanValidationRejectsMalformedRequests() throws Exception {
        mockMvc.perform(post("/api/saved-views")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recordType\":\"\",\"name\":\"\",\"position\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.recordType").exists())
            .andExpect(jsonPath("$.name").exists())
            .andExpect(jsonPath("$.config").exists())
            .andExpect(jsonPath("$.position").exists());

        mockMvc.perform(put("/api/saved-views/42/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":-1}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/saved-views/defaults/company")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"savedViewId\":0}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void inaccessibleTargetsUseTheExactGenericNotFoundBody() throws Exception {
        when(service.getById(42)).thenThrow(new ResourceNotFoundException("Saved view not found"));

        mockMvc.perform(get("/api/saved-views/42"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Saved view not found"));
    }

    @Test
    void inaccessibleMutationTargetsUseTheExactGenericNotFoundBody() throws Exception {
        ResourceNotFoundException notFound = new ResourceNotFoundException("Saved view not found");
        when(service.update(anyInt(), any(SavedViewUpdateRequest.class))).thenThrow(notFound);
        when(service.pin(42, null)).thenThrow(notFound);
        when(service.setDefault("company", 42)).thenThrow(notFound);
        doThrow(notFound).when(service).delete(42);
        doThrow(notFound).when(service).unpin(42);

        mockMvc.perform(put("/api/saved-views/42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recordType\":\"company\",\"name\":\"View\",\"config\":{\"version\":1}}"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Saved view not found"));
        mockMvc.perform(delete("/api/saved-views/42"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Saved view not found"));
        mockMvc.perform(put("/api/saved-views/42/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Saved view not found"));
        mockMvc.perform(delete("/api/saved-views/42/pin"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Saved view not found"));
        mockMvc.perform(put("/api/saved-views/defaults/company")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"savedViewId\":42}"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Saved view not found"));
    }

    private SavedView view() throws Exception {
        SavedView view = new SavedView();
        view.setId(42);
        view.setWorkspaceId(9);
        view.setUserId(7);
        view.setRecordType("company");
        view.setName("Warm prospects");
        view.setVisibility("workspace");
        view.setConfig(JsonMapper.builder().build().readTree("{\"version\":1,\"filters\":{},\"query\":\"\"}"));
        view.setPosition(2);
        view.setPinned(true);
        view.setPinPosition(0);
        view.setDefaultView(true);
        view.setCreatedAt("2026-07-19 10:00:00");
        view.setUpdatedAt("2026-07-19 10:05:00");
        return view;
    }
}
