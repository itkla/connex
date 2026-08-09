package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import ooo.klae.connex.backend.dto.IdentityCollisionGroupPageRow;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;

class DuplicatePreflightDatabaseTest extends AbstractServiceTest {

    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;
    @Autowired private IdentityCollisionMapper identityCollisionMapper;

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
    void exactMatchReleaseFixtureHasZeroFalsePositives() throws IOException {
        List<ExactMatchFixtureRow> fixture = exactMatchFixture();
        Map<String, Integer> targetIds = new HashMap<>();
        for (ExactMatchFixtureRow row : fixture) {
            if (targetIds.containsKey(row.targetKey())) {
                continue;
            }
            int targetId;
            if ("person".equals(row.recordType())) {
                targetId = createPerson(
                    row.storedName(),
                    row.storedEmail(),
                    row.storedPhone()).getId();
            } else if ("company".equals(row.recordType())) {
                Company company = new Company();
                company.setName(row.storedName());
                company.setWebsite(row.storedWebsite());
                company.setPhone(row.storedPhone());
                targetId = companyService.createCompany(company).getId();
            } else {
                throw new IllegalStateException(
                    "Unsupported fixture record type: " + row.recordType());
            }
            targetIds.put(row.targetKey(), targetId);
        }

        for (ExactMatchFixtureRow row : fixture) {
            DuplicatePreflightResponse response;
            if ("person".equals(row.recordType())) {
                response = duplicatePreflightService.preflightPerson(
                    new PersonDuplicatePreflightRequest(
                        row.probeName(),
                        nullableList(row.probeEmail()),
                        nullableList(row.probePhone())));
            } else {
                response = duplicatePreflightService.preflightCompany(
                    new CompanyDuplicatePreflightRequest(
                        row.probeName(),
                        nullableList(row.probeWebsite()),
                        nullableList(row.probePhone())));
            }
            List<Integer> actualIds = response.candidates().stream()
                .map(candidate -> candidate.recordId())
                .toList();
            List<Integer> expectedIds = row.expectedMatch()
                ? List.of(targetIds.get(row.targetKey()))
                : List.of();
            assertEquals(expectedIds, actualIds, row.caseId());
        }
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

    private static List<String> nullableList(String value) {
        return value == null ? List.of() : List.of(value);
    }

    private static List<ExactMatchFixtureRow> exactMatchFixture() throws IOException {
        InputStream stream = DuplicatePreflightDatabaseTest.class.getResourceAsStream(
            "/fixtures/exact-match-release-fixture.tsv");
        if (stream == null) {
            throw new IllegalStateException("Exact-match release fixture is missing");
        }
        List<ExactMatchFixtureRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] columns = line.split("\\t", -1);
                if (columns.length != 12) {
                    throw new IllegalStateException(
                        "Exact-match fixture row has " + columns.length + " columns");
                }
                rows.add(new ExactMatchFixtureRow(
                    columns[0],
                    columns[1],
                    columns[2],
                    nullable(columns[3]),
                    nullable(columns[4]),
                    nullable(columns[5]),
                    nullable(columns[6]),
                    nullable(columns[7]),
                    nullable(columns[8]),
                    nullable(columns[9]),
                    nullable(columns[10]),
                    Boolean.parseBoolean(columns[11])));
            }
        }
        return List.copyOf(rows);
    }

    private static String nullable(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record ExactMatchFixtureRow(
        String caseId,
        String targetKey,
        String recordType,
        String storedName,
        String storedEmail,
        String storedPhone,
        String storedWebsite,
        String probeName,
        String probeEmail,
        String probePhone,
        String probeWebsite,
        boolean expectedMatch
    ) {}

}
