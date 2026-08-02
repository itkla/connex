package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.ImportPreviewResult;
import ooo.klae.connex.backend.dto.ImportRequest;
import ooo.klae.connex.backend.dto.ImportResult;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;

class ImportServiceTest extends AbstractServiceTest {

    @Autowired ImportService importService;
    @Autowired ExportService exportService;
    @Autowired DealService dealService;
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
    @MockitoSpyBean IdentityMapper identityMapperSpy;

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

    private ImportResult reviewAndCommitPersons(ImportRequest request) {
        ImportPreviewResult preview = importService.previewPersons(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitPersons(request);
    }

    private ImportResult reviewAndCommitCompanies(ImportRequest request) {
        ImportPreviewResult preview = importService.previewCompanies(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitCompanies(request);
    }

    private ImportResult reviewAndCommitDeals(ImportRequest request) {
        ImportPreviewResult preview = importService.previewDeals(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        return importService.commitDeals(request);
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
    void personImportReviewsAndReusesAUniqueCompanyDependency() {
        String companyName = "Reviewed company " + unique();
        Company existing = new Company();
        existing.setWorkspaceId(workspace.getId());
        existing.setName(companyName);
        companyMapper.insert(existing);
        ImportRequest request = req(
            List.of(map("Name", "name"), map("Company", "company")),
            List.of(Map.of(
                "Name", "Reviewed person " + unique(),
                "Company", companyName)),
            "fill_empty");

        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(1, preview.getRows().getFirst().getCandidates().size());
        assertEquals(
            "company",
            preview.getRows().getFirst().getCandidates().getFirst().recordType());
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult result = importService.commitPersons(request);
        Integer linkedCompanyId = jdbcTemplate.queryForObject(
            "SELECT company_id FROM person WHERE workspace_id = ? AND name = ?",
            Integer.class,
            workspace.getId(),
            request.getRows().getFirst().get("Name"));
        assertEquals(1, result.getCreated());
        assertEquals(existing.getId(), linkedCompanyId);
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(*) FROM company WHERE workspace_id = ? AND name = ?",
                workspace.getId(),
                companyName));
    }

    @Test
    void personImportFailsClosedOnAmbiguousCompanyDependencies() {
        String companyName = "Ambiguous company " + unique();
        Company first = new Company();
        first.setWorkspaceId(workspace.getId());
        first.setName(companyName);
        companyMapper.insert(first);
        Company second = new Company();
        second.setWorkspaceId(workspace.getId());
        second.setName(companyName);
        companyMapper.insert(second);

        ImportPreviewResult preview = importService.previewPersons(req(
            List.of(map("Name", "name"), map("Company", "company")),
            List.of(Map.of(
                "Name", "Ambiguous dependency " + unique(),
                "Company", companyName)),
            "fill_empty"));

        assertEquals("invalid", preview.getRows().getFirst().getStatus());
        assertEquals(2, preview.getRows().getFirst().getCandidates().size());
        assertTrue(preview.getRows().getFirst().getErrors().getFirst()
            .contains("Multiple visible companies"));
    }

    @Test
    void skippedPersonMatchDoesNotConsumeAnAmbiguousCompanyDependency() {
        String companyName = "Unused ambiguous company " + unique();
        Company first = new Company();
        first.setWorkspaceId(workspace.getId());
        first.setName(companyName);
        companyMapper.insert(first);
        Company second = new Company();
        second.setWorkspaceId(workspace.getId());
        second.setName(companyName);
        companyMapper.insert(second);
        Person existing = new Person();
        existing.setName("Skip dependency target " + unique());
        existing.setEmail("skip-dependency-" + unique() + "@example.test");
        Person created = personService.create(existing);
        ImportRequest request = req(
            List.of(
                map("Name", "name"),
                map("Email", "email"),
                map("Company", "company")),
            List.of(Map.of(
                "Name", "Skipped incoming person",
                "Email", created.getEmail(),
                "Company", companyName)),
            "skip");

        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(0, preview.getInvalid());
        assertEquals(1, preview.getToSkip());
        assertEquals("skip", preview.getRows().getFirst().getStatus());
    }

    @Test
    void personImport_createsValidRowsAndReportsInvalidOnes() {
        ImportResult result = reviewAndCommitPersons(req(
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
    void personImportDoesNotAutoMatchIdentifiersRejectedByMatchingService() {
        String storedEmail = "A..B-" + unique() + "@Example.com";
        Person existing = new Person();
        existing.setName("Noncanonical email person");
        existing.setEmail(storedEmail);
        Person created = personService.create(existing);
        String importedEmail = storedEmail.toLowerCase();
        ImportPreviewResult unmatched = importService.previewPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Noncanonical import", "Email", importedEmail)),
            "fill_empty"));

        assertEquals(1, unmatched.getToCreate());
        assertEquals(0, unmatched.getToUpdate());
        assertNull(unmatched.getRows().getFirst().getMatchedId());
        assertEquals(
            0,
            rowCount(
                "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                created.getId()));

        String duplicateEmail = "C..D-" + unique() + "@Example.com";
        ImportRequest duplicateRequest = req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(
                Map.of("Name", "Fallback first", "Email", duplicateEmail),
                Map.of(
                    "Name", "Fallback second",
                    "Email", "  " + duplicateEmail.toLowerCase() + "  ")),
            "fill_empty");
        ImportPreviewResult preview = importService.previewPersons(duplicateRequest);

        assertEquals(2, preview.getToCreate());
        assertEquals(0, preview.getToSkip());
    }

    @Test
    void recordImportsWriteTraceableIdentityProvenanceForCreatesAndUpdates() {
        String companyDomain = "identity-import-" + unique() + ".co.jp";
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
                "Website", "https://" + companyDomain,
                "Phone", "090-2345-6789")),
            "fill_empty");

        reviewAndCommitPersons(personCreate);
        reviewAndCommitCompanies(companyCreate);

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

        reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email"), map("Phone", "phone")),
            List.of(
                Map.of(
                    "Name", "Other identity import",
                    "Email", unique() + "@example.com",
                    "Phone", "090-3456-7890"),
                Map.of(
                    "Name", "Identity import",
                    "Email", "IDENTITY-IMPORT@EXAMPLE.COM",
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
    void previewsConsumeCurrentCanonicalIdentitiesAndRejectAmbiguity() {
        Person first = new Person();
        first.setName("Canonical person");
        first.setEmail("Case@Example.com");
        Person firstCreated = personService.create(first);

        ImportPreviewResult personMatch = importService.previewPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Canonical person", "Email", "case@example.com")),
            "fill_empty"));

        assertEquals(1, personMatch.getToUpdate());
        assertEquals(firstCreated.getId(), personMatch.getRows().getFirst().getMatchedId());

        Person second = new Person();
        second.setName("Canonical collision");
        second.setEmail("CASE@example.com");
        personService.create(second);

        ImportPreviewResult ambiguous = importService.previewPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Ambiguous person", "Email", "case@example.com")),
            "fill_empty"));

        assertEquals(1, ambiguous.getInvalid());
        assertTrue(ambiguous.getRows().getFirst().getErrors().getFirst()
            .contains("Multiple visible contact records"));
    }

    @Test
    void companyPreviewMatchesTheRegistrableCurrentDomain() {
        String domain = "canonical-" + unique() + ".co.jp";
        Company draft = new Company();
        draft.setName("Canonical company");
        draft.setWebsite("https://www." + domain + "/about");
        Company existing = companyService.createCompany(draft);

        ImportPreviewResult preview = importService.previewCompanies(req(
            List.of(map("Name", "name"), map("Website", "website")),
            List.of(Map.of(
                "Name", "Canonical company import",
                "Website", "http://sales." + domain + "/contact")),
            "fill_empty"));

        assertEquals(1, preview.getToUpdate());
        assertEquals(existing.getId(), preview.getRows().getFirst().getMatchedId());
    }

    @Test
    void previewShowsWeakExactNameCandidatesWithoutSilentlyMatchingThem() {
        Person existing = new Person();
        existing.setName("山田 太郎 " + unique());
        Person created = personService.create(existing);
        ImportRequest request = req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of(
                "Name", "  " + created.getName() + "  ",
                "Email", unique() + "@example.test")),
            "fill_empty");

        ImportPreviewResult preview = importService.previewPersons(request);

        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getToUpdate());
        assertNotNull(preview.getRows().getFirst().getCandidates());
        assertEquals(1, preview.getRows().getFirst().getCandidates().size());
        assertEquals(
            created.getId(),
            preview.getRows().getFirst().getCandidates().getFirst().recordId());
        assertEquals(
            DuplicateMatchStrength.WEAK,
            preview.getRows().getFirst().getCandidates().getFirst().strength());

        ImportPreviewResult linked = importService.previewPersons(req(
            request.getMapping(),
            request.getRows(),
            "fill_empty",
            Map.of(0, created.getId())));
        assertEquals(1, linked.getToUpdate());
        assertEquals(
            created.getId(),
            linked.getRows().getFirst().getMatchedId());
    }

    @Test
    void recordPreviewsDedupeEveryCanonicalStrongKeyWithinTheFile() {
        ImportPreviewResult people = importService.previewPersons(req(
            List.of(
                map("Name", "name"),
                map("Email", "email"),
                map("Phone", "phone")),
            List.of(
                Map.of(
                    "Name", "Phone first",
                    "Email", unique() + "@example.test",
                    "Phone", "090-1234-5678"),
                Map.of(
                    "Name", "Phone second",
                    "Email", unique() + "@example.test",
                    "Phone", "+81 90 1234 5678")),
            "fill_empty"));
        ImportPreviewResult companies = importService.previewCompanies(req(
            List.of(
                map("Name", "name"),
                map("Website", "website"),
                map("Phone", "phone")),
            List.of(
                Map.of(
                    "Name", "Company phone first",
                    "Website", "https://" + unique() + ".test",
                    "Phone", "090-2345-6789"),
                Map.of(
                    "Name", "Company phone second",
                    "Website", "https://" + unique() + ".test",
                    "Phone", "+81 90 2345 6789")),
            "fill_empty"));

        assertEquals(1, people.getToCreate());
        assertEquals(0, people.getToSkip());
        assertEquals(1, companies.getToCreate());
        assertEquals(0, companies.getToSkip());
        assertTrue(people.getRows().stream()
            .allMatch(row -> "create".equals(row.getStatus())));
        assertTrue(companies.getRows().stream()
            .allMatch(row -> "create".equals(row.getStatus())));
    }

    @Test
    void recordImportsAssignNewRowsToTheCurrentActor() {
        reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", "Owned Import Person", "Email", "owned.import@x.test")),
            "fill_empty"));
        reviewAndCommitCompanies(req(
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
        reviewAndCommitPersons(req(mapping,
            List.of(Map.of("Name", "Carol", "Email", "carol@x.test", "Title", "")), "fill_empty"));

        ImportResult second = reviewAndCommitPersons(req(mapping,
            List.of(Map.of("Name", "Carol", "Email", "carol@x.test", "Title", "CTO")), "fill_empty"));

        assertEquals(0, second.getCreated());
        assertEquals(1, second.getUpdated());

        List<Person> matches = personMapper.findByEmails(workspace.getId(), List.of("carol@x.test"));
        assertEquals(1, matches.size());
        Person carol = personMapper.getPersonById(workspace.getId(), matches.get(0).getId());
        assertEquals("CTO", carol.getTitle());
    }

    @Test
    void personImportGivesLinkedMatchPrecedenceOverEarlierCreateForSameIdentity() {
        Person target = new Person();
        target.setName("Linked identity target");
        target.setEmail("old-" + unique() + "@example.test");
        Person createdTarget = personService.create(target);
        String incomingEmail = "linked-precedence-" + unique() + "@example.test";

        ImportResult result = reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(
                Map.of("Name", "Create appears first", "Email", incomingEmail),
                Map.of("Name", "Linked update", "Email", incomingEmail)),
            "overwrite",
            Map.of(1, createdTarget.getId())));

        assertEquals(0, result.getCreated());
        assertEquals(1, result.getUpdated());
        assertEquals(0, result.getSkipped());
        assertEquals(
            incomingEmail,
            personMapper.getPersonById(
                workspace.getId(), createdTarget.getId()).getEmail());
    }

    @Test
    void personImportRejectsOneCanonicalIdentityLinkedToDifferentTargets() {
        Person first = new Person();
        first.setName("First linked target");
        first.setEmail("first-" + unique() + "@example.test");
        Person firstTarget = personService.create(first);
        Person second = new Person();
        second.setName("Second linked target");
        second.setEmail("second-" + unique() + "@example.test");
        Person secondTarget = personService.create(second);
        String incomingEmail = "conflicting-links-" + unique() + "@example.test";

        ImportResult result = reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(
                Map.of("Name", "First conflict", "Email", incomingEmail),
                Map.of("Name", "Second conflict", "Email", incomingEmail)),
            "overwrite",
            Map.of(0, firstTarget.getId(), 1, secondTarget.getId())));

        assertEquals(0, result.getUpdated());
        assertEquals(2, result.getFailed().size());
        assertTrue(result.getFailed().stream()
            .allMatch(error -> error.getReason().contains("multiple import targets")));
    }

    @Test
    void personImportRejectsBridgedCanonicalIdentityGroups() {
        ImportPreviewResult result = importService.previewPersons(req(
            List.of(
                map("Name", "name"),
                map("Email", "email"),
                map("Phone", "phone")),
            List.of(
                Map.of(
                    "Name", "Bridge first",
                    "Email", "bridge-a@example.test",
                    "Phone", "+1 202 555 0101"),
                Map.of(
                    "Name", "Bridge middle",
                    "Email", "bridge-b@example.test",
                    "Phone", "+1 202 555 0101"),
                Map.of(
                    "Name", "Bridge last",
                    "Email", "bridge-b@example.test",
                    "Phone", "+1 202 555 0102")),
            "fill_empty"));

        assertEquals(3, result.getInvalid());
        assertTrue(result.getRows().stream()
            .allMatch(row -> row.getErrors().stream()
                .anyMatch(error -> error.contains("explicit duplicate resolution"))));
    }

    @Test
    void personImport_autoCreatesCustomFieldAndAppliesValue() {
        ColumnMapping budget = new ColumnMapping("Budget", null, true, "number", "Budget");
        ImportResult result = reviewAndCommitPersons(req(
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
                Map.of("Company", "Acme One", "Web", "https://acme-dedupe.co.jp"),
                Map.of("Company", "Acme Two", "Web", "http://www.acme-dedupe.co.jp/")),
            "fill_empty"));

        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getToSkip());
        assertTrue(preview.getRows().stream()
            .allMatch(row -> "create".equals(row.getStatus())));
        assertTrue(preview.getRows().stream()
            .allMatch(row -> Integer.valueOf(0).equals(
                row.getCanonicalRowIndex())));
        assertTrue(preview.getRows().stream()
            .allMatch(row -> Integer.valueOf(2).equals(
                row.getMergedRowCount())));
    }

    @Test
    void companyImportCoalescesReferencesAndReplaysWithoutSideEffects() {
        Tag firstTag = newTag();
        Tag secondTag = newTag();
        CustomFieldDefinition custom = customDefinition("company");
        User secondActor = memberWithPermissions(
            "COMPANY_CREATE", "COMPANY_UPDATE");
        authenticateAs(currentUser, workspace.getId());
        User thirdActor = memberWithPermissions(
            "COMPANY_CREATE", "COMPANY_UPDATE");
        authenticateAs(currentUser, workspace.getId());
        String website = "https://company-coalesced-" + unique() + ".test";
        ImportRequest request = req(
            List.of(
                map("Company", "name"),
                map("Website", "website"),
                map("Phone", "phone"),
                map("Tags", "tags"),
                map("Custom", "custom:" + custom.getId())),
            List.of(
                Map.of(
                    "Company", "Canonical company",
                    "Website", website,
                    "Tags", firstTag.getName()),
                Map.of(
                    "Company", "Later company",
                    "Website", website,
                    "Phone", "+1 202 555 0199",
                    "Tags", secondTag.getName(),
                    "Custom", "unioned")),
            "fill_empty");

        ImportPreviewResult preview = importService.previewCompanies(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult first = importService.commitCompanies(request);
        Company imported = companyMapper.getAllCompanies(workspace.getId())
            .stream()
            .filter(company -> website.equals(company.getWebsite()))
            .findFirst()
            .orElseThrow();
        Map<String, Object> companyAfterFirst =
            companySnapshot(workspace.getId(), imported.getId());
        List<Map<String, Object>> identitiesAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT id, kind, `value`, normalized_value, source_system,
                       source_row_ref, acquired_at, superseded_at
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                ORDER BY id
                """,
                workspace.getId(),
                imported.getId());
        List<Map<String, Object>> tagsAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT company_id, tag_id
                FROM company_tag
                WHERE company_id = ?
                ORDER BY tag_id
                """,
                imported.getId());
        List<Map<String, Object>> customAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT definition_id, value_text, value_number,
                       value_date, value_bool, created_at, updated_at
                FROM custom_field_value
                WHERE workspace_id = ? AND entity_type = 'company'
                  AND entity_id = ?
                ORDER BY definition_id
                """,
                workspace.getId(),
                imported.getId());
        int auditCountAfterFirst = rowCount(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE workspace_id = ? AND action = 'import.company'",
            workspace.getId());

        authenticateAs(secondActor, workspace.getId());
        ImportResult second = reviewAndCommitCompanies(request);
        authenticateAs(thirdActor, workspace.getId());
        ImportResult third = reviewAndCommitCompanies(request);

        assertEquals(1, preview.getToCreate());
        assertEquals(1, first.getCreated());
        assertEquals("Canonical company", imported.getName());
        assertEquals("+1 202 555 0199", imported.getPhone());
        assertEquals(2, tagMapper.getTagsByCompanyId(
            workspace.getId(), imported.getId()).size());
        assertEquals(
            "unioned",
            customFieldValueService.getForEntities(
                "company", List.of(imported.getId()))
                .get(imported.getId())
                .get(custom.getId()));
        assertEquals(
            "csv-row:1",
            jdbcTemplate.queryForObject(
                """
                SELECT source_row_ref
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                  AND kind = 'domain' AND superseded_at IS NULL
                """,
                String.class,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            "csv-row:2",
            jdbcTemplate.queryForObject(
                """
                SELECT source_row_ref
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                  AND kind = 'phone' AND superseded_at IS NULL
                """,
                String.class,
                workspace.getId(),
                imported.getId()));
        assertEquals(0, second.getUpdated());
        assertEquals(0, third.getUpdated());
        assertEquals(
            companyAfterFirst,
            companySnapshot(workspace.getId(), imported.getId()));
        assertEquals(
            identitiesAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT id, kind, `value`, normalized_value, source_system,
                       source_row_ref, acquired_at, superseded_at
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                ORDER BY id
                """,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            tagsAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT company_id, tag_id
                FROM company_tag
                WHERE company_id = ?
                ORDER BY tag_id
                """,
                imported.getId()));
        assertEquals(
            customAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT definition_id, value_text, value_number,
                       value_date, value_bool, created_at, updated_at
                FROM custom_field_value
                WHERE workspace_id = ? AND entity_type = 'company'
                  AND entity_id = ?
                ORDER BY definition_id
                """,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            auditCountAfterFirst,
            rowCount(
                "SELECT COUNT(*) FROM audit_log "
                    + "WHERE workspace_id = ? AND action = 'import.company'",
                workspace.getId()));
    }

    @Test
    void personImportCoalescesComplementaryStrongKeyRowsWithUnionedReferences() {
        Company company = newCompany();
        Tag firstTag = newTag();
        Tag secondTag = newTag();
        CustomFieldDefinition custom = customDefinition("person");
        String email = "coalesced-" + unique() + "@example.test";
        ImportRequest request = req(
            List.of(
                map("Name", "name"),
                map("Email", "email"),
                map("Phone", "phone"),
                map("Title", "title"),
                map("Company", "company"),
                map("Tags", "tags"),
                map("Custom", "custom:" + custom.getId())),
            List.of(
                Map.of(
                    "Name", "Canonical person",
                    "Email", email,
                    "Title", "Director",
                    "Company", company.getName(),
                    "Tags", firstTag.getName()),
                Map.of(
                    "Name", "Later duplicate",
                    "Email", email.toUpperCase(),
                    "Phone", "+1 202 555 0104",
                    "Tags", secondTag.getName(),
                    "Custom", "unioned")),
            "fill_empty");

        ImportPreviewResult preview = importService.previewPersons(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult result = importService.commitPersons(request);

        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getToSkip());
        assertTrue(preview.getRows().stream()
            .allMatch(row -> "create".equals(row.getStatus())));
        assertTrue(preview.getRows().stream()
            .allMatch(row -> Integer.valueOf(0).equals(
                row.getCanonicalRowIndex())));
        assertTrue(preview.getRows().stream()
            .allMatch(row -> Integer.valueOf(2).equals(
                row.getMergedRowCount())));
        assertEquals(1, result.getCreated());
        assertEquals(0, result.getSkipped());
        List<Person> matches = personMapper.findByEmails(
            workspace.getId(), List.of(email));
        assertEquals(1, matches.size());
        Person imported = personMapper.getPersonById(
            workspace.getId(), matches.getFirst().getId());
        assertEquals("Canonical person", imported.getName());
        assertEquals("Director", imported.getTitle());
        assertEquals("+1 202 555 0104", imported.getPhone());
        assertEquals(company.getId(), imported.getCompany().getId());
        assertEquals(2, tagMapper.getTagsByPersonId(
            workspace.getId(), imported.getId()).size());
        assertEquals(
            "unioned",
            customFieldValueService.getForEntities(
                "person", List.of(imported.getId()))
                .get(imported.getId())
                .get(custom.getId()));
        assertEquals(
            "csv-row:1",
            jdbcTemplate.queryForObject(
                """
                SELECT source_row_ref
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                  AND kind = 'email' AND superseded_at IS NULL
                """,
                String.class,
                workspace.getId(),
                imported.getId()));
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
                "SELECT COUNT(*) FROM person_employment "
                    + "WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                imported.getId()));
    }

    @Test
    void personImportCoalescesOneTargetWithDeterministicPrecedenceAndOneWrite() {
        Company firstCompany = newCompany();
        Company secondCompany = newCompany();
        CustomFieldDefinition custom = customDefinition("person");
        Person target = new Person();
        target.setName("Canonical target");
        target.setWorkspaceId(workspace.getId());
        Person created = personService.create(target);
        List<ColumnMapping> mapping = List.of(
            map("Name", "name"),
            map("Title", "title"),
            map("Company", "company"),
            map("Custom", "custom:" + custom.getId()));
        ImportRequest fillEmpty = req(
            mapping,
            List.of(
                Map.of(
                    "Name", "First source",
                    "Title", "First title",
                    "Company", firstCompany.getName(),
                    "Custom", "first"),
                Map.of(
                    "Name", "Second source",
                    "Title", "Second title",
                    "Company", secondCompany.getName(),
                    "Custom", "second")),
            "fill_empty",
            Map.of(0, created.getId(), 1, created.getId()));

        ImportPreviewResult fillPreview =
            importService.previewPersons(fillEmpty);
        fillEmpty.setDuplicateReviewProof(
            fillPreview.getDuplicateReviewProof());
        clearInvocations(personMapperSpy);
        ImportResult fillResult = importService.commitPersons(fillEmpty);

        assertEquals(1, fillPreview.getToUpdate());
        assertTrue(fillPreview.getRows().stream()
            .allMatch(row -> created.getId() == row.getMatchedId()));
        assertEquals(1, fillResult.getUpdated());
        verify(personMapperSpy, times(1)).update(
            org.mockito.ArgumentMatchers.argThat(
                person -> person.getId() == created.getId()));
        Person filled = personMapper.getPersonById(
            workspace.getId(), created.getId());
        assertEquals("Canonical target", filled.getName());
        assertEquals("First title", filled.getTitle());
        assertEquals(firstCompany.getId(), filled.getCompany().getId());
        assertEquals(
            "first",
            customFieldValueService.getForEntities(
                "person", List.of(created.getId()))
                .get(created.getId())
                .get(custom.getId()));
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(*) FROM person_employment "
                    + "WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                created.getId()));

        ImportRequest overwrite = req(
            mapping,
            fillEmpty.getRows(),
            "overwrite",
            Map.of(0, created.getId(), 1, created.getId()));
        ImportPreviewResult overwritePreview =
            importService.previewPersons(overwrite);
        overwrite.setDuplicateReviewProof(
            overwritePreview.getDuplicateReviewProof());
        clearInvocations(personMapperSpy);
        ImportResult overwriteResult =
            importService.commitPersons(overwrite);

        assertEquals(1, overwriteResult.getUpdated());
        verify(personMapperSpy, times(1)).update(
            org.mockito.ArgumentMatchers.argThat(
                person -> person.getId() == created.getId()));
        Person overwritten = personMapper.getPersonById(
            workspace.getId(), created.getId());
        assertEquals("Second source", overwritten.getName());
        assertEquals("Second title", overwritten.getTitle());
        assertEquals(secondCompany.getId(), overwritten.getCompany().getId());
        assertEquals(
            "second",
            customFieldValueService.getForEntities(
                "person", List.of(created.getId()))
                .get(created.getId())
                .get(custom.getId()));
        assertEquals(
            2,
            rowCount(
                "SELECT COUNT(*) FROM person_employment "
                    + "WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                created.getId()));
    }

    @Test
    void personImportThreeIdenticalReplaysKeepCanonicalStateStable() {
        Company company = newCompany();
        Tag tag = newTag();
        CustomFieldDefinition custom = customDefinition("person", "number");
        User secondActor = memberWithPermissions(
            "PERSON_CREATE", "PERSON_UPDATE");
        authenticateAs(currentUser, workspace.getId());
        User thirdActor = memberWithPermissions(
            "PERSON_CREATE", "PERSON_UPDATE");
        authenticateAs(currentUser, workspace.getId());
        String email = "stable-replay-" + unique() + "@example.test";
        ImportRequest request = req(
            List.of(
                map("Name", "name"),
                map("Email", "email"),
                map("Phone", "phone"),
                map("Company", "company"),
                map("Tags", "tags"),
                map("Custom", "custom:" + custom.getId())),
            List.of(Map.of(
                "Name", "Stable replay",
                "Email", email,
                "Phone", "+1 202 555 0105",
                "Company", company.getName(),
                "Tags", tag.getName(),
                "Custom", "1.23456")),
            "overwrite");

        ImportResult first = reviewAndCommitPersons(request);
        Person imported = personMapper.getPersonById(
            workspace.getId(),
            personMapper.findByEmails(
                workspace.getId(), List.of(email)).getFirst().getId());
        Map<String, Object> recordAfterFirst =
            personSnapshot(workspace.getId(), imported.getId());
        List<Map<String, Object>> identitiesAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT id, kind, `value`, normalized_value, source_system,
                       source_row_ref, acquired_at, superseded_at
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                ORDER BY id
                """,
                workspace.getId(),
                imported.getId());
        List<Map<String, Object>> referencesAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT person_id, tag_id
                FROM person_tag
                WHERE person_id = ?
                ORDER BY tag_id
                """,
                imported.getId());
        List<Map<String, Object>> customAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT definition_id, value_text, value_number,
                       value_date, value_bool, created_at, updated_at
                FROM custom_field_value
                WHERE workspace_id = ? AND entity_type = 'person'
                  AND entity_id = ?
                ORDER BY definition_id
                """,
                workspace.getId(),
                imported.getId());
        List<Map<String, Object>> historyAfterFirst =
            jdbcTemplate.queryForList(
                """
                SELECT company_id, company_name, title, started_at, ended_at
                FROM person_employment
                WHERE workspace_id = ? AND person_id = ?
                ORDER BY id
                """,
                workspace.getId(),
                imported.getId());
        int auditCountAfterFirst = rowCount(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE workspace_id = ? AND action = 'import.person'",
            workspace.getId());

        authenticateAs(secondActor, workspace.getId());
        ImportResult second = reviewAndCommitPersons(request);
        authenticateAs(thirdActor, workspace.getId());
        ImportResult third = reviewAndCommitPersons(request);

        assertEquals(1, first.getCreated());
        assertEquals(0, second.getUpdated());
        assertEquals(0, third.getUpdated());
        assertEquals(
            auditCountAfterFirst,
            rowCount(
                "SELECT COUNT(*) FROM audit_log "
                    + "WHERE workspace_id = ? AND action = 'import.person'",
                workspace.getId()));
        assertEquals(
            recordAfterFirst,
            personSnapshot(workspace.getId(), imported.getId()));
        assertEquals(
            identitiesAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT id, kind, `value`, normalized_value, source_system,
                       source_row_ref, acquired_at, superseded_at
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ?
                ORDER BY id
                """,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            referencesAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT person_id, tag_id
                FROM person_tag
                WHERE person_id = ?
                ORDER BY tag_id
                """,
                imported.getId()));
        assertEquals(
            customAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT definition_id, value_text, value_number,
                       value_date, value_bool, created_at, updated_at
                FROM custom_field_value
                WHERE workspace_id = ? AND entity_type = 'person'
                  AND entity_id = ?
                ORDER BY definition_id
                """,
                workspace.getId(),
                imported.getId()));
        assertEquals(
            historyAfterFirst,
            jdbcTemplate.queryForList(
                """
                SELECT company_id, company_name, title, started_at, ended_at
                FROM person_employment
                WHERE workspace_id = ? AND person_id = ?
                ORDER BY id
                """,
                workspace.getId(),
                imported.getId()));
    }

    @Test
    void personImportRejectsManualTargetWhenIdentityBelongsToAnotherRecord() {
        Person target = new Person();
        target.setName("Manual identity target");
        target.setEmail("manual-target-" + unique() + "@example.test");
        Person selected = personService.create(target);
        Person conflicting = new Person();
        conflicting.setName("Conflicting identity owner");
        conflicting.setEmail("identity-owner-" + unique() + "@example.test");
        Person owner = personService.create(conflicting);
        ImportRequest request = req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of(
                "Name", "Rejected manual update",
                "Email", owner.getEmail())),
            "overwrite",
            Map.of(0, selected.getId()));

        ImportResult result = reviewAndCommitPersons(request);

        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().getFirst().getReason()
            .contains("belongs to another contact"));
        assertEquals(
            target.getEmail(),
            personMapper.getPersonById(
                workspace.getId(), selected.getId()).getEmail());
    }

    @Test
    void companyImportRejectsManualTargetWhenIdentityBelongsToAnotherRecord() {
        Company selectedDraft = new Company();
        selectedDraft.setName("Selected company " + unique());
        selectedDraft.setWebsite(
            "https://selected-" + unique() + ".test");
        Company selected = companyService.createCompany(selectedDraft);
        Company ownerDraft = new Company();
        ownerDraft.setName("Identity owner " + unique());
        ownerDraft.setWebsite(
            "https://identity-owner-" + unique() + ".test");
        Company owner = companyService.createCompany(ownerDraft);
        ImportRequest request = req(
            List.of(
                map("Name", "name"),
                map("Website", "website")),
            List.of(Map.of(
                "Name", "Rejected manual company update",
                "Website", owner.getWebsite())),
            "overwrite",
            Map.of(0, selected.getId()));

        ImportResult result = reviewAndCommitCompanies(request);

        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().getFirst().getReason()
            .contains("belongs to another company"));
        assertFalse(
            owner.getWebsite().equals(
                companyMapper.getCompanyById(
                    workspace.getId(), selected.getId()).getWebsite()));
    }

    @Test
    void manualImportsRepairMissingCanonicalIdentitiesOnce() {
        User replayActor = memberWithPermissions(
            "PERSON_CREATE",
            "PERSON_UPDATE",
            "COMPANY_CREATE",
            "COMPANY_UPDATE");
        authenticateAs(currentUser, workspace.getId());
        Person personDraft = new Person();
        personDraft.setName("Identity repair person " + unique());
        personDraft.setEmail(
            "identity-repair-" + unique() + "@example.test");
        Person person = personService.create(personDraft);
        Company companyDraft = new Company();
        companyDraft.setName("Identity repair company " + unique());
        companyDraft.setWebsite(
            "https://identity-repair-" + unique() + ".example.test");
        Company company = companyService.createCompany(companyDraft);
        assertEquals(1, jdbcTemplate.update(
            "DELETE FROM person_identity "
                + "WHERE workspace_id = ? AND person_id = ? AND kind = 'email'",
            workspace.getId(),
            person.getId()));
        assertEquals(1, jdbcTemplate.update(
            "DELETE FROM company_identity "
                + "WHERE workspace_id = ? AND company_id = ? AND kind = 'domain'",
            workspace.getId(),
            company.getId()));
        ImportRequest personRequest = req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of(
                "Name", person.getName(),
                "Email", person.getEmail())),
            "fill_empty",
            Map.of(0, person.getId()));
        ImportRequest companyRequest = req(
            List.of(
                map("Name", "name"),
                map("Website", "website")),
            List.of(Map.of(
                "Name", company.getName(),
                "Website", company.getWebsite())),
            "fill_empty",
            Map.of(0, company.getId()));

        ImportResult firstPerson =
            reviewAndCommitPersons(personRequest);
        ImportResult firstCompany =
            reviewAndCommitCompanies(companyRequest);
        authenticateAs(replayActor, workspace.getId());
        ImportResult replayedPerson =
            reviewAndCommitPersons(personRequest);
        ImportResult replayedCompany =
            reviewAndCommitCompanies(companyRequest);

        assertEquals(1, firstPerson.getUpdated());
        assertEquals(1, firstCompany.getUpdated());
        assertEquals(0, replayedPerson.getUpdated());
        assertEquals(0, replayedCompany.getUpdated());
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(*) FROM person_identity "
                    + "WHERE workspace_id = ? AND person_id = ? "
                    + "AND kind = 'email' AND superseded_at IS NULL",
                workspace.getId(),
                person.getId()));
        assertEquals(
            1,
            rowCount(
                "SELECT COUNT(*) FROM company_identity "
                    + "WHERE workspace_id = ? AND company_id = ? "
                    + "AND kind = 'domain' AND superseded_at IS NULL",
                workspace.getId(),
                company.getId()));
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
        ImportResult result = reviewAndCommitPersons(request);

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
        ImportResult result = reviewAndCommitCompanies(request);

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
        ImportResult personResult = reviewAndCommitPersons(personRequest);
        ImportPreviewResult companyPreview = importService.previewCompanies(companyRequest);
        ImportResult companyResult = reviewAndCommitCompanies(companyRequest);

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
        ImportResult personResult = reviewAndCommitPersons(personRequest);
        ImportPreviewResult companyPreview = importService.previewCompanies(companyRequest);
        ImportResult companyResult = reviewAndCommitCompanies(companyRequest);

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

        ImportResult noOpPerson = reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of("Name", personName, "Email", personEmail)),
            "fill_empty",
            Map.of(0, person.getId())));
        ImportResult noOpCompany = reviewAndCommitCompanies(req(
            List.of(map("Name", "name"), map("Website", "website")),
            List.of(Map.of("Name", companyName, "Website", companyWebsite)),
            "fill_empty",
            Map.of(0, company.getId())));
        assertEquals(0, noOpPerson.getUpdated());
        assertTrue(noOpPerson.getFailed().isEmpty());
        assertEquals(0, noOpCompany.getUpdated());
        assertTrue(noOpCompany.getFailed().isEmpty());
    }

    @Test
    void recordImports_failVanishedMatchesBeforeTargetSideEffects() {
        Person person = newPerson(newCompany());
        Company company = newCompany();
        Company replacementCompany = newCompany();
        Tag tag = newTag();
        CustomFieldDefinition personCustom = customDefinition("person");
        CustomFieldDefinition companyCustom = customDefinition("company");
        ImportRequest personRequest = req(
            List.of(
                map("Name", "name"),
                map("Company", "company"),
                map("Tags", "tags"),
                map("Custom", "custom:" + personCustom.getId())),
            List.of(Map.of(
                "Name", "Vanished person update",
                "Company", replacementCompany.getName(),
                "Tags", tag.getName(),
                "Custom", "vanished value")),
            "overwrite",
            Map.of(0, person.getId()));
        ImportRequest companyRequest = req(
            List.of(
                map("Name", "name"),
                map("Tags", "tags"),
                map("Custom", "custom:" + companyCustom.getId())),
            List.of(Map.of(
                "Name", "Vanished company update",
                "Tags", tag.getName(),
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
        doReturn(null).when(personMapperSpy).getOwnedPersonByIdForUpdate(workspace.getId(), person.getId());
        doReturn(null).when(companyMapperSpy).getOwnedCompanyByIdForUpdate(workspace.getId(), company.getId());

        ImportResult personResult = reviewAndCommitPersons(personRequest);
        ImportResult companyResult = reviewAndCommitCompanies(companyRequest);

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
    }

    @Test
    void personImportRejectsRestrictedManualLinks() {
        Person suspended = newPerson(newCompany());
        Person provisionCeased = newPerson(newCompany());
        personService.updateProcessingRestrictions(suspended.getId(), true, false);
        personService.updateProcessingRestrictions(provisionCeased.getId(), false, true);

        ImportRequest request = req(
            List.of(map("Name", "name")),
            List.of(
                Map.of("Name", "Suspended update"),
                Map.of("Name", "Provision-ceased update")),
            "overwrite",
            Map.of(0, suspended.getId(), 1, provisionCeased.getId()));
        ImportPreviewResult preview = importService.previewPersons(request);
        ImportResult result = reviewAndCommitPersons(request);

        assertEquals(2, preview.getInvalid());
        assertEquals(0, result.getUpdated());
        assertEquals(2, result.getFailed().size());
        assertFalse(personMapper.getPersonById(
            workspace.getId(), suspended.getId()).getName().equals("Suspended update"));
        assertFalse(personMapper.getPersonById(
            workspace.getId(), provisionCeased.getId()).getName().equals("Provision-ceased update"));
    }

    @Test
    void personImportRejectsAutomaticMatchWhoseIdentityChangedBeforeLockValidation() {
        Person target = new Person();
        target.setName("Changing automatic target");
        target.setEmail("changing-target-" + unique() + "@example.test");
        Person created = personService.create(target);
        doReturn(List.of()).when(identityMapperSpy)
            .findCurrentPersonIdentityMatches(
                workspace.getId(),
                "email",
                List.of(target.getEmail()));

        ImportResult result = reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of(
                "Name", "Stale automatic update",
                "Email", created.getEmail())),
            "overwrite"));

        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().getFirst().getReason()
            .contains("no longer uniquely carries the supplied identities"));
    }

    @Test
    void personImportRejectsAutomaticMatchRestrictedBeforeTargetValidation() {
        Person target = new Person();
        target.setName("Restricted automatic target");
        target.setEmail("restricted-target-" + unique() + "@example.test");
        Person created = personService.create(target);
        Person restricted = personMapper.getPersonById(workspace.getId(), created.getId());
        restricted.setSuspendedAt(LocalDateTime.now());
        doReturn(restricted).when(personMapperSpy)
            .getOwnedPersonByIdForUpdate(workspace.getId(), created.getId());

        ImportResult result = reviewAndCommitPersons(req(
            List.of(map("Name", "name"), map("Email", "email")),
            List.of(Map.of(
                "Name", "Restricted automatic update",
                "Email", created.getEmail())),
            "overwrite"));

        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().getFirst().getReason().contains("unavailable"));
    }

    @Test
    void recordImportsLockMatchedTargetsByAscendingIdRegardlessOfRowOrder() {
        Person lowerPerson = newPerson(newCompany());
        Person higherPerson = newPerson(newCompany());
        Company lowerCompany = newCompany();
        Company higherCompany = newCompany();
        clearInvocations(personMapperSpy, companyMapperSpy);

        ImportResult personResult = reviewAndCommitPersons(req(
            List.of(map("Name", "name")),
            List.of(
                Map.of("Name", "Higher person update"),
                Map.of("Name", "Lower person update")),
            "overwrite",
            Map.of(0, higherPerson.getId(), 1, lowerPerson.getId())));
        ImportResult companyResult = reviewAndCommitCompanies(req(
            List.of(map("Name", "name")),
            List.of(
                Map.of("Name", "Higher company update"),
                Map.of("Name", "Lower company update")),
            "overwrite",
            Map.of(0, higherCompany.getId(), 1, lowerCompany.getId())));

        assertEquals(2, personResult.getUpdated());
        assertEquals(2, companyResult.getUpdated());
        InOrder personLocks = inOrder(personMapperSpy);
        personLocks.verify(personMapperSpy).getOwnedPersonByIdForUpdate(
            workspace.getId(), lowerPerson.getId());
        personLocks.verify(personMapperSpy).getOwnedPersonByIdForUpdate(
            workspace.getId(), higherPerson.getId());
        InOrder companyLocks = inOrder(companyMapperSpy);
        companyLocks.verify(companyMapperSpy).getOwnedCompanyByIdForUpdate(
            workspace.getId(), lowerCompany.getId());
        companyLocks.verify(companyMapperSpy).getOwnedCompanyByIdForUpdate(
            workspace.getId(), higherCompany.getId());
    }

    @Test
    void personImportContinuesUnrelatedRowsWhenOneMatchedTargetVanishes() {
        Person vanished = newPerson(newCompany());
        Person retained = newPerson(newCompany());
        doReturn(null).when(personMapperSpy).getOwnedPersonByIdForUpdate(
            workspace.getId(), vanished.getId());

        ImportResult result = reviewAndCommitPersons(req(
            List.of(map("Name", "name")),
            List.of(
                Map.of("Name", "Vanished update"),
                Map.of("Name", "Retained update")),
            "overwrite",
            Map.of(0, vanished.getId(), 1, retained.getId())));

        assertEquals(1, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertEquals(
            "Retained update",
            personMapper.getPersonById(workspace.getId(), retained.getId()).getName());
    }

    @Test
    void dealImport_resolvesStageByNameAndDedupesByNameAndCompany() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        List<ColumnMapping> mapping = List.of(map("Deal", "name"), map("Pipe", "pipeline"), map("Stage", "stage"));
        List<Map<String, String>> rows = List.of(
            Map.of("Deal", "Import Deal", "Pipe", pipeline.getName(), "Stage", stage.getName()));

        ImportResult first = reviewAndCommitDeals(req(mapping, rows, "fill_empty"));
        assertEquals(1, first.getCreated());

        ImportResult second = reviewAndCommitDeals(req(mapping, rows, "overwrite"));
        assertEquals(0, second.getCreated());
        assertEquals(0, second.getUpdated());
    }

    @Test
    void dealImportFillEmptyTreatsScaleTwoZeroAsEmpty() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateValueAndSource(
            workspace.getId(), deal.getId(), new BigDecimal("0.00"), "manual");

        ImportResult result = reviewAndCommitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Value", "value"),
                map("Company", "company")),
            List.of(Map.of(
                "Deal", deal.getName(),
                "Value", "125.50",
                "Company", company.getName())),
            "fill_empty"));

        Deal updated = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(1, result.getUpdated());
        assertEquals(0, new BigDecimal("125.50").compareTo(updated.getValue()));
        assertEquals("manual", updated.getValueSource());
    }

    @Test
    void dealImportReplayPreservesParticipantRoleWithoutAuditSideEffects() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        Person person = newPerson(company);
        dealMapper.addPerson(
            workspace.getId(), deal.getId(), person.getId(), "champion");
        int auditCountBefore = rowCount(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE action = 'import.deal' AND actor_id = ?",
            currentUser.getId());

        ImportResult result = reviewAndCommitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Company", "company"),
                map("People", "people")),
            List.of(Map.of(
                "Deal", deal.getName(),
                "Company", company.getName(),
                "People", person.getEmail())),
            "fill_empty"));

        assertEquals(0, result.getUpdated());
        assertEquals(
            "champion",
            jdbcTemplate.queryForObject(
                "SELECT role FROM deal_person "
                    + "WHERE deal_id = ? AND person_id = ?",
                String.class,
                deal.getId(),
                person.getId()));
        assertEquals(
            auditCountBefore,
            rowCount(
                "SELECT COUNT(*) FROM audit_log "
                    + "WHERE action = 'import.deal' AND actor_id = ?",
                currentUser.getId()));
    }

    @Test
    void dealImportCanonicalKeyEncodingKeepsDistinctRowsSeparate() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        String prefix = "Collision-" + unique();
        ImportRequest request = req(
            List.of(
                map("Deal", "name"),
                map("Company", "company"),
                map("Pipeline", "pipeline"),
                map("Stage", "stage")),
            List.of(
                Map.of(
                    "Deal", prefix,
                    "Company", "B:name:C",
                    "Pipeline", pipeline.getName(),
                    "Stage", stage.getName()),
                Map.of(
                    "Deal", prefix + ":name:B",
                    "Company", "C",
                    "Pipeline", pipeline.getName(),
                    "Stage", stage.getName())),
            "fill_empty");

        ImportPreviewResult preview = importService.previewDeals(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult result = importService.commitDeals(request);

        assertEquals(2, preview.getToCreate());
        assertEquals(2, result.getCreated());
        assertEquals(
            2,
            dealMapper.getAllDeals(workspace.getId()).stream()
                .filter(deal -> deal.getName().startsWith(prefix))
                .count());
    }

    @Test
    void dealImportCoalescesSameFileRowsWithUnionedParticipantsAndTags() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Person firstPerson = new Person();
        firstPerson.setName("First participant");
        firstPerson.setEmail("first-participant-" + unique() + "@example.test");
        Person first = personService.create(firstPerson);
        Person secondPerson = new Person();
        secondPerson.setName("Second participant");
        secondPerson.setEmail("second-participant-" + unique() + "@example.test");
        Person second = personService.create(secondPerson);
        Tag firstTag = newTag();
        Tag secondTag = newTag();
        CustomFieldDefinition custom = customDefinition("deal");
        String dealName = "Coalesced deal " + unique();
        ImportRequest request = req(
            List.of(
                map("Deal", "name"),
                map("Pipe", "pipeline"),
                map("Stage", "stage"),
                map("Company", "company"),
                map("People", "people"),
                map("Tags", "tags"),
                map("Custom", "custom:" + custom.getId())),
            List.of(
                Map.of(
                    "Deal", dealName,
                    "Pipe", pipeline.getName(),
                    "Stage", stage.getName(),
                    "Company", company.getName(),
                    "People", first.getEmail(),
                    "Tags", firstTag.getName(),
                    "Custom", "first"),
                Map.of(
                    "Deal", dealName,
                    "Pipe", pipeline.getName(),
                    "Stage", stage.getName(),
                    "Company", company.getName(),
                    "People", second.getEmail(),
                    "Tags", secondTag.getName(),
                    "Custom", "second")),
            "overwrite");

        ImportPreviewResult preview = importService.previewDeals(request);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());
        ImportResult result = importService.commitDeals(request);

        Deal imported = dealMapper.getAllDeals(workspace.getId()).stream()
            .filter(deal -> dealName.equals(deal.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals(1, preview.getToCreate());
        assertEquals(0, preview.getToSkip());
        assertTrue(preview.getRows().stream()
            .allMatch(row -> "create".equals(row.getStatus())));
        assertTrue(preview.getRows().stream()
            .allMatch(row -> Integer.valueOf(0).equals(
                row.getCanonicalRowIndex())));
        assertTrue(preview.getRows().stream()
            .allMatch(row -> Integer.valueOf(2).equals(
                row.getMergedRowCount())));
        assertEquals(1, result.getCreated());
        assertEquals(2, dealMapper.getDealPeopleByDealId(
            workspace.getId(), imported.getId()).size());
        assertEquals(2, tagMapper.getTagsByDealId(
            workspace.getId(), imported.getId()).size());
        assertEquals(
            "second",
            customFieldValueService.getForEntities(
                "deal", List.of(imported.getId()))
                .get(imported.getId())
                .get(custom.getId()));
        assertEquals(
            1,
            dealStageHistoryService.getHistory(imported.getId()).size());
    }

    @Test
    void dealImportCoalescesOneTargetIntoOneFinalStageTransition() {
        Pipeline pipeline = newPipeline();
        Stage initialStage = newStage(pipeline, 0);
        Stage middleStage = newStage(pipeline, 1);
        Stage finalStage = newStage(pipeline, 2);
        String dealName = "One stage transition " + unique();
        ImportResult created = reviewAndCommitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Pipe", "pipeline"),
                map("Stage", "stage")),
            List.of(Map.of(
                "Deal", dealName,
                "Pipe", pipeline.getName(),
                "Stage", initialStage.getName())),
            "fill_empty"));
        assertEquals(1, created.getCreated());
        Deal deal = dealMapper.getAllDeals(workspace.getId()).stream()
            .filter(candidate -> dealName.equals(candidate.getName()))
            .findFirst()
            .orElseThrow();
        ImportRequest update = req(
            List.of(
                map("Deal", "name"),
                map("Pipe", "pipeline"),
                map("Stage", "stage")),
            List.of(
                Map.of(
                    "Deal", dealName,
                    "Pipe", pipeline.getName(),
                    "Stage", middleStage.getName()),
                Map.of(
                    "Deal", dealName,
                    "Pipe", pipeline.getName(),
                    "Stage", finalStage.getName())),
            "overwrite",
            Map.of(0, deal.getId(), 1, deal.getId()));

        ImportResult result = reviewAndCommitDeals(update);

        assertEquals(1, result.getUpdated());
        assertEquals(
            finalStage.getId(),
            dealMapper.getDealById(
                workspace.getId(), deal.getId()).getStageId());
        assertEquals(
            2,
            dealStageHistoryService.getHistory(deal.getId()).size());
    }

    @Test
    void dealImportFailsClosedOnAmbiguousCompositeMatches() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        String dealName = "Ambiguous composite " + unique();
        Deal first = new Deal();
        first.setWorkspaceId(workspace.getId());
        first.setOwnerId(currentUser.getId());
        first.setName(dealName);
        first.setCurrency("USD");
        first.setPipelineId(pipeline.getId());
        first.setStageId(stage.getId());
        dealMapper.insert(first);
        Deal second = new Deal();
        second.setWorkspaceId(workspace.getId());
        second.setOwnerId(currentUser.getId());
        second.setName(dealName);
        second.setCurrency("USD");
        second.setPipelineId(pipeline.getId());
        second.setStageId(stage.getId());
        dealMapper.insert(second);

        ImportPreviewResult preview = importService.previewDeals(req(
            List.of(map("Deal", "name")),
            List.of(Map.of("Deal", dealName)),
            "overwrite"));

        assertEquals(1, preview.getInvalid());
        assertTrue(preview.getRows().getFirst().getErrors().getFirst()
            .contains("Multiple owned deals"));
    }

    @Test
    void dealImportReplayUsesPersistedMoneyPrecision() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        User replayActor = memberWithPermissions(
            "DEAL_CREATE", "DEAL_UPDATE");
        authenticateAs(currentUser, workspace.getId());
        String dealName = "Rounded replay " + unique();
        ImportRequest request = req(
            List.of(
                map("Deal", "name"),
                map("Value", "value"),
                map("Pipeline", "pipeline"),
                map("Stage", "stage")),
            List.of(Map.of(
                "Deal", dealName,
                "Value", "1.005",
                "Pipeline", pipeline.getName(),
                "Stage", stage.getName())),
            "overwrite");

        ImportResult first = reviewAndCommitDeals(request);
        int auditCountAfterFirst = rowCount(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE workspace_id = ? AND action = 'import.deal'",
            workspace.getId());
        authenticateAs(replayActor, workspace.getId());
        ImportResult replayed = reviewAndCommitDeals(request);
        Deal imported = dealMapper.getAllDeals(workspace.getId()).stream()
            .filter(deal -> dealName.equals(deal.getName()))
            .findFirst()
            .orElseThrow();

        assertEquals(1, first.getCreated());
        assertEquals(0, replayed.getUpdated());
        assertEquals(0, new BigDecimal("1.01").compareTo(imported.getValue()));
        assertEquals(
            auditCountAfterFirst,
            rowCount(
                "SELECT COUNT(*) FROM audit_log "
                    + "WHERE workspace_id = ? AND action = 'import.deal'",
                workspace.getId()));
    }

    @Test
    void dealImportRejectsAMatchThatAppearsAfterPreview() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        String dealName = "Late deal match " + unique();
        ImportRequest request = req(
            List.of(map("Deal", "name")),
            List.of(Map.of("Deal", dealName)),
            "fill_empty");
        ImportPreviewResult preview = importService.previewDeals(request);
        Deal competing = new Deal();
        competing.setWorkspaceId(workspace.getId());
        competing.setOwnerId(currentUser.getId());
        competing.setName(dealName);
        competing.setCurrency("USD");
        competing.setPipelineId(pipeline.getId());
        competing.setStageId(stage.getId());
        dealMapper.insert(competing);
        request.setDuplicateReviewProof(preview.getDuplicateReviewProof());

        assertThrows(
            ConflictException.class,
            () -> importService.commitDeals(request));
    }

    @Test
    void dealImportRejectsAValueColumnWhenMatchedDealHasLineItems() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        addLineItem(deal, "25.00");

        ImportResult result = reviewAndCommitDeals(req(
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
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().get(0).getReason().contains("line items"));
        assertEquals(0, new BigDecimal("25.00").compareTo(updated.getValue()));
        assertNull(updated.getExpectedCloseDate());
    }

    @Test
    void dealImportRejectsACurrencyChangeWhenMatchedDealHasLineItems() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        addLineItem(deal, "25.00");

        ImportResult result = reviewAndCommitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Currency", "currency"),
                map("Company", "company")),
            List.of(Map.of(
                "Deal", deal.getName(),
                "Currency", "USD",
                "Company", company.getName())),
            "overwrite"));

        Deal updated = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(0, result.getUpdated());
        assertEquals(1, result.getFailed().size());
        assertTrue(result.getFailed().get(0).getReason().contains("currency"));
        assertEquals("JPY", updated.getCurrency());
    }

    @Test
    void dealImportLosingAWonDealRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = terminalStage(pipeline, 1, false);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);
        addLineItem(deal, "5000000.00");
        dealService.close(deal.getId(), true, "Signed", null);

        ImportResult result = reviewAndCommitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Pipe", "pipeline"),
                map("Stage", "stage"),
                map("Company", "company")),
            List.of(Map.of(
                "Deal", deal.getName(),
                "Pipe", pipeline.getName(),
                "Stage", lost.getName(),
                "Company", company.getName())),
            "overwrite"));

        Deal updated = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(1, result.getUpdated());
        assertEquals(Boolean.FALSE, updated.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(updated.getActualValue()));
    }

    @Test
    void dealImportWinningALineItemDealDerivesRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = terminalStage(pipeline, 1, true);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);
        addLineItem(deal, "5000000.00");

        ImportResult result = reviewAndCommitDeals(req(
            List.of(
                map("Deal", "name"),
                map("Pipe", "pipeline"),
                map("Stage", "stage"),
                map("Company", "company")),
            List.of(Map.of(
                "Deal", deal.getName(),
                "Pipe", pipeline.getName(),
                "Stage", won.getName(),
                "Company", company.getName())),
            "overwrite"));

        Deal updated = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(1, result.getUpdated());
        assertEquals(Boolean.TRUE, updated.getWon());
        assertEquals(0, new BigDecimal("5000000.00").compareTo(updated.getActualValue()));
    }

    @Test
    void dealImportCreatingOnAWonStageRecordsTheImportedValueAsRealized() {
        Pipeline pipeline = newPipeline();
        Stage won = terminalStage(pipeline, 0, true);

        ImportResult result = reviewAndCommitDeals(req(
            List.of(map("Deal", "name"), map("Value", "value"),
                map("Pipe", "pipeline"), map("Stage", "stage")),
            List.of(Map.of(
                "Deal", "Imported Won " + unique(),
                "Value", "5000000.00",
                "Pipe", pipeline.getName(),
                "Stage", won.getName())),
            "fill_empty"));

        assertEquals(1, result.getCreated());
        List<Deal> deals = dealMapper.getAllDeals(workspace.getId());
        assertEquals(1, deals.size());
        assertEquals(Boolean.TRUE, deals.get(0).getWon());
        assertEquals(0, new BigDecimal("5000000.00").compareTo(deals.get(0).getActualValue()));
    }

    @Test
    void dealImportCreatingOnALostStageRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage lost = terminalStage(pipeline, 0, false);

        ImportResult result = reviewAndCommitDeals(req(
            List.of(map("Deal", "name"), map("Value", "value"),
                map("Pipe", "pipeline"), map("Stage", "stage")),
            List.of(Map.of(
                "Deal", "Imported Lost " + unique(),
                "Value", "5000000.00",
                "Pipe", pipeline.getName(),
                "Stage", lost.getName())),
            "fill_empty"));

        assertEquals(1, result.getCreated());
        List<Deal> deals = dealMapper.getAllDeals(workspace.getId());
        assertEquals(1, deals.size());
        assertEquals(Boolean.FALSE, deals.get(0).getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(deals.get(0).getActualValue()));
    }

    private Stage terminalStage(Pipeline pipeline, int position, boolean success) {
        Stage stage = new Stage();
        stage.setName((success ? "Won " : "Lost ") + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setWorkspaceId(workspace.getId());
        if (success) {
            stage.setSuccess(true);
        } else {
            stage.setFailure(true);
        }
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private void addLineItem(Deal deal, String unitPrice) {
        DealLineItemRequest lineItem = new DealLineItemRequest();
        lineItem.setName("Imported deal line " + unique());
        lineItem.setUnitPrice(new BigDecimal(unitPrice));
        lineItem.setQuantity(BigDecimal.ONE);
        dealLineItemService.create(deal.getId(), lineItem);
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

        ImportResult result = reviewAndCommitDeals(req(
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

        ImportResult result = reviewAndCommitDeals(req(
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
        ImportResult result = reviewAndCommitDeals(req(
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
        reviewAndCommitPersons(req(
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
        reviewAndCommitPersons(req(mapping,
            List.of(Map.of("Name", "Erin", "Email", "erin@x.test", "Budget", "5000")), "fill_empty"));

        ImportResult second = reviewAndCommitPersons(req(mapping,
            List.of(Map.of("Name", "Erin", "Email", "erin@x.test", "Budget", "9999")), "fill_empty"));
        assertEquals(0, second.getUpdated());

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
        reviewAndCommitPersons(req(mapping,
            List.of(Map.of("Name", "Gwen", "Email", "gwen@x.test", "Company", a.getName())), "fill_empty"));

        ImportResult result = reviewAndCommitPersons(req(mapping,
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
        reviewAndCommitPersons(req(mapping, List.of(Map.of("Name", "Fred", "Email", "fred@x.test")), "fill_empty"));

        memberWithPermissions("PERSON_CREATE");
        ImportRequest duplicate = req(
            mapping, List.of(Map.of("Name", "Fred Updated", "Email", "fred@x.test")), "fill_empty");

        assertThrows(ForbiddenException.class, () -> importService.previewPersons(duplicate));
        assertThrows(ForbiddenException.class, () -> reviewAndCommitPersons(duplicate));
    }

    @Test
    void companyAndDealPreviews_requireUpdatePermissionForMatchedRows() {
        Company companyDraft = new Company();
        companyDraft.setName("Permission company " + unique());
        companyDraft.setWebsite("https://permission-" + unique() + ".example.com");
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
        reviewAndCommitPersons(req(
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
        ImportResult result = reviewAndCommitPersons(request);

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
        ImportResult result = reviewAndCommitPersons(request);

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
        reviewAndCommitPersons(req(
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
        ImportResult skippedResult = reviewAndCommitPersons(skipped);
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
        workspaceMapper.insert(other);
        return other;
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
        return customDefinition(entityType, "text");
    }

    private CustomFieldDefinition customDefinition(
            String entityType,
            String fieldType) {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setEntityType(entityType);
        definition.setFieldKey("import_guard_" + unique());
        definition.setLabel("Import guard " + unique());
        definition.setFieldType(fieldType);
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
