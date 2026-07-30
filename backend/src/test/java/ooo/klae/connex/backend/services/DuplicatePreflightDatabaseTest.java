package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;

class DuplicatePreflightDatabaseTest extends AbstractServiceTest {

    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;

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

    private Person createPerson(String name, String email, String phone) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        return personService.create(person);
    }

}
