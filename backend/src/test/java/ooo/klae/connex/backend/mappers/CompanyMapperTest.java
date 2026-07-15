package ooo.klae.connex.backend.mappers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.CompanyEngagementPersonDto;
import ooo.klae.connex.backend.dto.CompanyEngagementUserDto;
import ooo.klae.connex.backend.dto.CompanyEngagementWeekBucketDto;
import ooo.klae.connex.backend.dto.CompanyRevenueCurrencyDto;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;

class CompanyMapperTest extends AbstractMapperTest {

    @Autowired private DataSource dataSource;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private NoteMapper noteMapper;
    @Autowired private TaskMapper taskMapper;

    /**
     * Inserts a new company and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Company company = newCompany();
        assertNotEquals(0, company.getId());
    }

    /**
     * Gets a company by ID and checks if the returned company is not null.
     */
    @Test
    void getCompanyById_returnsInsertedRow() {
        Company company = newCompany();

        Company found = companyMapper.getCompanyById(workspace.getId(), company.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals(company.getName(), found.getName());
        assertEquals(company.getWebsite(), found.getWebsite());
        assertEquals("Tech", found.getIndustry());
        assertEquals(company.getPhone(), found.getPhone());
        assertEquals(company.getAddress(), found.getAddress());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    /**
     * Gets a company by ID and checks if the returned company is null when the ID is missing.
     */
    @Test
    void getCompanyById_returnsNullWhenMissing() {
        assertNull(companyMapper.getCompanyById(workspace.getId(), -1));
    }

    /**
     * Gets all companies and checks if the returned list includes the inserted company.
     */
    @Test
    void getAllCompanies_includesInsertedRow() {
        Company company = newCompany();

        List<Company> allCompanies = companyMapper.getAllCompanies(workspace.getId());

        assertTrue(allCompanies.stream().anyMatch(x -> x.getId() == company.getId()));
    }

    @Test
    void getCompaniesPageLimitsAndCountsVisibleRows() {
        Workspace pageWorkspace = newWorkspace();
        Company first = newCompanyIn(pageWorkspace);
        Company second = newCompanyIn(pageWorkspace);
        Company third = newCompanyIn(pageWorkspace);
        first.setName("Same Name");
        second.setName("Same Name");
        third.setName("Same Name");
        companyMapper.update(first);
        companyMapper.update(second);
        companyMapper.update(third);
        setTimestamps(first, "2026-01-01 00:00:00", "2026-02-01 00:00:00");
        setTimestamps(second, "2026-01-01 00:00:00", "2026-02-01 00:00:00");
        setTimestamps(third, "2026-01-01 00:00:00", "2026-02-01 00:00:00");
        Company foreign = newCompany();

        List<Company> page = companyMapper.getCompaniesPage(
            pageWorkspace.getId(), null, null, null, null, false, null, 2, 0);

        assertEquals(List.of(first.getId(), second.getId()), page.stream().map(Company::getId).toList());
        List<Integer> stableIds = List.of(first.getId(), second.getId(), third.getId());
        for (String sort : List.of("name", "website", "industry", "phone", "address", "createdAt", "updatedAt")) {
            assertEquals(stableIds, companyPageIds(pageWorkspace, sort, "asc"));
            assertEquals(stableIds, companyPageIds(pageWorkspace, sort, "desc"));
        }
        assertEquals(3, companyMapper.countCompanies(pageWorkspace.getId(), null, null, false, null));
        assertTrue(page.stream().noneMatch(company -> company.getId() == foreign.getId()));
    }

    @Test
    void getCompaniesPageSortsByEveryWhitelistedFieldInBothDirections() {
        Workspace pageWorkspace = newWorkspace();
        Company alpha = newCompanyIn(pageWorkspace, "Alpha", "https://charlie.example.com", "Zulu", "300", "Bravo");
        Company bravo = newCompanyIn(pageWorkspace, "Bravo", "https://alpha.example.com", "Mike", "100", "Charlie");
        Company charlie = newCompanyIn(pageWorkspace, "Charlie", "https://bravo.example.com", "Alpha", "200", "Alpha");
        setTimestamps(alpha, "2026-01-03 00:00:00", "2026-02-01 00:00:00");
        setTimestamps(bravo, "2026-01-01 00:00:00", "2026-02-03 00:00:00");
        setTimestamps(charlie, "2026-01-02 00:00:00", "2026-02-02 00:00:00");

        assertSort(pageWorkspace, "name", alpha, bravo, charlie);
        assertSort(pageWorkspace, "website", bravo, charlie, alpha);
        assertSort(pageWorkspace, "industry", charlie, bravo, alpha);
        assertSort(pageWorkspace, "phone", bravo, charlie, alpha);
        assertSort(pageWorkspace, "address", charlie, alpha, bravo);
        assertSort(pageWorkspace, "createdAt", bravo, charlie, alpha);
        assertSort(pageWorkspace, "updatedAt", alpha, charlie, bravo);
        assertEquals(List.of(alpha.getId(), bravo.getId(), charlie.getId()),
            companyPageIds(pageWorkspace, null, "asc"));
    }

    @Test
    void getCompaniesPageSearchesEveryBrowserFieldWithinWorkspace() {
        Workspace pageWorkspace = newWorkspace();
        Company name = newCompanyIn(pageWorkspace, "NameMarker", "https://one.example.com", "One", "101", "One Road");
        Company website = newCompanyIn(pageWorkspace, "Two", "https://website-marker.example.com", "Two", "202", "Two Road");
        Company industry = newCompanyIn(pageWorkspace, "Three", "https://three.example.com", "IndustryMarker", "303", "Three Road");
        Company phone = newCompanyIn(pageWorkspace, "Four", "https://four.example.com", "Four", "PhoneMarker", "Four Road");
        Company address = newCompanyIn(pageWorkspace, "Five", "https://five.example.com", "Five", "505", "Address Marker");
        newCompanyIn(pageWorkspace, "Unmatched", "https://unmatched.example.com", "Other", "606", "Elsewhere");
        newCompanyIn(newWorkspace(), "NameMarker", "https://foreign.example.com", "Foreign", "707", "Foreign Road");

        assertEquals(List.of(name.getId()), companySearchIds(pageWorkspace, "%NameMarker%"));
        assertEquals(List.of(website.getId()), companySearchIds(pageWorkspace, "%website-marker%"));
        assertEquals(List.of(industry.getId()), companySearchIds(pageWorkspace, "%IndustryMarker%"));
        assertEquals(List.of(phone.getId()), companySearchIds(pageWorkspace, "%PhoneMarker%"));
        assertEquals(List.of(address.getId()), companySearchIds(pageWorkspace, "%Address Marker%"));
    }

    @Test
    void companyFiltersSupportIndustryMissingIndustryAndIdsWithinWorkspace() {
        Workspace pageWorkspace = newWorkspace();
        Company finance = newCompanyIn(pageWorkspace, "Finance", "https://finance.example.com", "Finance", "100", "A");
        Company technology = newCompanyIn(pageWorkspace, "Technology", "https://technology.example.com", "Technology", "200", "B");
        Company noIndustry = newCompanyIn(pageWorkspace, "No Industry", "https://none.example.com", null, "300", "C");
        Company emptyIndustry = newCompanyIn(pageWorkspace, "Empty Industry", "https://empty.example.com", "", "400", "D");
        Company other = newCompanyIn(pageWorkspace, "Other", "https://other.example.com", "Other", "500", "E");
        Company foreign = newCompanyIn(newWorkspace(), "Foreign Finance", "https://foreign.example.com", "Finance", "600", "F");

        assertEquals(List.of(finance.getId(), technology.getId()),
            filteredCompanyIds(pageWorkspace, null, List.of("Finance", "Technology"), false, null));
        assertEquals(List.of(emptyIndustry.getId(), noIndustry.getId()),
            filteredCompanyIds(pageWorkspace, null, null, true, null));
        assertEquals(List.of(emptyIndustry.getId(), noIndustry.getId(), technology.getId()),
            filteredCompanyIds(pageWorkspace, null, List.of("Technology"), true, null));
        assertEquals(List.of(finance.getId(), noIndustry.getId()),
            companyMapper.getCompanyIdsFiltered(pageWorkspace.getId(), null, null, false,
                List.of(noIndustry.getId(), finance.getId(), foreign.getId()), 100, 0));
        assertEquals(2, companyMapper.countCompanies(pageWorkspace.getId(), null, null, false,
            List.of(noIndustry.getId(), finance.getId(), foreign.getId())));
        assertFalse(filteredCompanyIds(pageWorkspace, null, List.of("Finance"), false, null)
            .contains(foreign.getId()));
        assertFalse(filteredCompanyIds(pageWorkspace, null, null, false, List.of(other.getId()))
            .contains(technology.getId()));
    }

    @Test
    void pageCountAndIdsApplyTheSameCombinedFilters() {
        Workspace pageWorkspace = newWorkspace();
        Company alpha = newCompanyIn(pageWorkspace, "Target Alpha", "https://alpha.example.com", "Technology", "100", "A");
        Company bravo = newCompanyIn(pageWorkspace, "Target Bravo", "https://bravo.example.com", "Technology", "200", "B");
        Company finance = newCompanyIn(pageWorkspace, "Target Finance", "https://finance.example.com", "Finance", "300", "C");
        Company different = newCompanyIn(pageWorkspace, "Different", "https://different.example.com", "Technology", "400", "D");
        Company foreign = newCompanyIn(newWorkspace(), "Target Foreign", "https://foreign.example.com", "Technology", "500", "E");
        List<Integer> ids = List.of(alpha.getId(), bravo.getId(), finance.getId(), different.getId(), foreign.getId());

        List<Company> page = companyMapper.getCompaniesPage(
            pageWorkspace.getId(), "%Target%", "name", "asc", List.of("Technology"), false, ids, 100, 0);
        long count = companyMapper.countCompanies(
            pageWorkspace.getId(), "%Target%", List.of("Technology"), false, ids);
        List<Integer> matchingIds = companyMapper.getCompanyIdsFiltered(
            pageWorkspace.getId(), "%Target%", List.of("Technology"), false, ids, 100, 0);

        assertEquals(List.of(alpha.getId(), bravo.getId()), page.stream().map(Company::getId).toList());
        assertEquals(2, count);
        assertEquals(List.of(alpha.getId(), bravo.getId()), matchingIds);
    }

    @Test
    void segmentJsonTableQueriesPageCountAndBoundIdsWithoutOffsetScanning() {
        Workspace segmentWorkspace = newWorkspace();
        Company alpha = newCompanyIn(
            segmentWorkspace, "Target Alpha", "https://alpha.example.com", "Technology", "100", "A");
        Company bravo = newCompanyIn(
            segmentWorkspace, "Target Bravo", "https://bravo.example.com", "Technology", "200", "B");
        Company excluded = newCompanyIn(
            segmentWorkspace, "Target Finance", "https://finance.example.com", "Finance", "300", "C");
        Company foreign = newCompanyIn(
            newWorkspace(), "Target Foreign", "https://foreign.example.com", "Technology", "400", "D");
        String idsJson = "[" + alpha.getId() + "," + bravo.getId() + ","
            + excluded.getId() + "," + foreign.getId() + "]";

        List<Company> page = companyMapper.getSegmentCompaniesPage(
            segmentWorkspace.getId(), idsJson, "%Target%", "name", "asc",
            List.of("Technology"), false, 1, 1);
        long count = companyMapper.countSegmentCompanies(
            segmentWorkspace.getId(), idsJson, "%Target%", List.of("Technology"), false);
        List<Integer> ids = companyMapper.getSegmentCompanyIdsFiltered(
            segmentWorkspace.getId(), idsJson, "%Target%", List.of("Technology"), false, 1001);

        assertEquals(List.of(bravo.getId()), page.stream().map(Company::getId).toList());
        assertEquals(2, count);
        assertEquals(List.of(alpha.getId(), bravo.getId()), ids);
    }

    @Test
    void companyFacetsAreDistinctOrderedAndWorkspaceScoped() {
        Workspace facetWorkspace = newWorkspace();
        newCompanyIn(facetWorkspace, "Alpha One", "https://alpha-one.example.com", "Alpha", "100", "A");
        newCompanyIn(facetWorkspace, "Alpha Two", "https://alpha-two.example.com", "Alpha", "200", "B");
        newCompanyIn(facetWorkspace, "Zulu", "https://zulu.example.com", "Zulu", "300", "C");
        newCompanyIn(facetWorkspace, "No Industry", "https://none.example.com", null, "400", "D");
        newCompanyIn(facetWorkspace, "Empty Industry", "https://empty.example.com", "", "500", "E");
        Workspace completeWorkspace = newWorkspace();
        newCompanyIn(completeWorkspace, "Complete", "https://complete.example.com", "Complete", "600", "F");
        Workspace foreignWorkspace = newWorkspace();
        newCompanyIn(foreignWorkspace, "Foreign", "https://foreign.example.com", "ForeignOnly", "700", "G");
        newCompanyIn(foreignWorkspace, "Foreign Missing", "https://foreign-missing.example.com", null, "800", "H");

        assertEquals(List.of("Alpha", "Zulu"), companyMapper.distinctIndustries(facetWorkspace.getId()));
        assertTrue(companyMapper.hasCompanyWithoutIndustry(facetWorkspace.getId()));
        assertEquals(List.of("Complete"), companyMapper.distinctIndustries(completeWorkspace.getId()));
        assertFalse(companyMapper.hasCompanyWithoutIndustry(completeWorkspace.getId()));
    }

    /**
     * Updates a company and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Company company = newCompany();
        company.setName("Renamed Co");
        company.setIndustry("Finance");
        company.setPhone("+1-555-9999");

        companyMapper.update(company);

        Company found = companyMapper.getCompanyById(workspace.getId(), company.getId());
        assertEquals("Renamed Co", found.getName());
        assertEquals("Finance", found.getIndustry());
        assertEquals("+1-555-9999", found.getPhone());
    }

    @Test
    void genericUpdateCannotReplaceManagedLogoAndCasRejectsStaleReplacement() {
        Company company = newCompany();
        String first = "/api/companies/" + company.getId() + "/logo/550e8400-e29b-41d4-a716-446655440000.png";
        String second = "/api/companies/" + company.getId() + "/logo/550e8400-e29b-41d4-a716-446655440001.png";
        assertEquals(1, companyMapper.updateLogoUrlIfCurrent(
            workspace.getId(), company.getId(), null, first));

        company.setLogoUrl("https://attacker.example/logo.png");
        companyMapper.update(company);

        assertEquals(first,
            companyMapper.getCompanyById(workspace.getId(), company.getId()).getLogoUrl());
        assertEquals(0, companyMapper.updateLogoUrlIfCurrent(
            workspace.getId(), company.getId(), null, second));
        assertEquals(1, companyMapper.updateLogoUrlIfCurrent(
            workspace.getId(), company.getId(), first, second));
    }

    /**
     * Deletes a company and checks if the company is removed.
     */
    @Test
    void delete_removesRow() {
        Company company = newCompany();

        companyMapper.delete(workspace.getId(), company.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()));
    }

    /**
     * Adds a tag to a company and checks if the returned list includes the inserted company.
     */
    @Test
    void addTag_thenGetCompaniesByTagId_returnsCompany() {
        Company company = newCompany();
        Tag tag = newTag();

        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        assertTrue(companies.stream().anyMatch(x -> x.getId() == company.getId()));
    }

    /**
     * Adds a tag to a company and checks if the tag is added only once.
     */
    @Test
    void addTag_isIdempotent() {
        Company company = newCompany();
        Tag tag = newTag();

        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());
        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        long matching = companies.stream().filter(x -> x.getId() == company.getId()).count();
        assertEquals(1, matching);
    }

    /**
     * Removes a tag from a company and checks if the tag is removed.
     */
    @Test
    void removeTag_dropsAssociation() {
        Company company = newCompany();
        Tag tag = newTag();
        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());

        companyMapper.removeTag(workspace.getId(), company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        assertTrue(companies.stream().noneMatch(x -> x.getId() == company.getId()));
    }

    /**
     * A tag write issued with another workspace's id must not associate the tag: the
     * scoped statement only matches a company owned by the given workspace, so the
     * insert affects no rows (write-path tenant isolation — pairs with the static
     * {@code TenantScopeArchTest}).
     */
    @Test
    void addTag_fromAnotherWorkspace_doesNotAssociate() {
        Company company = newCompany();
        Tag tag = newTag();
        Workspace other = newWorkspace();

        int affected = companyMapper.addTag(other.getId(), company.getId(), tag.getId());

        assertEquals(0, affected, "cross-workspace addTag must affect no rows");
        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        assertTrue(companies.stream().noneMatch(x -> x.getId() == company.getId()));
    }

    /**
     * insertTags links only tags owned by the active workspace: a foreign-workspace tag id is
     * filtered out by the {@code t.workspace_id} join predicate, while a same-workspace tag in the
     * same call still links — so exactly one of the two ids is written.
     */
    @Test
    void insertTags_linksOnlySameWorkspaceTags() {
        Company company = newCompany();
        Tag ownTag = newTag();
        Workspace other = newWorkspace();
        Tag foreignTag = new Tag();
        foreignTag.setName("tag_" + unique());
        foreignTag.setColor("#abcdef");
        foreignTag.setWorkspaceId(other.getId());
        tagMapper.insert(foreignTag);

        int affected = companyMapper.insertTags(workspace.getId(), company.getId(),
            List.of(ownTag.getId(), foreignTag.getId()));

        assertEquals(1, affected, "only the same-workspace tag links; the foreign tag is filtered out");
        List<Tag> tags = tagMapper.getTagsByCompanyId(workspace.getId(), company.getId());
        assertTrue(tags.stream().anyMatch(t -> t.getId() == ownTag.getId()));
        assertTrue(tags.stream().noneMatch(t -> t.getId() == foreignTag.getId()));
    }

    /**
     * A company in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void companies_areIsolatedByWorkspace() {
        Company mine = newCompany();
        Company foreign = newCompanyIn(newWorkspace());

        assertNull(companyMapper.getCompanyById(workspace.getId(), foreign.getId()));
        assertFalse(companyMapper.exists(workspace.getId(), foreign.getId()));
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream().noneMatch(c -> c.getId() == foreign.getId()));
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream().anyMatch(c -> c.getId() == mine.getId()));

        // cross-workspace mutation affects zero rows; the foreign row survives
        assertEquals(0, companyMapper.delete(workspace.getId(), foreign.getId()));
        assertTrue(companyMapper.exists(foreign.getWorkspaceId(), foreign.getId()));
    }

    @Test
    void companyEngagementAggregatesExcludePrivateNotes() {
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        User current = newUser();
        User other = newUser();
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("Company meeting");
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(current);
        activity.setTimestamp("2026-07-01 10:00:00");
        activityMapper.insert(activity);
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Company task");
        task.setStatus("todo");
        task.setAssignedTo(current);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        Note visible = new Note();
        visible.setWorkspaceId(workspace.getId());
        visible.setContent("Visible");
        visible.setAuthor(current);
        visible.setPerson(person);
        noteMapper.insert(visible);
        Note ownPrivate = new Note();
        ownPrivate.setWorkspaceId(workspace.getId());
        ownPrivate.setContent("Own private");
        ownPrivate.setVisibility("private");
        ownPrivate.setAuthor(current);
        ownPrivate.setDeal(deal);
        noteMapper.insert(ownPrivate);
        Note otherPrivate = new Note();
        otherPrivate.setWorkspaceId(workspace.getId());
        otherPrivate.setContent("Other private");
        otherPrivate.setVisibility("private");
        otherPrivate.setAuthor(other);
        otherPrivate.setPerson(person);
        noteMapper.insert(otherPrivate);

        CompanyEngagementCountsDto counts = companyMapper.getCompanyEngagementCounts(
            workspace.getId(), company.getId());
        List<CompanyEngagementUserDto> users = companyMapper.getCompanyEngagementUsers(
            workspace.getId(), company.getId(), 5);
        List<CompanyEngagementWeekBucketDto> weeks = companyMapper.getCompanyEngagementWeeks(
            workspace.getId(), company.getId(), "2026-06-01 00:00:00", "2026-08-31 23:59:59");
        List<CompanyEngagementPersonDto> people = personMapper.getCompanyEngagementPeople(
            workspace.getId(), company.getId(), 5);

        assertEquals(1, counts.numNotes());
        assertEquals(1, counts.numActivities());
        assertEquals(1, counts.numTasks());
        assertEquals(1, counts.openTasks());
        assertTrue(users.stream().noneMatch(user -> user.userId() == other.getId()));
        assertEquals(1, weeks.stream().mapToLong(CompanyEngagementWeekBucketDto::notes).sum());
        assertEquals(List.of(person.getId()), people.stream().map(CompanyEngagementPersonDto::id).toList());
        assertEquals(person.getName(), people.getFirst().name());
    }

    @Test
    void relationshipScoreAggregatesAreCompactDeduplicatedAndExcludePrivateNotes() {
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        User user = newUser();
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("meeting");
        activity.setSubject("Scoring meeting");
        activity.setPerson(person);
        activity.setDeal(deal);
        activity.setCreatedBy(user);
        activity.setTimestamp("2026-07-10 00:00:00");
        activityMapper.insert(activity);
        Activity boundary = new Activity();
        boundary.setWorkspaceId(workspace.getId());
        boundary.setType("meeting");
        boundary.setSubject("Boundary scoring meeting");
        boundary.setPerson(person);
        boundary.setDeal(deal);
        boundary.setCreatedBy(user);
        boundary.setTimestamp("2026-07-11 00:00:00");
        activityMapper.insert(boundary);
        Activity future = new Activity();
        future.setWorkspaceId(workspace.getId());
        future.setType("meeting");
        future.setSubject("Future scoring meeting");
        future.setPerson(person);
        future.setDeal(deal);
        future.setCreatedBy(user);
        future.setTimestamp("2026-07-11 00:00:01");
        activityMapper.insert(future);
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Scoring task");
        task.setStatus("todo");
        task.setAssignedTo(user);
        task.setPerson(person);
        task.setDeal(deal);
        taskMapper.insert(task);
        Note visible = new Note();
        visible.setWorkspaceId(workspace.getId());
        visible.setContent("Visible scoring note");
        visible.setAuthor(user);
        visible.setPerson(person);
        visible.setDeal(deal);
        noteMapper.insert(visible);
        Note privateNote = new Note();
        privateNote.setWorkspaceId(workspace.getId());
        privateNote.setContent("Private scoring note");
        privateNote.setVisibility("private");
        privateNote.setAuthor(user);
        privateNote.setPerson(person);
        privateNote.setDeal(deal);
        noteMapper.insert(privateNote);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE task SET created_at = ? WHERE id = ?",
            Timestamp.valueOf("2026-07-10 00:00:00"), task.getId());
        jdbc.update("UPDATE note SET created_at = ? WHERE id IN (?, ?)",
            Timestamp.valueOf("2026-07-10 00:00:00"), visible.getId(), privateNote.getId());
        LocalDateTime reference = LocalDateTime.parse("2026-07-11T00:00:00");

        RelationshipScoreAggregateDto personScore = personMapper
            .getRelationshipScoreAggregates(workspace.getId(), reference).stream()
            .filter(score -> score.id() == person.getId())
            .findFirst().orElseThrow();
        RelationshipScoreAggregateDto companyScore = companyMapper
            .getRelationshipScoreAggregates(workspace.getId(), reference).stream()
            .filter(score -> score.id() == company.getId())
            .findFirst().orElseThrow();

        assertEquals(4, personScore.recentTouchCount());
        assertEquals(4, companyScore.recentTouchCount());
        assertEquals(2.7, personScore.recentWeight(), 0.000001);
        assertEquals(2.7, companyScore.recentWeight(), 0.000001);
        assertEquals("2026-07-11 00:00:00", personScore.lastTouchAt());
        assertEquals("2026-07-11 00:00:00", companyScore.lastTouchAt());
        assertTrue(personScore.rawWeight() > 0);
        assertTrue(companyScore.rawWeight() > 0);
    }

    @Test
    void companyRevenueUsesWonActualsAndOpenForecastOnly() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal won = newDeal(pipeline, stage, company);
        won.setValue(1_000);
        won.setActualValue(750);
        won.setWon(true);
        won.setClosedAt("2026-07-01 00:00:00");
        dealMapper.update(won);
        Deal lost = newDeal(pipeline, stage, company);
        lost.setValue(500);
        lost.setActualValue(999);
        lost.setWon(false);
        lost.setClosedAt("2026-07-02 00:00:00");
        dealMapper.update(lost);
        Deal open = newDeal(pipeline, stage, company);
        open.setValue(400);
        open.setActualValue(100);
        dealMapper.update(open);

        List<CompanyRevenueCurrencyDto> revenue = companyMapper.getCompanyRevenueByCurrency(
            workspace.getId(), company.getId());

        assertEquals(1, revenue.size());
        assertEquals(3, revenue.getFirst().dealCount());
        assertEquals(750, revenue.getFirst().pastRevenue());
        assertEquals(400, revenue.getFirst().projectedRevenue());
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Company newCompanyIn(Workspace ws) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }

    private Company newCompanyIn(Workspace ws, String name, String website, String industry,
            String phone, String address) {
        Company company = new Company();
        company.setName(name);
        company.setWebsite(website);
        company.setIndustry(industry);
        company.setPhone(phone);
        company.setAddress(address);
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }

    private List<Integer> companyPageIds(Workspace ws, String sort, String dir) {
        return companyMapper.getCompaniesPage(
            ws.getId(), null, sort, dir, null, false, null, 100, 0)
            .stream().map(Company::getId).toList();
    }

    private List<Integer> companySearchIds(Workspace ws, String query) {
        return companyMapper.getCompaniesPage(
            ws.getId(), query, "name", "asc", null, false, null, 100, 0)
            .stream().map(Company::getId).toList();
    }

    private List<Integer> filteredCompanyIds(Workspace ws, String query, List<String> industry,
            boolean noIndustry, List<Integer> ids) {
        return companyMapper.getCompaniesPage(
            ws.getId(), query, "name", "asc", industry, noIndustry, ids, 100, 0)
            .stream().map(Company::getId).toList();
    }

    private void assertSort(Workspace ws, String sort, Company first, Company second, Company third) {
        assertEquals(List.of(first.getId(), second.getId(), third.getId()), companyPageIds(ws, sort, "asc"));
        assertEquals(List.of(third.getId(), second.getId(), first.getId()), companyPageIds(ws, sort, "desc"));
    }

    private void setTimestamps(Company company, String createdAt, String updatedAt) {
        new JdbcTemplate(dataSource).update(
            "UPDATE company SET created_at = ?, updated_at = ? WHERE id = ?",
            Timestamp.valueOf(createdAt), Timestamp.valueOf(updatedAt), company.getId());
    }
}
