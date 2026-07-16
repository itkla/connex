package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.dto.MapReplayDto;
import ooo.klae.connex.backend.dto.ReplayCompanyDto;
import ooo.klae.connex.backend.dto.ReplayContactDto;
import ooo.klae.connex.backend.dto.ReplayDealDto;
import ooo.klae.connex.backend.dto.ReplayFrameDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonEmploymentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

/**
 * Tests the time-travel replay assembly: frame structure, as-of membership, the frame-time
 * employment edge, deal resolution, and touch-time company-warmth attribution (a contact's pre-move
 * touches keep warming the employer they had when the touch happened, not the one they later joined).
 */
class MapReplayServiceTest {

    private static final int WS = 1;
    private static final int CONTACT = 1;
    private static final int COMPANY_A = 10;
    private static final int COMPANY_B = 20;
    private static final int DEAL = 100;

    @Test
    void buildReplay_membership_employmentEdge_dealResolution_andTouchTimeAttribution() {
        MapReplayService replay = service();

        MapReplayDto out = replay.buildReplay(WS,
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-30"), Period.ofWeeks(1));

        assertTrue(out.getFrames().size() > 1);
        assertEquals("2026-06-30", out.getFrames().get(out.getFrames().size() - 1).getAsOf());

        ReplayFrameDto beforeDeal = frameOn(out, "2026-02-05");
        assertTrue(present(beforeDeal.getContacts(), CONTACT));
        assertTrue(deal(beforeDeal, DEAL) == null, "deal created 2026-02-15 must be absent on 2026-02-05");

        ReplayFrameDto afterMove = frameOn(out, "2026-03-05");
        assertEquals(Integer.valueOf(COMPANY_B), contact(afterMove, CONTACT).getEmployerId());
        assertNotEquals("cold", company(afterMove, COMPANY_A).getBand(),
            "the pre-move touch must keep warming company A under touch-time attribution");
        assertEquals("cold", company(afterMove, COMPANY_B).getBand(),
            "company B has no touch yet, so it must be cold right after the move");
        assertEquals("open", deal(afterMove, DEAL).getResolution());

        ReplayFrameDto afterClose = frameOn(out, "2026-05-07");
        assertNotEquals("cold", company(afterClose, COMPANY_B).getBand());
        assertEquals("won", deal(afterClose, DEAL).getResolution());
    }

    private MapReplayService service() {
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        PersonEmploymentMapper employmentMapper = mock(PersonEmploymentMapper.class);

        when(personMapper.getProcessablePersons(WS))
            .thenReturn(List.of(person(CONTACT, "2025-06-01 00:00:00", COMPANY_B)));
        when(companyMapper.getAllCompanies(WS)).thenReturn(List.of(
            company(COMPANY_A, "2025-01-01 00:00:00"), company(COMPANY_B, "2025-01-01 00:00:00")));
        when(dealMapper.getAllDeals(WS)).thenReturn(List.of(
            deal(DEAL, "2026-02-15 00:00:00", COMPANY_A, "2026-04-15 00:00:00", true)));
        when(activityMapper.getAllActivities(WS)).thenReturn(List.of(
            meeting(CONTACT, "2026-02-25 12:00:00"), meeting(CONTACT, "2026-05-01 12:00:00")));
        when(noteMapper.getAllNotes(WS)).thenReturn(List.of());
        when(taskMapper.getAllTasks(WS)).thenReturn(List.of());
        when(employmentMapper.getAllEmployment(WS)).thenReturn(List.of(
            stint(CONTACT, COMPANY_A, "2025-06-01 00:00:00", "2026-03-01 00:00:00"),
            stint(CONTACT, COMPANY_B, "2026-03-01 00:00:00", null)));

        Clock clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
        ScoringService scoring = new ScoringService(
            personMapper, companyMapper, dealMapper, activityMapper, noteMapper, taskMapper, clock);
        return new MapReplayService(scoring, personMapper, companyMapper, dealMapper, employmentMapper, clock);
    }

    private static ReplayFrameDto frameOn(MapReplayDto out, String asOf) {
        return out.getFrames().stream().filter(f -> f.getAsOf().equals(asOf)).findFirst().orElseThrow();
    }

    private static boolean present(List<ReplayContactDto> contacts, int id) {
        return contacts.stream().anyMatch(c -> c.getId() == id);
    }

    private static ReplayContactDto contact(ReplayFrameDto frame, int id) {
        return frame.getContacts().stream().filter(c -> c.getId() == id).findFirst().orElseThrow();
    }

    private static ReplayCompanyDto company(ReplayFrameDto frame, int id) {
        return frame.getCompanies().stream().filter(c -> c.getId() == id).findFirst().orElseThrow();
    }

    private static ReplayDealDto deal(ReplayFrameDto frame, int id) {
        return frame.getDeals().stream().filter(d -> d.getId() == id).findFirst().orElse(null);
    }

    private static Person person(int id, String createdAt, int companyId) {
        Person p = new Person();
        p.setId(id);
        p.setCreatedAt(createdAt);
        Company c = new Company();
        c.setId(companyId);
        p.setCompany(c);
        return p;
    }

    private static Company company(int id, String createdAt) {
        Company c = new Company();
        c.setId(id);
        c.setCreatedAt(createdAt);
        return c;
    }

    private static Deal deal(int id, String createdAt, int companyId, String closedAt, Boolean won) {
        Deal d = new Deal();
        d.setId(id);
        d.setCreatedAt(createdAt);
        d.setCompanyId(companyId);
        d.setClosedAt(closedAt);
        d.setWon(won);
        return d;
    }

    private static Activity meeting(int personId, String timestamp) {
        Person p = new Person();
        p.setId(personId);
        Activity a = new Activity();
        a.setPerson(p);
        a.setType("meeting");
        a.setTimestamp(timestamp);
        return a;
    }

    private static PersonEmployment stint(int personId, int companyId, String startedAt, String endedAt) {
        PersonEmployment e = new PersonEmployment();
        e.setPersonId(personId);
        e.setCompanyId(companyId);
        e.setStartedAt(startedAt);
        e.setEndedAt(endedAt);
        return e;
    }
}
