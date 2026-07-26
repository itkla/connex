package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupRow;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberRow;

/**
 * MySQL coverage for deterministic, restriction-aware collision membership.
 */
class IdentityCollisionMapperTest extends AbstractMapperTest {

    private static final LocalDateTime REBUILT_AT =
        LocalDateTime.of(2026, 7, 25, 12, 0);

    @Autowired private IdentityCollisionMapper collisionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useIsolatedWorkspace() {
        workspace = newWorkspace("collision-map");
    }

    @Test
    void rebuildCreatesMembershipOnlyForCrossRecordGroups() {
        Company company = newCompany();
        Person single = newPerson(workspace, company, "single@example.com", "090-1111-1111");
        Person first = newPerson(workspace, company, "same@example.com", "090-2222-2222");
        Person second = newPerson(workspace, company, "same@example.com", "090-3333-3333");
        Person third = newPerson(workspace, company, "third@example.com", "090-4444-4444");
        insertPersonIdentity(single, "email", "single@example.com");
        insertPersonIdentity(first, "email", "same@example.com");
        insertPersonIdentity(second, "email", "same@example.com");
        insertPersonIdentity(first, "phone", "+819099999999");
        insertPersonIdentity(second, "phone", "+819099999999");
        insertPersonIdentity(third, "phone", "+819099999999");

        Company firstCompany = newCompany(workspace, "first.example.com");
        Company secondCompany = newCompany(workspace, "second.example.com");
        insertCompanyIdentity(firstCompany, "phone", "+819099999999");
        insertCompanyIdentity(secondCompany, "phone", "+819099999999");

        int memberships = rebuild(workspace.getId());

        assertEquals(7, memberships);
        assertEquals(
            7,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_collision WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));
        List<IdentityCollisionGroupRow> groups =
            collisionMapper.findVisibleGroups(workspace.getId(), null, null, 100, 0);
        assertEquals(
            List.of(
                "company:phone:+819099999999:2",
                "person:email:same@example.com:2",
                "person:phone:+819099999999:3"),
            groups.stream()
                .map(group -> group.getRecordType() + ":" + group.getKind() + ":"
                    + group.getNormalizedValue() + ":" + group.getCollisionSize())
                .toList());
    }

    @Test
    void kindAndRecordTypePartitionsRemainIndependent() {
        Company company = newCompany();
        Person first = newPerson(workspace, company, "first@example.com", "090-1111-1111");
        Person second = newPerson(workspace, company, "second@example.com", "090-2222-2222");
        insertPersonIdentity(first, "email", "shared-value");
        insertPersonIdentity(second, "phone", "shared-value");
        Company firstCompany = newCompany(workspace, "first-kind.example.com");
        Company secondCompany = newCompany(workspace, "second-kind.example.com");
        insertCompanyIdentity(firstCompany, "domain", "shared-value");
        insertCompanyIdentity(secondCompany, "phone", "shared-value");

        assertEquals(0, rebuild(workspace.getId()));
        assertEquals(
            0L,
            collisionMapper.countVisibleGroups(workspace.getId(), null, null));
    }

    @Test
    void liveReadsSuppressGroupsAfterRestrictionWithoutNeedingARebuild() {
        Company company = newCompany();
        Person first = newPerson(workspace, company, "restricted@example.com", "090-1111-1111");
        Person second = newPerson(workspace, company, "restricted@example.com", "090-2222-2222");
        insertPersonIdentity(first, "email", "restricted@example.com");
        insertPersonIdentity(second, "email", "restricted@example.com");
        assertEquals(2, rebuild(workspace.getId()));

        personMapper.updateProcessingRestrictions(workspace.getId(), first.getId(), true, false);

        assertEquals(
            0L,
            collisionMapper.countVisibleGroups(workspace.getId(), "person", "email"));
        assertEquals(
            List.of(),
            collisionMapper.findVisibleGroups(workspace.getId(), "person", "email", 100, 0));
        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_collision WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));

        personMapper.updateProcessingRestrictions(workspace.getId(), first.getId(), false, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), second.getId(), false, true);

        assertEquals(
            0L,
            collisionMapper.countVisibleGroups(workspace.getId(), "person", "email"));
    }

    @Test
    void rebuildDeletesStaleMembershipBeforeReinsertion() {
        Company company = newCompany();
        Person first = newPerson(workspace, company, "stale@example.com", "090-1111-1111");
        Person second = newPerson(workspace, company, "stale@example.com", "090-2222-2222");
        long firstIdentity = insertPersonIdentity(first, "email", "stale@example.com");
        insertPersonIdentity(second, "email", "stale@example.com");
        assertEquals(2, rebuild(workspace.getId()));

        jdbcTemplate.update(
            "DELETE FROM person_identity WHERE workspace_id = ? AND id = ?",
            workspace.getId(),
            firstIdentity);

        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_collision WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));
        assertEquals(0, rebuild(workspace.getId()));
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_collision WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));
    }

    @Test
    void membershipCheckAndCompositeForeignKeysFailClosed() {
        Company company = newCompany();
        Person person = newPerson(company);
        long personIdentity = insertPersonIdentity(person, "email", "check@example.com");
        long companyIdentity = insertCompanyIdentity(company, "domain", "check.example.com");
        Workspace other = newWorkspace("collision-fk");

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO identity_collision (
                  workspace_id, person_identity_id, company_identity_id, rebuilt_at
                )
                VALUES (?, ?, ?, ?)
                """,
                workspace.getId(),
                personIdentity,
                companyIdentity,
                REBUILT_AT));
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbcTemplate.update(
                """
                INSERT INTO identity_collision (
                  workspace_id, person_identity_id, rebuilt_at
                )
                VALUES (?, ?, ?)
                """,
                other.getId(),
                personIdentity,
                REBUILT_AT));
    }

    @Test
    void groupPaginationIsDeterministicAndMembersAreNotSplit() {
        createCompanyDomainGroup("alpha.example", 2);
        createCompanyDomainGroup("beta.example", 3);
        createCompanyDomainGroup("gamma.example", 2);
        rebuild(workspace.getId());

        List<IdentityCollisionGroupRow> page =
            collisionMapper.findVisibleGroups(workspace.getId(), "company", "domain", 1, 1);
        List<IdentityCollisionMemberRow> members =
            collisionMapper.findVisibleMembers(workspace.getId(), page);

        assertEquals(3L, collisionMapper.countVisibleGroups(
            workspace.getId(), "company", "domain"));
        assertEquals(1, page.size());
        assertEquals("beta.example", page.getFirst().getNormalizedValue());
        assertEquals(3, page.getFirst().getCollisionSize());
        assertEquals(3, members.size());
        assertEquals(
            List.of("beta.example", "beta.example", "beta.example"),
            members.stream().map(member -> member.getNormalizedValue()).toList());
        assertEquals(
            members.stream().map(member -> member.getRecordId()).sorted().toList(),
            members.stream().map(member -> member.getRecordId()).toList());
    }

    @Test
    void otherWorkspaceGroupsAreNeverCountedOrReturned() {
        createCompanyDomainGroup("local.example", 2);
        rebuild(workspace.getId());

        Workspace other = newWorkspace("collision-other");
        createCompanyDomainGroup(other, "foreign.example", 2);
        rebuild(other.getId());

        List<IdentityCollisionGroupRow> local =
            collisionMapper.findVisibleGroups(workspace.getId(), null, null, 100, 0);

        assertEquals(1L, collisionMapper.countVisibleGroups(workspace.getId(), null, null));
        assertEquals(List.of("local.example"),
            local.stream().map(group -> group.getNormalizedValue()).toList());
    }

    private int rebuild(int workspaceId) {
        collisionMapper.deleteForWorkspace(workspaceId);
        return collisionMapper.insertPersonCollisionMembers(workspaceId, REBUILT_AT)
            + collisionMapper.insertCompanyCollisionMembers(workspaceId, REBUILT_AT);
    }

    private long insertPersonIdentity(Person person, String kind, String normalizedValue) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, acquired_at
            )
            VALUES (?, ?, ?, ?, ?, 'manual', CURRENT_TIMESTAMP)
            """,
            person.getWorkspaceId(),
            person.getId(),
            kind,
            normalizedValue,
            normalizedValue);
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM person_identity
            WHERE workspace_id = ? AND person_id = ? AND kind = ? AND normalized_value = ?
            """,
            Long.class,
            person.getWorkspaceId(),
            person.getId(),
            kind,
            normalizedValue);
    }

    private long insertCompanyIdentity(Company company, String kind, String normalizedValue) {
        jdbcTemplate.update(
            """
            INSERT INTO company_identity (
              workspace_id, company_id, kind, `value`, normalized_value,
              source_system, acquired_at
            )
            VALUES (?, ?, ?, ?, ?, 'manual', CURRENT_TIMESTAMP)
            """,
            company.getWorkspaceId(),
            company.getId(),
            kind,
            normalizedValue,
            normalizedValue);
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM company_identity
            WHERE workspace_id = ? AND company_id = ? AND kind = ? AND normalized_value = ?
            """,
            Long.class,
            company.getWorkspaceId(),
            company.getId(),
            kind,
            normalizedValue);
    }

    private void createCompanyDomainGroup(String normalizedValue, int size) {
        createCompanyDomainGroup(workspace, normalizedValue, size);
    }

    private void createCompanyDomainGroup(
            Workspace owner, String normalizedValue, int size) {
        for (int index = 0; index < size; index++) {
            Company company = newCompany(
                owner,
                index + "." + normalizedValue);
            insertCompanyIdentity(company, "domain", normalizedValue);
        }
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
}
