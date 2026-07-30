package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupKey;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupPageRow;
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
        List<IdentityCollisionGroupPageRow> groups =
            visibleGroups(workspace.getId(), null, null, 100, 0);
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
            totalOf(collisionMapper.findVisibleGroupPage(
                workspace.getId(), null, null, 100, 0)));
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
            totalOf(collisionMapper.findVisibleGroupPage(
                workspace.getId(), "person", "email", 100, 0)));
        assertEquals(
            List.of(),
            visibleGroups(workspace.getId(), "person", "email", 100, 0));
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
            totalOf(collisionMapper.findVisibleGroupPage(
                workspace.getId(), "person", "email", 100, 0)));
    }

    @Test
    void supersededIdentitiesStopMatchingBeforeAndAfterARebuild() {
        Company company = newCompany();
        Person first = newPerson(
            workspace, company, "superseded@example.com", "090-1111-1111");
        Person second = newPerson(
            workspace, company, "superseded@example.com", "090-2222-2222");
        long firstIdentity =
            insertPersonIdentity(first, "email", "superseded@example.com");
        insertPersonIdentity(second, "email", "superseded@example.com");
        assertEquals(2, rebuild(workspace.getId()));

        jdbcTemplate.update(
            """
            UPDATE person_identity
            SET superseded_at = CURRENT_TIMESTAMP
            WHERE workspace_id = ? AND id = ?
            """,
            workspace.getId(),
            firstIdentity);

        assertEquals(
            0L,
            totalOf(collisionMapper.findVisibleGroupPage(
                workspace.getId(), "person", "email", 100, 0)));
        assertEquals(2L, collisionMapper.countForWorkspace(workspace.getId()));
        assertEquals(0, rebuild(workspace.getId()));
        assertEquals(0L, collisionMapper.countForWorkspace(workspace.getId()));
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
            DataAccessException.class,
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
    void maxExecutionTimeHintRaisesMySqlErrorCode3024() {
        DataAccessException thrown = assertThrows(
            DataAccessException.class,
            () -> jdbcTemplate.queryForList("""
                WITH timeout_probe AS (SELECT SLEEP(0.25) AS marker)
                SELECT /*+ MAX_EXECUTION_TIME(25) */ marker
                FROM timeout_probe
                """));

        assertEquals(3024, nestedSqlException(thrown).getErrorCode());
    }

    @Test
    void groupPaginationIsDeterministicAndMembersAreNotSplit() {
        createCompanyDomainGroup("alpha.example", 2);
        createCompanyDomainGroup("beta.example", 3);
        createCompanyDomainGroup("gamma.example", 2);
        rebuild(workspace.getId());

        List<IdentityCollisionGroupPageRow> page =
            visibleGroups(workspace.getId(), "company", "domain", 1, 1);
        List<IdentityCollisionMemberRow> members =
            collisionMapper.findVisibleMembers(workspace.getId(), keysOf(page), 0, 10);

        assertEquals(3L, page.getFirst().getTotal());
        assertEquals(2L, page.getFirst().getPageOrdinal());
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
    void memberReadsAreBoundedPerGroupWithoutLosingTheGroupSize() {
        createCompanyDomainGroup("bounded.example", 5);
        assertEquals(5, rebuild(workspace.getId()));

        List<IdentityCollisionGroupPageRow> page =
            visibleGroups(workspace.getId(), "company", "domain", 10, 0);
        List<IdentityCollisionMemberRow> members =
            collisionMapper.findVisibleMembers(workspace.getId(), keysOf(page), 0, 2);

        assertEquals(5, page.getFirst().getCollisionSize());
        assertEquals(
            memberIds("bounded.example", 0, 2),
            members.stream().map(member -> member.getRecordId()).toList());
    }

    @Test
    void everyRequestedGroupGetsItsOwnBoundedPageInOneRead() {
        createCompanyDomainGroup("first-bounded.example", 4);
        createCompanyDomainGroup("second-bounded.example", 5);
        rebuild(workspace.getId());

        List<IdentityCollisionGroupPageRow> page =
            visibleGroups(workspace.getId(), "company", "domain", 10, 0);
        List<IdentityCollisionMemberRow> members =
            collisionMapper.findVisibleMembers(workspace.getId(), keysOf(page), 0, 2);

        assertEquals(2, page.size());
        assertEquals(4, members.size());
        assertEquals(
            List.of(
                "first-bounded.example", "first-bounded.example",
                "second-bounded.example", "second-bounded.example"),
            members.stream().map(member -> member.getNormalizedValue()).toList());
        assertEquals(
            memberIds("first-bounded.example", 0, 2),
            members.stream()
                .filter(member -> "first-bounded.example".equals(member.getNormalizedValue()))
                .map(member -> member.getRecordId())
                .toList());
        assertEquals(
            memberIds("second-bounded.example", 0, 2),
            members.stream()
                .filter(member -> "second-bounded.example".equals(member.getNormalizedValue()))
                .map(member -> member.getRecordId())
                .toList());
    }

    @Test
    void theMemberCursorReachesEveryRecordPastTheReportBound() {
        createCompanyDomainGroup("cursor.example", 5);
        rebuild(workspace.getId());
        IdentityCollisionGroupKey key =
            new IdentityCollisionGroupKey("company", "domain", "cursor.example");
        List<Integer> all = memberIds("cursor.example", 0, 5);

        List<IdentityCollisionMemberRow> second =
            collisionMapper.findVisibleMembers(workspace.getId(), List.of(key), all.get(1), 2);
        List<IdentityCollisionMemberRow> last =
            collisionMapper.findVisibleMembers(workspace.getId(), List.of(key), all.get(3), 2);
        List<IdentityCollisionMemberRow> exhausted =
            collisionMapper.findVisibleMembers(workspace.getId(), List.of(key), all.getLast(), 2);

        assertEquals(
            List.of(all.get(2), all.get(3)),
            second.stream().map(member -> member.getRecordId()).toList());
        assertEquals(
            List.of(all.get(4)),
            last.stream().map(member -> member.getRecordId()).toList());
        assertEquals(List.of(), exhausted);
    }

    @Test
    void restrictedMembersNeverConsumeASlotInABoundedMemberPage() {
        Company company = newCompany();
        List<Person> people = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Person person = newPerson(
                workspace, company, "slot@example.com", "090-5555-000" + index);
            insertPersonIdentity(person, "email", "slot@example.com");
            people.add(person);
        }
        assertEquals(4, rebuild(workspace.getId()));
        personMapper.updateProcessingRestrictions(
            workspace.getId(), people.getFirst().getId(), true, false);
        IdentityCollisionGroupKey key =
            new IdentityCollisionGroupKey("person", "email", "slot@example.com");

        List<IdentityCollisionMemberRow> members =
            collisionMapper.findVisibleMembers(workspace.getId(), List.of(key), 0, 2);
        List<IdentityCollisionMemberRow> allVisible =
            collisionMapper.findVisibleMembers(workspace.getId(), List.of(key), 0, 10);

        assertEquals(3, allVisible.size());
        assertEquals(
            List.of(people.get(1).getId(), people.get(2).getId()),
            members.stream().map(member -> member.getRecordId()).toList());
    }

    @Test
    void boundedMemberProbesAreWorkspaceScopedAndGroupSpecific() {
        createCompanyDomainGroup("counted.example", 3);
        rebuild(workspace.getId());
        Workspace other = newWorkspace("collision-count");
        createCompanyDomainGroup(other, "counted.example", 4);
        rebuild(other.getId());
        IdentityCollisionGroupKey companyGroup =
            new IdentityCollisionGroupKey("company", "domain", "counted.example");
        IdentityCollisionGroupKey absentGroup =
            new IdentityCollisionGroupKey("company", "domain", "absent.example");
        IdentityCollisionGroupKey wrongRecordType =
            new IdentityCollisionGroupKey("person", "email", "counted.example");

        assertEquals(2, collisionMapper.findVisibleMembers(
            workspace.getId(), List.of(companyGroup), 0, 2).size());
        assertEquals(2, collisionMapper.findVisibleMembers(
            other.getId(), List.of(companyGroup), 0, 2).size());
        assertEquals(List.of(), collisionMapper.findVisibleMembers(
            workspace.getId(), List.of(absentGroup), 0, 2));
        assertEquals(List.of(), collisionMapper.findVisibleMembers(
            workspace.getId(), List.of(wrongRecordType), 0, 2));
    }

    @Test
    void repeatedMembershipInsertsConvergeInsteadOfFailingOnDuplicateKeys() {
        Company company = newCompany();
        Person first = newPerson(workspace, company, "converge@example.com", "090-1111-1111");
        Person second = newPerson(workspace, company, "converge@example.com", "090-2222-2222");
        insertPersonIdentity(first, "email", "converge@example.com");
        insertPersonIdentity(second, "email", "converge@example.com");
        createCompanyDomainGroup("converge.example", 2);
        assertEquals(2, collisionMapper.insertPersonCollisionMembers(workspace.getId(), REBUILT_AT));
        assertEquals(2, collisionMapper.insertCompanyCollisionMembers(workspace.getId(), REBUILT_AT));

        LocalDateTime replayedAt = REBUILT_AT.plusHours(1);
        collisionMapper.insertPersonCollisionMembers(workspace.getId(), replayedAt);
        collisionMapper.insertCompanyCollisionMembers(workspace.getId(), replayedAt);

        assertEquals(4L, collisionMapper.countForWorkspace(workspace.getId()));
        assertEquals(
            4,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM identity_collision
                WHERE workspace_id = ? AND rebuilt_at = ?
                """,
                Integer.class,
                workspace.getId(),
                replayedAt));
    }

    @Test
    void otherWorkspaceGroupsAreNeverCountedOrReturned() {
        createCompanyDomainGroup("local.example", 2);
        rebuild(workspace.getId());

        Workspace other = newWorkspace("collision-other");
        createCompanyDomainGroup(other, "foreign.example", 2);
        rebuild(other.getId());

        List<IdentityCollisionGroupPageRow> local =
            visibleGroups(workspace.getId(), null, null, 100, 0);

        assertEquals(1L, local.getFirst().getTotal());
        assertEquals(List.of("local.example"),
            local.stream().map(group -> group.getNormalizedValue()).toList());
    }

    private List<IdentityCollisionGroupPageRow> visibleGroups(
            int workspaceId,
            String recordType,
            String kind,
            int limit,
            long offset) {
        return collisionMapper.findVisibleGroupPage(
                workspaceId, recordType, kind, limit, offset)
            .stream()
            .filter(row -> row.getRecordType() != null)
            .toList();
    }

    private static long totalOf(List<IdentityCollisionGroupPageRow> rows) {
        assertEquals(1, rows.size());
        return rows.getFirst().getTotal();
    }

    private static SQLException nestedSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        throw new AssertionError("Expected a nested SQLException");
    }

    private static List<IdentityCollisionGroupKey> keysOf(
            List<IdentityCollisionGroupPageRow> groups) {
        return groups.stream()
            .map(group -> new IdentityCollisionGroupKey(
                group.getRecordType(), group.getKind(), group.getNormalizedValue()))
            .toList();
    }

    private List<Integer> memberIds(String normalizedValue, int afterCompanyId, int limit) {
        return jdbcTemplate.queryForList(
            """
            SELECT company_id
            FROM company_identity
            WHERE workspace_id = ?
              AND kind = 'domain'
              AND normalized_value = ?
              AND company_id > ?
            ORDER BY company_id
            LIMIT ?
            """,
            Integer.class,
            workspace.getId(),
            normalizedValue,
            afterCompanyId,
            limit);
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
