package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DealDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupPageRow;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;

class DuplicatePreflightDatabaseTest extends AbstractServiceTest {

    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;
    @Autowired private IdentityCollisionMapper identityCollisionMapper;
    @Autowired private DealDuplicateReviewProofMapper dealDuplicateReviewProofMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private DuplicatePreflightProperties duplicatePreflightProperties;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void personPreflightCombinesStrongCanonicalEvidenceAndWeakExactName() {
        Person person = createPerson(
            "Ada Lovelace", "ada@example.com", "090-1234-5678");

        DuplicatePreflightResponse response =
            duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    "  ADA   LOVELACE ",
                    List.of("ADA@EXAMPLE.COM"),
                    List.of("+81 90 1234 5678")));

        assertEquals(1, response.candidates().size());
        assertEquals(
            DuplicateMatchStrength.STRONG,
            response.candidates().getFirst().strength());
        assertEquals(
            Set.of(
                DuplicateMatchKind.EMAIL,
                DuplicateMatchKind.PHONE,
                DuplicateMatchKind.NAME),
            response.candidates().getFirst().matches().stream()
                .map(match -> match.kind())
                .collect(Collectors.toSet()));

        DuplicatePreflightResponse weak =
            duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    "ada lovelace", List.of(), List.of()));
        DuplicatePreflightResponse fuzzy =
            duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    "ada lovelac", List.of(), List.of()));

        assertEquals(
            DuplicateMatchStrength.WEAK,
            weak.candidates().getFirst().strength());
        assertTrue(fuzzy.candidates().isEmpty());
    }

    @Test
    void personPreflightReturnsCollisionsAndExcludesSupersededValues() {
        Person first = createPerson(
            "First shared key", "shared-key@example.com", null);
        createPerson("Second shared key", "shared-key@example.com", null);

        DuplicatePreflightResponse collision =
            duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    null,
                    List.of("shared-key@example.com"),
                    List.of()));

        assertEquals(2, collision.candidates().size());

        Person update = new Person();
        update.setName(first.getName());
        update.setEmail("replacement@example.com");
        personService.update(first.getId(), update);

        DuplicatePreflightResponse oldKey =
            duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    null,
                    List.of("shared-key@example.com"),
                    List.of()));
        assertEquals(1, oldKey.candidates().size());
        assertFalse(oldKey.candidates().stream()
            .anyMatch(candidate -> candidate.recordId() == first.getId()));
    }

    @Test
    void companyPreflightCombinesDomainPhoneAndName() {
        Company company = new Company();
        company.setName("Example Holdings");
        company.setWebsite("https://sales.example.co.jp/about");
        company.setPhone("090-2345-6789");
        companyService.createCompany(company);

        DuplicatePreflightResponse response =
            duplicatePreflightService.preflightCompany(
                new CompanyDuplicatePreflightRequest(
                    "example holdings",
                    List.of("https://www.example.co.jp"),
                    List.of("+81 90 2345 6789")));

        assertEquals(1, response.candidates().size());
        assertEquals(
            Set.of(
                DuplicateMatchKind.DOMAIN,
                DuplicateMatchKind.PHONE,
                DuplicateMatchKind.NAME),
            response.candidates().getFirst().matches().stream()
                .map(match -> match.kind())
                .collect(Collectors.toSet()));
    }

    @Test
    void archivedRecordsLeavePreflightAndReturnAfterRestoreEvenWhenTheirKeysWereReused() {
        Person firstPerson = createPerson(
            "Archived person", "reused-person@example.com", null);
        personService.archive(firstPerson.getId());
        Person secondPerson = createPerson(
            "Active person", "reused-person@example.com", null);

        DuplicatePreflightResponse activePersons = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                null, List.of("reused-person@example.com"), List.of()));
        assertEquals(List.of(secondPerson.getId()), activePersons.candidates().stream()
            .map(candidate -> candidate.recordId())
            .toList());
        assertTrue(visibleCollisionGroups("person", "email").isEmpty());

        personService.restore(firstPerson.getId());
        DuplicatePreflightResponse restoredPersons = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                null, List.of("reused-person@example.com"), List.of()));
        assertEquals(
            Set.of(firstPerson.getId(), secondPerson.getId()),
            restoredPersons.candidates().stream()
                .map(candidate -> candidate.recordId())
                .collect(Collectors.toSet()));
        assertEquals(2, visibleCollisionGroups("person", "email").getFirst().getCollisionSize());

        Company firstCompany = new Company();
        firstCompany.setName("Archived company");
        firstCompany.setWebsite("https://reused-company.example.com");
        firstCompany = companyService.createCompany(firstCompany);
        companyService.archiveCompany(firstCompany.getId());

        Company secondCompany = new Company();
        secondCompany.setName("Active company");
        secondCompany.setWebsite("https://reused-company.example.com");
        secondCompany = companyService.createCompany(secondCompany);

        DuplicatePreflightResponse activeCompanies = duplicatePreflightService.preflightCompany(
            new CompanyDuplicatePreflightRequest(
                null, List.of("https://reused-company.example.com"), List.of()));
        assertEquals(List.of(secondCompany.getId()), activeCompanies.candidates().stream()
            .map(candidate -> candidate.recordId())
            .toList());
        assertTrue(visibleCollisionGroups("company", "domain").isEmpty());

        companyService.restoreCompany(firstCompany.getId());
        DuplicatePreflightResponse restoredCompanies = duplicatePreflightService.preflightCompany(
            new CompanyDuplicatePreflightRequest(
                null, List.of("https://reused-company.example.com"), List.of()));
        assertEquals(
            Set.of(firstCompany.getId(), secondCompany.getId()),
            restoredCompanies.candidates().stream()
                .map(candidate -> candidate.recordId())
                .collect(Collectors.toSet()));
        assertEquals(2, visibleCollisionGroups("company", "domain").getFirst().getCollisionSize());
    }

    @Test
    void dealReviewProofRejectsMismatchedPrincipalBindingReuseAndExpiry() {
        DealDuplicateReviewProofService issuer = new DealDuplicateReviewProofService(
            dealDuplicateReviewProofMapper,
            workspaceService,
            duplicatePreflightProperties);
        DealDuplicateReviewProofService consumer = new DealDuplicateReviewProofService(
            dealDuplicateReviewProofMapper,
            workspaceService,
            duplicatePreflightProperties);
        String workflow = "a".repeat(64);
        String result = "b".repeat(64);
        String proof = issuer.issue(workflow, result);

        assertFalse(consumer.consume(proof, "c".repeat(64), result));
        assertFalse(consumer.consume(proof, workflow, "d".repeat(64)));
        User issuingUser = currentUser;
        Workspace otherWorkspace = new Workspace();
        otherWorkspace.setName("Proof isolation " + unique());
        otherWorkspace.setSlug("proof-isolation-" + unique());
        otherWorkspace.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(otherWorkspace);
        workspaceMapper.addMember(otherWorkspace.getId(), issuingUser.getId(), "member");
        authenticateAs(issuingUser, otherWorkspace.getId());
        assertFalse(consumer.consume(proof, workflow, result));
        authenticateAs(issuingUser, workspace.getId());
        User otherUser = newUser();
        authenticateAs(otherUser, workspace.getId());
        assertFalse(consumer.consume(proof, workflow, result));
        authenticateAs(issuingUser, workspace.getId());
        assertTrue(consumer.consume(proof, workflow, result));
        assertFalse(issuer.consume(proof, workflow, result));

        String expiringProof = issuer.issue(workflow, result);
        jdbcTemplate.update(
            "UPDATE deal_duplicate_review_proof "
                + "SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE workspace_id = ?",
            workspace.getId());
        assertFalse(consumer.consume(expiringProof, workflow, result));
    }

    @Test
    void dealPreflightFiltersBeforeItsBoundAndSignalsTruncationAtFiftyOneMatches() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        for (int index = 0; index < 55; index++) {
            newDeal(pipeline, stage, company);
        }
        Deal firstMatch = newDeal(pipeline, stage, company);
        Deal secondMatch = newDeal(pipeline, stage, company);
        dealMapper.updateName(
            workspace.getId(), firstMatch.getId(), "Renewal  Opportunity", "renewal opportunity");
        dealMapper.updateName(
            workspace.getId(), secondMatch.getId(), "RENEWAL OPPORTUNITY", "renewal opportunity");

        DuplicatePreflightResponse bounded = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest("renewal opportunity", company.getId()));

        assertEquals(
            List.of(firstMatch.getId(), secondMatch.getId()),
            bounded.candidates().stream().map(candidate -> candidate.recordId()).toList());
        assertFalse(bounded.truncated());

        for (int index = 0; index < 49; index++) {
            Deal match = newDeal(pipeline, stage, company);
            dealMapper.updateName(
                workspace.getId(),
                match.getId(),
                "Renewal Opportunity",
                "renewal opportunity");
        }

        DuplicatePreflightResponse truncated = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest("renewal opportunity", company.getId()));

        assertEquals(50, truncated.candidates().size());
        assertTrue(truncated.truncated());
    }

    @Test
    void dealPreflightUsesCanonicalKeysAndSafelyIncludesLegacyReplicaRenames() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal canonical = newDeal(pipeline, stage, company);
        canonical.setName("ＲＥＮＥＷＡＬ　ＯＰＰＯＲＴＵＮＩＴＹ");
        dealMapper.update(canonical);
        assertEquals(
            "renewal opportunity",
            jdbcTemplate.queryForObject(
                "SELECT duplicate_normalized_name FROM deal "
                    + "WHERE workspace_id = ? AND id = ?",
                String.class,
                workspace.getId(),
                canonical.getId()));

        DuplicatePreflightResponse indexed = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest("renewal opportunity", company.getId()));

        assertEquals(
            List.of(canonical.getId()),
            indexed.candidates().stream().map(candidate -> candidate.recordId()).toList());
        assertFalse(indexed.truncated());

        jdbcTemplate.update(
            "UPDATE deal SET name = ? "
                + "WHERE workspace_id = ? AND id = ?",
            "ＬＥＧＡＣＹ　ＲＥＮＥＷＡＬ",
            workspace.getId(),
            canonical.getId());
        assertNull(jdbcTemplate.queryForObject(
            "SELECT duplicate_normalized_name FROM deal "
                + "WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            canonical.getId()));

        DuplicatePreflightResponse legacy = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest("legacy renewal", company.getId()));

        assertEquals(
            List.of(canonical.getId()),
            legacy.candidates().stream().map(candidate -> candidate.recordId()).toList());
        assertFalse(legacy.truncated());
    }

    private List<IdentityCollisionGroupPageRow> visibleCollisionGroups(
            String recordType, String kind) {
        return identityCollisionMapper.findVisibleGroupPage(
                workspace.getId(), recordType, kind, 100, 0)
            .stream()
            .filter(row -> row.getRecordType() != null)
            .toList();
    }

    private Person createPerson(String name, String email, String phone) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        return personService.create(person);
    }

}
