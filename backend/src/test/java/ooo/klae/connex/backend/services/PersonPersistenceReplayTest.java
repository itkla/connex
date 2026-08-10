package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersonPersistenceReplayTest extends AbstractServiceTest {

    @Autowired private PersonService personService;
    @Autowired private DuplicatePreflightService duplicatePreflightService;
    @Autowired private ScoringService scoringService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private NotificationChangePublisher notificationChanges;

    private Integer persistedCompanyId;
    private Integer persistedPersonId;
    private Integer persistedTagId;
    private String replayEmail;

    @AfterEach
    void cleanUpCommittedFixtures() {
        List<Integer> replayPersonIds = persistedReplayPersonIds();
        if (workspace != null) {
            for (int personId : replayPersonIds) {
                jdbcTemplate.update(
                    "DELETE FROM activity WHERE workspace_id = ? AND person_id = ?",
                    workspace.getId(),
                    personId);
                jdbcTemplate.update(
                    "DELETE FROM task WHERE workspace_id = ? AND person_id = ?",
                    workspace.getId(),
                    personId);
            }
        }
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM notification WHERE workspace_id = ? AND recipient_id = ?",
                workspace.getId(),
                currentUser.getId());
        }
        if (workspace != null) {
            for (int personId : replayPersonIds) {
                jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                    workspace.getId(),
                    personId);
            }
        }
        if (workspace != null && persistedTagId != null) {
            jdbcTemplate.update(
                "DELETE FROM tag WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedTagId);
        }
        if (workspace != null && persistedCompanyId != null) {
            jdbcTemplate.update(
                "DELETE FROM company WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                persistedCompanyId);
        }
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(),
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
    }

    @Test
    void manualReviewedCreateThreeIdenticalCyclesKeepEveryArtifactStable() {
        Company company = newCompany();
        persistedCompanyId = company.getId();
        String email = "manual-replay-" + unique() + "@example.test";
        replayEmail = email;
        PersonDuplicatePreflightRequest request = new PersonDuplicatePreflightRequest(
            "Manual replay",
            List.of(email),
            List.of("+81 90 7654 3210"));
        DuplicatePreflightResponse review = duplicatePreflightService.preflightPerson(request);
        Person first = personService.createReviewed(
            personDraft(
                "Manual replay",
                email,
                "+81 90 7654 3210",
                company),
            review.reviewToken());
        persistedPersonId = first.getId();
        Tag tag = newTag();
        persistedTagId = tag.getId();
        personService.addTag(first.getId(), tag.getId());
        newActivity(currentUser, first, null);
        newTask(currentUser, first, null);
        newNotification(workspace.getId(), currentUser.getId());
        ReplayArtifacts afterFirst = replayArtifacts(first.getId());

        assertThrows(
            ConflictException.class,
            () -> personService.createReviewed(
                personDraft(
                    "Manual replay",
                    email,
                    "+81 90 7654 3210",
                    company),
                review.reviewToken()));
        ReplayArtifacts afterSecond = replayArtifacts(first.getId());
        assertThrows(
            ConflictException.class,
            () -> personService.createReviewed(
                personDraft(
                    "Manual replay",
                    email,
                    "+81 90 7654 3210",
                    company),
                review.reviewToken()));

        assertEquals(1, afterFirst.activities());
        assertEquals(1, afterFirst.tasks());
        assertTrue(afterFirst.tags() >= 2);
        assertTrue(afterFirst.relationships() >= 1);
        assertTrue(afterFirst.notifications() >= 1);
        assertEquals(2, afterFirst.relationshipEvidenceEvents());
        assertEquals(afterFirst, afterSecond);
        assertEquals(afterFirst, replayArtifacts(first.getId()));
    }

    private static Person personDraft(
            String name,
            String email,
            String phone,
            Company company) {
        Person person = new Person();
        person.setName(name);
        person.setEmail(email);
        person.setPhone(phone);
        person.setTitle("Engineer");
        person.setCompany(company);
        return person;
    }

    private ReplayArtifacts replayArtifacts(int personId) {
        int records = rowCount(
                "SELECT COUNT(*) FROM person WHERE workspace_id = ?", workspace.getId())
            + rowCount(
                "SELECT COUNT(*) FROM company WHERE workspace_id = ?", workspace.getId())
            + rowCount(
                "SELECT COUNT(*) FROM deal WHERE workspace_id = ?", workspace.getId());
        int tags = rowCount(
                "SELECT COUNT(*) FROM tag WHERE workspace_id = ?", workspace.getId())
            + rowCount("SELECT COUNT(*) FROM person_tag WHERE person_id = ?", personId);
        int relationships = rowCount(
                "SELECT COUNT(*) FROM person_employment WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId)
            + rowCount(
                "SELECT COUNT(*) FROM deal_person dp JOIN deal d ON d.id = dp.deal_id "
                    + "WHERE d.workspace_id = ? AND dp.person_id = ?",
                workspace.getId(),
                personId);
        return new ReplayArtifacts(
            records,
            rowCount(
                "SELECT COUNT(*) FROM activity WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            rowCount(
                "SELECT COUNT(*) FROM task WHERE workspace_id = ? AND person_id = ?",
                workspace.getId(),
                personId),
            tags,
            relationships,
            rowCount(
                "SELECT COUNT(*) FROM notification WHERE workspace_id = ?",
                workspace.getId()),
            scoringService.contactEvidence(
                workspace.getId(), personId, currentUser.getId())
                .totals()
                .contributorCount());
    }

    private int rowCount(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private List<Integer> persistedReplayPersonIds() {
        if (workspace == null) {
            return List.of();
        }
        if (replayEmail != null) {
            return jdbcTemplate.queryForList(
                "SELECT id FROM person WHERE workspace_id = ? AND email = ?",
                Integer.class,
                workspace.getId(),
                replayEmail);
        }
        return persistedPersonId == null ? List.of() : List.of(persistedPersonId);
    }

    private record ReplayArtifacts(
        int records,
        int activities,
        int tasks,
        int tags,
        int relationships,
        int notifications,
        int relationshipEvidenceEvents
    ) {}
}
