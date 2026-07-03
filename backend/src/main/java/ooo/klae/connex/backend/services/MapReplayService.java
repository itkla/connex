package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.dto.MapReplayDto;
import ooo.klae.connex.backend.dto.ReplayCompanyDto;
import ooo.klae.connex.backend.dto.ReplayContactDto;
import ooo.klae.connex.backend.dto.ReplayDealDto;
import ooo.klae.connex.backend.dto.ReplayFrameDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonEmploymentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.util.DateTimes;

/**
 * Assembles the time-travel replay (#48): a chronological series of frames reconstructing the
 * relationship graph as of each instant — which contacts, companies, and deals existed, each node's
 * warmth band, the company each contact worked at, and each deal's outcome.
 *
 * <p>Warmth comes from {@link ScoringService} (the single source of truth for the decay model); this
 * service adds membership (from {@code created_at}), as-of employment (from {@code person_employment}),
 * and deal resolution (from {@code closed_at}/{@code won}). Every list is loaded once and every frame
 * is evaluated in memory, so the cost is independent of the number of frames in database round-trips.
 *
 * <p>Known v1 fidelity limits: hard-deleted records cannot appear in past frames; a deal's company is
 * its present-day company (deals carry no employment-style history); {@code created_at} is the row's
 * insert time, which can lag the real-world relationship start for imported data.
 */
@Service
@RequiredArgsConstructor
public class MapReplayService {
    private final ScoringService scoringService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final PersonEmploymentMapper personEmploymentMapper;
    private final Clock clock;

    /** Upper bound on emitted entries (frames x nodes) so a huge workspace can't produce a giant payload. */
    private static final long MAX_ENTRIES = 2_000_000L;

    /** One stint of employment as epoch-millis bounds; {@code endMillis == Long.MAX_VALUE} is current. */
    private record Stint(long startMillis, long endMillis, Integer companyId) {}

    /**
     * Builds the replay frames from {@code from} to {@code to} inclusive, stepping by {@code step}
     * (a week or a month). Each frame is anchored to the end of its calendar day in UTC, and the
     * final frame is always exactly {@code to}.
     */
    public MapReplayDto buildReplay(int workspaceId, LocalDate from, LocalDate to, Period step) {
        List<LocalDate> dates = frameDates(from, to, step);
        long now = Instant.now(clock).toEpochMilli();
        long[] cutoffs = new long[dates.size()];
        for (int i = 0; i < dates.size(); i++) cutoffs[i] = Math.min(endOfDayMillis(dates.get(i)), now);

        List<Person> persons = personMapper.getAllPersons(workspaceId);
        List<Company> companies = companyMapper.getAllCompanies(workspaceId);
        List<Deal> deals = dealMapper.getAllDeals(workspaceId);
        if ((long) dates.size() * (persons.size() + companies.size() + deals.size()) > MAX_ENTRIES) {
            throw new BadRequestException(
                "Workspace is too large to replay this range; narrow the range or use a coarser granularity");
        }
        Map<Integer, List<Stint>> stints = stintsByPerson(personEmploymentMapper.getAllEmployment(workspaceId));

        List<Map<Integer, Integer>> employerByFrame = new ArrayList<>(cutoffs.length);
        for (long cutoff : cutoffs) employerByFrame.add(employerAsOf(stints, cutoff));

        Map<Integer, Integer> dealCompany = new HashMap<>();
        for (Deal d : deals) {
            if (d.getCompanyId() != null) dealCompany.put(d.getId(), d.getCompanyId());
        }
        ScoringService.ReplayBands bands = scoringService.replayBands(workspaceId, cutoffs,
            (personId, epochMillis) -> employerAt(stints.get(personId), epochMillis), dealCompany);
        List<Map<Integer, String>> contactBands = bands.contactFrames();
        List<Map<Integer, String>> companyBands = bands.companyFrames();

        List<ReplayFrameDto> frames = new ArrayList<>(dates.size());
        for (int i = 0; i < dates.size(); i++) {
            long cutoff = cutoffs[i];
            Map<Integer, String> cBands = contactBands.get(i);
            Map<Integer, String> coBands = companyBands.get(i);
            Map<Integer, Integer> employer = employerByFrame.get(i);

            List<ReplayContactDto> contacts = new ArrayList<>();
            for (Person p : persons) {
                if (presentAsOf(p.getCreatedAt(), cutoff)) {
                    contacts.add(new ReplayContactDto(p.getId(),
                        cBands.getOrDefault(p.getId(), "cold"), employer.get(p.getId())));
                }
            }
            List<ReplayCompanyDto> companyDtos = new ArrayList<>();
            for (Company c : companies) {
                if (presentAsOf(c.getCreatedAt(), cutoff)) {
                    companyDtos.add(new ReplayCompanyDto(c.getId(), coBands.getOrDefault(c.getId(), "cold")));
                }
            }
            List<ReplayDealDto> dealDtos = new ArrayList<>();
            for (Deal d : deals) {
                if (presentAsOf(d.getCreatedAt(), cutoff)) {
                    dealDtos.add(new ReplayDealDto(d.getId(), resolutionAsOf(d, cutoff)));
                }
            }
            frames.add(new ReplayFrameDto(dates.get(i).toString(), contacts, companyDtos, dealDtos));
        }
        return new MapReplayDto(frames);
    }

    /** The frame dates from {@code from} to {@code to} stepping by {@code step}, always ending at {@code to}. */
    private List<LocalDate> frameDates(LocalDate from, LocalDate to, Period step) {
        List<LocalDate> dates = new ArrayList<>();
        for (int k = 0; ; k++) {
            LocalDate d = from.plus(step.multipliedBy(k));
            if (d.isAfter(to)) break;
            dates.add(d);
        }
        if (dates.isEmpty() || !dates.get(dates.size() - 1).equals(to)) dates.add(to);
        return dates;
    }

    private Map<Integer, List<Stint>> stintsByPerson(List<PersonEmployment> employment) {
        Map<Integer, List<Stint>> byPerson = new HashMap<>();
        for (PersonEmployment e : employment) {
            long start = orMin(epoch(e.getStartedAt()));
            long end = orMax(epoch(e.getEndedAt()));
            byPerson.computeIfAbsent(e.getPersonId(), k -> new ArrayList<>())
                .add(new Stint(start, end, e.getCompanyId()));
        }
        return byPerson;
    }

    /** The company each contact worked at as of {@code cutoff}, for drawing the frame's employment edge. */
    private Map<Integer, Integer> employerAsOf(Map<Integer, List<Stint>> stints, long cutoff) {
        Map<Integer, Integer> employer = new HashMap<>();
        for (Map.Entry<Integer, List<Stint>> e : stints.entrySet()) {
            Integer companyId = employerAt(e.getValue(), cutoff);
            if (companyId != null) employer.put(e.getKey(), companyId);
        }
        return employer;
    }

    /** The company id of the stint covering {@code millis} (latest-starting when overlapping), or null. */
    private Integer employerAt(List<Stint> personStints, long millis) {
        if (personStints == null) {
            return null;
        }
        Stint best = null;
        for (Stint s : personStints) {
            if (s.startMillis() <= millis && s.endMillis() > millis
                    && (best == null || s.startMillis() > best.startMillis())) {
                best = s;
            }
        }
        return best == null ? null : best.companyId();
    }

    private String resolutionAsOf(Deal deal, long cutoff) {
        Long closed = epoch(deal.getClosedAt());
        if (closed == null || closed > cutoff) return "open";
        Boolean won = deal.getWon();
        return won == null ? "open" : won ? "won" : "lost";
    }

    private boolean presentAsOf(String createdAt, long cutoff) {
        Long created = epoch(createdAt);
        return created == null || created <= cutoff;
    }

    private static long endOfDayMillis(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
    }

    private static long orMin(Long value) {
        return value == null ? Long.MIN_VALUE : value;
    }

    private static long orMax(Long value) {
        return value == null ? Long.MAX_VALUE : value;
    }

    private static Long epoch(String s) {
        return DateTimes.epochMillis(s);
    }
}
