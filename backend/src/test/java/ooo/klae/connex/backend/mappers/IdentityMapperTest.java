package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.IdentityKeyRow;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;

/**
 * MySQL coverage for canonical identity ownership, provenance, and tenant scoping.
 */
class IdentityMapperTest extends AbstractMapperTest {

    @Autowired private IdentityMapper identityMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useIsolatedWorkspace() {
        workspace = newWorkspace("identity-map");
    }

    @Test
    void uniquenessIsRecordScopedAndWorkspaceScoped() {
        Company company = newCompany();
        Person first = newPerson(company);
        Person second = newPerson(company);
        insertPersonIdentity(
            workspace.getId(), first.getId(), "email", "First@Example.com", "same@example.com", "manual");

        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertPersonIdentity(
                workspace.getId(),
                first.getId(),
                "email",
                "Other@Example.com",
                "same@example.com",
                "csv_import"));

        insertPersonIdentity(
            workspace.getId(), second.getId(), "email", "Same@example.com", "same@example.com", "manual");

        Workspace otherWorkspace = newWorkspace("identity-other");
        Company otherCompany = newCompany(otherWorkspace, "other.example.com");
        Person otherPerson = newPerson(otherWorkspace, otherCompany, "other@example.com", "090-1111-2222");
        insertPersonIdentity(
            otherWorkspace.getId(),
            otherPerson.getId(),
            "email",
            "Same@example.com",
            "same@example.com",
            "manual");

        assertEquals(
            3,
            count("person_identity", "normalized_value", "same@example.com"));
    }

    @Test
    void compositeOwnershipAndKindChecksFailClosed() {
        Company company = newCompany();
        Person person = newPerson(company);
        Workspace otherWorkspace = newWorkspace("identity-fk");

        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertPersonIdentity(
                otherWorkspace.getId(),
                person.getId(),
                "email",
                "person@example.com",
                "person@example.com",
                "manual"));
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertPersonIdentity(
                workspace.getId(),
                person.getId(),
                "domain",
                "example.com",
                "example.com",
                "manual"));
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertCompanyIdentity(
                workspace.getId(),
                company.getId(),
                "email",
                "team@example.com",
                "team@example.com",
                "manual"));
    }

    @Test
    void keysetCandidatesAndIdentityKeysExcludeRestrictedPeople() {
        Company company = newCompany();
        Person first = newPerson(workspace, company, "first@example.com", "090-1111-1111");
        Person second = newPerson(workspace, company, "second@example.com", "090-2222-2222");
        Person third = newPerson(workspace, company, "third@example.com", "090-3333-3333");
        Person suspended = newPerson(workspace, company, "suspended@example.com", "090-4444-4444");
        Person ceased = newPerson(workspace, company, "ceased@example.com", "090-5555-5555");
        personMapper.updateProcessingRestrictions(workspace.getId(), suspended.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), ceased.getId(), false, true);

        List<Integer> firstPage = identityMapper
            .findPersonBackfillCandidates(workspace.getId(), 0, 2)
            .stream()
            .map(candidate -> candidate.getId())
            .toList();
        List<Integer> secondPage = identityMapper
            .findPersonBackfillCandidates(workspace.getId(), firstPage.getLast(), 2)
            .stream()
            .map(candidate -> candidate.getId())
            .toList();

        assertEquals(List.of(first.getId(), second.getId()), firstPage);
        assertEquals(List.of(third.getId()), secondPage);

        insertPersonIdentity(
            workspace.getId(), first.getId(), "email", first.getEmail(), "first@example.com", "manual");
        insertPersonIdentity(
            workspace.getId(),
            suspended.getId(),
            "email",
            suspended.getEmail(),
            "suspended@example.com",
            "manual");
        insertPersonIdentity(
            workspace.getId(), ceased.getId(), "email", ceased.getEmail(), "ceased@example.com", "manual");
        List<IdentityKeyRow> keys = identityMapper.findPersonIdentityKeys(
            workspace.getId(),
            List.of(first.getId(), suspended.getId(), ceased.getId()));

        assertEquals(1, keys.size());
        assertEquals(first.getId(), keys.getFirst().getRecordId());
    }

    @Test
    void backfillWritesRevalidateRawValuesRestrictionsAndProvenance() {
        Company company = newCompany();
        Person active = newPerson(workspace, company, "Case@example.com", "090-1234-5678");
        Person suspended = newPerson(workspace, company, "blocked@example.com", "090-3333-4444");
        personMapper.updateProcessingRestrictions(workspace.getId(), suspended.getId(), true, false);

        assertEquals(
            0,
            identityMapper.insertBackfilledPersonEmailIfAbsent(
                workspace.getId(), active.getId(), "case@example.com", "case@example.com"));
        assertEquals(
            0,
            identityMapper.insertBackfilledPersonEmailIfAbsent(
                workspace.getId(), suspended.getId(), suspended.getEmail(), "blocked@example.com"));
        assertEquals(
            1,
            identityMapper.insertBackfilledPersonEmailIfAbsent(
                workspace.getId(), active.getId(), active.getEmail(), "case@example.com"));
        assertEquals(
            1,
            identityMapper.insertBackfilledPersonPhoneIfAbsent(
                workspace.getId(), active.getId(), active.getPhone(), "+819012345678"));

        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ? AND person_id = ?",
                Integer.class,
                workspace.getId(),
                active.getId()));
        assertEquals(
            "backfill",
            jdbcTemplate.queryForObject(
                """
                SELECT source_system
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                """,
                String.class,
                workspace.getId(),
                active.getId()));
        assertEquals(
            "person.email",
            jdbcTemplate.queryForObject(
                """
                SELECT source_channel
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                """,
                String.class,
                workspace.getId(),
                active.getId()));
        assertEquals(
            "person:" + active.getId(),
            jdbcTemplate.queryForObject(
                """
                SELECT source_row_ref
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                """,
                String.class,
                workspace.getId(),
                active.getId()));
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT acquired_at = p.created_at
                FROM person_identity pi
                JOIN person p ON p.workspace_id = pi.workspace_id AND p.id = pi.person_id
                WHERE pi.workspace_id = ? AND pi.person_id = ? AND pi.kind = 'email'
                """,
                Integer.class,
                workspace.getId(),
                active.getId()));
        assertNull(jdbcTemplate.queryForObject(
            """
            SELECT source_external_id
            FROM person_identity
            WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
            """,
            String.class,
            workspace.getId(),
            active.getId()));
        assertNull(jdbcTemplate.queryForObject(
            """
            SELECT purpose_of_use_code
            FROM person_identity
            WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
            """,
            String.class,
            workspace.getId(),
            active.getId()));
    }

    @Test
    void existingManualIdentityIsPreservedByBackfillReplay() {
        Company company = newCompany();
        Person person = newPerson(workspace, company, "Manual@Example.com", "090-1234-5678");
        insertPersonIdentity(
            workspace.getId(),
            person.getId(),
            "email",
            "original-acquired@example.com",
            "manual@example.com",
            "manual");

        identityMapper.insertBackfilledPersonEmailIfAbsent(
            workspace.getId(),
            person.getId(),
            person.getEmail(),
            "manual@example.com");

        assertEquals(
            "manual",
            jdbcTemplate.queryForObject(
                """
                SELECT source_system
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND normalized_value = ?
                """,
                String.class,
                workspace.getId(),
                person.getId(),
                "manual@example.com"));
        assertEquals(
            "original-acquired@example.com",
            jdbcTemplate.queryForObject(
                """
                SELECT `value`
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND normalized_value = ?
                """,
                String.class,
                workspace.getId(),
                person.getId(),
                "manual@example.com"));
    }

    @Test
    void parentDeletesCascadeAndWorkspaceTeardownIsExact() {
        Company company = newCompany();
        Person person = newPerson(company);
        insertPersonIdentity(
            workspace.getId(), person.getId(), "email", person.getEmail(), person.getEmail(), "manual");
        insertCompanyIdentity(
            workspace.getId(), company.getId(), "domain", company.getWebsite(), "example.com", "manual");

        personMapper.delete(workspace.getId(), person.getId());
        companyMapper.delete(workspace.getId(), company.getId());

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company_identity WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));

        Company retainedCompany = newCompany();
        Person retainedPerson = newPerson(retainedCompany);
        insertPersonIdentity(
            workspace.getId(),
            retainedPerson.getId(),
            "email",
            retainedPerson.getEmail(),
            retainedPerson.getEmail(),
            "manual");
        Workspace other = newWorkspace("identity-delete");
        Company otherCompany = newCompany(other, "other-delete.example.com");
        Person otherPerson =
            newPerson(other, otherCompany, "other-delete@example.com", "090-8888-9999");
        insertPersonIdentity(
            other.getId(),
            otherPerson.getId(),
            "email",
            otherPerson.getEmail(),
            otherPerson.getEmail(),
            "manual");

        assertEquals(1, identityMapper.deletePersonIdentitiesForWorkspace(workspace.getId()));
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ?",
                Integer.class,
                other.getId()));
    }

    @Test
    void indexedKeyColumnsUseCanonicalCollationAndFullLength() {
        List<String> collations = jdbcTemplate.queryForList(
            """
            SELECT collation_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name IN ('person_identity', 'company_identity')
              AND column_name IN ('kind', 'normalized_value', 'purpose_of_use_code')
            ORDER BY table_name, column_name
            """,
            String.class);
        List<Integer> prefixes = jdbcTemplate.queryForList(
            """
            SELECT sub_part
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND index_name IN (
                'uq_person_identity_workspace_kind_normalized_value_person_id',
                'uq_company_identity_workspace_kind_normalized_value_company_id'
              )
              AND column_name = 'normalized_value'
            """,
            Integer.class);

        assertEquals(6, collations.size());
        assertTrue(collations.stream().allMatch("utf8mb4_0900_ai_ci"::equals));
        assertEquals(2, prefixes.size());
        assertTrue(prefixes.stream().allMatch(prefix -> prefix == null));
    }

    private Workspace newWorkspace(String prefix) {
        String suffix = unique();
        Workspace created = new Workspace();
        created.setName(prefix + "-" + suffix);
        created.setSlug(prefix + "-" + suffix);
        workspaceMapper.insert(created);
        return created;
    }

    private Company newCompany(Workspace owner, String website) {
        Company company = new Company();
        company.setWorkspaceId(owner.getId());
        company.setName("Company " + unique());
        company.setWebsite("https://" + website);
        company.setIndustry("Tech");
        company.setPhone("+81-90-1234-5678");
        company.setAddress("Tokyo");
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(
            Workspace owner, Company company, String email, String phone) {
        Person person = new Person();
        person.setWorkspaceId(owner.getId());
        person.setName("Person " + unique());
        person.setEmail(email);
        person.setPhone(phone);
        person.setCompany(company);
        person.setTitle("Engineer");
        personMapper.insert(person);
        return person;
    }

    private void insertPersonIdentity(
            int workspaceId,
            int personId,
            String kind,
            String value,
            String normalizedValue,
            String sourceSystem) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, acquired_at
            )
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """,
            workspaceId,
            personId,
            kind,
            value,
            normalizedValue,
            sourceSystem);
    }

    private void insertCompanyIdentity(
            int workspaceId,
            int companyId,
            String kind,
            String value,
            String normalizedValue,
            String sourceSystem) {
        jdbcTemplate.update(
            """
            INSERT INTO company_identity (
              workspace_id, company_id, kind, `value`, normalized_value,
              source_system, acquired_at
            )
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """,
            workspaceId,
            companyId,
            kind,
            value,
            normalizedValue,
            sourceSystem);
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
            Integer.class,
            value);
    }
}
