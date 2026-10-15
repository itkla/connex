package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Cursor;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Kind;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Reference;
import ooo.klae.connex.backend.support.AssignmentSweepFixtures;

/** Exercises actual SQL for all lifecycle states, exact predicates, and bounded numeric discovery. */
class AssignmentSweepMapperTest extends AbstractMapperTest {
    @Autowired private AssignmentSweepMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void allEightKindsRemainDiscoverableWhenArchivedOrClosedAndClearIdempotently() {
        User owner = newUser();
        var company = newCompany();
        var person = newPerson(company);
        var pipeline = newPipeline();
        var deal = newDeal(pipeline, newStage(pipeline, 0), company);
        int ws = workspace.getId();
        jdbc.update("UPDATE company SET owner_id = ?, archived_at = CURRENT_TIMESTAMP WHERE id = ?",
            owner.getId(), company.getId());
        jdbc.update("UPDATE person SET owner_id = ?, archived_at = CURRENT_TIMESTAMP WHERE id = ?",
            owner.getId(), person.getId());
        jdbc.update("UPDATE deal SET owner_id = ?, closed_at = CURRENT_TIMESTAMP, won = TRUE WHERE id = ?",
            owner.getId(), deal.getId());
        jdbc.update("INSERT INTO deal_collaborator (workspace_id, deal_id, user_id) VALUES (?, ?, ?)",
            ws, deal.getId(), owner.getId());
        jdbc.update("INSERT INTO task (workspace_id, description, completed, status, assigned_to_id) VALUES (?, 'closed', TRUE, 'done', ?)",
            ws, owner.getId());
        jdbc.update("INSERT INTO campaign (workspace_id, name, type, status, owner_user_id) VALUES (?, 'closed', 'event', 'archived', ?)",
            ws, owner.getId());
        jdbc.update("""
            INSERT INTO person_lifecycle_pass (workspace_id, person_id, entered_at, ended_at, owner_id)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
            """, ws, person.getId(), owner.getId());
        long intervention = AssignmentSweepFixtures.intervention(jdbc, ws, person.getId(), owner.getId());
        for (Kind kind : Kind.values()) {
            var page = mapper.findReferencesAnywhere(kind, owner.getId(), Cursor.beginning(), mapper.findWatermark(kind), 200);
            if (kind == Kind.LIFECYCLE_PASS) {
                assertTrue(page.isEmpty());
                continue;
            }
            assertEquals(1, page.size(), kind.name());
            Reference ref = page.getFirst();
            assertNotNull(mapper.lockParent(kind, ref));
            Reference foreign = new Reference(ref.id(), ws + 1000000, ref.parentId(), ref.userId());
            assertNull(mapper.lockParent(kind, foreign));
            assertEquals(0, clear(kind, foreign));
            assertEquals(0, clear(kind, new Reference(ref.id(), ws, ref.parentId(), Integer.MAX_VALUE)));
            assertEquals(1, clear(kind, ref), kind.name());
            assertEquals(0, clear(kind, ref), kind.name());
        }
        assertEquals(1, jdbc.queryForObject("SELECT source_version FROM workflow_intervention WHERE id = ?",
            Integer.class, intervention));
        assertEquals(List.of(owner.getId()), userMapper.findExistingIds(List.of(owner.getId(), Integer.MAX_VALUE)));
    }

    @Test
    void aHistoricalPassWithASurvivingCurrentOwnerIsClearedWithoutChangingThatOwner() {
        User erased = newUser();
        User survivor = newUser();
        var person = newPerson(newCompany());
        jdbc.update("UPDATE person SET owner_id = ? WHERE id = ?", survivor.getId(), person.getId());
        jdbc.update("""
            INSERT INTO person_lifecycle_pass (workspace_id, person_id, entered_at, owner_id)
            VALUES (?, ?, CURRENT_TIMESTAMP, ?)
            """, workspace.getId(), person.getId(), erased.getId());
        Reference ref = mapper.findReferencesAnywhere(Kind.LIFECYCLE_PASS, erased.getId(), Cursor.beginning(),
            mapper.findWatermark(Kind.LIFECYCLE_PASS), 2).getFirst();
        assertNotNull(mapper.lockParent(Kind.LIFECYCLE_PASS, ref));
        assertEquals(0, mapper.clearOwner(Kind.LIFECYCLE_PASS, ref));
        assertEquals(0, mapper.clearPersonPassOwners(new Reference(ref.id(), ref.workspaceId() + 1000000, ref.parentId(), ref.userId())));
        assertEquals(0, mapper.clearPersonPassOwners(new Reference(ref.id(), ref.workspaceId(), ref.parentId(), Integer.MAX_VALUE)));
        jdbc.update("""
            INSERT INTO person_lifecycle_pass (workspace_id, person_id, entered_at, ended_at, owner_id)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
            """, workspace.getId(), person.getId(), survivor.getId());
        assertEquals(1, mapper.clearPersonPassOwners(ref));
        assertEquals(0, mapper.clearPersonPassOwners(ref));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM person_lifecycle_pass WHERE person_id = ? AND owner_id = ?",
            Integer.class, person.getId(), survivor.getId()));
        assertEquals(survivor.getId(), jdbc.queryForObject("SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId()));
    }

    @Test
    void changedPointersAreNeverClearedByStaleCandidates() {
        User erased = newUser();
        User survivor = newUser();
        var company = newCompany();
        jdbc.update("UPDATE company SET owner_id = ? WHERE id = ?", erased.getId(), company.getId());
        Reference stale = mapper.findReferencesAnywhere(Kind.COMPANY, erased.getId(), Cursor.beginning(),
            mapper.findWatermark(Kind.COMPANY), 2).getFirst();
        jdbc.update("UPDATE company SET owner_id = ? WHERE id = ?", survivor.getId(), company.getId());
        assertNotNull(mapper.lockParent(Kind.COMPANY, stale));
        assertEquals(0, mapper.clearOwner(Kind.COMPANY, stale));
        assertEquals(survivor.getId(), jdbc.queryForObject("SELECT owner_id FROM company WHERE id = ?", Integer.class, company.getId()));
    }

    private int clear(Kind kind, Reference ref) {
        return switch (kind) {
            case COMPANY, PERSON, DEAL -> {
                int cleared = mapper.clearOwner(kind, ref);
                if (kind == Kind.PERSON) {
                    mapper.clearPersonPassOwners(ref);
                }
                yield cleared;
            }
            case COLLABORATOR -> mapper.removeCollaborator(ref);
            case TASK -> mapper.unassignTask(ref);
            case INTERVENTION -> mapper.clearInterventionOwner(ref);
            case CAMPAIGN -> mapper.clearCampaignOwner(ref);
            case LIFECYCLE_PASS -> mapper.clearPersonPassOwners(ref);
        };
    }
}
