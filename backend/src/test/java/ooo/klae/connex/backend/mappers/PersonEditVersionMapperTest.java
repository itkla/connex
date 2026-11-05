package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonLeadSource;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;

class PersonEditVersionMapperTest extends AbstractMapperTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;

    @Test
    void everyTargetedLegacyAndMaintenanceWriterAdvancesTheDatabaseCounter() {
        Person person = newPerson(newCompany());
        int workspaceId = workspace.getId();
        int id = person.getId();
        LocalDateTime now = LocalDateTime.of(2026, 11, 1, 0, 0);
        var user = newUser();
        List<Runnable> writes = List.of(
            () -> personMapper.update(person),
            () -> personMapper.updateOwner(workspaceId, id, user.getId()),
            () -> personMapper.clearMemberOwnership(workspaceId, user.getId()),
            () -> personMapper.updateOwner(workspaceId, id, user.getId()),
            () -> personMapper.clearOwnershipAnywhere(user.getId()),
            () -> personMapper.updateImageUrlIfCurrent(workspaceId, id, null, "managed-image"),
            () -> personMapper.updateEvaluationExclusions(workspaceId, id, true, true),
            () -> personMapper.updateProcessingRestrictions(workspaceId, id, false, false),
            () -> personMapper.updateProvenance(workspaceId, id, PersonLeadSource.OTHER, null, null),
            () -> personMapper.updateLifecycle(workspaceId, id, PersonLifecycleStage.NEW, now, null, null),
            () -> personMapper.startFirstResponseClock(workspaceId, id, now, now.plusHours(1)),
            () -> personMapper.recordFirstResponseBreach(workspaceId, id, now.plusHours(2)),
            () -> personMapper.recordFirstResponse(workspaceId, id, now.plusHours(3)),
            () -> personMapper.clearFirstResponseClock(workspaceId, id),
            () -> {
                jdbcTemplate.update("UPDATE person SET name = ?, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                    "Timestamp-preserving maintenance", workspaceId, id);
                sqlSessionTemplate.clearCache();
            }
        );
        for (Runnable write : writes) {
            long before = current(person).getVersion();
            write.run();
            assertEquals(before + 1, current(person).getVersion());
        }
        long beforeArchive = current(person).getVersion();
        personMapper.archive(workspaceId, id);
        assertEquals(beforeArchive + 1, personMapper.getOwnedArchivedPersonById(workspaceId, dealScope(workspaceId), id).getVersion());
        personMapper.restore(workspaceId, id);
        assertEquals(beforeArchive + 2, current(person).getVersion());
    }

    @Test
    void explicitEmptyFieldsClearExistingContactValuesAndReturnAnAdvancedVersion() {
        Person person = newPerson(newCompany());
        person.setEmail("clear-fields@example.test");
        person.setPhone("123456");
        person.setTitle("Director");
        assertEquals(1, personMapper.update(person));
        Person edit = current(person);
        long before = edit.getVersion();
        edit.setExpectedVersion(before);
        edit.setEmail("");
        edit.setPhone("");
        edit.setTitle("");
        assertEquals(1, personMapper.update(edit));
        Person saved = current(person);
        assertEquals("", saved.getEmail());
        assertEquals("", saved.getPhone());
        assertEquals("", saved.getTitle());
        assertEquals(before + 1, saved.getVersion());
    }

    @Test
    void maintenanceCannotResetTheCounterEvenWhenItPreservesTheTimestamp() {
        Person person = newPerson(newCompany());
        Person before = current(person);
        jdbcTemplate.update("UPDATE person SET version = 1000, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
            workspace.getId(), person.getId());
        sqlSessionTemplate.clearCache();
        Person after = current(person);
        assertEquals(before.getVersion() + 1, after.getVersion());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
    }

    @Test
    void companyDeletionAdvancesTheVersionWhenItsForeignKeyDetachesASurvivingContact() {
        var company = newCompany();
        Person person = newPerson(company);
        long before = current(person).getVersion();
        jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ? AND id = ?", workspace.getId(), company.getId());
        sqlSessionTemplate.clearCache();
        Person detached = current(person);
        assertNull(detached.getCompany());
        assertEquals(before + 1, detached.getVersion());
    }

    @Test
    void staleAndForeignWorkspaceUpdatesWriteNothingAndLegacyUpdateStillWorks() {
        Person stale = newPerson(newCompany());
        stale.setExpectedVersion(0L);
        Person changed = current(stale);
        changed.setTitle("New title");
        assertEquals(1, personMapper.update(changed));
        assertEquals(0, personMapper.update(stale));
        stale.setExpectedVersion(current(stale).getVersion());
        stale.setWorkspaceId(-1);
        assertEquals(0, personMapper.update(stale));
        stale.setWorkspaceId(workspace.getId());
        stale.setExpectedVersion(null);
        assertEquals(1, personMapper.update(stale));
        assertTrue(current(stale).getVersion() > 0);
    }

    private Person current(Person person) {
        int workspaceId = workspace.getId();
        return personMapper.getPersonById(workspaceId, dealScope(workspaceId), person.getId());
    }
}
