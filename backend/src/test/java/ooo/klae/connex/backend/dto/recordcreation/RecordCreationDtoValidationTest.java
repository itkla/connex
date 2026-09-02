package ooo.klae.connex.backend.dto.recordcreation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldValueType;
import ooo.klae.connex.backend.recordcreation.RecordCreationImpactOperation;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecordCreationDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void requiredCollectionsRejectNull() {
        RecordCreationTemplateDefinitionDto definition =
            new RecordCreationTemplateDefinitionDto(1, null);
        RecordCreationTemplateGroupDto group = new RecordCreationTemplateGroupDto(
            "basics", new LocalizedTextDto("Basics", "基本情報"), null, null);

        assertFalse(validator.validate(definition).isEmpty());
        assertFalse(validator.validate(group).isEmpty());
    }

    @Test
    void nestedAdminRequestValidationIsExact() {
        RecordCreationTemplateCreateRequestDto invalid =
            new RecordCreationTemplateCreateRequestDto(
                null,
                new LocalizedTextDto(null, null),
                null,
                new RecordCreationTemplateDefinitionDto(1, List.of()),
                false,
                -1);

        var paths = validator.validate(invalid).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .toList();

        assertTrue(paths.contains("recordType"));
        assertTrue(paths.contains("name.en"));
        assertTrue(paths.contains("name.ja"));
        assertTrue(paths.contains("expectedSetRevision"));
    }

    @Test
    void enumSerializationUsesContractWireValues() throws Exception {
        assertEquals("\"person\"", objectMapper.writeValueAsString(RecordCreationRecordType.person));
        assertEquals("\"boolean\"", objectMapper.writeValueAsString(RecordCreationFieldValueType.BOOLEAN));
    }

    @Test
    void dateAndDecimalDefaultPayloadsRoundTrip() throws Exception {
        RecordCreationDefaultSpecDto number = new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_number,
            null,
            new BigDecimal("12.3400"),
            null,
            null,
            null,
            null);
        RecordCreationDefaultSpecDto date = new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_date,
            null,
            null,
            null,
            LocalDate.of(2026, 8, 31),
            null,
            null);

        RecordCreationDefaultSpecDto numberRoundTrip = objectMapper.readValue(
            objectMapper.writeValueAsString(number), RecordCreationDefaultSpecDto.class);
        RecordCreationDefaultSpecDto dateRoundTrip = objectMapper.readValue(
            objectMapper.writeValueAsString(date), RecordCreationDefaultSpecDto.class);

        assertEquals(number.numberValue(), numberRoundTrip.numberValue());
        assertEquals(date.dateValue(), dateRoundTrip.dateValue());
    }

    @Test
    void literalReferenceListCarriesTheContractLimit() {
        RecordCreationDefaultSpecDto tooMany = new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_references,
            null,
            null,
            null,
            null,
            null,
            java.util.stream.IntStream.rangeClosed(1, 21).boxed().toList());

        assertFalse(validator.validate(tooMany).isEmpty());
    }

    @Test
    void referenceAndImpactListsRejectInvalidElements() {
        List<Integer> referenceIds = new java.util.ArrayList<>(List.of(4));
        referenceIds.add(null);
        RecordCreationDefaultSpecDto defaults = new RecordCreationDefaultSpecDto(
            RecordCreationDefaultKind.literal_references,
            null, null, null, null, null, referenceIds);
        List<String> removedFieldKeys = new java.util.ArrayList<>(List.of("owner"));
        removedFieldKeys.add(null);
        RecordCreationImpactRequestDto impact = new RecordCreationImpactRequestDto(
            RecordCreationImpactOperation.remove_fields,
            RecordCreationRecordType.person,
            "workspace:42",
            removedFieldKeys,
            2,
            7);

        assertTrue(validator.validate(defaults).stream()
            .anyMatch(violation -> violation.getPropertyPath().toString()
                .equals("referenceIds[1].<list element>")));
        assertTrue(validator.validate(impact).stream()
            .anyMatch(violation -> violation.getPropertyPath().toString()
                .equals("removedFieldKeys[1].<list element>")));
    }

    @Test
    void guidedRequestsRequireNestedTemplateAndNonNullContainers() {
        GuidedPersonCreateRequestDto invalid = new GuidedPersonCreateRequestDto(
            null, null, null, null);

        var paths = validator.validate(invalid).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .toList();

        assertTrue(paths.contains("record"));
        assertTrue(paths.contains("templateUse"));
        assertTrue(paths.contains("customFields"));
        assertTrue(paths.contains("tagIds"));
    }

    @Test
    void guidedContainersRejectNullAndInvalidElements() {
        Map<Integer, JsonNode> customFields = new LinkedHashMap<>();
        customFields.put(0, objectMapper.valueToTree("invalid"));
        customFields.put(4, null);
        List<Integer> tagIds = new ArrayList<>(List.of(3));
        tagIds.add(null);
        GuidedCompanyCreateRequestDto request = new GuidedCompanyCreateRequestDto(
            new GuidedCompanyRecordDto("Connex", null, null, null, null, null),
            templateUse(RecordCreationRecordType.company),
            customFields,
            tagIds);

        var paths = validator.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .toList();

        assertTrue(paths.stream().anyMatch(path -> path.startsWith("customFields")));
        assertTrue(paths.stream().anyMatch(path -> path.startsWith("tagIds")));
    }

    @Test
    void guidedDealUsesLocalDateAndThirteenByTwoValueBounds() throws Exception {
        GuidedDealCreateRequestDto valid = objectMapper.readValue("""
            {"record":{"name":"Renewal","value":9999999999999.99,"currency":"USD",
              "pipeline":2,"stage":3,"expectedCloseDate":"2026-12-31"},
             "templateUse":{"templateId":"system:deal:standard","templateVersion":1,
              "templateSetRevision":0,"entryPoint":"calendar","context":{"relatedCompanyId":null}},
             "customFields":{},"tagIds":[]}
            """, GuidedDealCreateRequestDto.class);
        GuidedDealCreateRequestDto invalid = new GuidedDealCreateRequestDto(
            new GuidedDealRecordDto(
                "Renewal", new BigDecimal("10000000000000.001"), "USD",
                2, 3, null, LocalDate.parse("2026-12-31"), null),
            templateUse(RecordCreationRecordType.deal),
            Map.of(),
            List.of());

        assertTrue(validator.validate(valid).isEmpty());
        assertEquals(LocalDate.parse("2026-12-31"), valid.record().expectedCloseDate());
        assertTrue(validator.validate(invalid).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("record.value")));
    }

    @Test
    void duplicateReviewTokensAreAcceptedOnlyAsWriteOnlyInput() throws Exception {
        String proof = "a".repeat(64);
        GuidedPersonRecordDto person = objectMapper.readValue(
            """
            {"name":"Ada","duplicateReviewToken":"%s"}
            """.formatted(proof),
            GuidedPersonRecordDto.class);
        GuidedCompanyRecordDto company = objectMapper.readValue(
            """
            {"name":"Connex","duplicateReviewToken":"%s"}
            """.formatted(proof),
            GuidedCompanyRecordDto.class);
        GuidedDealRecordDto deal = objectMapper.readValue(
            """
            {"name":"Renewal","value":1.00,"currency":"USD","pipeline":2,"stage":3,
             "duplicateReviewToken":"%s"}
            """.formatted(proof), GuidedDealRecordDto.class);

        assertEquals(proof, person.duplicateReviewToken());
        assertEquals(proof, company.duplicateReviewToken());
        assertEquals(proof, deal.duplicateReviewToken());
        assertFalse(objectMapper.writeValueAsString(person).contains("duplicateReviewToken"));
        assertFalse(objectMapper.writeValueAsString(company).contains("duplicateReviewToken"));
        assertFalse(objectMapper.writeValueAsString(deal).contains("duplicateReviewToken"));
    }

    @Test
    void safeGuidedRecordsExposeNoOwnerOrDealOutcomeFields() {
        var personComponents = java.util.Arrays.stream(GuidedPersonRecordDto.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
        var companyComponents = java.util.Arrays.stream(GuidedCompanyRecordDto.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
        var dealComponents = java.util.Arrays.stream(GuidedDealRecordDto.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();

        assertFalse(personComponents.contains("ownerId"));
        assertFalse(companyComponents.contains("ownerId"));
        assertFalse(dealComponents.contains("ownerId"));
        assertFalse(dealComponents.contains("actualValue"));
        assertFalse(dealComponents.contains("won"));
        assertFalse(dealComponents.contains("closedAt"));
    }

    private static RecordCreationTemplateUseDto templateUse(RecordCreationRecordType type) {
        return new RecordCreationTemplateUseDto(
            "system:" + type.name() + ":standard",
            1,
            0,
            RecordCreationEntryPoint.quick_create,
            new RecordCreationContextDto(null));
    }
}
