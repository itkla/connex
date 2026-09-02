package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.exceptions.DuplicateReviewException;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;

class GuidedRecordCreationControllerTest {
    private final GuidedRecordCreationService service = mock(GuidedRecordCreationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new GuidedRecordCreationController(service))
            .setControllerAdvice(new GuidedRecordCreationRequestBodyAdvice(
                JsonMapper.builder().findAndAddModules().build()))
            .build();
    }

    @Test
    void nestedRequestsBindAllThreeCanonicalCreatePaths() throws Exception {
        Person person = new Person();
        person.setId(101);
        person.setName("Ada");
        Company company = new Company();
        company.setId(202);
        company.setName("Analytical Engines");
        Deal deal = new Deal();
        deal.setId(303);
        deal.setName("Expansion");
        deal.setValue(new BigDecimal("1200.00"));
        deal.setCurrency("USD");
        deal.setPipelineId(4);
        deal.setStageId(5);
        when(service.createPerson(any())).thenReturn(person);
        when(service.createCompany(any())).thenReturn(company);
        when(service.createDeal(any())).thenReturn(deal);

        mockMvc.perform(post("/api/persons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(101))
            .andExpect(jsonPath("$.name").value("Ada"));
        mockMvc.perform(post("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(202));
        mockMvc.perform(post("/api/deals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dealBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(303));

        verify(service).createPerson(any(GuidedPersonCreateRequestDto.class));
        verify(service).createCompany(any(GuidedCompanyCreateRequestDto.class));
        verify(service).createDeal(any(GuidedDealCreateRequestDto.class));
    }

    @Test
    void flatAndMissingNestedBodiesUseRequestBodyInvalid() throws Exception {
        mockMvc.perform(post("/api/persons")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Legacy\",\"email\":\"legacy@example.com\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mockMvc.perform(post("/api/persons")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"record\":{\"name\":\"Missing template\"},\"customFields\":{},\"tagIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));

        verify(service, never()).createPerson(any());
    }

    @Test
    void unknownAndUnsafeRecordFieldsUseRequestBodyInvalid() throws Exception {
        mockMvc.perform(post("/api/persons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody().replace("\"name\":\"Ada\"", "\"name\":\"Ada\",\"ownerId\":99")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mockMvc.perform(post("/api/deals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dealBody().replace("\"value\":1200.00", "\"value\":1200.00,\"actualValue\":900.00")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mockMvc.perform(post("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyBody().replace("\"templateVersion\":1", "\"templateVersion\":1,\"unknown\":true")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));

        verify(service, never()).createPerson(any());
        verify(service, never()).createDeal(any());
        verify(service, never()).createCompany(any());
    }

    @Test
    void malformedTypesAndNullContainerElementsFailBeforeService() throws Exception {
        mockMvc.perform(post("/api/deals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dealBody().replace("\"calendar\"", "\"not-an-entry-point\"")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
        mockMvc.perform(post("/api/persons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody().replace("\"tagIds\":[]", "\"tagIds\":[null]")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(service, never()).createDeal(any());
        verify(service, never()).createPerson(any());
    }

    @Test
    void duplicateConflictUsesGuidedCodedResponseShape() throws Exception {
        when(service.createPerson(any())).thenThrow(new DuplicateReviewException(
            "DUPLICATE_REVIEW_REQUIRED",
            "Possible duplicates must be reviewed before creation"));

        mockMvc.perform(post("/api/persons")
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW_REQUIRED"))
            .andExpect(jsonPath("$.message")
                .value("Possible duplicates must be reviewed before creation"))
            .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    private static String personBody() {
        return """
            {"record":{"name":"Ada","email":"ada@example.com"},
             "templateUse":{"templateId":"system:person:standard","templateVersion":1,
               "templateSetRevision":0,"entryPoint":"record_detail","context":{"relatedCompanyId":44}},
             "customFields":{"9":"VIP"},"tagIds":[]}
            """;
    }

    private static String companyBody() {
        return """
            {"record":{"name":"Analytical Engines"},
             "templateUse":{"templateId":"system:company:standard","templateVersion":1,
               "templateSetRevision":0,"entryPoint":"quick_create","context":{"relatedCompanyId":null}},
             "customFields":{},"tagIds":[]}
            """;
    }

    private static String dealBody() {
        return """
            {"record":{"name":"Expansion","value":1200.00,"currency":"USD","pipeline":4,"stage":5},
             "templateUse":{"templateId":"system:deal:standard","templateVersion":1,
               "templateSetRevision":0,"entryPoint":"calendar","context":{"relatedCompanyId":null}},
             "customFields":{},"tagIds":[7]}
            """;
    }
}
