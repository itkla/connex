package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationImpactRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefaultRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDuplicateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplatePreviewRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateReorderRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateResetRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateStateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateSummaryDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUpdateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.recordcreation.RecordCreationImpactOperation;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateStatus;
import ooo.klae.connex.backend.services.RecordCreationTemplateService;
import ooo.klae.connex.backend.tenant.TenantContext;

class RecordCreationTemplateControllerTest {

    private final RecordCreationTemplateService service = mock(RecordCreationTemplateService.class);
    private final ErrorReporter errorReporter = mock(ErrorReporter.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new RecordCreationTemplateController(service))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, new TenantContext()))
            .build();
    }

    @Test
    void readPreviewAndImpactEndpointsBindAndDelegate() throws Exception {
        when(service.list(RecordCreationRecordType.person, true)).thenReturn(List.of(summary()));
        when(service.get("workspace:42")).thenReturn(template());
        when(service.preview(any())).thenReturn(resolved());
        when(service.impact(any())).thenReturn(impact());

        mockMvc.perform(get("/api/record-creation/templates")
                .queryParam("recordType", "person")
                .queryParam("includeArchived", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("workspace:42"));
        mockMvc.perform(get("/api/record-creation/templates/workspace:42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("workspace:42"));
        mockMvc.perform(post("/api/record-creation/templates/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"templateId":"workspace:42","recordType":"person",
                     "locale":"ja","viewport":"mobile"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availability").value("available"));
        mockMvc.perform(post("/api/record-creation/templates/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"operation":"archive","recordType":"person",
                     "templateId":"workspace:42","removedFieldKeys":[],
                     "expectedTemplateVersion":2,"expectedSetRevision":7}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requiresConfirmation").value(true));

        verify(service).list(RecordCreationRecordType.person, true);
        verify(service).get("workspace:42");
        verify(service).preview(any(RecordCreationTemplatePreviewRequestDto.class));
        verify(service).impact(any(RecordCreationImpactRequestDto.class));
    }

    @Test
    void allMutationEndpointsBindAndDelegate() throws Exception {
        when(service.create(any())).thenReturn(template());
        when(service.update(eq("workspace:42"), any())).thenReturn(template());
        when(service.duplicate(eq("workspace:42"), any())).thenReturn(template());
        when(service.reorder(any())).thenReturn(List.of(summary()));
        when(service.setDefault(any())).thenReturn(catalog());
        when(service.archive(eq("workspace:42"), any())).thenReturn(template());
        when(service.restore(eq("workspace:42"), any())).thenReturn(template());
        when(service.reset(any())).thenReturn(catalog());

        mockMvc.perform(post("/api/record-creation/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("workspace:42"));
        mockMvc.perform(put("/api/record-creation/templates/workspace:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/record-creation/templates/workspace:42/duplicate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":{"en":"Copy","ja":"コピー"},
                     "expectedSourceVersion":2,"expectedSetRevision":7}
                    """))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/record-creation/templates/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"person","orderedTemplateIds":["workspace:42"],
                     "expectedSetRevision":7}
                    """))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/record-creation/templates/default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"person","templateId":"workspace:42",
                     "expectedSetRevision":7}
                    """))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/record-creation/templates/workspace:42/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stateBody()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/record-creation/templates/workspace:42/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stateBody()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/record-creation/templates/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"person","expectedSetRevision":7,"confirmImpact":true}
                    """))
            .andExpect(status().isOk());

        verify(service).create(any(RecordCreationTemplateCreateRequestDto.class));
        verify(service).update(eq("workspace:42"), any(RecordCreationTemplateUpdateRequestDto.class));
        verify(service).duplicate(
            eq("workspace:42"), any(RecordCreationTemplateDuplicateRequestDto.class));
        verify(service).reorder(any(RecordCreationTemplateReorderRequestDto.class));
        verify(service).setDefault(any(RecordCreationTemplateDefaultRequestDto.class));
        verify(service).archive(eq("workspace:42"), any(RecordCreationTemplateStateRequestDto.class));
        verify(service).restore(eq("workspace:42"), any(RecordCreationTemplateStateRequestDto.class));
        verify(service).reset(any(RecordCreationTemplateResetRequestDto.class));
    }

    @Test
    void beanValidationRejectsUnsafeAdminBodiesBeforeService() throws Exception {
        mockMvc.perform(post("/api/record-creation/templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/record-creation/templates/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"person","locale":"fr","viewport":"tablet"}
                    """))
            .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/record-creation/templates/default")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"person","templateId":"","expectedSetRevision":0}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void beanValidationRejectsNullListElementsBeforeService() throws Exception {
        clearInvocations(service);

        mockMvc.perform(post("/api/record-creation/templates/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"recordType":"person","name":{"en":"Template","ja":"テンプレート"},
                     "definition":{"schemaVersion":1,"groups":[
                       {"key":"basics","label":{"en":"Basics","ja":"基本情報"},
                        "fields":[{"key":"tags","required":false,
                          "defaultSpec":{"kind":"literal_references","referenceIds":[4,null]}}]}]}}
                    """))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/record-creation/templates/impact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"operation":"remove_fields","recordType":"person",
                     "templateId":"workspace:42","removedFieldKeys":[null,"owner"],
                     "expectedTemplateVersion":2,"expectedSetRevision":7}
                    """))
            .andExpect(status().isBadRequest());

        verify(service, never()).preview(any());
        verify(service, never()).impact(any());
    }

    @Test
    void impactConfirmationSystemImmutabilityAndStaleStateUseContractStatuses() throws Exception {
        when(service.archive(eq("workspace:42"), any()))
            .thenThrow(RecordCreationTemplateException.impact(impact()));
        when(service.archive(eq("system:person:standard"), any()))
            .thenThrow(RecordCreationTemplateException.of(
                HttpStatus.CONFLICT,
                "SYSTEM_TEMPLATE_IMMUTABLE",
                "System templates are immutable"));
        when(service.update(eq("workspace:42"), any()))
            .thenThrow(RecordCreationTemplateException.stale(
                "TEMPLATE_VERSION_STALE", "Template revision is stale", 8, 4, 3));
        when(service.get("workspace:missing")).thenThrow(RecordCreationTemplateException.of(
            HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Template not found"));

        mockMvc.perform(post("/api/record-creation/templates/workspace:42/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stateBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TEMPLATE_IMPACT_CONFIRMATION_REQUIRED"))
            .andExpect(jsonPath("$.impact.requiresConfirmation").value(true));
        mockMvc.perform(post("/api/record-creation/templates/system:person:standard/archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(stateBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SYSTEM_TEMPLATE_IMMUTABLE"));
        mockMvc.perform(put("/api/record-creation/templates/workspace:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.currentSetRevision").value(8))
            .andExpect(jsonPath("$.currentTemplateRevision").value(4))
            .andExpect(jsonPath("$.currentTemplateVersion").value(3));
        mockMvc.perform(get("/api/record-creation/templates/workspace:missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
    }

    private static String createBody() {
        return """
            {"recordType":"person","name":{"en":"Template","ja":"テンプレート"},
             "definition":{"schemaVersion":1,"groups":[]},"enabled":true,
             "expectedSetRevision":7}
            """;
    }

    private static String updateBody() {
        return """
            {"name":{"en":"Template","ja":"テンプレート"},
             "definition":{"schemaVersion":1,"groups":[]},"enabled":true,
             "expectedTemplateRevision":3,"expectedTemplateVersion":2,
             "expectedSetRevision":7,"confirmImpact":true}
            """;
    }

    private static String stateBody() {
        return """
            {"expectedTemplateRevision":3,"expectedSetRevision":7,"confirmImpact":true}
            """;
    }

    private static RecordCreationTemplateDto template() {
        return new RecordCreationTemplateDto(
            "workspace:42", RecordCreationRecordType.person,
            RecordCreationTemplateStatus.enabled, false, 0, 3, 2,
            names(), null, null, "0123", false,
            RecordCreationTemplateAvailability.available, List.of(),
            11, 11, null, null, null);
    }

    private static RecordCreationTemplateSummaryDto summary() {
        return new RecordCreationTemplateSummaryDto(
            "workspace:42", RecordCreationRecordType.person,
            RecordCreationTemplateStatus.enabled, false, 0, 3, 2,
            names(), null, false, RecordCreationTemplateAvailability.available,
            List.of(), 11, 11, null, null, null);
    }

    private static ResolvedCreationTemplateDto resolved() {
        return new ResolvedCreationTemplateDto(
            "workspace:42", RecordCreationRecordType.person, false, 2,
            names(), null, RecordCreationTemplateAvailability.available,
            List.of(), List.of());
    }

    private static RecordCreationImpactDto impact() {
        return new RecordCreationImpactDto(
            RecordCreationImpactOperation.archive, RecordCreationRecordType.person,
            "workspace:42", true, true, List.of(), List.of(),
            "system:person:standard", 0, true);
    }

    private static RecordCreationPresetCatalogDto catalog() {
        return new RecordCreationPresetCatalogDto(
            RecordCreationRecordType.person, null, 8,
            "workspace:42", List.of(resolved()), false, List.of());
    }

    private static LocalizedTextDto names() {
        return new LocalizedTextDto("Template", "テンプレート");
    }
}
