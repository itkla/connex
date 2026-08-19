package ooo.klae.connex.backend.mappers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmthFilter;
import ooo.klae.connex.backend.services.ScoringService;

/**
 * Covers the contact and company browsers' relationship-warmth sort, band facet, no-history bucket,
 * and decay horizon against real rows, including that the SQL bands agree with the scoring service
 * the smart-segment warmth predicates read.
 */
class WarmthBrowserMapperTest extends AbstractMapperTest {

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private ActivityMapper activityMapper;
    @Autowired private ScoringService scoringService;

    private User author;

    @Test
    void bandFacetPartitionsEveryVisibleContactIncludingThoseWithNoHistory() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Person hot = contactWithTouch(ws, "meeting", reference, 0);
        Person warm = contactWithTouch(ws, "email", reference, 0);
        Person cool = contactWithTouch(ws, "other", reference, 30);
        Person cold = contactWithTouch(ws, "other", reference, 200);
        Person none = newPersonIn(ws);

        Map<String, Long> facets = facetCounts(
            personMapper.countsByWarmthBand(ws.getId(), scoringFilter(reference)));

        assertEquals(Map.of("hot", 1L, "warm", 1L, "cool", 1L, "cold", 1L, "__none__", 1L), facets);
        assertEquals(List.of(hot.getId()), contactIdsInBands(ws, reference, List.of("hot"), false));
        assertEquals(List.of(warm.getId()), contactIdsInBands(ws, reference, List.of("warm"), false));
        assertEquals(List.of(cool.getId()), contactIdsInBands(ws, reference, List.of("cool"), false));
        assertEquals(List.of(cold.getId()), contactIdsInBands(ws, reference, List.of("cold"), false));
        assertEquals(List.of(none.getId()), contactIdsInBands(ws, reference, List.of(), true));
    }

    @Test
    void everyBandFacetCountEqualsWhatTheMatchingBandFilterReturns() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        contactWithTouch(ws, "meeting", reference, 0);
        contactWithTouch(ws, "meeting", reference, 0);
        contactWithTouch(ws, "email", reference, 0);
        contactWithTouch(ws, "other", reference, 200);
        newPersonIn(ws);

        for (FacetCount facet : personMapper.countsByWarmthBand(ws.getId(), scoringFilter(reference))) {
            boolean noWarmth = WarmthFilter.NO_WARMTH_KEY.equals(facet.getKey());
            List<Integer> matching = contactIdsInBands(
                ws, reference, noWarmth ? List.of() : List.of(facet.getKey()), noWarmth);
            assertEquals(facet.getCount(), matching.size(), "facet " + facet.getKey());
            assertEquals(
                facet.getCount(),
                personMapper.countPersons(ws.getId(), null, null, null, false, MemberScope.allTeam(),
                    null, false, null, false, null, false, false,
                    filter(reference, noWarmth ? List.of() : List.of(facet.getKey()), noWarmth, null)),
                "count for facet " + facet.getKey());
        }
    }

    /**
     * The browser and the {@code warmth_*} smart-segment predicates must never disagree, so the SQL
     * band of every scored contact has to equal the band the scoring service computes in Java.
     */
    @Test
    void sqlBandsAgreeWithTheScoringServiceForEveryScoredContact() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.now();
        contactWithTouch(ws, "meeting", reference, 0);
        contactWithTouch(ws, "call", reference, 5);
        contactWithTouch(ws, "email", reference, 0);
        contactWithTouch(ws, "other", reference, 45);
        contactWithTouch(ws, "other", reference, 200);
        newPersonIn(ws);

        Map<Integer, String> javaBands = scoringService.scoreContacts(ws.getId()).stream()
            .collect(Collectors.toMap(
                RelationshipTemperatureDto::getId, RelationshipTemperatureDto::getBand));
        Map<Integer, String> sqlBands = new HashMap<>();
        for (String band : List.of("hot", "warm", "cool", "cold")) {
            for (Integer id : contactIdsInBands(ws, reference, List.of(band), false)) {
                sqlBands.put(id, band);
            }
        }
        List<Integer> noHistory = contactIdsInBands(ws, reference, List.of(), true);

        for (Map.Entry<Integer, String> entry : javaBands.entrySet()) {
            if (noHistory.contains(entry.getKey())) {
                assertEquals("cold", entry.getValue(),
                    "a contact with no touch history scores cold in Java and buckets as __none__");
                continue;
            }
            assertEquals(entry.getValue(), sqlBands.get(entry.getKey()),
                "band for contact " + entry.getKey());
        }
    }

    @Test
    void warmthSortOrdersContactsByDecayedWeightInBothDirections() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Person hot = contactWithTouch(ws, "meeting", reference, 0);
        Person warm = contactWithTouch(ws, "email", reference, 0);
        Person cool = contactWithTouch(ws, "other", reference, 30);
        Person none = newPersonIn(ws);

        assertEquals(
            List.of(none.getId(), cool.getId(), warm.getId(), hot.getId()),
            sortedContactIds(ws, reference, "asc"));
        assertEquals(
            List.of(hot.getId(), warm.getId(), cool.getId(), none.getId()),
            sortedContactIds(ws, reference, "desc"));
    }

    @Test
    void decayHorizonSelectsExactlyTheContactsTheModelPredictsGoingColdInTime() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Person hot = contactWithTouch(ws, "meeting", reference, 0);
        Person warm = contactWithTouch(ws, "email", reference, 0);
        Person cool = contactWithTouch(ws, "other", reference, 30);
        Person alreadyCold = contactWithTouch(ws, "other", reference, 200);
        Person none = newPersonIn(ws);

        Map<Integer, Integer> predicted = scoringService.scoreContacts(ws.getId(), reference).stream()
            .filter(temperature -> temperature.getDaysUntilCold() != null)
            .collect(Collectors.toMap(
                RelationshipTemperatureDto::getId, RelationshipTemperatureDto::getDaysUntilCold));

        for (int horizon : List.of(30, 60, 90)) {
            Set<Integer> expected = predicted.entrySet().stream()
                .filter(entry -> entry.getValue() <= horizon)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            assertEquals(expected, Set.copyOf(horizonContactIds(ws, reference, horizon)),
                "horizon " + horizon);
        }
        assertTrue(horizonContactIds(ws, reference, 30).contains(cool.getId()));
        assertTrue(horizonContactIds(ws, reference, 60).contains(warm.getId()));
        assertTrue(horizonContactIds(ws, reference, 90).contains(hot.getId()));
        assertFalse(horizonContactIds(ws, reference, 90).contains(alreadyCold.getId()));
        assertFalse(horizonContactIds(ws, reference, 90).contains(none.getId()));
    }

    @Test
    void warmthReadsNeverCrossWorkspaces() {
        Workspace mine = newWorkspace();
        Workspace other = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Person myHot = contactWithTouch(mine, "meeting", reference, 0);
        Person foreignHot = contactWithTouch(other, "meeting", reference, 0);

        assertEquals(List.of(myHot.getId()), contactIdsInBands(mine, reference, List.of("hot"), false));
        assertEquals(
            List.of(foreignHot.getId()), contactIdsInBands(other, reference, List.of("hot"), false));
        assertEquals(
            Map.of("hot", 1L),
            facetCounts(personMapper.countsByWarmthBand(mine.getId(), scoringFilter(reference))));
        assertFalse(sortedContactIds(mine, reference, "desc").contains(foreignHot.getId()));
    }

    @Test
    void companyBandFacetAndFilterAgreeAndStayWorkspaceScoped() {
        Workspace mine = newWorkspace();
        Workspace other = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Company hot = newCompanyIn(mine);
        Company quiet = newCompanyIn(mine);
        Company foreign = newCompanyIn(other);
        touch(mine, personIn(mine, hot), "meeting", reference, 0);
        touch(other, personIn(other, foreign), "meeting", reference, 0);

        Map<String, Long> facets = facetCounts(
            companyMapper.countsByWarmthBand(mine.getId(), scoringFilter(reference)));

        assertEquals(Map.of("hot", 1L, "__none__", 1L), facets);
        assertEquals(List.of(hot.getId()), companyIdsInBands(mine, reference, List.of("hot"), false));
        assertEquals(List.of(quiet.getId()), companyIdsInBands(mine, reference, List.of(), true));
        assertFalse(companyIdsInBands(mine, reference, List.of("hot"), false).contains(foreign.getId()));
    }

    @Test
    void companyWarmthSortOrdersByDecayedWeight() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Company hot = newCompanyIn(ws);
        Company cool = newCompanyIn(ws);
        Company quiet = newCompanyIn(ws);
        touch(ws, personIn(ws, hot), "meeting", reference, 0);
        touch(ws, personIn(ws, cool), "other", reference, 30);

        List<Integer> descending = companyMapper.getCompaniesPage(
                ws.getId(), null, "warmth", "desc", null, false, null, MemberScope.allTeam(), false,
                scoringFilter(reference), 100, 0)
            .stream().map(Company::getId).toList();

        assertEquals(List.of(hot.getId(), cool.getId(), quiet.getId()), descending);
    }

    @Test
    void bandAndNoHistoryFiltersCombineAsOneBucketSelection() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Person hot = contactWithTouch(ws, "meeting", reference, 0);
        Person cold = contactWithTouch(ws, "other", reference, 200);
        Person none = newPersonIn(ws);

        assertEquals(
            List.of(hot.getId(), none.getId()).stream().sorted().toList(),
            contactIdsInBands(ws, reference, List.of("hot"), true).stream().sorted().toList());
        assertFalse(contactIdsInBands(ws, reference, List.of("hot"), true).contains(cold.getId()));
    }

    @Test
    void exportAndIdSelectionHonorTheSameBandFilterAsThePage() {
        Workspace ws = newWorkspace();
        Instant reference = Instant.parse("2026-06-30T00:00:00Z");
        Person hot = contactWithTouch(ws, "meeting", reference, 0);
        contactWithTouch(ws, "other", reference, 200);
        WarmthFilter warmth = filter(reference, List.of("hot"), false, null);

        List<Integer> exported = personMapper.getPersonsFiltered(
                ws.getId(), null, null, null, false, MemberScope.allTeam(), null, false, null, false,
                null, false, false, warmth)
            .stream().map(Person::getId).toList();
        List<Integer> selected = personMapper.getPersonIdsFiltered(
            ws.getId(), null, null, null, false, MemberScope.allTeam(), null, false, null, false,
            null, false, false, warmth, 100);

        assertEquals(List.of(hot.getId()), exported);
        assertEquals(List.of(hot.getId()), selected);
    }

    private List<Integer> contactIdsInBands(
            Workspace ws, Instant reference, List<String> bands, boolean noWarmth) {
        return personMapper.getPersonIdsFiltered(
            ws.getId(), null, null, null, false, MemberScope.allTeam(), null, false, null, false,
            null, false, false, filter(reference, bands, noWarmth, null), 100);
    }

    private List<Integer> horizonContactIds(Workspace ws, Instant reference, int days) {
        return personMapper.getPersonIdsFiltered(
            ws.getId(), null, null, null, false, MemberScope.allTeam(), null, false, null, false,
            null, false, false, filter(reference, List.of(), false, days), 100);
    }

    private List<Integer> companyIdsInBands(
            Workspace ws, Instant reference, List<String> bands, boolean noWarmth) {
        return companyMapper.getCompanyIdsFiltered(
            ws.getId(), null, null, false, null, MemberScope.allTeam(), false,
            filter(reference, bands, noWarmth, null), 100, 0);
    }

    private List<Integer> sortedContactIds(Workspace ws, Instant reference, String dir) {
        return personMapper.getPersonsPage(
                ws.getId(), null, "warmth", dir, null, null, false, MemberScope.allTeam(), null,
                false, null, false, null, false, false, scoringFilter(reference), 100, 0)
            .stream().map(Person::getId).toList();
    }

    private static WarmthFilter scoringFilter(Instant reference) {
        return WarmthFilter.forScoring(reference);
    }

    private static WarmthFilter filter(
            Instant reference, List<String> bands, boolean noWarmth, Integer goesColdWithinDays) {
        return WarmthFilter.fromRequest(bands, noWarmth, goesColdWithinDays, "warmth", reference);
    }

    private Person contactWithTouch(Workspace ws, String type, Instant reference, int ageDays) {
        Person person = newPersonIn(ws);
        touch(ws, person, type, reference, ageDays);
        return person;
    }

    private void touch(Workspace ws, Person person, String type, Instant reference, int ageDays) {
        Activity activity = new Activity();
        activity.setWorkspaceId(ws.getId());
        activity.setType(type);
        activity.setSubject("Touch " + unique());
        activity.setPerson(person);
        activity.setCreatedBy(author());
        activity.setTimestamp(LocalDateTime.ofInstant(reference, ZoneOffset.UTC)
            .minusDays(ageDays)
            .format(MYSQL_DATETIME));
        activityMapper.insert(activity);
    }

    private User author() {
        if (author == null) {
            author = newUser();
        }
        return author;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Person newPersonIn(Workspace ws) {
        return personIn(ws, null);
    }

    private Person personIn(Workspace ws, Company company) {
        Person person = new Person();
        person.setName("Person " + unique());
        person.setEmail(unique() + "@example.com");
        person.setWorkspaceId(ws.getId());
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private Company newCompanyIn(Workspace ws) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }

    private static Map<String, Long> facetCounts(List<FacetCount> counts) {
        return counts.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }
}
