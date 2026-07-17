package ooo.klae.connex.backend.mappers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ActivityVolumeBucketDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.TeamLeaderboardEntryDto;

class ActivityMapperTest extends AbstractMapperTest {

    @Autowired ActivityMapper activityMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired NoteMapper noteMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private Activity build(String type, String subject, Person person, Deal deal, User user) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType(type);
        activity.setSubject(subject);
        activity.setNotes("notes-" + unique());
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(user);
        activity.setTimestamp("2024-06-01 10:00:00");
        return activity;
    }

    /**
     * Inserts a new activity and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        User user = newUser();
        Activity activity = build("call", "Intro call", null, null, user);

        activityMapper.insert(activity);

        assertNotEquals(0, activity.getId());
    }

    /**
     * Gets an activity by ID and checks if the returned activity is not null.
     */
    @Test
    void getActivityById_returnsInsertedRow() {
        User user = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);

        Activity activity = build("meeting", "Kickoff", person, deal, user);
        activityMapper.insert(activity);

        Activity found = activityMapper.getActivityById(workspace.getId(), activity.getId());

        assertNotNull(found);
        assertEquals("meeting", found.getType());
        assertEquals("Kickoff", found.getSubject());
        assertEquals(activity.getNotes(), found.getNotes());
        assertEquals(person.getId(), found.getPerson().getId());
        assertEquals(deal.getId(), found.getDeal().getId());
        assertEquals(user.getId(), found.getCreatedBy().getId());
        assertNotNull(found.getTimestamp());
    }

    /**
     * Gets an activity by ID and checks if the returned activity is null when the ID is missing.
     */
    @Test
    void getActivityById_returnsNullWhenMissing() {
        assertNull(activityMapper.getActivityById(workspace.getId(), -1));
    }

    /**
     * Inserts a new activity and checks if the person and deal are null when they are not provided.
     */
    @Test
    void insert_acceptsNullPersonAndDeal() {
        User user = newUser();
        Activity activity = build("email", "FYI", null, null, user);

        activityMapper.insert(activity);

        Activity found = activityMapper.getActivityById(workspace.getId(), activity.getId());
        assertNotNull(found);
        // Result map always materializes the association; with null FK the association id is 0.
        assertTrue(found.getPerson() == null || found.getPerson().getId() == 0);
        assertTrue(found.getDeal() == null || found.getDeal().getId() == 0);
    }

    /**
     * Gets all activities and checks if the returned list includes the inserted activity.
     */
    @Test
    void getAllActivities_includesInsertedRow() {
        User user = newUser();
        Activity activity = build("call", "Hello", null, null, user);
        activityMapper.insert(activity);

        List<Activity> allActivities = activityMapper.getAllActivities(workspace.getId());

        assertTrue(allActivities.stream().anyMatch(x -> x.getId() == activity.getId()));
    }

    @Test
    void getActivitiesByPersonIdsBatchesOnlyRequestedWorkspaceContacts() {
        User user = newUser();
        Person included = newPerson(newCompany());
        Person excluded = newPerson(newCompany());
        Activity includedActivity = build("meeting", "included", included, null, user);
        Activity excludedActivity = build("call", "excluded", excluded, null, user);
        activityMapper.insert(includedActivity);
        activityMapper.insert(excludedActivity);

        List<Activity> activities = activityMapper.getActivitiesByPersonIds(
            workspace.getId(), List.of(included.getId()));

        assertEquals(List.of(includedActivity.getId()), activities.stream().map(Activity::getId).toList());
    }

    @Test
    void getActivitiesPageLimitsAndCountsWorkspaceRows() {
        Workspace pageWorkspace = newWorkspace();
        User user = newUser();
        Activity first = build("call", "first", null, null, user);
        first.setWorkspaceId(pageWorkspace.getId());
        first.setTimestamp("2024-01-01 09:00:00");
        activityMapper.insert(first);
        Activity second = build("call", "second", null, null, user);
        second.setWorkspaceId(pageWorkspace.getId());
        second.setTimestamp("2024-01-01 09:00:00");
        activityMapper.insert(second);
        Activity third = build("call", "third", null, null, user);
        third.setWorkspaceId(pageWorkspace.getId());
        third.setTimestamp("2024-01-01 09:00:00");
        activityMapper.insert(third);
        Activity foreign = build("call", "foreign", null, null, user);
        activityMapper.insert(foreign);

        List<Activity> page = activityMapper.getActivitiesPage(pageWorkspace.getId(), 2, 0);

        assertEquals(List.of(third.getId(), second.getId()), page.stream().map(Activity::getId).toList());
        assertEquals(3, activityMapper.countActivities(pageWorkspace.getId(), null, null, null));
        assertTrue(page.stream().noneMatch(activity -> activity.getId() == foreign.getId()));
    }

    /**
     * Updates an activity and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        User user = newUser();
        Activity activity = build("call", "Initial", null, null, user);
        activityMapper.insert(activity);

        activity.setType("email");
        activity.setSubject("Updated");
        activity.setNotes("changed");
        activity.setTimestamp("2024-12-01 12:30:00");

        activityMapper.update(activity);

        Activity found = activityMapper.getActivityById(workspace.getId(), activity.getId());
        assertEquals("email", found.getType());
        assertEquals("Updated", found.getSubject());
        assertEquals("changed", found.getNotes());
    }

    /**
     * Deletes an activity and checks if the activity is removed.
     */
    @Test
    void delete_removesRow() {
        User user = newUser();
        Activity activity = build("call", "Bye", null, null, user);
        activityMapper.insert(activity);

        activityMapper.delete(workspace.getId(), activity.getId());

        assertNull(activityMapper.getActivityById(workspace.getId(), activity.getId()));
    }

    /**
     * Gets activities by person ID and checks if the returned list includes the inserted activity.
     */
    @Test
    void getActivitiesByPersonId_filtersByPerson() {
        User user = newUser();
        Person person1 = newPerson(newCompany());
        Person person2 = newPerson(newCompany());

        Activity activity1 = build("call", "for p1", person1, null, user);
        Activity activity2 = build("call", "for p2", person2, null, user);
        activityMapper.insert(activity1);
        activityMapper.insert(activity2);

        List<Activity> matched = activityMapper.getActivitiesByPersonId(workspace.getId(), person1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == activity1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == activity2.getId()));
    }

    /**
     * Gets activities by deal ID and checks if the returned list includes the inserted activity.
     */
    @Test
    void getActivitiesByDealId_filtersByDeal() {
        User user = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal1 = newDeal(pipeline, stage, newCompany());
        Deal deal2 = newDeal(pipeline, stage, newCompany());

        Activity activity1 = build("call", "d1", null, deal1, user);
        Activity activity2 = build("call", "d2", null, deal2, user);
        activityMapper.insert(activity1);
        activityMapper.insert(activity2);

        List<Activity> matched = activityMapper.getActivitiesByDealId(workspace.getId(), deal1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == activity1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == activity2.getId()));
    }

    /**
     * Gets activities by created by ID and checks if the returned list includes the inserted activity.
     */
    @Test
    void getActivitiesByCreatedById_filtersByUser() {
        User user1 = newUser();
        User user2 = newUser();

        Activity activity1 = build("call", "by u1", null, null, user1);
        Activity activity2 = build("call", "by u2", null, null, user2);
        activityMapper.insert(activity1);
        activityMapper.insert(activity2);

        List<Activity> matched = activityMapper.getActivitiesByCreatedById(workspace.getId(), user1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == activity1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == activity2.getId()));
    }

    /**
     * An activity in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void activities_areIsolatedByWorkspace() {
        User user = newUser();
        Activity mine = build("call", "mine", null, null, user);
        activityMapper.insert(mine);

        Workspace other = newWorkspace();
        Activity foreign = build("call", "foreign", null, null, user);
        foreign.setWorkspaceId(other.getId());
        activityMapper.insert(foreign);

        assertNull(activityMapper.getActivityById(workspace.getId(), foreign.getId()));
        assertTrue(activityMapper.getAllActivities(workspace.getId()).stream().noneMatch(a -> a.getId() == foreign.getId()));
        assertTrue(activityMapper.getAllActivities(workspace.getId()).stream().anyMatch(a -> a.getId() == mine.getId()));

        assertEquals(0, activityMapper.delete(workspace.getId(), foreign.getId()));
        assertNotNull(activityMapper.getActivityById(other.getId(), foreign.getId()));
    }

    @Test
    void activityVolumeNormalizesTypeCasingAndPreservesWorkspaceScope() {
        Workspace target = newWorkspace();
        User user = newUser();
        Activity call = build("call", "call", null, null, user);
        call.setWorkspaceId(target.getId());
        activityMapper.insert(call);
        Activity email = build("  eMAIL  ", "email", null, null, user);
        email.setWorkspaceId(target.getId());
        activityMapper.insert(email);
        Activity meeting = build("MEETING", "meeting", null, null, user);
        meeting.setWorkspaceId(target.getId());
        activityMapper.insert(meeting);
        Activity note = build("nOtE", "note", null, null, user);
        note.setWorkspaceId(target.getId());
        activityMapper.insert(note);
        Activity other = build("Demo", "other", null, null, user);
        other.setWorkspaceId(target.getId());
        activityMapper.insert(other);
        Activity expired = build("Call", "expired", null, null, user);
        expired.setWorkspaceId(target.getId());
        activityMapper.insert(expired);
        Activity future = build("Call", "future", null, null, user);
        future.setWorkspaceId(target.getId());
        activityMapper.insert(future);
        Activity foreign = build("Call", "foreign", null, null, user);
        activityMapper.insert(foreign);
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id IN (?, ?, ?, ?)",
            call.getId(), email.getId(), meeting.getId(), note.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(NOW(), INTERVAL 6 DAY) WHERE id = ?", other.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id = ?", expired.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_ADD(NOW(), INTERVAL 1 DAY) WHERE id = ?", future.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id = ?", foreign.getId());

        Map<Integer, ActivityVolumeBucketDto> volume = activityMapper
            .activityVolume(target.getId(), 30, 6, 5.0, MemberScope.allTeam()).stream()
            .collect(Collectors.toMap(ActivityVolumeBucketDto::bucketIndex, bucket -> bucket));

        assertEquals(2, volume.size());
        assertEquals(new ActivityVolumeBucketDto(5, 1, 1, 1, 1, 0), volume.get(5));
        assertEquals(new ActivityVolumeBucketDto(4, 0, 0, 0, 0, 1), volume.get(4));
    }

    @Test
    void activityVolumeAlignsTwelveMonthsToCalendarMonths() {
        Workspace target = newWorkspace();
        User user = newUser();
        Activity current = build("Call", "current", null, null, user);
        current.setWorkspaceId(target.getId());
        activityMapper.insert(current);
        Activity oldest = build("Email", "oldest", null, null, user);
        oldest.setWorkspaceId(target.getId());
        activityMapper.insert(oldest);
        Activity beforeRange = build("Meeting", "before", null, null, user);
        beforeRange.setWorkspaceId(target.getId());
        activityMapper.insert(beforeRange);
        jdbcTemplate.update("UPDATE activity SET timestamp = NOW() WHERE id = ?", current.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_ADD(DATE_SUB(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 11 MONTH), INTERVAL 1 DAY) WHERE id = ?",
            oldest.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 12 MONTH) WHERE id = ?",
            beforeRange.getId());

        Map<Integer, ActivityVolumeBucketDto> volume = activityMapper
            .activityVolume(target.getId(), 365, 12, 365.0 / 12.0, MemberScope.allTeam()).stream()
            .collect(Collectors.toMap(ActivityVolumeBucketDto::bucketIndex, bucket -> bucket));

        assertEquals(2, volume.size());
        assertEquals(1, volume.get(11).call());
        assertEquals(1, volume.get(0).email());
    }

    @Test
    void activityVolumeHonorsMemberScopeByCreator() {
        Workspace target = newWorkspace();
        User creator = newUser();
        User other = newUser();
        Activity mineCall = build("Call", "m1", null, null, creator);
        mineCall.setWorkspaceId(target.getId());
        activityMapper.insert(mineCall);
        Activity mineEmail = build("Email", "m2", null, null, creator);
        mineEmail.setWorkspaceId(target.getId());
        activityMapper.insert(mineEmail);
        Activity theirsCall = build("Call", "t1", null, null, other);
        theirsCall.setWorkspaceId(target.getId());
        activityMapper.insert(theirsCall);
        Activity foreign = build("Call", "f1", null, null, creator);
        activityMapper.insert(foreign);
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id IN (?, ?, ?, ?)",
            mineCall.getId(), mineEmail.getId(), theirsCall.getId(), foreign.getId());

        MemberScope me = MemberScope.fromRequest("me", null, creator.getId());
        MemberScope members = MemberScope.fromRequest(
            "members", List.of(creator.getId(), other.getId()), creator.getId());
        MemberScope unassigned = MemberScope.fromRequest("unassigned", null, creator.getId());

        assertEquals(new ActivityVolumeBucketDto(5, 2, 1, 0, 0, 0),
            volumeBucket(target, MemberScope.allTeam(), 5));
        assertEquals(new ActivityVolumeBucketDto(5, 1, 1, 0, 0, 0),
            volumeBucket(target, me, 5));
        assertEquals(new ActivityVolumeBucketDto(5, 2, 1, 0, 0, 0),
            volumeBucket(target, members, 5));
        assertTrue(activityMapper.activityVolume(target.getId(), 30, 6, 5.0, unassigned).isEmpty());
    }

    private ActivityVolumeBucketDto volumeBucket(Workspace ws, MemberScope scope, int bucketIndex) {
        return activityMapper.activityVolume(ws.getId(), 30, 6, 5.0, scope).stream()
            .filter(bucket -> bucket.bucketIndex() == bucketIndex)
            .findFirst()
            .orElseThrow();
    }

    @Test
    void leaderboardAndUpcomingCountAggregateAllTouchSourcesWithinWorkspace() {
        Workspace target = newWorkspace();
        User leader = newUser();
        User second = newUser();
        Activity activity = build("Call", "touch", null, null, leader);
        activity.setWorkspaceId(target.getId());
        activityMapper.insert(activity);
        Activity upcoming = build("Meeting", "upcoming", null, null, leader);
        upcoming.setWorkspaceId(target.getId());
        activityMapper.insert(upcoming);
        Activity tooLate = build("Meeting", "too-late", null, null, leader);
        tooLate.setWorkspaceId(target.getId());
        activityMapper.insert(tooLate);
        Activity foreignUpcoming = build("Meeting", "foreign-upcoming", null, null, leader);
        activityMapper.insert(foreignUpcoming);

        Task completed = new Task();
        completed.setWorkspaceId(target.getId());
        completed.setDescription("completed");
        completed.setCompleted(true);
        completed.setStatus("done");
        completed.setAssignedTo(leader);
        taskMapper.insert(completed);
        Task incomplete = new Task();
        incomplete.setWorkspaceId(target.getId());
        incomplete.setDescription("incomplete");
        incomplete.setCompleted(false);
        incomplete.setStatus("todo");
        incomplete.setAssignedTo(second);
        taskMapper.insert(incomplete);
        Task foreignCompleted = new Task();
        foreignCompleted.setWorkspaceId(workspace.getId());
        foreignCompleted.setDescription("foreign-completed");
        foreignCompleted.setCompleted(true);
        foreignCompleted.setStatus("done");
        foreignCompleted.setAssignedTo(leader);
        taskMapper.insert(foreignCompleted);

        Note note = new Note();
        note.setWorkspaceId(target.getId());
        note.setContent("touch");
        note.setVisibility("workspace");
        note.setAuthor(second);
        noteMapper.insert(note);
        Note privateNote = new Note();
        privateNote.setWorkspaceId(target.getId());
        privateNote.setContent("private");
        privateNote.setVisibility("private");
        privateNote.setAuthor(second);
        noteMapper.insert(privateNote);
        Note foreignNote = new Note();
        foreignNote.setWorkspaceId(workspace.getId());
        foreignNote.setContent("foreign");
        foreignNote.setAuthor(leader);
        noteMapper.insert(foreignNote);

        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id = ?", activity.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_ADD(NOW(), INTERVAL 1 DAY) WHERE id = ?", upcoming.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_ADD(NOW(), INTERVAL 8 DAY) WHERE id = ?", tooLate.getId());
        jdbcTemplate.update("UPDATE activity SET timestamp = DATE_ADD(NOW(), INTERVAL 1 DAY) WHERE id = ?", foreignUpcoming.getId());
        jdbcTemplate.update("UPDATE task SET updated_at = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id IN (?, ?, ?)",
            completed.getId(), incomplete.getId(), foreignCompleted.getId());
        jdbcTemplate.update("UPDATE note SET created_at = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id IN (?, ?, ?)",
            note.getId(), foreignNote.getId(), privateNote.getId());

        List<TeamLeaderboardEntryDto> leaderboard = activityMapper.teamLeaderboard(target.getId(), 30);

        assertEquals(List.of(
            new TeamLeaderboardEntryDto(leader.getId(), 2),
            new TeamLeaderboardEntryDto(second.getId(), 1)
        ), leaderboard);
        assertEquals(1, activityMapper.upcomingCount(target.getId(), 7));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
