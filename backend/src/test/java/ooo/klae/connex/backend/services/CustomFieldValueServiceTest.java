package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;

class CustomFieldValueServiceTest extends AbstractServiceTest {

    @Autowired CustomFieldDefinitionService definitionService;
    @Autowired CustomFieldValueService valueService;
    @Autowired CompanyService companyService;
    @Autowired CustomFieldValueMapper valueMapper;

    @Test
    void getForEntity_listsEveryFieldEvenUnfilled() {
        CustomFieldDefinition def = companyField("tier", "text", null);
        Company company = newCompany();

        List<CustomFieldEntryDto> entries = valueService.getForEntity("company", company.getId());

        assertTrue(entries.stream().anyMatch(e -> e.getDefinitionId() == def.getId() && e.getValue() == null));
    }

    @Test
    void applyValues_coercesEachType() {
        Company company = newCompany();
        CustomFieldDefinition text = companyField("t", "text", null);
        CustomFieldDefinition number = companyField("n", "number", null);
        CustomFieldDefinition date = companyField("d", "date", null);
        CustomFieldDefinition bool = companyField("b", "boolean", null);
        CustomFieldDefinition select = companyField("s", "select",
            List.of(new CustomFieldOption("hot", "Hot"), new CustomFieldOption("cold", "Cold")));

        valueService.applyValues("company", company.getId(), Map.of(
            text.getId(), "Gold", number.getId(), "42.5", date.getId(), "2026-06-26",
            bool.getId(), true, select.getId(), "hot"));

        List<CustomFieldEntryDto> entries = valueService.getForEntity("company", company.getId());
        assertEquals("Gold", value(entries, text.getId()));
        assertEquals(0, new BigDecimal("42.5").compareTo((BigDecimal) value(entries, number.getId())));
        assertEquals("2026-06-26", value(entries, date.getId()));
        assertEquals(true, value(entries, bool.getId()));
        assertEquals("hot", value(entries, select.getId()));
    }

    @Test
    void applyValues_isPartial_doesNotWipeOmittedFields() {
        Company company = newCompany();
        CustomFieldDefinition a = companyField("a", "text", null);
        CustomFieldDefinition b = companyField("b", "text", null);
        valueService.applyValues("company", company.getId(), Map.of(a.getId(), "AA", b.getId(), "BB"));

        valueService.applyValues("company", company.getId(), Map.of(a.getId(), "changed"));

        List<CustomFieldEntryDto> entries = valueService.getForEntity("company", company.getId());
        assertEquals("changed", value(entries, a.getId()));
        assertEquals("BB", value(entries, b.getId()));
    }

    @Test
    void applyValues_rejectsBadValues() {
        Company company = newCompany();
        CustomFieldDefinition number = companyField("n", "number", null);
        CustomFieldDefinition date = companyField("d", "date", null);
        CustomFieldDefinition select = companyField("s", "select", List.of(new CustomFieldOption("hot", "Hot")));

        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of(number.getId(), "notnum")));
        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of(date.getId(), "31/12/2026")));
        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of(select.getId(), "unknown")));
    }

    @Test
    void applyValues_clearingRequiredField_rejected() {
        Company company = newCompany();
        CustomFieldDefinition required = companyField("must", "text", null, true);
        valueService.applyValue("company", company.getId(), required.getId(), "set");

        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of(required.getId(), "")));
    }

    @Test
    void applyValues_omittingRequiredField_doesNotBlockOtherSaves() {
        Company company = newCompany();
        companyField("must", "text", null, true);
        CustomFieldDefinition other = companyField("other", "text", null);

        valueService.applyValues("company", company.getId(), Map.of(other.getId(), "ok"));

        assertEquals("ok", value(valueService.getForEntity("company", company.getId()), other.getId()));
    }

    @Test
    void applyValues_rejectsUnknownDefinition() {
        Company company = newCompany();
        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of(-1, "x")));
    }

    @Test
    void applyValue_setsAndClearsSingleField() {
        Company company = newCompany();
        CustomFieldDefinition def = companyField("tier", "text", null);

        valueService.applyValue("company", company.getId(), def.getId(), "Gold");
        assertEquals("Gold", value(valueService.getForEntity("company", company.getId()), def.getId()));

        valueService.applyValue("company", company.getId(), def.getId(), "");
        assertNull(value(valueService.getForEntity("company", company.getId()), def.getId()));
    }

    @Test
    void applyValue_clearingRequiredField_rejected() {
        Company company = newCompany();
        CustomFieldDefinition required = companyField("must", "text", null, true);

        assertThrows(BadRequestException.class,
            () -> valueService.applyValue("company", company.getId(), required.getId(), ""));
    }

    @Test
    void applyValue_booleanFalseRoundTripsAndIsDistinctFromUnset() {
        Company company = newCompany();
        CustomFieldDefinition flag = companyField("flag", "boolean", null);

        valueService.applyValue("company", company.getId(), flag.getId(), false);
        assertEquals(false, value(valueService.getForEntity("company", company.getId()), flag.getId()));

        valueService.applyValue("company", company.getId(), flag.getId(), "");
        assertNull(value(valueService.getForEntity("company", company.getId()), flag.getId()));
    }

    @Test
    void applyValue_numberRoundsToScaleAndRejectsTooLarge() {
        Company company = newCompany();
        CustomFieldDefinition number = companyField("n", "number", null);

        valueService.applyValue("company", company.getId(), number.getId(), "42.123456");
        assertEquals(0, new BigDecimal("42.1235")
            .compareTo((BigDecimal) value(valueService.getForEntity("company", company.getId()), number.getId())));

        assertThrows(BadRequestException.class,
            () -> valueService.applyValue("company", company.getId(), number.getId(), "123456789012345678901"));
    }

    @Test
    void applyValue_rejectsPathologicalNumberExponentsCheaply() {
        Company company = newCompany();
        CustomFieldDefinition number = companyField("n", "number", null);
        assertThrows(BadRequestException.class,
            () -> valueService.applyValue("company", company.getId(), number.getId(), "1E2000000000"));
        assertThrows(BadRequestException.class,
            () -> valueService.applyValue("company", company.getId(), number.getId(), "1E-2000000000"));
    }

    @Test
    void applyValue_urlValidation() {
        Company company = newCompany();
        CustomFieldDefinition url = companyField("site", "url", null);

        valueService.applyValue("company", company.getId(), url.getId(), "https://example.com");
        assertEquals("https://example.com",
            value(valueService.getForEntity("company", company.getId()), url.getId()));

        assertThrows(BadRequestException.class,
            () -> valueService.applyValue("company", company.getId(), url.getId(), "not-a-url"));
    }

    @Test
    void getForEntities_groupsValuesByEntity() {
        CustomFieldDefinition def = companyField("tier", "text", null);
        Company a = newCompany();
        Company b = newCompany();
        valueService.applyValue("company", a.getId(), def.getId(), "Gold");
        valueService.applyValue("company", b.getId(), def.getId(), "Silver");

        Map<Integer, Map<Integer, Object>> values =
            valueService.getForEntities("company", List.of(a.getId(), b.getId()));

        assertEquals("Gold", values.get(a.getId()).get(def.getId()));
        assertEquals("Silver", values.get(b.getId()).get(def.getId()));
    }

    @Test
    void companyService_updateFieldThenDeleteCleansUp() {
        CustomFieldDefinition def = companyField("tier", "text", null);
        Company company = newCompany();

        companyService.updateCustomField(company.getId(), def.getId(), "Gold");
        assertEquals("Gold", value(companyService.getCustomFields(company.getId()), def.getId()));

        companyService.deleteCompany(company.getId());
        assertTrue(valueMapper.getForEntity(workspace.getId(), "company", company.getId()).stream()
            .noneMatch(v -> v.getValueText() != null));
    }

    @Test
    void companyService_customFieldMethodsGuardInvisibleRecord() {
        CustomFieldDefinition def = companyField("tier", "text", null);
        assertThrows(ResourceNotFoundException.class, () -> companyService.getCustomFields(-1));
        assertThrows(ResourceNotFoundException.class, () -> companyService.updateCustomFields(-1, Map.of()));
        assertThrows(ResourceNotFoundException.class, () -> companyService.updateCustomField(-1, def.getId(), "x"));
    }

    private Object value(List<CustomFieldEntryDto> entries, int definitionId) {
        return entries.stream().filter(e -> e.getDefinitionId() == definitionId).findFirst().orElseThrow().getValue();
    }

    private CustomFieldDefinition companyField(String key, String type, List<CustomFieldOption> options) {
        return companyField(key, type, options, false);
    }

    private CustomFieldDefinition companyField(String key, String type, List<CustomFieldOption> options, boolean required) {
        CustomFieldDefinition def = new CustomFieldDefinition();
        def.setEntityType("company");
        def.setFieldType(type);
        def.setFieldKey(key);
        def.setLabel(key);
        def.setRequired(required);
        return definitionService.create(def, options);
    }
}
