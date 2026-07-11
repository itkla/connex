package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.BandCounts;
import ooo.klae.connex.backend.dto.DecayCounts;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.TrendCounts;
import ooo.klae.connex.backend.dto.WarmthSummaryDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

/**
 * Unit tests for the as-of scoring behaviour that the time-travel replay (#48) relies on: a
 * past-instant reading must decay against that instant and exclude touches logged after it, while
 * the no-arg "now" reading stays byte-identical to its previous behaviour.
 */
class ScoringServiceTest {

    private static final int WS = 1;
    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");

    @Test
    void asOfNow_matchesLiveContactScores() {
        Person warm = person(1, null);
        Person quiet = person(2, null);
        ScoringService service = service(NOW,
            List.of(warm, quiet), List.of(), List.of(),
            List.of(activity(warm, "meeting", "2026-06-01 09:00:00"),
                    activity(warm, "call", "2026-06-20 09:00:00")),
            List.of(), List.of());

        List<RelationshipTemperatureDto> live = service.scoreContacts(WS);
        List<RelationshipTemperatureDto> asOfNow = service.scoreContacts(WS, NOW);

        assertEquals(live, asOfNow);
        assertNotEquals("cold", scoreFor(live, 1).getBand());
    }

    @Test
    void asOfNow_matchesLiveCompanyScores() {
        Person contact = person(1, 10);
        ScoringService service = service(NOW,
            List.of(contact), List.of(company(10)), List.of(),
            List.of(activity(contact, "meeting", "2026-06-25 09:00:00")),
            List.of(), List.of());

        assertEquals(service.scoreCompanies(WS), service.scoreCompanies(WS, NOW));
    }

    @Test
    void asOf_excludesTouchesAfterTheInstant_butLiveStillCountsThem() {
        Person p = person(1, null);
        ScoringService service = service(NOW,
            List.of(p), List.of(), List.of(),
            List.of(activity(p, "meeting", "2026-07-15 09:00:00")),
            List.of(), List.of());

        RelationshipTemperatureDto asOfNow = scoreFor(service.scoreContacts(WS, NOW), 1);
        RelationshipTemperatureDto live = scoreFor(service.scoreContacts(WS), 1);

        assertEquals("cold", asOfNow.getBand());
        assertEquals(0, asOfNow.getScore());
        assertNotEquals("cold", live.getBand());
    }

    @Test
    void asOf_reAnchorsDecayToTheRequestedInstant() {
        Person p = person(1, null);
        ScoringService service = service(NOW,
            List.of(p), List.of(), List.of(),
            List.of(activity(p, "meeting", "2026-06-29 12:00:00"),
                    activity(p, "meeting", "2026-06-29 18:00:00")),
            List.of(), List.of());

        RelationshipTemperatureDto fresh = scoreFor(service.scoreContacts(WS, Instant.parse("2026-06-30T00:00:00Z")), 1);
        RelationshipTemperatureDto aged = scoreFor(service.scoreContacts(WS, Instant.parse("2027-06-30T00:00:00Z")), 1);

        assertNotEquals("cold", fresh.getBand());
        assertTrue(fresh.getScore() > aged.getScore());
        assertEquals("cold", aged.getBand());
    }

    @Test
    void scoreContactsSubsetOnlyScoresVisibleRequestedIds() {
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        Person person = person(1, null);
        Activity activity = activity(person, "meeting", "2026-06-29 12:00:00");
        activity.setId(10);
        when(personMapper.getExistingPersonIds(WS, List.of(1, 2))).thenReturn(List.of(1));
        when(activityMapper.getActivitiesByPersonIds(WS, List.of(1, 2))).thenReturn(List.of(activity));
        when(noteMapper.getNotesByPersonIds(WS, List.of(1, 2))).thenReturn(List.of());
        when(taskMapper.getTasksByPersonIds(WS, List.of(1, 2))).thenReturn(List.of());
        ScoringService service = new ScoringService(personMapper, companyMapper, dealMapper,
            activityMapper, noteMapper, taskMapper, Clock.fixed(NOW, ZoneOffset.UTC));

        List<RelationshipTemperatureDto> scores = service.scoreContacts(WS, new LinkedHashSet<>(List.of(1, 2)));

        assertEquals(List.of(1), scores.stream().map(RelationshipTemperatureDto::getId).toList());
        assertNotEquals("cold", scores.getFirst().getBand());
        verify(personMapper, never()).getAllPersons(anyInt());
        verify(activityMapper, never()).getAllActivities(anyInt());
        verify(personMapper, never()).exists(anyInt(), anyInt());
    }

    @Test
    void scoreContactsSubsetRejectsAnUnboundedStakeholderSetBeforeQuerying() {
        PersonMapper personMapper = mock(PersonMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        ScoringService service = new ScoringService(
            personMapper, mock(CompanyMapper.class), mock(DealMapper.class), activityMapper,
            mock(NoteMapper.class), mock(TaskMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));
        LinkedHashSet<Integer> ids = IntStream.rangeClosed(1, 1_001)
            .boxed()
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThrows(ooo.klae.connex.backend.exceptions.BadRequestException.class,
            () -> service.scoreContacts(WS, ids));

        verify(personMapper, never()).getExistingPersonIds(anyInt(), org.mockito.ArgumentMatchers.anyList());
        verify(activityMapper, never()).getActivitiesByPersonIds(anyInt(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void scoreCompaniesSubsetDeduplicatesTouchesAndAvoidsWorkspaceScan() {
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        Company company = company(10);
        Person person = person(1, 10);
        Deal deal = new Deal();
        deal.setId(20);
        Activity activity = activity(person, "meeting", "2026-06-29 12:00:00");
        activity.setId(30);
        activity.setDeal(deal);
        when(companyMapper.exists(WS, 10)).thenReturn(true);
        when(personMapper.getPersonsByCompanyId(WS, 10)).thenReturn(List.of(person));
        when(dealMapper.getDealsByCompanyId(WS, 10)).thenReturn(List.of(deal));
        when(activityMapper.getActivitiesByPersonId(WS, 1)).thenReturn(List.of(activity));
        when(noteMapper.getNotesByPersonId(WS, 1)).thenReturn(List.of());
        when(taskMapper.getTasksByPersonId(WS, 1)).thenReturn(List.of());
        when(activityMapper.getActivitiesByDealId(WS, 20)).thenReturn(List.of(activity));
        when(noteMapper.getNotesByDealId(WS, 20)).thenReturn(List.of());
        when(taskMapper.getTasksByDealId(WS, 20)).thenReturn(List.of());
        ScoringService service = new ScoringService(personMapper, companyMapper, dealMapper,
            activityMapper, noteMapper, taskMapper, Clock.fixed(NOW, ZoneOffset.UTC));

        List<RelationshipTemperatureDto> scores = service.scoreCompanies(WS, new LinkedHashSet<>(List.of(company.getId())));

        assertEquals(List.of(10), scores.stream().map(RelationshipTemperatureDto::getId).toList());
        assertEquals(1, scores.getFirst().getTouchCount());
        verify(companyMapper, never()).getAllCompanies(anyInt());
        verify(activityMapper, never()).getAllActivities(anyInt());
    }

    @Test
    void summarizeReducesWorkspaceWideScoresIntoBandsTrendsAndDecayBuckets() {
        ScoringService service = spy(service(NOW, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        List<RelationshipTemperatureDto> contacts = List.of(
            temperature(1, "hot", "rising", -1),
            temperature(2, "warm", "steady", 0),
            temperature(3, "cool", "cooling", 30),
            temperature(4, "cold", "rising", 31),
            temperature(5, "hot", "steady", 60),
            temperature(6, "warm", "cooling", 61),
            temperature(7, "cool", "rising", 90),
            temperature(8, "cold", "steady", 91),
            temperature(9, "cold", "steady", null)
        );
        List<RelationshipTemperatureDto> companies = List.of(
            temperature(10, "hot", "steady", null),
            temperature(11, "warm", "steady", null),
            temperature(12, "cool", "steady", null),
            temperature(13, "cold", "steady", null)
        );
        doReturn(contacts).when(service).scoreContacts(WS);
        doReturn(companies).when(service).scoreCompanies(WS);

        WarmthSummaryDto summary = service.summarize(WS);

        assertEquals(new BandCounts(2, 2, 2, 3), summary.contacts());
        assertEquals(new BandCounts(1, 1, 1, 1), summary.companies());
        assertEquals(new TrendCounts(3, 4, 2), summary.contactTrends());
        assertEquals(new DecayCounts(2, 2, 2), summary.contactDecay());
        verify(service, times(1)).scoreContacts(WS);
        verify(service, times(1)).scoreCompanies(WS);
    }

    private static RelationshipTemperatureDto scoreFor(List<RelationshipTemperatureDto> scores, int id) {
        return scores.stream().filter(s -> s.getId() == id).findFirst().orElseThrow();
    }

    private ScoringService service(Instant now, List<Person> persons, List<Company> companies, List<Deal> deals,
            List<Activity> activities, List<Note> notes, List<Task> tasks) {
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        when(personMapper.getAllPersons(WS)).thenReturn(persons);
        when(companyMapper.getAllCompanies(WS)).thenReturn(companies);
        when(dealMapper.getAllDeals(WS)).thenReturn(deals);
        when(activityMapper.getAllActivities(WS)).thenReturn(activities);
        when(noteMapper.getAllNotes(WS)).thenReturn(notes);
        when(taskMapper.getAllTasks(WS)).thenReturn(tasks);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new ScoringService(personMapper, companyMapper, dealMapper, activityMapper, noteMapper, taskMapper, clock);
    }

    private static Person person(int id, Integer companyId) {
        Person p = new Person();
        p.setId(id);
        if (companyId != null) {
            Company c = new Company();
            c.setId(companyId);
            p.setCompany(c);
        }
        return p;
    }

    private static Company company(int id) {
        Company c = new Company();
        c.setId(id);
        return c;
    }

    private static Activity activity(Person person, String type, String timestamp) {
        Activity a = new Activity();
        a.setPerson(person);
        a.setType(type);
        a.setTimestamp(timestamp);
        return a;
    }

    private static RelationshipTemperatureDto temperature(int id, String band, String trend, Integer daysUntilCold) {
        return new RelationshipTemperatureDto(id, 0, band, trend, null, null, 0, null, daysUntilCold);
    }
}
