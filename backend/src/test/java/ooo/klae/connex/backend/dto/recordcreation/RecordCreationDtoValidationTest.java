package ooo.klae.connex.backend.dto.recordcreation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldValueType;
import ooo.klae.connex.backend.recordcreation.RecordCreationImpactOperation;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
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
}
