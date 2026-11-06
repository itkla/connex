package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.exceptions.ImportValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.exceptions.ConflictException;

class ImportReviewServiceTest extends AbstractServiceTest {
    @Autowired ImportService importService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired PersonService personService;

    private Company company(String name) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(name);
        companyMapper.insert(company);
        return company;
    }

    private ImportRequest contacts(String companyName) {
        return new ImportRequest(List.of(
            Map.of("Name", "First " + unique(), "Company", companyName),
            Map.of("Name", "Second " + unique(), "Company", companyName)),
            List.of(new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Company", "company", null, null, null)), "fill_empty", null);
    }

    @Test
    void ambiguousCompanyChoicesArePerRowAndProofBound() {
        String name = "Company choice " + unique();
        Company first = company(name);
        Company second = company(name);
        ImportRequest request = contacts(name);
        ImportPreviewResult unresolved = importService.previewPersons(request);
        assertEquals(2, unresolved.getInvalid());
        assertEquals(2, unresolved.getCompanyCandidates().get(unresolved.getRows().getLast().getCompanyDependency().key()).size());
        assertEquals("COMPANY_CHOICE_REQUIRED", unresolved.getRows().getFirst().getProblems().getFirst().code());

        request.setCompanyLinks(Map.of(0, first.getId()));
        ImportPreviewResult oneChoice = importService.previewPersons(request);
        assertEquals(1, oneChoice.getInvalid());
        request.setCompanyLinks(Map.of(0, first.getId(), 1, second.getId()));
        request.setDuplicateReviewProof(oneChoice.getDuplicateReviewProof());
        assertThrows(ConflictException.class, () -> importService.commitPersons(request));
    }

    @Test
    void reviewedBulkChoiceLinksEverySourceRowToTheChosenCompany() {
        String name = "Bulk company " + unique();
        company(name);
        Company selected = company(name);
        ImportRequest request = contacts(name);
        request.setCompanyLinks(Map.of(0, selected.getId(), 1, selected.getId()));
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(0, preview.getInvalid());
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult result = importService.commitPersons(request);
        assertEquals(2, result.getCreated());
        assertTrue(result.getFailed().isEmpty());
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND company_id = ?",
            Integer.class, workspace.getId(), selected.getId()));
        assertThrows(ConflictException.class, () -> importService.commitPersons(request));
    }

    @Test
    void sameEmailCannotSilentlyChooseBetweenConflictingCompanySelections() {
        String name = "Conflict company " + unique();
        Company first = company(name);
        Company second = company(name);
        ImportRequest request = contacts(name);
        String email = unique() + "@example.test";
        request.setRows(List.of(Map.of("Name", "First", "Company", name, "Email", email),
            Map.of("Name", "Second", "Company", name, "Email", email)));
        request.setMapping(List.of(new ColumnMapping("Name", "name", null, null, null),
            new ColumnMapping("Company", "company", null, null, null),
            new ColumnMapping("Email", "email", null, null, null)));
        request.setCompanyLinks(Map.of(0, first.getId(), 1, second.getId()));
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(2, preview.getInvalid());
        assertEquals("COMPANY_CHOICES_CONFLICT", preview.getRows().getFirst().getProblems().getFirst().code());
        request.setSkippedRows(Set.of(1));
        preview = importService.previewPersons(request);
        assertEquals(0, preview.getInvalid());
        assertEquals(1, preview.getToSkip());
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult result = importService.commitPersons(request);
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getSkipped());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignCompanyIsNeitherShownNorAcceptedAsAChoice(boolean sameOrganization) {
        String name = "Private company " + unique();
        Company visible = company(name);
        Workspace other = new Workspace();
        other.setName("Private " + unique());
        other.setSlug("private-" + unique());
        if (sameOrganization) other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(other);
        Company foreign = new Company();
        foreign.setWorkspaceId(other.getId());
        foreign.setName(name);
        companyMapper.insert(foreign);
        ImportRequest request = contacts(name);
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(List.of(visible.getId()), preview.getCompanyCandidates().get(preview.getRows().getFirst().getCompanyDependency().key())
            .stream().map(candidate -> candidate.recordId()).toList());
        request.setCompanyLinks(Map.of(0, foreign.getId()));
        preview = importService.previewPersons(request);
        assertEquals("COMPANY_CHOICE_UNAVAILABLE", preview.getRows().getFirst().getProblems().getFirst().code());
        assertFalse(preview.getCompanyCandidates().get(preview.getRows().getFirst().getCompanyDependency().key()).stream()
            .anyMatch(candidate -> candidate.recordId() == foreign.getId()));
    }

    @Test
    void changingASkipChoiceOrAnInlineValueRequiresANewProof() {
        ImportRequest request = contacts("New company " + unique());
        request.setRows(List.of(Map.of("Name", "", "Company", "Ignored dependency " + unique()),
            Map.of("Name", "Kept " + unique())));
        request.setSkippedRows(Set.of(0));
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals("skip", preview.getRows().getFirst().getStatus());
        assertNotNull(preview.getDuplicateReviewProof());
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        request.setSkippedRows(Set.of());
        assertThrows(ConflictException.class, () -> importService.commitPersons(request));
    }

    @Test
    void selectedCompanyDisappearingInvalidatesTheReviewedImport() {
        String name = "Vanishing company " + unique();
        Company selected = company(name);
        ImportRequest request = contacts(name);
        request.setCompanyLinks(Map.of(0, selected.getId(), 1, selected.getId()));
        request.setDuplicateReviewProof(importService.previewPersons(request).getDuplicateReviewProof());
        jdbcTemplate.update("UPDATE company SET archived_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), selected.getId());
        assertThrows(ConflictException.class, () -> importService.commitPersons(request));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND name IN (?, ?)", Integer.class,
            workspace.getId(), request.getRows().getFirst().get("Name"), request.getRows().getLast().get("Name")));
    }
    @Test
    void combinedRowsRequireEveryConsumedCompanyChoiceToBeExplicit() {
        String name = "Combined choice " + unique();
        Company selected = company(name);
        ImportRequest request = contacts(name);
        String email = unique() + "@example.test";
        request.setRows(List.of(Map.of("Name", "First", "Company", name, "Email", email),
            Map.of("Name", "Second", "Company", name, "Email", email)));
        request.setMapping(List.of(new ColumnMapping("Name", "name", null, null, null),
            new ColumnMapping("Company", "company", null, null, null),
            new ColumnMapping("Email", "email", null, null, null)));
        request.setCompanyLinks(Map.of(0, selected.getId()));
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(2, preview.getInvalid());
        request.setCompanyLinks(Map.of(0, selected.getId(), 1, selected.getId()));
        preview = importService.previewPersons(request);
        assertEquals(0, preview.getInvalid());
        assertEquals(1, preview.getToCreate());
        assertEquals(1, preview.getCompanyCandidates().size());
        assertEquals(1, preview.getCompanyCandidates().values().stream().mapToInt(List::size).sum());
    }

    @ParameterizedTest
    @ValueSource(strings = {"skip", "fill_empty"})
    void unusedCompanyChoicesDoNotInvalidateCombinedMatches(String action) {
        String name = "Unused choices " + unique();
        Company first = company(name);
        Company second = company(name);
        Person person = new Person();
        person.setName("Existing " + unique());
        person.setEmail(unique() + "@example.test");
        person.setCompany(first);
        Person existing = personService.create(person);
        ImportRequest request = contacts(name);
        request.setRows(List.of(Map.of("Name", "First", "Company", name, "Email", existing.getEmail()),
            Map.of("Name", "Second", "Company", name, "Email", existing.getEmail())));
        request.setMapping(List.of(new ColumnMapping("Name", "name", null, null, null),
            new ColumnMapping("Company", "company", null, null, null),
            new ColumnMapping("Email", "email", null, null, null)));
        request.setOnDuplicate(action);
        request.setCompanyLinks(Map.of(0, first.getId(), 1, second.getId()));
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(0, preview.getInvalid());
    }

    @Test
    void duplicateResolvedCustomTargetsFailDuringPreview() {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setEntityType("person");
        definition.setFieldKey("region");
        definition.setLabel("Region");
        definition.setFieldType("text");
        customFieldDefinitionMapper.insert(definition);
        ImportRequest request = contacts("Unused " + unique());
        request.setMapping(List.of(new ColumnMapping("Name", "name", null, null, null),
            new ColumnMapping("Region", "custom:" + definition.getId(), null, null, null),
            new ColumnMapping("Legacy", null, true, "text", "Region")));
        ImportValidationException exception = assertThrows(ImportValidationException.class,
            () -> importService.previewPersons(request));
        assertEquals("IMPORT_MAPPING_DUPLICATE", exception.getCode());
        request.setMapping(List.of(new ColumnMapping("Name", "name", null, null, null),
            new ColumnMapping("Region", "custom:" + definition.getId(), null, null, null),
            new ColumnMapping("Legacy", "custom:" + definition.getId(), null, null, null)));
        assertEquals("IMPORT_MAPPING_DUPLICATE", assertThrows(ImportValidationException.class,
            () -> importService.previewPersons(request)).getCode());
    }

    @Test
    void archivingAMappedCustomFieldRefusesTheCommitInsteadOfDroppingValues() {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setEntityType("person");
        definition.setFieldKey("kept_region");
        definition.setLabel("Kept region");
        definition.setFieldType("text");
        customFieldDefinitionMapper.insert(definition);
        ImportRequest request = new ImportRequest(List.of(Map.of("Name", "Field contact " + unique(), "Region", "West")),
            List.of(new ColumnMapping("Name", "name", null, null, null),
                new ColumnMapping("Region", "custom:" + definition.getId(), null, null, null)), "fill_empty", null);
        request.setDuplicateReviewProof(importService.previewPersons(request).getDuplicateReviewProof());
        jdbcTemplate.update("UPDATE custom_field_definition SET archived = TRUE WHERE workspace_id = ? AND id = ?",
            workspace.getId(), definition.getId());
        assertEquals("IMPORT_CUSTOM_UNAVAILABLE", assertThrows(ImportValidationException.class,
            () -> importService.commitPersons(request)).getCode());
    }

    @Test
    void editingRawValuesRequiresANewProof() {
        ImportRequest request = contacts("Inline company " + unique());
        request.setDuplicateReviewProof(importService.previewPersons(request).getDuplicateReviewProof());
        request.setRows(List.of(Map.of("Name", "Corrected " + unique())));
        assertThrows(ConflictException.class, () -> importService.commitPersons(request));
    }

    @Test
    void selectingACandidateCannotAcknowledgeATruncatedCompanySet() {
        String name = "Truncated company " + unique();
        Company selected = company(name);
        for (int index = 0; index < 8; index++) company(name);
        ImportRequest request = contacts(name);
        request.setCompanyLinks(Map.of(0, selected.getId(), 1, selected.getId()));
        ImportPreviewResult preview = importService.previewPersons(request);
        assertEquals(2, preview.getInvalid());
        assertTrue(preview.getRows().getFirst().getCompanyDependency().truncated());
        assertEquals("COMPANY_TRUNCATED", preview.getRows().getFirst().getProblems().getFirst().code());
    }

}
