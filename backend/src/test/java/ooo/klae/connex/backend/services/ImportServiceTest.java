package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.ColumnMapping;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;

/**
 * CSV import behaviour. Sibling workspaces are committed because duplicate preflight resolves
 * its same-organization ceiling from the control plane in its own transaction.
 */
class ImportServiceTest extends AbstractServiceTest {

    @Autowired ImportService importService;
    @Autowired ExportService exportService;
    @Autowired DealLineItemService dealLineItemService;
    @Autowired DealStageHistoryService dealStageHistoryService;
    @Autowired CustomFieldValueService customFieldValueService;
    @Autowired CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;
    @Autowired PersonService personService;
    @Autowired CompanyService companyService;
    @MockitoSpyBean PersonMapper personMapperSpy;
    @MockitoSpyBean CompanyMapper companyMapperSpy;
    @MockitoSpyBean DealMapper dealMapperSpy;
    @MockitoSpyBean PipelineMapper pipelineMapperSpy;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<Integer> committedWorkspaceIds = new ArrayList<>();

    @AfterTransaction
    void removeCommittedWorkspaces() {
        committed(() -> {
            committedWorkspaceIds.forEach(id ->
                jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", id));
            return null;
        });
        committedWorkspaceIds.clear();
    }

    private static ColumnMapping map(String column, String field) {
        return new ColumnMapping(column, field, null, null, null);
    }

    private static ImportRequest req(List<ColumnMapping> mapping, List<Map<String, String>> rows, String onDuplicate) {
        return new ImportRequest(rows, mapping, onDuplicate, null);
    }

    private static ImportRequest req(List<ColumnMapping> mapping, List<Map<String, String>> rows,
            String onDuplicate, Map<Integer, Integer> links) {
        return new ImportRequest(rows, mapping, onDuplicate, links);
    }

    private User memberWithPermissions(String... permissions) {
        WorkspaceRole role = roleService.createRole(
            workspace.getId(), currentUser.getId(), "Import Preview " + unique(), List.of(permissions));
        User member = newUser();
        workspaceService.assignCustomRole(workspace.getId(), currentUser.getId(), member.getId(), role.getId());
        authenticateAs(member, workspace.getId());
        return member;
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
    void recordImportsWriteTraceableIdentityProvenanceForCreatesAndUpdates() {
        ImportRequest personCreate = req(
            List.of(map("Name", "name"), map("Email", "email"), map("Phone", "phone")),
            List.of(Map.of(
                "Name", "Identity import",
                "Email", "identity-import@example.com",
                "Phone", "090-1234-5678")),
            "fill_empty");
        ImportRequest companyCreate = req(
            List.of(map("Name", "name"), map("Website", "website"), map("Phone", "phone")),
            List.of(Map.of(
                "Name", "Identity import company",
                "Website", "https://identity-import.example.org",
                "Phone", "090-2345-6789")),
            "fill_empty");

        importService.commitPersons(personCreate);
        importService.commitCompanies(companyCreate);

        Person imported = personMapper.findByEmails(
            workspace.getId(), List.of("identity-import@example.com")).getFirst();
        Company importedCompany = companyMapper.getAllCompanies(workspace.getId()).stream()
            .filter(candidate -> "Identity import company".equals(candidate.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals(
            2,
            rowCount(
                """
                SELECT COUNT(*)
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                  AND source_system = 'csv_import'
                  AND source_row_ref = 'csv-row:1'
                  AND acquired_at IS NOT NULL
                  AND purpose_of_use_code IS NULL
                """,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            2,
            rowCount(
                """
                SELECT COUNT(*)
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                  AND source_system = 'csv_import'
                  AND source_row_ref = 'csv-row:1'
                  AND acquired_at IS NOT NULL
                  AND purpose_of_use_code IS NULL
                """,
                workspace.getId(),
                importedCompany.getId()));

        importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email"), map("Phone", "phone")),
            List.of(
                Map.of(
                    "Name", "Other identity import",
                    "Email", unique() + "@example.com",
                    "Phone", "090-3456-7890"),
                Map.of(
                    "Name", "Identity import",
                    "Email", "identity-import@example.com",
                    "Phone", "090-4567-8901")),
            "overwrite"));

        assertEquals(
            "csv-row:2",
            jdbcTemplate.queryForObject(
                """
                SELECT source_row_ref
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                  AND kind = 'phone' AND superseded_at IS NULL
                """,
                String.class,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            1,
            rowCount(
                """
                SELECT COUNT(*)
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                  AND kind = 'phone' AND superseded_at IS NOT NULL
                """,
                workspace.getId(),
                imported.getId()));
    }

    @Test
    void recordImportsAssignNewRowsToTheCurrentActor() {
        importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Owned Import Person", "Email", "owned.import@x.test")),
            "fill_empty"));
        importService.commitCompanies(req(
            List.of(map("Company", "name"), map("Web", "website")),
            List.of(Map.of("Company", "Owned Import Company", "Web", "https://owned-import.test")),
            "fill_empty"));

        Person matched = personMapper.findByEmails(
            workspace.getId(), List.of("owned.import@x.test")).getFirst();
        Person person = personMapper.getPersonById(workspace.getId(), matched.getId());
        Company company = companyMapper.getAllCompanies(workspace.getId()).stream()
            .filter(candidate -> candidate.getName().equals("Owned Import Company"))
            .findFirst().orElseThrow();
        assertEquals(currentUser.getId(), person.getOwnerId());
        assertEquals(currentUser.getId(), company.getOwnerId());
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
    void personImportPreviewReturnsSharedStrongCandidatesWithoutAutoUpdatingThem() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company ownerCompany = companyInWorkspace(ownerWorkspace);
        Person shared = personInWorkspace(ownerWorkspace, ownerCompany);
        String email = "shared-preflight-" + unique() + "@example.com";
        jdbcTemplate.update(
            """
            UPDATE person
            SET email = ?
            WHERE workspace_id = ? AND id = ?
            """,
            email,
            ownerWorkspace.getId(),
            shared.getId());
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, acquired_at
            )
            VALUES (?, ?, 'email', ?, ?, 'test', 'person.email', CURRENT_TIMESTAMP)
            """,
            ownerWorkspace.getId(),
            shared.getId(),
            email,
            email);
        assertEquals(1, shareMapper.sharePerson(
            shared.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), false));

        ImportPreviewResult preview = importService.previewPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Shared candidate", "Email", email)),
            "fill_empty"));

        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getToUpdate());
        assertEquals(1, preview.getRows().getFirst().getCandidates().size());
        assertFalse(preview.getRows().getFirst().getCandidates().getFirst()
            .ownedByActiveWorkspace());
        assertEquals(
            DuplicateMatchStrength.STRONG,
            preview.getRows().getFirst().getCandidates().getFirst().strength());
        assertEquals(
            DuplicateMatchKind.EMAIL,
            preview.getRows().getFirst().getCandidates().getFirst().matches().getFirst().kind());
    }

    @Test
    void personImportPreviewRejectsAmbiguousOwnedStrongMatches() {
        String email = "ambiguous-" + unique() + "@example.com";
        Person first = new Person();
        first.setName("Ambiguous first");
        first.setEmail(email);
        Person second = new Person();
        second.setName("Ambiguous second");
        second.setEmail(email);
        personService.create(first);
        personService.create(second);

        ImportPreviewResult preview = importService.previewPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Ambiguous import", "Email", email)),
            "fill_empty"));

        assertEquals(1, preview.getInvalid());
        assertEquals(2, preview.getRows().getFirst().getCandidates().size());
        assertTrue(preview.getRows().getFirst().getErrors().getFirst()
            .contains("Multiple visible contact records"));
    }

    @Test
    void companyImportPreviewKeepsExactNameOnlyMatchesAsWeakSuggestions() {
        Company draft = new Company();
        draft.setName("Weak Name Candidate " + unique());
        Company existing = companyService.createCompany(draft);

        ImportPreviewResult preview = importService.previewCompanies(req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", existing.getName().toUpperCase())),
            "fill_empty"));

        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getToUpdate());
        assertEquals(
            DuplicateMatchStrength.WEAK,
            preview.getRows().getFirst().getCandidates().getFirst().strength());
        assertEquals(
            DuplicateMatchKind.NAME,
            preview.getRows().getFirst().getCandidates().getFirst().matches().getFirst().kind());
    }

    @Test
    void companyImportPreviewAutoMatchesAnExactCanonicalDomain() {
        Company draft = new Company();
        draft.setName("Canonical Domain " + unique());
        draft.setWebsite("https://www.canonical-" + unique() + ".co.jp/about");
        Company existing = companyService.createCompany(draft);

        ImportPreviewResult preview = importService.previewCompanies(req(
            List.of(map("Name", "name"), map("Website", "website")),
            List.of(Map.of(
                "Name", "Canonical domain import",
                "Website", existing.getWebsite().replace("https://www.", "http://"))),
            "fill_empty"));

        assertEquals(0, preview.getToCreate());
        assertEquals(1, preview.getToUpdate());
        assertEquals(existing.getId(), preview.getRows().getFirst().getMatchedId());
    }

    @Test
    void personImport_rejectsSharedManualLinkWithoutSideEffects() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company ownerCompany = companyInWorkspace(ownerWorkspace);
        Person shared = personInWorkspace(ownerWorkspace, ownerCompany);
        assertEquals(1, shareMapper.sharePerson(
            shared.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), false));
        Company activeCompany = newCompany();
        Tag activeTag = newTag();
        CustomFieldDefinition custom = customDefinition("person");
        List<ColumnMapping> mapping = List.of(
            map("Name", "name"),
            map("Email", "email"),
            map("Title", "title"),
            map("Company", "company"),
            map("Tags", "tags"),
            map("Custom", "custom:" + custom.getId()));
        ImportRequest request = req(mapping, List.of(Map.of(
            "Name", "Rejected shared update",
            "Email", unique() + "@x.test",
            "Title", "Rejected title",
            "Company", activeCompany.getName(),
            "Tags", activeTag.getName(),
            "Custom", "rejected value")), "overwrite", Map.of(0, shared.getId()));
        Map<String, Object> before = personSnapshot(ownerWorkspace.getId(), shared.getId());
        int tagCount = rowCount("SELECT COUNT(*) FROM person_tag WHERE person_id = ?", shared.getId());
        int employmentCount = rowCount(
            "SELECT COUNT(*) FROM person_employment WHERE person_id = ?", shared.getId());
        int customCount = rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'person' AND entity_id = ?",
            shared.getId());
        int auditCount = rowCount(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'person' AND entity_id = ?", shared.getId());

        ImportPreviewResult preview = importService.previewPersons(request);
        ImportResult result = importService.commitPersons(request);

        assertEquals(1, preview.getInvalid());
        assertEquals(0, preview.getToUpdate());
        assertTrue(preview.getRows().getFirst().getErrors().getFirst().contains("not found"));
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertEquals(before, personSnapshot(ownerWorkspace.getId(), shared.getId()));
        assertEquals(tagCount, rowCount("SELECT COUNT(*) FROM person_tag WHERE person_id = ?", shared.getId()));
        assertEquals(employmentCount, rowCount(
            "SELECT COUNT(*) FROM person_employment WHERE person_id = ?", shared.getId()));
        assertEquals(customCount, rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'person' AND entity_id = ?",
            shared.getId()));
        assertEquals(auditCount, rowCount(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'person' AND entity_id = ?", shared.getId()));
    }

    @Test
    void companyImport_rejectsSharedManualLinkWithoutSideEffects() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company shared = companyInWorkspace(ownerWorkspace);
        assertEquals(1, shareMapper.shareCompany(
            shared.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), false));
        Tag activeTag = newTag();
        CustomFieldDefinition custom = customDefinition("company");
        List<ColumnMapping> mapping = List.of(
            map("Name", "name"),
            map("Website", "website"),
            map("Industry", "industry"),
            map("Phone", "phone"),
            map("Address", "address"),
            map("Tags", "tags"),
            map("Custom", "custom:" + custom.getId()));
        ImportRequest request = req(mapping, List.of(Map.of(
            "Name", "Rejected shared company",
            "Website", "https://" + unique() + ".test",
            "Industry", "Rejected industry",
            "Phone", "+1-555-0100",
            "Address", "Rejected address",
            "Tags", activeTag.getName(),
            "Custom", "rejected value")), "overwrite", Map.of(0, shared.getId()));
        Map<String, Object> before = companySnapshot(ownerWorkspace.getId(), shared.getId());
        int tagCount = rowCount("SELECT COUNT(*) FROM company_tag WHERE company_id = ?", shared.getId());
        int customCount = rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'company' AND entity_id = ?",
            shared.getId());
        int auditCount = rowCount(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'company' AND entity_id = ?", shared.getId());

        ImportPreviewResult preview = importService.previewCompanies(request);
        ImportResult result = importService.commitCompanies(request);

        assertEquals(1, preview.getInvalid());
        assertEquals(0, preview.getToUpdate());
        assertTrue(preview.getRows().getFirst().getErrors().getFirst().contains("not found"));
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertEquals(before, companySnapshot(ownerWorkspace.getId(), shared.getId()));
        assertEquals(tagCount, rowCount("SELECT COUNT(*) FROM company_tag WHERE company_id = ?", shared.getId()));
        assertEquals(customCount, rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'company' AND entity_id = ?",
            shared.getId()));
        assertEquals(auditCount, rowCount(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'company' AND entity_id = ?", shared.getId()));
    }

    @Test
    void recordImports_rejectUnsharedForeignManualLinks() {
        Workspace foreignWorkspace = newForeignWorkspace();
        Company foreignCompany = companyInWorkspace(foreignWorkspace);
        Person foreignPerson = personInWorkspace(foreignWorkspace, foreignCompany);
        ImportRequest personRequest = req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", "Rejected foreign person")),
            "overwrite",
            Map.of(0, foreignPerson.getId()));
        ImportRequest companyRequest = req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", "Rejected foreign company")),
            "overwrite",
            Map.of(0, foreignCompany.getId()));
        Map<String, Object> personBefore = personSnapshot(foreignWorkspace.getId(), foreignPerson.getId());
        Map<String, Object> companyBefore = companySnapshot(foreignWorkspace.getId(), foreignCompany.getId());

        ImportPreviewResult personPreview = importService.previewPersons(personRequest);
        ImportResult personResult = importService.commitPersons(personRequest);
        ImportPreviewResult companyPreview = importService.previewCompanies(companyRequest);
        ImportResult companyResult = importService.commitCompanies(companyRequest);

        assertEquals(1, personPreview.getInvalid());
        assertEquals(1, personResult.getFailed().size());
        assertEquals(1, companyPreview.getInvalid());
        assertEquals(1, companyResult.getFailed().size());
        assertEquals(personBefore, personSnapshot(foreignWorkspace.getId(), foreignPerson.getId()));
        assertEquals(companyBefore, companySnapshot(foreignWorkspace.getId(), foreignCompany.getId()));
    }

    @Test
    void recordImports_updateOwnedManualLinks() {
        Person person = newPerson(newCompany());
        Company company = newCompany();
        String personName = "Owned linked person " + unique();
        String personEmail = unique() + "@x.test";
        String companyName = "Owned linked company " + unique();
        String companyWebsite = "https://" + unique() + ".test";
        ImportRequest personRequest = req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", personName, "Email", personEmail)),
            "overwrite",
            Map.of(0, person.getId()));
        ImportRequest companyRequest = req(
            List.of(map("Name", "name"), map("Website", "website")),
            List.of(Map.of("Name", companyName, "Website", companyWebsite)),
            "overwrite",
            Map.of(0, company.getId()));

        ImportPreviewResult personPreview = importService.previewPersons(personRequest);
        ImportResult personResult = importService.commitPersons(personRequest);
        ImportPreviewResult companyPreview = importService.previewCompanies(companyRequest);
        ImportResult companyResult = importService.commitCompanies(companyRequest);

        assertEquals(1, personPreview.getToUpdate());
        assertEquals(1, personResult.getUpdated());
        assertEquals(1, companyPreview.getToUpdate());
        assertEquals(1, companyResult.getUpdated());
        Person updatedPerson = personMapper.getPersonById(workspace.getId(), person.getId());
        Company updatedCompany = companyMapper.getCompanyById(workspace.getId(), company.getId());
        assertEquals(personName, updatedPerson.getName());
        assertEquals(personEmail, updatedPerson.getEmail());
        assertEquals(companyName, updatedCompany.getName());
        assertEquals(companyWebsite, updatedCompany.getWebsite());

        ImportResult noOpPerson = importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", personName, "Email", personEmail)),
            "fill_empty",
            Map.of(0, person.getId())));
        ImportResult noOpCompany = importService.commitCompanies(req(
            List.of(map("Name", "name"), map("Website", "website")),
            List.of(Map.of("Name", companyName, "Website", companyWebsite)),
            "fill_empty",
            Map.of(0, company.getId())));
        assertEquals(1, noOpPerson.getUpdated());
        assertTrue(noOpPerson.getFailed().isEmpty());
        assertEquals(1, noOpCompany.getUpdated());
        assertTrue(noOpCompany.getFailed().isEmpty());
    }

    @Test
    void recordImports_failVanishedMatchesBeforeTargetSideEffects() {
        Person person = newPerson(newCompany());
        Company company = newCompany();
        String replacementCompanyName = "Orphan company " + unique();
        String personTagName = "orphan_person_tag_" + unique();
        String companyTagName = "orphan_company_tag_" + unique();
        String personCustomKey = "orphan_person_" + unique();
        String companyCustomKey = "orphan_company_" + unique();
        ImportRequest personRequest = req(
            List.of(
                map("Name", "name"),
                map("Company", "company"),
                map("Tags", "tags"),
                new ColumnMapping("Custom", null, true, "text", personCustomKey)),
            List.of(Map.of(
                "Name", "Vanished person update",
                "Company", replacementCompanyName,
                "Tags", personTagName,
                "Custom", "vanished value")),
            "overwrite",
            Map.of(0, person.getId()));
        ImportRequest companyRequest = req(
            List.of(
                map("Name", "name"),
                map("Tags", "tags"),
                new ColumnMapping("Custom", null, true, "text", companyCustomKey)),
            List.of(Map.of(
                "Name", "Vanished company update",
                "Tags", companyTagName,
                "Custom", "vanished value")),
            "overwrite",
            Map.of(0, company.getId()));
        Map<String, Object> personBefore = personSnapshot(workspace.getId(), person.getId());
        Map<String, Object> companyBefore = companySnapshot(workspace.getId(), company.getId());
        int personTagCount = rowCount("SELECT COUNT(*) FROM person_tag WHERE person_id = ?", person.getId());
        int companyTagCount = rowCount("SELECT COUNT(*) FROM company_tag WHERE company_id = ?", company.getId());
        int employmentCount = rowCount(
            "SELECT COUNT(*) FROM person_employment WHERE person_id = ?", person.getId());
        int personCustomCount = rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'person' AND entity_id = ?",
            person.getId());
        int companyCustomCount = rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'company' AND entity_id = ?",
            company.getId());
        int auditCount = rowCount(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action LIKE 'import.%'",
            workspace.getId());
        doReturn(null).when(personMapperSpy).getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
        doReturn(null).when(companyMapperSpy).getOwnedCompanyByIdForUpdate(workspace.getId(), company.getId());

        ImportResult personResult = importService.commitPersons(personRequest);
        ImportResult companyResult = importService.commitCompanies(companyRequest);

        assertEquals(0, personResult.getUpdated());
        assertEquals(1, personResult.getFailed().size());
        assertEquals(0, companyResult.getUpdated());
        assertEquals(1, companyResult.getFailed().size());
        assertEquals(personBefore, personSnapshot(workspace.getId(), person.getId()));
        assertEquals(companyBefore, companySnapshot(workspace.getId(), company.getId()));
        assertEquals(personTagCount, rowCount(
            "SELECT COUNT(*) FROM person_tag WHERE person_id = ?", person.getId()));
        assertEquals(companyTagCount, rowCount(
            "SELECT COUNT(*) FROM company_tag WHERE company_id = ?", company.getId()));
        assertEquals(employmentCount, rowCount(
            "SELECT COUNT(*) FROM person_employment WHERE person_id = ?", person.getId()));
        assertEquals(personCustomCount, rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'person' AND entity_id = ?",
            person.getId()));
        assertEquals(companyCustomCount, rowCount(
            "SELECT COUNT(*) FROM custom_field_value WHERE entity_type = 'company' AND entity_id = ?",
            company.getId()));
        assertFalse(companyMapper.getCompaniesForDedup(workspace.getId()).stream()
            .anyMatch(candidate -> replacementCompanyName.equals(candidate.getName())));
        assertNull(tagMapper.getTagByName(workspace.getId(), personTagName));
        assertNull(tagMapper.getTagByName(workspace.getId(), companyTagName));
        assertNull(customFieldDefinitionMapper.getByKey(workspace.getId(), "person", personCustomKey));
        assertNull(customFieldDefinitionMapper.getByKey(workspace.getId(), "company", companyCustomKey));
        assertEquals(auditCount, rowCount(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action LIKE 'import.%'",
            workspace.getId()));
    }

    @Test
    void matchedImportLocksUseAscendingTargetIds() {
        Person firstPerson = newPerson(newCompany());
        Person secondPerson = newPerson(newCompany());
        Company firstCompany = newCompany();
        Company secondCompany = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal firstDeal = newDeal(pipeline, stage, firstCompany);
        Deal secondDeal = newDeal(pipeline, stage, secondCompany);

        ImportResult personResult = importService.commitPersons(req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", "Second person"), Map.of("Name", "First person")),
            "overwrite",
            Map.of(0, secondPerson.getId(), 1, firstPerson.getId())));
        ImportResult companyResult = importService.commitCompanies(req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", "Second company"), Map.of("Name", "First company")),
            "overwrite",
            Map.of(0, secondCompany.getId(), 1, firstCompany.getId())));
        ImportResult dealResult = importService.commitDeals(req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", "Second deal"), Map.of("Name", "First deal")),
            "overwrite",
            Map.of(0, secondDeal.getId(), 1, firstDeal.getId())));

        assertEquals(2, personResult.getUpdated());
        assertEquals(2, companyResult.getUpdated());
        assertEquals(2, dealResult.getUpdated());
        InOrder personLocks = inOrder(personMapperSpy);
        personLocks.verify(personMapperSpy).getOwnedPersonByIdForUpdate(workspace.getId(), firstPerson.getId());
        personLocks.verify(personMapperSpy).getOwnedPersonByIdForUpdate(workspace.getId(), secondPerson.getId());
        InOrder companyLocks = inOrder(companyMapperSpy);
        companyLocks.verify(companyMapperSpy).getOwnedCompanyByIdForUpdate(workspace.getId(), firstCompany.getId());
        companyLocks.verify(companyMapperSpy).getOwnedCompanyByIdForUpdate(workspace.getId(), secondCompany.getId());
        InOrder dealLocks = inOrder(dealMapperSpy);
        dealLocks.verify(dealMapperSpy).getDealByIdForUpdate(workspace.getId(), firstDeal.getId());
        dealLocks.verify(dealMapperSpy).getDealByIdForUpdate(workspace.getId(), secondDeal.getId());
    }

    @Test
    void dependencyLocksPrecedeMatchedTargetsInDeterministicOrder() {
        Company firstCompany = newCompany();
        Company secondCompany = newCompany();
        Person firstPerson = newPerson(firstCompany);
        Person secondPerson = newPerson(secondCompany);

        ImportResult personResult = importService.commitPersons(req(
            List.of(map("Name", "name"), map("Company", "company")),
            List.of(
                Map.of("Name", "Second dependency", "Company", secondCompany.getName()),
                Map.of("Name", "First dependency", "Company", firstCompany.getName())),
            "overwrite",
            Map.of(0, secondPerson.getId(), 1, firstPerson.getId())));

        assertEquals(2, personResult.getUpdated());
        InOrder personOrder = inOrder(companyMapperSpy, personMapperSpy);
        personOrder.verify(companyMapperSpy).lockById(
            workspace.getId(), Math.min(firstCompany.getId(), secondCompany.getId()));
        personOrder.verify(companyMapperSpy).lockById(
            workspace.getId(), Math.max(firstCompany.getId(), secondCompany.getId()));
        personOrder.verify(personMapperSpy).getOwnedPersonByIdForUpdate(
            workspace.getId(), Math.min(firstPerson.getId(), secondPerson.getId()));
        personOrder.verify(personMapperSpy).getOwnedPersonByIdForUpdate(
            workspace.getId(), Math.max(firstPerson.getId(), secondPerson.getId()));

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, firstCompany);
        clearInvocations(companyMapperSpy, pipelineMapperSpy, dealMapperSpy);
        ImportResult dealResult = importService.commitDeals(req(
            List.of(
                map("Name", "name"),
                map("Company", "company"),
                map("Pipeline", "pipeline"),
                map("Stage", "stage")),
            List.of(Map.of(
                "Name", "Ordered deal",
                "Company", firstCompany.getName(),
                "Pipeline", pipeline.getName(),
                "Stage", stage.getName())),
            "overwrite",
            Map.of(0, deal.getId())));

        assertEquals(1, dealResult.getUpdated());
        InOrder dealOrder = inOrder(companyMapperSpy, pipelineMapperSpy, dealMapperSpy);
        dealOrder.verify(companyMapperSpy).lockById(workspace.getId(), firstCompany.getId());
        dealOrder.verify(pipelineMapperSpy).lockVisiblePipelineById(
            workspace.getId(), pipeline.getId());
        dealOrder.verify(pipelineMapperSpy).lockVisibleStageById(
            workspace.getId(), stage.getId());
        dealOrder.verify(dealMapperSpy).getDealByIdForUpdate(
            workspace.getId(), deal.getId());
    }

    @Test
    void dealImportFailsWhenMatchedTargetVanishes() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        doReturn(null).when(dealMapperSpy).getDealByIdForUpdate(workspace.getId(), deal.getId());

        ImportResult result = importService.commitDeals(req(
            List.of(map("Name", "name")),
            List.of(Map.of("Name", "Vanished deal update")),
            "overwrite",
            Map.of(0, deal.getId())));

        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
    }

    @Test
    void vanishedMatchedRowsDoNotInvalidateValidRows() {
        Person vanished = newPerson(newCompany());
        String validEmail = unique() + "@x.test";
        doReturn(null).when(personMapperSpy)
            .getOwnedPersonByIdForUpdate(workspace.getId(), vanished.getId());

        ImportResult result = importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(
                Map.of("Name", "Vanished update", "Email", vanished.getEmail()),
                Map.of("Name", "Valid create", "Email", validEmail)),
            "overwrite",
            Map.of(0, vanished.getId())));

        assertEquals(1, result.getCreated());
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertEquals(1, personMapper.findByEmails(workspace.getId(), List.of(validEmail)).size());
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
    void dealImportPreservesValueWhenMatchedDealHasLineItems() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        DealLineItemRequest lineItem = new DealLineItemRequest();
        lineItem.setName("Imported deal line " + unique());
        lineItem.setUnitPrice(new BigDecimal("25.00"));
        lineItem.setQuantity(BigDecimal.ONE);
        dealLineItemService.create(deal.getId(), lineItem);

        ImportResult result = importService.commitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Value", "value"),
                map("Close", "expectedCloseDate"),
                map("Company", "company")),
            List.of(Map.of(
                "Deal", deal.getName(),
                "Value", "2500.00",
                "Close", "2027-12-31",
                "Company", company.getName())),
            "overwrite"));

        Deal updated = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(1, result.getUpdated());
        assertTrue(result.getFailed().isEmpty());
        assertEquals(1000.0, updated.getValue());
        assertEquals("2027-12-31", updated.getExpectedCloseDate());
    }

    @Test
    void dealImport_closedAtIngestStage_recordsConversionIneligibleInitialHistory() {
        Pipeline pipeline = newPipeline();
        Stage wonStage = new Stage();
        wonStage.setName("Won " + unique());
        wonStage.setPipeline(pipeline);
        wonStage.setPosition(0);
        wonStage.setWorkspaceId(workspace.getId());
        wonStage.setSuccess(true);
        pipelineMapper.insertStage(wonStage);

        ImportResult result = importService.commitDeals(req(
            List.of(map("Deal", "name"), map("Pipe", "pipeline"), map("Stage", "stage")),
            List.of(Map.of("Deal", "Closed Import " + unique(), "Pipe", pipeline.getName(), "Stage", wonStage.getName())),
            "fill_empty"));
        assertEquals(1, result.getCreated());

        List<Deal> deals = dealMapper.getAllDeals(workspace.getId());
        assertEquals(1, deals.size());
        assertEquals(Boolean.TRUE, deals.get(0).getWon());
        List<DealStageHistory> history = dealStageHistoryService.getHistory(deals.get(0).getId());
        assertEquals(1, history.size());
        assertFalse(history.get(0).isConversionEligible());
    }

    @Test
    void dealImport_openStage_recordsConversionEligibleInitialHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        ImportResult result = importService.commitDeals(req(
            List.of(map("Deal", "name"), map("Pipe", "pipeline"), map("Stage", "stage")),
            List.of(Map.of("Deal", "Open Import " + unique(), "Pipe", pipeline.getName(), "Stage", stage.getName())),
            "fill_empty"));
        assertEquals(1, result.getCreated());

        List<Deal> deals = dealMapper.getAllDeals(workspace.getId());
        assertEquals(1, deals.size());
        assertNull(deals.get(0).getWon());
        List<DealStageHistory> history = dealStageHistoryService.getHistory(deals.get(0).getId());
        assertEquals(1, history.size());
        assertTrue(history.get(0).isConversionEligible());
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
        String csv = exportService.exportCompanies(
            null, null, false, List.of(a.getId()), MemberScope.allTeam());
        assertTrue(csv.contains(a.getName()), "selected company present");
        assertTrue(!csv.contains(b.getName()), "unselected company absent");
    }

    @Test
    void exportPersons_neutralizesFormulaInjectionAndIncludesHeader() {
        importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "=SUM(A1:A2)", "Email", "danger@x.test")),
            "fill_empty"));

        String csv = exportService.exportPersons(null, null, null, false, MemberScope.allTeam());

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

        memberWithPermissions("PERSON_CREATE");
        ImportRequest duplicate = req(
            mapping, List.of(Map.of("Name", "Fred Updated", "Email", "fred@x.test")), "fill_empty");

        assertThrows(ForbiddenException.class, () -> importService.previewPersons(duplicate));
        assertThrows(ForbiddenException.class, () -> importService.commitPersons(duplicate));
    }

    @Test
    void companyAndDealPreviews_requireUpdatePermissionForMatchedRows() {
        Company companyDraft = new Company();
        companyDraft.setName("Permission company " + unique());
        companyDraft.setWebsite("https://permission-" + unique() + ".co.jp");
        Company company = companyService.createCompany(companyDraft);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);

        memberWithPermissions("COMPANY_CREATE", "DEAL_CREATE");

        assertThrows(ForbiddenException.class, () -> importService.previewCompanies(req(
            List.of(map("Company", "name"), map("Web", "website")),
            List.of(Map.of("Company", company.getName(), "Web", company.getWebsite())),
            "fill_empty")));
        assertThrows(ForbiddenException.class, () -> importService.previewDeals(req(
            List.of(map("Deal", "name"), map("Company", "company")),
            List.of(Map.of("Deal", deal.getName(), "Company", company.getName())),
            "fill_empty")));
    }

    @Test
    void personPreview_requiresCompanyCreateForMissingActiveWorkspaceCompany() {
        String companyName = "Missing Company " + unique();
        memberWithPermissions("PERSON_CREATE");

        assertThrows(ForbiddenException.class, () -> importService.previewPersons(req(
            List.of(map("Name", "name"), map("Company", "company")),
            List.of(Map.of("Name", "New Person", "Company", companyName)),
            "fill_empty")));
        assertFalse(companyMapper.getCompaniesForDedup(workspace.getId()).stream()
            .anyMatch(company -> companyName.equals(company.getName())));
    }

    @Test
    void personPreview_requiresTagManageForMissingActiveWorkspaceTag() {
        String tagName = "missing_" + unique();
        memberWithPermissions("PERSON_CREATE");

        assertThrows(ForbiddenException.class, () -> importService.previewPersons(req(
            List.of(map("Name", "name"), map("Tags", "tags")),
            List.of(Map.of("Name", "New Person", "Tags", tagName)),
            "fill_empty")));
        assertNull(tagMapper.getTagByName(workspace.getId(), tagName));
    }

    @Test
    void personPreview_requiresCustomFieldManageForMissingActiveWorkspaceDefinition() {
        String fieldKey = "budget_" + unique();
        ColumnMapping budget = new ColumnMapping("Budget", null, true, "number", fieldKey);
        memberWithPermissions("PERSON_CREATE");

        assertThrows(ForbiddenException.class, () -> importService.previewPersons(req(
            List.of(map("Name", "name"), budget),
            List.of(Map.of("Name", "New Person", "Budget", "5000")),
            "fill_empty")));
        assertNull(customFieldDefinitionMapper.getByKey(workspace.getId(), "person", fieldKey));
    }

    @Test
    void personPreview_allowsExistingActiveWorkspaceDependenciesWithoutManagePermissions() {
        Company company = newCompany();
        Tag tag = newTag();
        String fieldKey = "budget_" + unique();
        ColumnMapping budget = new ColumnMapping("Budget", null, true, "number", fieldKey);
        importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email"), budget),
            List.of(Map.of("Name", "Seed Person", "Email", unique() + "@x.test", "Budget", "1")),
            "fill_empty"));

        memberWithPermissions("PERSON_CREATE");
        ImportRequest request = req(
            List.of(
                map("Name", "name"),
                map("Email", "email"),
                map("Company", "company"),
                map("Tags", "tags"),
                budget),
            List.of(Map.ofEntries(
                Map.entry("Name", "Dependency User"),
                Map.entry("Email", unique() + "@x.test"),
                Map.entry("Company", company.getName()),
                Map.entry("Tags", tag.getName()),
                Map.entry("Budget", "2"))),
            "fill_empty");
        ImportPreviewResult preview = importService.previewPersons(request);
        ImportResult result = importService.commitPersons(request);

        assertEquals(1, preview.getToCreate());
        assertEquals(1, result.getCreated());
    }

    @Test
    void personPreviewAndCommit_allowAuthorizedDependencyCreation() {
        String companyName = "Authorized Company " + unique();
        String tagName = "authorized_" + unique();
        String fieldKey = "authorized_" + unique();
        ColumnMapping custom = new ColumnMapping("Custom", null, true, "text", fieldKey);
        ImportRequest request = req(
            List.of(
                map("Name", "name"),
                map("Company", "company"),
                map("Tags", "tags"),
                custom),
            List.of(Map.of(
                "Name", "Authorized Person",
                "Company", companyName,
                "Tags", tagName,
                "Custom", "value")),
            "fill_empty");
        memberWithPermissions("PERSON_CREATE", "COMPANY_CREATE", "TAG_MANAGE", "CUSTOM_FIELD_MANAGE");

        ImportPreviewResult preview = importService.previewPersons(request);
        ImportResult result = importService.commitPersons(request);

        assertEquals(1, preview.getToCreate());
        assertEquals(1, result.getCreated());
        assertTrue(companyMapper.getCompaniesForDedup(workspace.getId()).stream()
            .anyMatch(company -> companyName.equals(company.getName())));
        assertNotNull(tagMapper.getTagByName(workspace.getId(), tagName));
        assertNotNull(customFieldDefinitionMapper.getByKey(workspace.getId(), "person", fieldKey));
    }

    @Test
    void skippedAndInvalidRows_doNotRequireDependencyPermissionsOrCreateDependencies() {
        String email = unique() + "@x.test";
        importService.commitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Existing Person", "Email", email)),
            "fill_empty"));
        String companyName = "Skipped Company " + unique();
        String tagName = "skipped_" + unique();
        String fieldKey = "skipped_" + unique();
        ColumnMapping custom = new ColumnMapping("Custom", null, true, "text", fieldKey);
        List<ColumnMapping> mapping = List.of(
            map("Name", "name"),
            map("Email", "email"),
            map("Company", "company"),
            map("Tags", "tags"),
            custom);
        memberWithPermissions("PERSON_CREATE");

        ImportRequest skipped = req(mapping, List.of(Map.of(
            "Name", "Existing Person",
            "Email", email,
            "Company", companyName,
            "Tags", tagName,
            "Custom", "value")), "skip");
        ImportPreviewResult skippedPreview = importService.previewPersons(skipped);
        ImportResult skippedResult = importService.commitPersons(skipped);
        ImportPreviewResult invalidPreview = importService.previewPersons(req(mapping, List.of(Map.of(
            "Name", "",
            "Email", unique() + "@x.test",
            "Company", companyName,
            "Tags", tagName,
            "Custom", "value")), "fill_empty"));

        assertEquals(1, skippedPreview.getToSkip());
        assertEquals(1, skippedResult.getSkipped());
        assertEquals(1, invalidPreview.getInvalid());
        assertFalse(companyMapper.getCompaniesForDedup(workspace.getId()).stream()
            .anyMatch(company -> companyName.equals(company.getName())));
        assertNull(tagMapper.getTagByName(workspace.getId(), tagName));
        assertNull(customFieldDefinitionMapper.getByKey(workspace.getId(), "person", fieldKey));
    }

    @Test
    void foreignWorkspaceDependencies_doNotSatisfyPreviewPermissionPreflight() {
        String companyName = "Foreign Company " + unique();
        String tagName = "foreign_" + unique();
        String fieldKey = "foreign_" + unique();
        Workspace foreign = new Workspace();
        foreign.setName("Foreign " + unique());
        foreign.setSlug("foreign-" + unique());
        foreign.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(foreign);
        Company company = new Company();
        company.setWorkspaceId(foreign.getId());
        company.setName(companyName);
        companyMapper.insert(company);
        Tag tag = new Tag();
        tag.setWorkspaceId(foreign.getId());
        tag.setName(tagName);
        tag.setColor("#abcdef");
        tagMapper.insert(tag);
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(foreign.getId());
        definition.setEntityType("person");
        definition.setFieldKey(fieldKey);
        definition.setLabel(fieldKey);
        definition.setFieldType("text");
        customFieldDefinitionMapper.insert(definition);
        memberWithPermissions("PERSON_CREATE");

        assertThrows(ForbiddenException.class, () -> importService.previewPersons(req(
            List.of(map("Name", "name"), map("Company", "company")),
            List.of(Map.of("Name", "New Person", "Company", companyName)),
            "fill_empty")));
        assertThrows(ForbiddenException.class, () -> importService.previewPersons(req(
            List.of(map("Name", "name"), map("Tags", "tags")),
            List.of(Map.of("Name", "New Person", "Tags", tagName)),
            "fill_empty")));
        ColumnMapping custom = new ColumnMapping("Custom", null, true, "text", fieldKey);
        assertThrows(ForbiddenException.class, () -> importService.previewPersons(req(
            List.of(map("Name", "name"), custom),
            List.of(Map.of("Name", "New Person", "Custom", "value")),
            "fill_empty")));
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace other = new Workspace();
        other.setName("Shared owner " + unique());
        other.setSlug("shared-owner-" + unique());
        other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        committed(() -> workspaceMapper.insert(other));
        committedWorkspaceIds.add(other.getId());
        return other;
    }

    private <T> T committed(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private Workspace newForeignWorkspace() {
        Workspace other = new Workspace();
        other.setName("Foreign owner " + unique());
        other.setSlug("foreign-owner-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Company companyInWorkspace(Workspace target) {
        Company company = new Company();
        company.setWorkspaceId(target.getId());
        company.setName("Foreign company " + unique());
        company.setWebsite("https://" + unique() + ".example.com");
        company.setIndustry("Owner industry");
        company.setPhone("+81-90-0000-0000");
        company.setAddress("Owner address");
        companyMapper.insert(company);
        return company;
    }

    private Person personInWorkspace(Workspace target, Company company) {
        Person person = new Person();
        person.setWorkspaceId(target.getId());
        person.setName("Foreign person " + unique());
        person.setEmail(unique() + ".foreign@example.com");
        person.setPhone("+81-90-1111-1111");
        person.setTitle("Owner title");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private CustomFieldDefinition customDefinition(String entityType) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setEntityType(entityType);
        definition.setFieldKey("import_guard_" + unique());
        definition.setLabel("Import guard " + unique());
        definition.setFieldType("text");
        customFieldDefinitionMapper.insert(definition);
        return definition;
    }

    private Map<String, Object> personSnapshot(int workspaceId, int personId) {
        return jdbcTemplate.queryForMap(
            "SELECT workspace_id, owner_id, name, email, phone, company_id, title, image_url, created_at, updated_at "
                + "FROM person WHERE workspace_id = ? AND id = ?",
            workspaceId,
            personId);
    }

    private Map<String, Object> companySnapshot(int workspaceId, int companyId) {
        return jdbcTemplate.queryForMap(
            "SELECT workspace_id, owner_id, name, website, industry, phone, address, logo_url, created_at, updated_at "
                + "FROM company WHERE workspace_id = ? AND id = ?",
            workspaceId,
            companyId);
    }

    private int rowCount(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
