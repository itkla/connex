package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedCompanyRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedDealRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.recordcreation.RecordCreationAugmentation;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultOrigin;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldSource;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldValueType;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.tenant.Permission;

class GuidedRecordCreationServiceTest {
    private final PersonService personService = mock(PersonService.class);
    private final CompanyService companyService = mock(CompanyService.class);
    private final DealService dealService = mock(DealService.class);
    private final RecordCreationAugmentationService augmentationService =
        mock(RecordCreationAugmentationService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private GuidedRecordCreationService service;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        service = new GuidedRecordCreationService(
            personService, companyService, dealService, augmentationService, workspaceService);
    }

    @Test
    void personMaterializesActiveDefaultsAndDelegatesCustomFieldsAndTagsAtomically() {
        RecordCreationTemplateUseDto use = use(RecordCreationRecordType.person);
        ResolvedCreationTemplateDto resolved = resolved(
            RecordCreationRecordType.person,
            field("name", null, true, null),
            field("company", null, false, node(44)),
            field("custom:9", 9, true, node("default")),
            field("tags", null, true, array(6, 4)));
        when(augmentationService.resolvePreliminary(RecordCreationRecordType.person, use))
            .thenReturn(resolved);
        when(personService.createReviewed(any(), eq("proof"), any())).thenAnswer(invocation ->
            invocation.getArgument(0));
        GuidedPersonCreateRequestDto request = new GuidedPersonCreateRequestDto(
            new GuidedPersonRecordDto(
                "Ada", "ada@example.com", null, null, null, null, null, null, "proof"),
            use,
            Map.of(),
            List.of());

        Person created = service.createPerson(request);

        assertEquals(44, created.getCompany().getId());
        ArgumentCaptor<RecordCreationAugmentation> augmentation =
            ArgumentCaptor.forClass(RecordCreationAugmentation.class);
        verify(personService).createReviewed(any(), eq("proof"), augmentation.capture());
        assertEquals("default", augmentation.getValue().customFields().get(9).textValue());
        assertEquals(List.of(6, 4), augmentation.getValue().tagIds());
        verify(workspaceService).lockAndRequirePermissions(
            7, Map.of(11, Set.of(Permission.PERSON_CREATE)));
    }

    @Test
    void companyAndDealPreserveCanonicalSafeFieldsAndDelegateToCanonicalServices() {
        RecordCreationTemplateUseDto companyUse = use(RecordCreationRecordType.company);
        RecordCreationTemplateUseDto dealUse = use(RecordCreationRecordType.deal);
        when(augmentationService.resolvePreliminary(RecordCreationRecordType.company, companyUse))
            .thenReturn(resolved(RecordCreationRecordType.company, field("name", null, true, null)));
        when(augmentationService.resolvePreliminary(RecordCreationRecordType.deal, dealUse))
            .thenReturn(resolved(
                RecordCreationRecordType.deal,
                field("name", null, true, null),
                field("value", null, true, null),
                field("currency", null, true, null),
                field("pipeline", null, true, null),
                field("stage", null, true, null)));
        when(companyService.createCompanyReviewed(any(), eq(null), any())).thenAnswer(invocation ->
            invocation.getArgument(0));
        when(dealService.createReviewed(any(), eq(null), any())).thenAnswer(invocation ->
            invocation.getArgument(0));

        Company company = service.createCompany(new GuidedCompanyCreateRequestDto(
            new GuidedCompanyRecordDto("Connex", "https://connex.example", "CRM", null, null, null),
            companyUse, Map.of(), List.of(3)));
        Deal deal = service.createDeal(new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(
                "Renewal", new BigDecimal("12.34"), "USD", 8, 9, 10,
                LocalDate.parse("2026-12-31"), null),
            dealUse, Map.of(), List.of()));

        assertEquals("https://connex.example", company.getWebsite());
        assertEquals(new BigDecimal("12.34"), deal.getValue());
        assertEquals(new BigDecimal("0.00"), deal.getActualValue());
        assertEquals("2026-12-31", deal.getExpectedCloseDate());
        verify(companyService).createCompanyReviewed(any(), eq(null), any());
        verify(dealService).createReviewed(any(), eq(null), any());
    }

    @Test
    void missingTemplateRequiredValueStopsBeforeCanonicalCreation() {
        RecordCreationTemplateUseDto use = use(RecordCreationRecordType.person);
        when(augmentationService.resolvePreliminary(RecordCreationRecordType.person, use))
            .thenReturn(resolved(
                RecordCreationRecordType.person,
                field("name", null, true, null),
                field("title", null, true, null)));
        GuidedPersonCreateRequestDto request = new GuidedPersonCreateRequestDto(
            new GuidedPersonRecordDto("Ada", null, null, null, null, null, null, null, null),
            use, Map.of(), List.of());

        RecordCreationTemplateException exception = assertThrows(
            RecordCreationTemplateException.class,
            () -> service.createPerson(request));

        assertEquals("TEMPLATE_FIELD_NOT_SUBMITTED", exception.error().code());
        verify(personService, never()).createReviewed(any(), any(), any());
    }

    @Test
    void lockedPermissionCheckPrecedesTemplateAndCanonicalWork() {
        RecordCreationTemplateUseDto use = use(RecordCreationRecordType.deal);
        when(augmentationService.resolvePreliminary(RecordCreationRecordType.deal, use))
            .thenReturn(resolved(RecordCreationRecordType.deal));
        GuidedDealCreateRequestDto request = new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(
                "Deal", BigDecimal.ZERO, "USD", 1, 2, null, null, null),
            use, Map.of(), List.of());

        service.createDeal(request);

        var order = inOrder(workspaceService, augmentationService, dealService);
        order.verify(workspaceService).lockAndRequirePermissions(
            7, Map.of(11, Set.of(Permission.DEAL_CREATE)));
        order.verify(augmentationService).resolvePreliminary(RecordCreationRecordType.deal, use);
        order.verify(dealService).createReviewed(any(), eq(null), any());
    }

    private RecordCreationTemplateUseDto use(RecordCreationRecordType type) {
        return new RecordCreationTemplateUseDto(
            "system:" + type.name() + ":standard",
            1,
            0,
            RecordCreationEntryPoint.quick_create,
            new RecordCreationContextDto(null));
    }

    private static ResolvedCreationTemplateDto resolved(
            RecordCreationRecordType type,
            ResolvedCreationFieldDto... fields) {
        return new ResolvedCreationTemplateDto(
            "system:" + type.name() + ":standard",
            type,
            true,
            1,
            new LocalizedTextDto("Standard", "標準"),
            null,
            RecordCreationTemplateAvailability.available,
            List.of(new ResolvedCreationGroupDto(
                "basics", new LocalizedTextDto("Basics", "基本"), null, List.of(fields))),
            List.of());
    }

    private static ResolvedCreationFieldDto field(
            String key,
            Integer customId,
            boolean required,
            JsonNode defaultValue) {
        return new ResolvedCreationFieldDto(
            key,
            customId == null ? RecordCreationFieldSource.system : RecordCreationFieldSource.custom,
            customId,
            customId == null ? RecordCreationFieldValueType.text : RecordCreationFieldValueType.text,
            null,
            new LocalizedTextDto(key, key),
            null,
            null,
            required,
            required,
            false,
            defaultValue,
            defaultValue == null ? null : RecordCreationDefaultOrigin.template,
            List.of());
    }

    private JsonNode node(Object value) {
        return objectMapper.valueToTree(value);
    }

    private JsonNode array(int... values) {
        return objectMapper.valueToTree(values);
    }
}
