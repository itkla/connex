package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.services.RecordCreationPresetService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

class RecordCreationPresetControllerTest {
    private final RecordCreationPresetService service = mock(RecordCreationPresetService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new RecordCreationPresetController(service))
            .build();
    }

    @Test
    void threeRuntimeEndpointsBindEntryPointAndOptionalCompanyContext() throws Exception {
        when(service.persons(RecordCreationEntryPoint.record_detail, 44)).thenReturn(
            catalog(RecordCreationRecordType.person, RecordCreationEntryPoint.record_detail));
        when(service.companies(RecordCreationEntryPoint.quick_create)).thenReturn(
            catalog(RecordCreationRecordType.company, RecordCreationEntryPoint.quick_create));
        when(service.deals(RecordCreationEntryPoint.calendar, null)).thenReturn(
            catalog(RecordCreationRecordType.deal, RecordCreationEntryPoint.calendar));

        mockMvc.perform(get("/api/record-creation/presets/persons")
                .queryParam("entryPoint", "record_detail")
                .queryParam("relatedCompanyId", "44"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordType").value("person"));
        mockMvc.perform(get("/api/record-creation/presets/companies")
                .queryParam("entryPoint", "quick_create"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordType").value("company"));
        mockMvc.perform(get("/api/record-creation/presets/deals")
                .queryParam("entryPoint", "calendar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordType").value("deal"));

        verify(service).persons(RecordCreationEntryPoint.record_detail, 44);
        verify(service).companies(RecordCreationEntryPoint.quick_create);
        verify(service).deals(RecordCreationEntryPoint.calendar, null);
    }

    @Test
    void invalidEntryPointAndContextAreRejectedBeforeService() throws Exception {
        mockMvc.perform(get("/api/record-creation/presets/persons"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/record-creation/presets/persons")
                .queryParam("entryPoint", "unknown"))
            .andExpect(status().isBadRequest());
        verify(service, never()).persons(RecordCreationEntryPoint.quick_create, null);
    }

    @Test
    void relatedCompanyContextCarriesPositiveValidationAtBothRuntimeBoundaries() throws Exception {
        for (String methodName : List.of("persons", "deals")) {
            Method method = RecordCreationPresetController.class.getMethod(
                methodName, RecordCreationEntryPoint.class, Integer.class);
            Parameter context = method.getParameters()[1];
            assertEquals(true, context.isAnnotationPresent(jakarta.validation.constraints.Positive.class));
        }
    }

    @Test
    void runtimeBoundariesDeclareOnlyTheirDynamicCreatePermission() throws Exception {
        assertPermission("persons", Permission.PERSON_CREATE,
            RecordCreationEntryPoint.class, Integer.class);
        assertPermission("companies", Permission.COMPANY_CREATE,
            RecordCreationEntryPoint.class);
        assertPermission("deals", Permission.DEAL_CREATE,
            RecordCreationEntryPoint.class, Integer.class);
    }

    private static void assertPermission(
            String methodName,
            Permission expected,
            Class<?>... parameters) throws Exception {
        Method method = RecordCreationPresetService.class.getMethod(methodName, parameters);
        RequirePermission annotation = method.getAnnotation(RequirePermission.class);
        assertEquals(expected, annotation.value());
    }

    private static RecordCreationPresetCatalogDto catalog(
            RecordCreationRecordType recordType,
            RecordCreationEntryPoint entryPoint) {
        return new RecordCreationPresetCatalogDto(
            recordType, entryPoint, 0,
            "system:" + recordType.name() + ":standard", List.of(), false, List.of());
    }
}
