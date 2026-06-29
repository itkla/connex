package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;

class ImportServiceTest extends AbstractServiceTest {

    @Autowired ImportService importService;
    @Autowired ExportService exportService;
    @Autowired CustomFieldValueService customFieldValueService;
    @Autowired CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;

    private static ColumnMapping map(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private static ImportRequest req(List<ColumnMapping> mapping, List<Map<String, String>> rows, String onDuplicate) {
        return new ImportRequest(rows, mapping, onDuplicate, null);
    }

    @Test
    void personImport_createsValidRowsAndReportsInvalidOnes() {
        ImportResult result = importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(
                Map.of("Name", "Alice Import", "Email", "alice.import@x.test"),
                Map.of("Name", "Bob", "Email", "not-an-email"),
                Map.of("Name", "", "Email", "noname@x.test")),
            "fill_empty"));

        assertEquals(1, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(2, result.getFailed().size());
    }

    @Test
    void personImport_dedupesByEmailAndFillsEmptyFields() {
        List<ColumnMapping> mapping = List.of(map("Name", "name"), map("Email", "email"), map("Title", "title"));
        importService.commitPersons(req(mapping,
            List.of(Map.of("Name", "Carol", "Email", "carol@x.test", "Title", "")), "fill_empty"));

        ImportResult second = importService.commitPersons(req(mapping,
            List.of(Map.of("Name", "Carol", "Email", "carol@x.test", "Title", "CTO")), "fill_empty"));

        assertEquals(0, second.getCreated());
        assertEquals(1, second.getUpdated());

        List<Person> matches = personMapper.findByEmails(workspace.getId(), List.of("carol@x.test"));
        assertEquals(1, matches.size());
        Person carol = personMapper.getPersonById(workspace.getId(), matches.get(0).getId());
        assertEquals("CTO", carol.getTitle());
    }

    @Test
    void personImport_autoCreatesCustomFieldAndAppliesValue() {
        ColumnMapping budget = new ColumnMapping("Budget", null, true, "number", "Budget");
        ImportResult result = importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email"), budget),
            List.of(Map.of("Name", "Dave", "Email", "dave@x.test", "Budget", "5000")),
            "fill_empty"));

        assertEquals(1, result.getCreated());

        CustomFieldDefinition def = customFieldDefinitionMapper.getByKey(workspace.getId(), "person", "budget");
        assertNotNull(def);
        assertEquals("number", def.getFieldType());

        Person dave = personMapper.getPersonById(workspace.getId(),
            personMapper.findByEmails(workspace.getId(), List.of("dave@x.test")).get(0).getId());
        Map<Integer, Object> values = customFieldValueService.getForEntities("person", List.of(dave.getId()))
            .getOrDefault(dave.getId(), Map.of());
        assertTrue(values.containsKey(def.getId()));
    }

    @Test
    void companyImport_dedupesDuplicateWebsiteWithinFile() {
        ImportPreviewResult preview = importService.previewCompanies(req(
            List.of(map("Company", "name"), map("Web", "website")),
            List.of(
                Map.of("Company", "Acme One", "Web", "https://acme-dedupe.test"),
                Map.of("Company", "Acme Two", "Web", "http://www.acme-dedupe.test/")),
            "fill_empty"));

        assertEquals(1, preview.getToCreate());
        assertEquals(1, preview.getToSkip());
    }

    @Test
    void dealImport_resolvesStageByNameAndDedupesByNameAndCompany() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        List<ColumnMapping> mapping = List.of(map("Deal", "name"), map("Pipe", "pipeline"), map("Stage", "stage"));
        List<Map<String, String>> rows = List.of(
            Map.of("Deal", "Import Deal", "Pipe", pipeline.getName(), "Stage", stage.getName()));

        ImportResult first = importService.commitDeals(req(mapping, rows, "fill_empty"));
        assertEquals(1, first.getCreated());

        ImportResult second = importService.commitDeals(req(mapping, rows, "fill_empty"));
        assertEquals(0, second.getCreated());
        assertEquals(1, second.getUpdated());
    }

    @Test
    void dealImport_reportsUnknownStage() {
        newPipeline();
        ImportResult result = importService.commitDeals(req(
            List.of(map("Deal", "name"), map("Stage", "stage")),
            List.of(Map.of("Deal", "Orphan Deal", "Stage", "No Such Stage")),
            "fill_empty"));

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().get(0).getReason().contains("pipeline or stage"));
    }

    @Test
    void personImport_rejectsDuplicateFieldMapping() {
        assertThrows(BadRequestException.class, () -> importService.previewPersons(req(
            List.of(map("Phone", "phone"), map("Mobile", "phone")),
            List.of(Map.of("Phone", "111", "Mobile", "222")), "fill_empty")));
    }

    @Test
    void exportCompanies_byIdsReturnsOnlySelected() {
        Company a = newCompany();
        Company b = newCompany();
        String csv = exportService.exportCompanies(null, List.of(a.getId()));
        assertTrue(csv.contains(a.getName()), "selected company present");
        assertTrue(!csv.contains(b.getName()), "unselected company absent");
    }

    @Test
    void exportPersons_neutralizesFormulaInjectionAndIncludesHeader() {
        importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "=SUM(A1:A2)", "Email", "danger@x.test")),
            "fill_empty"));

        String csv = exportService.exportPersons(null, null, null, false);

        assertTrue(csv.startsWith("id,name,email"), "header present");
        assertTrue(csv.contains("'=SUM(A1:A2)"), "formula prefixed with apostrophe");
    }

    @Test
    void personImport_fillEmptyPreservesExistingCustomFieldValue() {
        ColumnMapping budget = new ColumnMapping("Budget", null, true, "number", "Budget");
        List<ColumnMapping> mapping = List.of(map("Name", "name"), map("Email", "email"), budget);
        importService.commitPersons(req(mapping,
            List.of(Map.of("Name", "Erin", "Email", "erin@x.test", "Budget", "5000")), "fill_empty"));

        ImportResult second = importService.commitPersons(req(mapping,
            List.of(Map.of("Name", "Erin", "Email", "erin@x.test", "Budget", "9999")), "fill_empty"));
        assertEquals(1, second.getUpdated());

        CustomFieldDefinition def = customFieldDefinitionMapper.getByKey(workspace.getId(), "person", "budget");
        Person erin = personMapper.getPersonById(workspace.getId(),
            personMapper.findByEmails(workspace.getId(), List.of("erin@x.test")).get(0).getId());
        Object value = customFieldValueService.getForEntities("person", List.of(erin.getId()))
            .getOrDefault(erin.getId(), Map.of()).get(def.getId());
        assertNotNull(value);
        assertTrue(String.valueOf(value).startsWith("5000"), "fill_empty must preserve 5000, got " + value);
    }

    @Test
    void personImport_overwriteChangingCompanyRecordsEmploymentTransition() {
        Company a = newCompany();
        Company b = newCompany();
        List<ColumnMapping> mapping = List.of(map("Name", "name"), map("Email", "email"), map("Company", "company"));
        importService.commitPersons(req(mapping,
            List.of(Map.of("Name", "Gwen", "Email", "gwen@x.test", "Company", a.getName())), "fill_empty"));

        ImportResult result = importService.commitPersons(req(mapping,
            List.of(Map.of("Name", "Gwen", "Email", "gwen@x.test", "Company", b.getName())), "overwrite"));
        assertEquals(1, result.getUpdated());

        Person gwen = personMapper.getPersonById(workspace.getId(),
            personMapper.findByEmails(workspace.getId(), List.of("gwen@x.test")).get(0).getId());
        assertNotNull(gwen.getCompany());
        assertEquals(b.getId(), gwen.getCompany().getId());
    }

    @Test
    void personImport_matchUpdateRequiresUpdatePermission() {
        List<ColumnMapping> mapping = List.of(map("Name", "name"), map("Email", "email"));
        importService.commitPersons(req(mapping, List.of(Map.of("Name", "Fred", "Email", "fred@x.test")), "fill_empty"));

        WorkspaceRole role = roleService.createRole(workspace.getId(), currentUser.getId(), "CreatorOnly", List.of("PERSON_CREATE"));
        User member = newUser();
        workspaceService.assignCustomRole(workspace.getId(), currentUser.getId(), member.getId(), role.getId());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));

        assertThrows(ForbiddenException.class, () -> importService.commitPersons(
            req(mapping, List.of(Map.of("Name", "Fred Updated", "Email", "fred@x.test")), "fill_empty")));
    }
}
