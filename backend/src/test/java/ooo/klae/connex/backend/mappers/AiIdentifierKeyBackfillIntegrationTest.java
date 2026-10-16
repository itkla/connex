package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.services.AiIdentifierKeyBackfillTransaction;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Exercises every mapper writer, stale compatibility, CAS and derived-data lifecycle on MySQL. */
class AiIdentifierKeyBackfillIntegrationTest extends AbstractMapperTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiAssistantIdentifierMapper identifiers;
    @Autowired private AiIdentifierKeyBackfillTransaction backfill;
    @Autowired private TenantLifecycleMapper lifecycle;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;

    @BeforeEach
    void isolatedWorkspace() {
        Workspace created = new Workspace();
        created.setName("identifier-backfill-" + unique());
        created.setSlug("identifier-backfill-" + unique());
        workspaceMapper.insert(created);
        workspace = created;
    }

    @Test
    void allTenNameWriterStatementsPopulateTheCompleteBundle() {
        Company company = newCompany();
        Person person = newPerson(company);
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage, company);
        assertBundle("company", company.getId(), company.getName());
        assertBundle("person", person.getId(), person.getName());
        assertBundle("deal", deal.getId(), deal.getName());

        company.setName("㍿ [Company](person:1)");
        person.setName("ＡＣＭＥ [Person](person:2)");
        deal.setName("𠀀 [Deal](person:3)");
        assertEquals(1, companyMapper.update(company));
        assertEquals(1, personMapper.update(person));
        assertEquals(1, dealMapper.update(deal));
        assertBundle("company", company.getId(), company.getName());
        assertBundle("person", person.getId(), person.getName());
        assertBundle("deal", deal.getId(), deal.getName());
        assertEquals(1, dealMapper.updateName(workspace.getId(), deal.getId(), "㍿ Direct", null));
        assertBundle("deal", deal.getId(), "㍿ Direct");

        Company batchCompany = new Company();
        batchCompany.setWorkspaceId(workspace.getId());
        batchCompany.setName("㍿ Batch Company");
        assertEquals(1, companyMapper.insertBatch(List.of(batchCompany)));
        Person batchPerson = new Person();
        batchPerson.setWorkspaceId(workspace.getId());
        batchPerson.setName("㍿ Batch Person");
        assertEquals(1, personMapper.insertBatch(List.of(batchPerson)));
        Deal batchDeal = new Deal();
        batchDeal.setWorkspaceId(workspace.getId());
        batchDeal.setName("㍿ Batch Deal");
        batchDeal.setValue(new BigDecimal("1000.00"));
        batchDeal.setPipelineId(pipeline.getId());
        batchDeal.setStageId(stage.getId());
        batchDeal.setCurrency("JPY");
        assertEquals(1, dealMapper.insertBatch(List.of(batchDeal)));
        assertBundle("company", batchCompany.getId(), batchCompany.getName());
        assertBundle("person", batchPerson.getId(), batchPerson.getName());
        assertBundle("deal", batchDeal.getId(), batchDeal.getName());

        String original = person.getName();
        person.setName(null);
        person.setTitle("partial update");
        personMapper.update(person);
        assertBundle("person", person.getId(), original);
    }

    @Test
    void oldBinaryRenamesAndOldVersionsUseLegacyUntilReconciled() {
        Company company = newCompany();
        company.setName("㍿ Case");
        companyMapper.update(company);
        assertTrue(lookup("unrelated turn").isEmpty());
        jdbc.update("UPDATE company SET name = ? WHERE workspace_id = ? AND id = ?",
                "㍿ CASE ", workspace.getId(), company.getId());
        assertEquals(1L, backfill.pendingCount(workspace.getId()));
        assertEquals(List.of(company.getId()), lookup("unrelated turn").stream().map(row -> row.getId()).toList());
        assertEquals(1, backfill.backfillPage(workspace.getId(), "company", 0).written());
        assertBundle("company", company.getId(), "㍿ CASE ");
        assertTrue(lookup("unrelated turn").isEmpty());
        assertEquals(1, lookup("株式会社 case").size());

        jdbc.update("UPDATE company SET ai_identifier_key_version = ?, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                AiIdentifierLookupKeys.VERSION - 1, workspace.getId(), company.getId());
        assertEquals(1, lookup("unrelated turn").size());
        assertEquals(1, backfill.backfillPage(workspace.getId(), "company", 0).written());
        jdbc.update("UPDATE company SET ai_identifier_label_key = NULL, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                workspace.getId(), company.getId());
        assertEquals(1, lookup("unrelated turn").size());
        for (String missing : List.of("ai_identifier_literal_key", "ai_identifier_source_name")) {
            assertEquals(1, backfill.backfillPage(workspace.getId(), "company", 0).written());
            jdbc.update("UPDATE company SET " + missing + " = NULL, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                    workspace.getId(), company.getId());
            assertEquals(1, lookup("unrelated turn").size());
            assertEquals(1L, backfill.pendingCount(workspace.getId()));
        }
    }

    @Test
    void pagesRestartSafelyPreserveTimestampsAndExcludeRestrictedArchivedAndForeignRows() {
        Company parent = newCompany();
        List<Person> people = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            Person person = new Person();
            person.setWorkspaceId(workspace.getId());
            person.setName("佐藤太郎%04d".formatted(index));
            people.add(person);
        }
        personMapper.insertBatch(people);
        Person restricted = newPerson(parent);
        Person ceased = newPerson(parent);
        Person archived = newPerson(parent);
        personMapper.updateProcessingRestrictions(workspace.getId(), restricted.getId(), true, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), ceased.getId(), false, true);
        personMapper.archive(workspace.getId(), archived.getId());
        jdbc.update("UPDATE person SET ai_identifier_key_version = NULL, updated_at = '2020-01-02 03:04:05' WHERE workspace_id = ?",
                workspace.getId());
        assertEquals(205L, backfill.pendingCount(workspace.getId()));
        var first = backfill.backfillPage(workspace.getId(), "person", 0);
        assertEquals(100, first.written());
        assertEquals(105L, backfill.pendingCount(workspace.getId()));
        var restarted = backfill.backfillPage(workspace.getId(), "person", 0);
        assertEquals(100, restarted.scanned());
        assertEquals(0, restarted.written());
        assertEquals(first.lastId(), restarted.lastId());
        assertEquals(105L, backfill.pendingCount(workspace.getId()));
        var resumed = backfill.backfillPage(workspace.getId(), "person", restarted.lastId());
        assertEquals(100, resumed.written());
        assertTrue(resumed.lastId() > first.lastId());
        assertEquals(5, backfill.backfillPage(workspace.getId(), "person", resumed.lastId()).written());
        assertEquals(0L, backfill.pendingCount(workspace.getId()));
        assertEquals(208, jdbc.queryForObject("SELECT COUNT(*) FROM person WHERE workspace_id = ? AND updated_at = '2020-01-02 03:04:05'",
                Integer.class, workspace.getId()));
        assertEquals(0, identifiers.updatePersonKeysIfNameCurrent(workspace.getId() + 1, people.getFirst().getId(), people.getFirst().getName(), AiIdentifierLookupKeys.VERSION));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM person WHERE workspace_id = ? AND ai_identifier_key_version IS NULL",
                Integer.class, workspace.getId()));
    }

    @Test
    void compareAndSetRechecksRenamesRestrictionsAndDeletionWithoutClobberingWriters() {
        Person person = newPerson(newCompany());
        jdbc.update("UPDATE person SET ai_identifier_key_version = NULL, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                workspace.getId(), person.getId());
        var selected = identifiers.findPersonKeyBackfillWindow(workspace.getId(), 0, 100, AiIdentifierLookupKeys.VERSION).getFirst();
        jdbc.update("UPDATE person SET name = 'New Japanese 名前' WHERE workspace_id = ? AND id = ?", workspace.getId(), person.getId());
        assertEquals(0, identifiers.updatePersonKeysIfNameCurrent(workspace.getId(), person.getId(), selected.getValue(), AiIdentifierLookupKeys.VERSION));
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), true, false);
        assertEquals(0, identifiers.updatePersonKeysIfNameCurrent(workspace.getId(), person.getId(), "New Japanese 名前", AiIdentifierLookupKeys.VERSION));
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), false, false);
        assertEquals(1, backfill.backfillPage(workspace.getId(), "person", 0).written());
        assertBundle("person", person.getId(), "New Japanese 名前");
        person.setName("Modern Writer 名前");
        personMapper.update(person);
        assertEquals(0, identifiers.updatePersonKeysIfNameCurrent(workspace.getId(), person.getId(), "New Japanese 名前", AiIdentifierLookupKeys.VERSION));
        assertBundle("person", person.getId(), person.getName());
        jdbc.update("DELETE FROM person WHERE workspace_id = ? AND id = ?", workspace.getId(), person.getId());
        assertEquals(0, identifiers.updatePersonKeysIfNameCurrent(workspace.getId(), person.getId(), person.getName(), AiIdentifierLookupKeys.VERSION));
    }

    @Test
    void expansionAndIneligibleKeysExportWithTheirParentAndDisappearOnErasure() throws Exception {
        Company company = newCompany();
        company.setName("ﷺ".repeat(255));
        companyMapper.update(company);
        assertBundle("company", company.getId(), company.getName());
        assertEquals(AiIdentifierLookupKeys.UNKEYED, AiIdentifierLookupKeys.fromName(company.getName()).literal());
        assertEquals(1, lookup(company.getName()).size());
        assertEquals(0L, backfill.pendingCount(workspace.getId()));
        try (var rows = lifecycle.streamRows(workspace.getId(), TenantLifecycleRegistry.declarations().get("company"))) {
            var exported = rows.iterator().next();
            assertEquals(company.getName(), exported.get("ai_identifier_source_name"));
            assertEquals(AiIdentifierLookupKeys.fromName(company.getName()).literal(), exported.get("ai_identifier_literal_key"));
            assertTrue(exported.containsKey("ai_identifier_label_key"));
            assertTrue(exported.containsKey("ai_identifier_key_version"));
        }
        company.setName("{{}}");
        companyMapper.update(company);
        assertBundle("company", company.getId(), "{{}}");
        assertEquals(0L, backfill.pendingCount(workspace.getId()));
        jdbc.update("DELETE FROM company WHERE workspace_id = ? AND id = ?", workspace.getId(), company.getId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM company WHERE id = ?", Integer.class, company.getId()));
    }

    /** Raw SQL models another binary, so observations must discard this test transaction's prior read cache. */
    private List<ooo.klae.connex.backend.beans.AiAssistantIdentifierMention> lookup(String text) {
        sqlSession.clearCache();
        var turn = MaskingEngine.mentionScanText(text);
        return identifiers.findMentionedRecords(workspace.getId(), "[" + workspace.getId() + "]",
                turn.literalText(), turn.labelText(), 200, "", 0);
    }

    private void assertBundle(String table, int id, String name) {
        assertTrue(List.of("person", "company", "deal").contains(table));
        var row = jdbc.queryForMap("SELECT ai_identifier_literal_key, ai_identifier_label_key, ai_identifier_source_name, ai_identifier_key_version FROM "
                + table + " WHERE workspace_id = ? AND id = ?", workspace.getId(), id);
        var keys = AiIdentifierLookupKeys.fromName(name);
        assertEquals(keys.literal(), row.get("ai_identifier_literal_key"));
        assertEquals(keys.label(), row.get("ai_identifier_label_key"));
        assertEquals(name, row.get("ai_identifier_source_name"));
        assertEquals(AiIdentifierLookupKeys.VERSION, ((Number) row.get("ai_identifier_key_version")).intValue());
    }
}
