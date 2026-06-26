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
    void applyValues_emptyClearsTheValue() {
        Company company = newCompany();
        CustomFieldDefinition def = companyField("tier", "text", null);
        valueService.applyValues("company", company.getId(), Map.of(def.getId(), "Gold"));

        valueService.applyValues("company", company.getId(), Map.of(def.getId(), ""));

        assertNull(value(valueService.getForEntity("company", company.getId()), def.getId()));
    }

    @Test
    void applyValues_rejectsUnknownDefinition() {
        Company company = newCompany();
        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of(-1, "x")));
    }

    @Test
    void applyValues_enforcesRequired() {
        Company company = newCompany();
        companyField("must", "text", null, true);

        assertThrows(BadRequestException.class,
            () -> valueService.applyValues("company", company.getId(), Map.of()));
    }

    @Test
    void companyService_updateAndReadThenDeleteCleansUp() {
        CustomFieldDefinition def = companyField("tier", "text", null);
        Company company = newCompany();

        companyService.updateCustomFields(company.getId(), Map.of(def.getId(), "Gold"));
        assertEquals("Gold", value(companyService.getCustomFields(company.getId()), def.getId()));

        companyService.deleteCompany(company.getId());
        boolean anyValue = valueMapper.getForEntity(workspace.getId(), "company", company.getId()).stream()
            .anyMatch(v -> v.getValueText() != null);
        assertTrue(!anyValue);
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
