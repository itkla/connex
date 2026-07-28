package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.services.IdentityBackfillTransaction;
import ooo.klae.connex.backend.services.IdentityBackfillTransaction.IdentityBackfillBatch;

/**
 * End-to-end MySQL coverage for tolerant, convergent identity backfill pages.
 */
class IdentityBackfillIntegrationTest extends AbstractMapperTest {

    @Autowired private IdentityBackfillTransaction backfillTransaction;
    @Autowired private IdentityCollisionMapper collisionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useIsolatedWorkspace() {
        workspace = newWorkspace("identity-backfill");
    }

    @Test
    void multiPageRunSkipsInvalidValuesAndBuildsCollisionsAfterward() {
        Company firstCompany = newCompany(workspace, "https://www.alpha.co.jp/about");
        Company secondCompany = newCompany(workspace, "https://sub.alpha.co.jp");
        newCompany(workspace, "not a valid domain");
        Person first = newPerson(
            workspace, firstCompany, "Dup@Example.com", "090-1111-1111");
        newPerson(
            workspace, firstCompany, "not-an-email", "090-2222-2222");
        newPerson(
            workspace, secondCompany, "dup@example.com", "123");
        Person restricted = newPerson(
            workspace, secondCompany, "restricted@example.com", "090-3333-3333");
        personMapper.updateProcessingRestrictions(
            workspace.getId(), restricted.getId(), true, false);

        BackfillTotals personTotals = runPersonPages(2);
        BackfillTotals companyTotals = runCompanyPages(2);
        int collisionMemberships =
            backfillTransaction.rebuildCollisionReport(null, workspace.getId());

        assertEquals(3, personTotals.recordsScanned);
        assertEquals(4, personTotals.identitiesCreated);
        assertEquals(1, personTotals.invalidEmails);
        assertEquals(1, personTotals.invalidPhones);
        assertEquals(3, companyTotals.recordsScanned);
        assertEquals(2, companyTotals.identitiesCreated);
        assertEquals(1, companyTotals.invalidDomains);
        assertEquals(4, collisionMemberships);
        assertEquals(
            2L,
            collisionMapper.findVisibleGroupPage(
                workspace.getId(), null, null, 100, 0)
                .getFirst()
                .getTotal());
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ? AND person_id = ?",
                Integer.class,
                workspace.getId(),
                restricted.getId()));
        assertEquals(
            "Dup@Example.com",
            jdbcTemplate.queryForObject(
                """
                SELECT `value`
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                """,
                String.class,
                workspace.getId(),
                first.getId()));
    }

    @Test
    void partialRunAndFullReplayConvergeWithoutAdditionalRows() {
        Company company = newCompany(workspace, "https://www.converge.co.jp");
        Person first =
            newPerson(workspace, company, "first@converge.co.jp", "090-1111-1111");
        newPerson(workspace, company, "second@converge.co.jp", "090-2222-2222");
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, acquired_at
            )
            SELECT workspace_id, id, 'email', email, 'first@converge.co.jp',
                   'manual', created_at
            FROM person
            WHERE workspace_id = ? AND id = ?
            """,
            workspace.getId(),
            first.getId());

        IdentityBackfillBatch partial =
            backfillTransaction.backfillPersonPage(null, workspace.getId(), 0, 1);
        BackfillTotals completion = runPersonPages(1);
        int rowCountAfterCompletion = countPersonIdentities();
        BackfillTotals replay = runPersonPages(1);

        assertEquals(1, partial.recordsScanned());
        assertEquals(1, partial.identitiesAlreadyPresent());
        assertEquals(1, partial.identitiesCreated());
        assertEquals(2, completion.identitiesCreated);
        assertEquals(4, rowCountAfterCompletion);
        assertEquals(0, replay.identitiesCreated);
        assertEquals(4, replay.identitiesAlreadyPresent);
        assertEquals(rowCountAfterCompletion, countPersonIdentities());
        assertEquals(
            "manual",
            jdbcTemplate.queryForObject(
                """
                SELECT source_system
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                """,
                String.class,
                workspace.getId(),
                first.getId()));
    }

    @Test
    void scalarFormattingAndValueChangesPreserveHistoricalIdentityRows() {
        Company company = newCompany(workspace, "https://history.example.com");
        Person person =
            newPerson(workspace, company, "Case@Example.com", "090-1111-1111");

        runPersonPages(10);
        person.setEmail("case@example.com");
        personMapper.update(person);
        BackfillTotals formattingReplay = runPersonPages(10);

        assertEquals(0, formattingReplay.identitiesCreated);
        assertEquals(
            "Case@Example.com",
            jdbcTemplate.queryForObject(
                """
                SELECT `value`
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                """,
                String.class,
                workspace.getId(),
                person.getId()));

        person.setEmail("new@example.com");
        personMapper.update(person);
        BackfillTotals changedReplay = runPersonPages(10);

        assertEquals(1, changedReplay.identitiesCreated);
        assertEquals(
            List.of("case@example.com", "new@example.com"),
            jdbcTemplate.queryForList(
                """
                SELECT normalized_value
                FROM person_identity
                WHERE workspace_id = ? AND person_id = ? AND kind = 'email'
                ORDER BY normalized_value
                """,
                String.class,
                workspace.getId(),
                person.getId()));
    }

    @Test
    void everyBackfillMutationBoundaryIsTransactional() throws Exception {
        Method person = IdentityBackfillTransaction.class.getMethod(
            "backfillPersonPage", String.class, int.class, int.class, int.class);
        Method company = IdentityBackfillTransaction.class.getMethod(
            "backfillCompanyPage", String.class, int.class, int.class, int.class);
        Method rebuild = IdentityBackfillTransaction.class.getMethod(
            "rebuildCollisionReport", String.class, int.class);

        assertNotNull(person.getAnnotation(Transactional.class));
        assertNotNull(company.getAnnotation(Transactional.class));
        assertNotNull(rebuild.getAnnotation(Transactional.class));
    }

    private BackfillTotals runPersonPages(int limit) {
        List<IdentityBackfillBatch> batches = new ArrayList<>();
        int afterId = 0;
        while (true) {
            IdentityBackfillBatch batch =
                backfillTransaction.backfillPersonPage(
                    null, workspace.getId(), afterId, limit);
            batches.add(batch);
            if (batch.recordsScanned() < limit) {
                break;
            }
            assertTrue(batch.lastRecordId() > afterId);
            afterId = batch.lastRecordId();
        }
        return BackfillTotals.from(batches);
    }

    private BackfillTotals runCompanyPages(int limit) {
        List<IdentityBackfillBatch> batches = new ArrayList<>();
        int afterId = 0;
        while (true) {
            IdentityBackfillBatch batch =
                backfillTransaction.backfillCompanyPage(
                    null, workspace.getId(), afterId, limit);
            batches.add(batch);
            if (batch.recordsScanned() < limit) {
                break;
            }
            assertTrue(batch.lastRecordId() > afterId);
            afterId = batch.lastRecordId();
        }
        return BackfillTotals.from(batches);
    }

    private int countPersonIdentities() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
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
        company.setWebsite(website);
        company.setIndustry("Tech");
        company.setPhone("+81-90-1234-5678");
        company.setAddress("Tokyo");
        companyMapper.insert(company);
        return company;
    }

    private Person newPerson(
            Workspace owner,
            Company company,
            String email,
            String phone) {
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

    private record BackfillTotals(
            int recordsScanned,
            int identitiesCreated,
            int identitiesAlreadyPresent,
            int invalidEmails,
            int invalidPhones,
            int invalidDomains,
            int skippedWrites) {

        private static BackfillTotals from(List<IdentityBackfillBatch> batches) {
            return new BackfillTotals(
                batches.stream().mapToInt(IdentityBackfillBatch::recordsScanned).sum(),
                batches.stream().mapToInt(IdentityBackfillBatch::identitiesCreated).sum(),
                batches.stream().mapToInt(IdentityBackfillBatch::identitiesAlreadyPresent).sum(),
                batches.stream().mapToInt(IdentityBackfillBatch::invalidEmails).sum(),
                batches.stream().mapToInt(IdentityBackfillBatch::invalidPhones).sum(),
                batches.stream().mapToInt(IdentityBackfillBatch::invalidDomains).sum(),
                batches.stream().mapToInt(IdentityBackfillBatch::skippedWrites).sum());
        }
    }
}
