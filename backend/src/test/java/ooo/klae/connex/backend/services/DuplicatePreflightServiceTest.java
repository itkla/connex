package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class DuplicatePreflightServiceTest extends AbstractServiceTest {

    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void personPreflightRanksExactIdentitiesStrongAndExactNameWeak() {
        Person person = createPerson(
            "Ada Lovelace", "ada@example.com", "090-1234-5678");
        insertPersonExternalId(person.getId(), "crm-ada");

        DuplicatePreflightResponse strong = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                "  ADA   LOVELACE ",
                List.of("ADA@EXAMPLE.COM"),
                List.of("+81 90 1234 5678"),
                List.of("CRM-ADA")));

        assertEquals(1, strong.candidates().size());
        assertEquals(DuplicateMatchStrength.STRONG, strong.candidates().getFirst().strength());
        assertEquals(
            Set.of(
                DuplicateMatchKind.EMAIL,
                DuplicateMatchKind.PHONE,
                DuplicateMatchKind.EXTERNAL_ID,
                DuplicateMatchKind.NAME),
            strong.candidates().getFirst().matches().stream()
                .map(match -> match.kind())
                .collect(Collectors.toSet()));
        assertTrue(strong.candidates().getFirst().ownedByActiveWorkspace());

        DuplicatePreflightResponse weak = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                "ada lovelace", List.of(), List.of(), List.of()));
        DuplicatePreflightResponse fuzzy = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                "ada lovelac", List.of(), List.of(), List.of()));

        assertEquals(DuplicateMatchStrength.WEAK, weak.candidates().getFirst().strength());
        assertTrue(fuzzy.candidates().isEmpty());
    }

    @Test
    void personPreflightReturnsMultipleRecordsPerKeyAndIgnoresSupersededValues() {
        Person first = createPerson(
            "First shared key", "shared-key@example.com", null);
        createPerson("Second shared key", "shared-key@example.com", null);

        DuplicatePreflightResponse collision = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                null, List.of("shared-key@example.com"), List.of(), List.of()));

        assertEquals(2, collision.candidates().size());

        Person update = new Person();
        update.setName(first.getName());
        update.setEmail("replacement@example.com");
        personService.update(first.getId(), update);

        DuplicatePreflightResponse oldKey = duplicatePreflightService.preflightPerson(
            new PersonDuplicatePreflightRequest(
                null, List.of("shared-key@example.com"), List.of(), List.of()));
        assertEquals(1, oldKey.candidates().size());
        assertFalse(oldKey.candidates().stream()
            .anyMatch(candidate -> candidate.recordId() == first.getId()));
    }

    @Test
    void companyPreflightMatchesDomainPhoneAndExternalId() {
        Company company = new Company();
        company.setName("Example Holdings");
        company.setWebsite("https://sales.example.co.jp/about");
        company.setPhone("090-2345-6789");
        Company created = companyService.createCompany(company);
        insertCompanyExternalId(created.getId(), "account-42");

        DuplicatePreflightResponse response = duplicatePreflightService.preflightCompany(
            new CompanyDuplicatePreflightRequest(
                "example holdings",
                List.of("https://www.example.co.jp"),
                List.of("+81 90 2345 6789"),
                List.of("ACCOUNT-42")));

        assertEquals(1, response.candidates().size());
        assertEquals(DuplicateMatchStrength.STRONG, response.candidates().getFirst().strength());
        assertEquals(
            Set.of(
                DuplicateMatchKind.DOMAIN,
                DuplicateMatchKind.PHONE,
                DuplicateMatchKind.EXTERNAL_ID,
                DuplicateMatchKind.NAME),
            response.candidates().getFirst().matches().stream()
                .map(match -> match.kind())
                .collect(Collectors.toSet()));
    }

    @Test
    void rejectsInvalidAndOversizedProbesBeforePersistenceQueries() {
        assertThrows(
            BadRequestException.class,
            () -> duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    " ", List.of("not-email"), List.of("invalid"), List.of("has space"))));
        assertThrows(
            BadRequestException.class,
            () -> duplicatePreflightService.preflightPerson(
                new PersonDuplicatePreflightRequest(
                    null,
                    Collections.nCopies(8, "first@example.com"),
                    Collections.nCopies(8, "090-1234-5678"),
                    List.of("one-more"))));
        PersonDuplicatePreflightRequest row = new PersonDuplicatePreflightRequest(
            "Bounded row", List.of(), List.of(), List.of());
        assertThrows(
            BadRequestException.class,
            () -> duplicatePreflightService.preflightPersonImport(
                Collections.nCopies(5_001, row)));
    }

    private Person createPerson(String name, String email, String phone) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        return personService.create(person);
    }

    private void insertPersonExternalId(int personId, String externalId) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, source_external_id, acquired_at
            )
            VALUES (?, ?, 'external_id', ?, ?, 'test', 'person.external_id', ?, CURRENT_TIMESTAMP)
            """,
            workspace.getId(),
            personId,
            externalId,
            externalId,
            externalId);
    }

    private void insertCompanyExternalId(int companyId, String externalId) {
        jdbcTemplate.update(
            """
            INSERT INTO company_identity (
              workspace_id, company_id, kind, `value`, normalized_value,
              source_system, source_channel, source_external_id, acquired_at
            )
            VALUES (?, ?, 'external_id', ?, ?, 'test', 'company.external_id', ?, CURRENT_TIMESTAMP)
            """,
            workspace.getId(),
            companyId,
            externalId,
            externalId,
            externalId);
    }
}
