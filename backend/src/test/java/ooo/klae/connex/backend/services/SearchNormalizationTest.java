package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.util.LikePattern;
import ooo.klae.connex.backend.util.SearchNormalizer;
import ooo.klae.connex.backend.mappers.RecordSearchMapper;
import ooo.klae.connex.backend.mappers.RecordSearchMapper.Kind;

@Transactional(isolation = Isolation.READ_COMMITTED)
class SearchNormalizationTest extends AbstractServiceTest {
    @Autowired private SearchService searchService;
    @Autowired private PersonService personService;
    @Autowired private CompanyService companyService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecordSearchMapper recordSearchMapper;

    @Test
    void listAndGlobalSearchFoldStoredAndQueriedJapaneseNames() {
        Person kanji = person("山田 太郎", null);
        Person compact = person("山田太郎", null);
        Person kana = person("ﾔﾏﾀﾞ\u3000ﾀﾛｳ", null);
        Company company = company("ｱﾏｿﾞﾝ\u3000ＡＢＣ", null);
        for (String query : List.of("山田太郎", "山田 太郎", "山田\u3000太郎")) {
            assertPersonMatch(query, kanji.getId());
            assertPersonMatch(query, compact.getId());
            assertEquals(2, people(query).total());
        }
        for (String query : List.of("ヤマダタロウ", "やまだ たろう", "ﾔﾏﾀﾞ ﾀﾛｳ")) {
            assertPersonMatch(query, kana.getId());
        }
        for (String query : List.of("アマゾンABC", "あまぞん ＡＢＣ", "ｱﾏｿﾞﾝABC")) {
            assertCompanyMatch(query, company.getId());
        }
        assertEquals("山田 太郎", personService.getPersonById(kanji.getId()).getName());
    }

    @Test
    void listAndGlobalSearchFindEquivalentPhonesAndDigitFragments() {
        Person person = person("Phone contact", "+81-90-9684-6208");
        Company company = company("Phone company", "090-1111-2222");
        for (String query : List.of("090-9684-6208", "09096846208", "+81 90 9684 6208", "96846208",
                "０９０（９６８４）６２０８")) {
            assertPersonMatch(query, person.getId());
        }
        for (String query : List.of("09011112222", "090 1111 2222", "+81-90-1111-2222", "11112222")) {
            assertCompanyMatch(query, company.getId());
        }
        Person international = person("International phone fragment", "+1 (909) 684-6208");
        assertPersonMatch("9096846208", international.getId());
    }

    @Test
    void phoneSeparatorsSpacesAndExtensionsMatchBidirectionally() {
        Person person = person("Equivalent phone contact", "09096846208");
        Company company = company("Equivalent phone company", "09096846208");
        List<String> variants = List.of("090-9684-6208", "+81 90 9684 6208", "090・9684・6208",
            "090ー9684ー6208", "090\t9684\t6208", "090-9684-6208 ext. 123", "090-9684-6208x123",
            "090\u00a09684\u20076208", "+81\u202f(0)90\u30009684\u20096208",
            "０９０－９６８４－６２０８ 内線１２３", "+81(0)90-9684-6208;ext=123");
        for (String stored : variants) {
            person.setPhone(stored);
            personMapper.update(person);
            company.setPhone(stored);
            companyMapper.update(company);
            for (String query : List.of("09096846208", "+81-90-9684-6208")) {
                assertPersonMatch(query, person.getId());
                assertCompanyMatch(query, company.getId());
            }
            assertPersonMatch("96846208", person.getId());
            assertCompanyMatch("96846208", company.getId());
        }
        person.setPhone("+81-90-9684-6208");
        personMapper.update(person);
        company.setPhone("+81-90-9684-6208");
        companyMapper.update(company);
        for (String query : variants) {
            assertPersonMatch(query, person.getId());
            assertCompanyMatch(query, company.getId());
        }
    }

    @Test
    void wholePhoneQueriesRetainDisplayFallbacksAndOtherFieldMatches() {
        Person legacy = person("Legacy phone contact", "090-9684-6208");
        Company legacyCompany = company("Legacy phone company", "090-9684-6208");
        jdbc.update("UPDATE person SET search_phone = NULL WHERE workspace_id = ? AND id = ?",
            workspace.getId(), legacy.getId());
        jdbc.update("UPDATE company SET search_phone = NULL WHERE workspace_id = ? AND id = ?",
            workspace.getId(), legacyCompany.getId());
        assertPersonMatch("090-9684-6208", legacy.getId());
        assertCompanyMatch("090-9684-6208", legacyCompany.getId());
        Person named = person("Reference 09096846208", null);
        Company namedCompany = company("Reference 09096846208", null);
        assertPersonMatch("09096846208", named.getId());
        assertCompanyMatch("09096846208", namedCompany.getId());
        Person display = person("Multiple display phones", "090-9684-6208/080-1111-2222");
        Company displayCompany = company("Multiple display phones", "090-9684-6208/080-1111-2222");
        assertNotNull(jdbc.queryForObject("SELECT search_phone FROM person WHERE id = ?", String.class, display.getId()));
        assertNotNull(jdbc.queryForObject("SELECT search_phone FROM company WHERE id = ?", String.class, displayCompany.getId()));
        assertPersonMatch("090-9684-6208", display.getId());
        assertCompanyMatch("090-9684-6208", displayCompany.getId());
    }

    @Test
    void normalizedPredicatesCannotMatchOtherTenantRows() {
        Workspace foreign = new Workspace();
        foreign.setName("Other search workspace");
        foreign.setSlug("search-" + unique());
        workspaceMapper.insert(foreign);
        TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, foreign.getId());
        Person hidden = person("Foreign 山田 太郎", "090-9684-6208");
        Company hiddenCompany = company("Foreign ｱﾏｿﾞﾝ ABC", "090-1111-2222");
        jdbc.update("UPDATE person SET workspace_id = ? WHERE id = ?", foreign.getId(), hidden.getId());
        jdbc.update("UPDATE company SET workspace_id = ? WHERE id = ?", foreign.getId(), hiddenCompany.getId());
        jdbc.update("INSERT INTO person_share (person_id, workspace_id) VALUES (?, ?)", hidden.getId(), workspace.getId());
        jdbc.update("INSERT INTO company_share (company_id, workspace_id) VALUES (?, ?)", hiddenCompany.getId(), workspace.getId());
        for (String query : List.of("Foreign山田太郎", "09096846208", "96846208")) {
            assertTrue(people(query).items().isEmpty());
            assertEquals(0, people(query).total());
            assertTrue(searchService.search(query, Set.of("people")).getPeople().isEmpty());
        }
        for (String query : List.of("ForeignあまぞんABC", "09011112222", "11112222")) {
            assertTrue(companies(query).items().isEmpty());
            assertEquals(0, companies(query).total());
            assertTrue(searchService.search(query, Set.of("companies")).getCompanies().isEmpty());
        }
    }

    @Test
    void updatedAndBatchInsertedKeysStayCurrentWithoutChangingIdentities() {
        Person person = person("Before", "090-1111-2222");
        person.setName("ヤマダ タロウ");
        person.setPhone("090-9684-6208");
        personMapper.update(person);
        assertPersonMatch("やまだたろう", person.getId());
        assertPersonMatch("09096846208", person.getId());
        assertTrue(people("09011112222").items().isEmpty());
        Company company = company("Before company", "090-1111-2222");
        company.setName("ｱﾏｿﾞﾝ ABC");
        company.setPhone("090-9684-6208");
        companyMapper.update(company);
        assertCompanyMatch("あまぞんabc", company.getId());
        assertCompanyMatch("09096846208", company.getId());
        Person batchPerson = draftPerson("Batch ヤマダ タロウ", "090-1111-2222");
        personMapper.insertBatch(List.of(batchPerson));
        Company batchCompany = draftCompany("Batch ｱﾏｿﾞﾝ ABC", "090-1111-2222");
        companyMapper.insertBatch(List.of(batchCompany));
        assertPersonMatch("Batchやまだたろう", batchPerson.getId());
        assertCompanyMatch("Batchあまぞんabc", batchCompany.getId());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ? AND person_id = ?",
            Integer.class, workspace.getId(), person.getId()));
    }

    @Test
    void legacyWritesClearObsoleteKeysAndBackfillRetainsDisplayData() {
        Person person = person("山田 太郎", "090-1111-2222");
        Company company = company("ｱﾏｿﾞﾝ ABC", "090-1111-2222");
        jdbc.update("UPDATE person SET name = 'Legacy Name', phone = '090-9684-6208' WHERE id = ?", person.getId());
        jdbc.update("UPDATE company SET phone = NULL WHERE id = ?", company.getId());
        assertNull(jdbc.queryForObject("SELECT search_name FROM person WHERE id = ?", String.class, person.getId()));
        assertNull(jdbc.queryForObject("SELECT search_phone FROM company WHERE id = ?", String.class, company.getId()));
        assertTrue(people("山田太郎").items().isEmpty());
        assertPersonMatch("Legacy Name", person.getId());
        assertPersonMatch("090-9684-6208", person.getId());
        backfill();
        assertPersonMatch("LegacyName", person.getId());
        assertPersonMatch("09096846208", person.getId());
        assertCompanyMatch("あまぞんabc", company.getId());
        assertEquals("", jdbc.queryForObject("SELECT search_phone FROM company WHERE id = ?", String.class, company.getId()));
        assertEquals("Legacy Name", personService.getPersonById(person.getId()).getName());
    }

    @Test
    void searchAndBackfillNeverRekeyCanonicalIdentities() {
        Person person = personService.create(draftPerson("Identity 山田 太郎", "090-9684-6208"));
        var before = jdbc.queryForList(
            "SELECT id, kind, normalized_value FROM person_identity WHERE workspace_id = ? AND person_id = ? ORDER BY id",
            workspace.getId(), person.getId());
        assertFalse(before.isEmpty());
        assertPersonMatch("Identity山田太郎", person.getId());
        assertPersonMatch("09096846208", person.getId());
        jdbc.update("UPDATE person SET search_name = NULL WHERE id = ?", person.getId());
        backfill();
        assertPersonMatch("Identity山田太郎", person.getId());
        assertEquals(before, jdbc.queryForList(
            "SELECT id, kind, normalized_value FROM person_identity WHERE workspace_id = ? AND person_id = ? ORDER BY id",
            workspace.getId(), person.getId()));
    }

    @Test
    void literalWildcardsAndEmptyPhoneProjectionDoNotExpandMatchesAndLimitsHold() {
        person("Ordinary", "090-1111-2222");
        assertTrue(people("%").items().isEmpty());
        assertTrue(people("_").items().isEmpty());
        assertTrue(searchService.search("%", Set.of("people", "companies")).getPeople().isEmpty());
        for (int index = 0; index < 12; index++) {
            person("Bounded ヤマダ " + index, null);
        }
        assertEquals(10, searchService.search("boundedやまだ", Set.of("people")).getPeople().size());
        assertEquals(12, people("boundedやまだ").total());
        assertEquals(5, people("boundedやまだ").items().size());
        assertFalse(people("ordinary").items().isEmpty());
    }

    private void backfill() {
        for (Kind kind : Kind.values()) {
            Integer throughId = recordSearchMapper.maxId(workspace.getId(), kind);
            if (throughId != null) {
                for (var row : recordSearchMapper.findMissing(workspace.getId(), kind, 0, throughId, 500)) {
                    recordSearchMapper.reconcile(workspace.getId(), kind, row,
                        SearchNormalizer.name(row.name()), SearchNormalizer.phone(row.phone()));
                }
            }
        }
    }

    private void assertPersonMatch(String query, int id) {
        assertTrue(people(query).items().stream().anyMatch(row -> row.getId() == id), query);
        assertTrue(people(query).total() > 0, query);
        assertTrue(searchService.search(query, Set.of("people")).getPeople().stream()
            .anyMatch(row -> row.getId() == id), query);
    }

    private void assertCompanyMatch(String query, int id) {
        assertTrue(companies(query).items().stream().anyMatch(row -> row.getId() == id), query);
        assertTrue(companies(query).total() > 0, query);
        assertTrue(searchService.search(query, Set.of("companies")).getCompanies().stream()
            .anyMatch(row -> row.getId() == id), query);
    }

    private PageResponse<Person> people(String raw) {
        return personService.queryPersonsPage(LikePattern.containing(raw), "name", "asc", List.of(), List.of(),
            false, MemberScope.allTeam(), List.of(), false, List.of(), false, List.of(), false,
            false, null, 5, 0);
    }

    private PageResponse<Company> companies(String raw) {
        return companyService.queryCompaniesPage(LikePattern.containing(raw), "name", "asc", List.of(),
            false, List.of(), MemberScope.allTeam(), false, null, 5, 0);
    }

    private Person person(String name, String phone) {
        Person person = draftPerson(name, phone);
        personMapper.insert(person);
        return person;
    }

    private Person draftPerson(String name, String phone) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setPhone(phone);
        return person;
    }

    private Company company(String name, String phone) {
        Company company = draftCompany(name, phone);
        companyMapper.insert(company);
        return company;
    }

    private Company draftCompany(String name, String phone) {
        Company company = new Company();
        company.setWorkspaceId(workspace.getId());
        company.setName(name);
        company.setPhone(phone);
        return company;
    }
}
