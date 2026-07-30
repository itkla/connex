package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

/**
 * Wave 2 (#854) archive contract: archiving a contact or company must hide it from every ordinary
 * read while destroying nothing, restore must return it intact, and both must stay workspace-scoped.
 */
class RecordArchiveServiceTest extends AbstractServiceTest {

    @Autowired PersonService personService;
    @Autowired CompanyService companyService;
    @Autowired BulkOperationService bulkOperationService;
    @Autowired CustomFieldDefinitionService definitionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;

    @Test
    void archivingAContactHidesItFromListsAndDetailReadsAndRestoreBringsItBack() {
        Company company = newCompany();
        Person person = newPerson(company);

        assertTrue(listedPersonIds().contains(person.getId()));
        assertNotNull(personService.getPersonById(person.getId()));

        Person archived = personService.archive(person.getId());

        assertNotNull(archived.getArchivedAt());
        assertFalse(listedPersonIds().contains(person.getId()),
            "an archived contact must leave the browser list");
        assertFalse(personService.getAllPersons().stream()
            .anyMatch(candidate -> candidate.getId() == person.getId()));
        assertThrows(ResourceNotFoundException.class, () -> personService.getPersonById(person.getId()));
        assertEquals(1, personService.countArchivedPersons());
        assertEquals(1, archivedPersonIds().stream().filter(id -> id == person.getId()).count());

        Person restored = personService.restore(person.getId());

        assertNull(restored.getArchivedAt());
        assertTrue(listedPersonIds().contains(person.getId()));
        assertNotNull(personService.getPersonById(person.getId()));
        assertEquals(0, personService.countArchivedPersons());
    }

    @Test
    void anArchivedContactCannotBeEditedThroughTheOrdinaryUpdatePath() {
        Person person = newPerson(newCompany());
        personService.archive(person.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> personService.update(person.getId(), newPersonDraft()));
    }

    @Test
    void restoringAContactReturnsItsOwnerCompanyTagsAndCustomFieldValues() {
        Company company = newCompany();
        Person person = newPerson(company);
        Tag tag = newTag();
        personService.addTag(person.getId(), tag.getId());
        personService.updateOwner(person.getId(), currentUser.getId());
        CustomFieldDefinition definition = personTextField();
        personService.updateCustomField(person.getId(), definition.getId(), "Platinum");

        personService.archive(person.getId());
        Person restored = personService.restore(person.getId());

        assertEquals(currentUser.getId(), restored.getOwnerId());
        assertNotNull(restored.getCompany());
        assertEquals(company.getId(), restored.getCompany().getId());
        assertEquals(List.of(tag.getName()),
            personService.getTagsByPersonId(person.getId()).stream().map(Tag::getName).toList());
        assertEquals("Platinum", personService.getCustomFields(person.getId()).stream()
            .filter(entry -> entry.getDefinitionId() == definition.getId())
            .map(entry -> String.valueOf(entry.getValue()))
            .findFirst().orElse(null));
    }

    @Test
    void archivingAContactRetainsItsConsentHistoryAndIdentityRows() {
        Company company = newCompany();
        Person draft = newPersonDraft();
        draft.setEmail(unique() + ".consent@example.com");
        draft.setCompany(company);
        Person person = personService.create(draft);
        insertConsentWithHistory(person);

        personService.archive(person.getId());

        assertEquals(1, countRows("SELECT COUNT(*) FROM contact_channel_consent "
            + "WHERE workspace_id = ? AND person_id = ?", person.getId()));
        assertEquals(1, countRows("SELECT COUNT(*) FROM contact_channel_consent_event "
            + "WHERE workspace_id = ? AND person_id = ?", person.getId()));
        assertEquals(1, countRows("SELECT COUNT(*) FROM person_identity "
            + "WHERE workspace_id = ? AND person_id = ?", person.getId()));
        assertEquals(1, countRows("SELECT COUNT(*) FROM person_employment "
            + "WHERE workspace_id = ? AND person_id = ?", person.getId()));
        assertEquals(1, countRows("SELECT COUNT(*) FROM person "
            + "WHERE workspace_id = ? AND id = ?", person.getId()));
    }

    @Test
    void archivingACompanyHidesItWhileItsPeopleKeepTheirLinkAndRestoreBringsItBack() {
        Company company = newCompany();
        Person person = newPerson(company);
        Tag tag = newTag();
        companyService.addTag(company.getId(), tag.getId());

        Company archived = companyService.archiveCompany(company.getId());

        assertNotNull(archived.getArchivedAt());
        assertThrows(ResourceNotFoundException.class, () -> companyService.getCompanyById(company.getId()));
        assertFalse(listedCompanyIds().contains(company.getId()));
        assertEquals(1, companyService.countArchivedCompanies());
        assertEquals(company.getId(), companyIdOf(person.getId()),
            "archiving a company must not orphan the people that point at it");

        Company restored = companyService.restoreCompany(company.getId());

        assertNull(restored.getArchivedAt());
        assertTrue(listedCompanyIds().contains(company.getId()));
        assertEquals(List.of(tag.getName()),
            companyService.getTagsByCompanyId(company.getId()).stream().map(Tag::getName).toList());
    }

    @Test
    void editingAContactWhileItsEmployerIsArchivedPreservesTheEmployerLink() {
        Company company = newCompany();
        Person person = newPerson(company);
        companyService.archiveCompany(company.getId());
        Person edit = personService.getPersonById(person.getId());
        assertNull(edit.getCompany());
        edit.setTitle("Updated while employer archived");

        Person updated = personService.update(person.getId(), edit);

        assertNull(updated.getCompany());
        assertEquals(company.getId(), companyIdOf(person.getId()));

        companyService.restoreCompany(company.getId());
        Person restored = personService.getPersonById(person.getId());
        assertNotNull(restored.getCompany());
        assertEquals(company.getId(), restored.getCompany().getId());
        assertEquals(company.getName(), restored.getCompany().getName());
        assertEquals("Updated while employer archived", restored.getTitle());
    }

    @Test
    void restoringAContactReconcilesMissingCanonicalIdentities() {
        Person draft = newPersonDraft();
        draft.setEmail(unique() + ".restore@example.com");
        draft.setPhone("+1 808 555 0101");
        Person person = personService.create(draft);
        deletePersonIdentities(person.getId());
        personService.archive(person.getId());

        personService.restore(person.getId());

        assertEquals(2, countCurrentPersonIdentities(person.getId()));
    }

    @Test
    void restoringACompanyReconcilesMissingCanonicalIdentities() {
        Company draft = new Company();
        draft.setName("Restore Identity " + unique());
        draft.setWebsite("https://" + unique() + ".example.com");
        draft.setPhone("+1 808 555 0102");
        Company company = companyService.createCompany(draft);
        deleteCompanyIdentities(company.getId());
        companyService.archiveCompany(company.getId());

        companyService.restoreCompany(company.getId());

        assertEquals(2, countCurrentCompanyIdentities(company.getId()));
    }

    @Test
    void archiveAndRestoreRejectAnotherWorkspacesRecords() {
        Workspace other = newForeignWorkspace();
        Person foreignPerson = personIn(other);
        Company foreignCompany = companyIn(other);

        assertThrows(ResourceNotFoundException.class, () -> personService.archive(foreignPerson.getId()));
        assertThrows(ResourceNotFoundException.class, () -> personService.restore(foreignPerson.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.archiveCompany(foreignCompany.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.restoreCompany(foreignCompany.getId()));

        assertNull(archivedAt("person", foreignPerson.getId()));
        assertNull(archivedAt("company", foreignCompany.getId()));
    }

    @Test
    void archivingTwiceAndRestoringAnActiveRecordAreBothNotFound() {
        Person person = newPerson(newCompany());

        personService.archive(person.getId());
        assertThrows(ResourceNotFoundException.class, () -> personService.archive(person.getId()));

        personService.restore(person.getId());
        assertThrows(ResourceNotFoundException.class, () -> personService.restore(person.getId()));
    }

    @Test
    void bulkArchiveAndRestoreStayWorkspaceScoped() {
        Person mine = newPerson(newCompany());
        Workspace other = newForeignWorkspace();
        Person foreign = personIn(other);

        BulkOperationResult archiveResult =
            bulkOperationService.archivePersons(List.of(mine.getId(), foreign.getId()));

        assertEquals(1, archiveResult.getSucceeded());
        assertEquals(1, archiveResult.getFailed());
        assertNotNull(archivedAt("person", mine.getId()));
        assertNull(archivedAt("person", foreign.getId()),
            "a contact in another workspace must never be archived by a bulk operation");

        BulkOperationResult restoreResult =
            bulkOperationService.restorePersons(List.of(mine.getId(), foreign.getId()));

        assertEquals(1, restoreResult.getSucceeded());
        assertEquals(1, restoreResult.getFailed());
        assertNull(archivedAt("person", mine.getId()));
    }

    @Test
    void bulkArchiveAndRestoreStayWorkspaceScopedForCompanies() {
        Company mine = newCompany();
        Workspace other = newForeignWorkspace();
        Company foreign = companyIn(other);

        BulkOperationResult archiveResult =
            bulkOperationService.archiveCompanies(List.of(mine.getId(), foreign.getId()));

        assertEquals(1, archiveResult.getSucceeded());
        assertEquals(1, archiveResult.getFailed());
        assertNotNull(archivedAt("company", mine.getId()));
        assertNull(archivedAt("company", foreign.getId()));

        BulkOperationResult restoreResult =
            bulkOperationService.restoreCompanies(List.of(mine.getId(), foreign.getId()));

        assertEquals(1, restoreResult.getSucceeded());
        assertEquals(1, restoreResult.getFailed());
        assertNull(archivedAt("company", mine.getId()));
    }

    @Test
    void archiveAndRestoreAreAudited() {
        Person person = newPerson(newCompany());

        personService.archive(person.getId());
        personService.restore(person.getId());

        assertEquals(1, countAudit("person.archive", person.getId()));
        assertEquals(1, countAudit("person.restore", person.getId()));
        assertEquals(0, countAudit("person.delete", person.getId()));
    }

    private List<Integer> listedPersonIds() {
        return personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), false, 100, 0)
            .stream().map(Person::getId).toList();
    }

    private List<Integer> archivedPersonIds() {
        return personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), true, 100, 0)
            .stream().map(Person::getId).toList();
    }

    private List<Integer> listedCompanyIds() {
        return companyService.getCompaniesPage(null, null, null, null, false, null,
                MemberScope.allTeam(), false, 100, 0)
            .stream().map(Company::getId).toList();
    }

    private Person newPersonDraft() {
        Person draft = new Person();
        draft.setName("Renamed " + unique());
        return draft;
    }

    private CustomFieldDefinition personTextField() {
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setEntityType("person");
        definition.setFieldKey("tier_" + unique());
        definition.setLabel("Tier");
        definition.setFieldType("text");
        return definitionService.create(definition, null);
    }

    private Integer companyIdOf(int personId) {
        return jdbcTemplate.queryForObject(
            "SELECT company_id FROM person WHERE workspace_id = ? AND id = ?",
            Integer.class, workspace.getId(), personId);
    }

    private void deletePersonIdentities(int personId) {
        jdbcTemplate.update(
            "DELETE FROM person_identity WHERE workspace_id = ? AND person_id = ?",
            workspace.getId(), personId);
    }

    private void deleteCompanyIdentities(int companyId) {
        jdbcTemplate.update(
            "DELETE FROM company_identity WHERE workspace_id = ? AND company_id = ?",
            workspace.getId(), companyId);
    }

    private int countCurrentPersonIdentities(int personId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_identity "
                + "WHERE workspace_id = ? AND person_id = ? AND superseded_at IS NULL",
            Integer.class, workspace.getId(), personId);
        return count == null ? 0 : count;
    }

    private int countCurrentCompanyIdentities(int companyId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM company_identity "
                + "WHERE workspace_id = ? AND company_id = ? AND superseded_at IS NULL",
            Integer.class, workspace.getId(), companyId);
        return count == null ? 0 : count;
    }

    private java.sql.Timestamp archivedAt(String table, int id) {
        return jdbcTemplate.queryForObject(
            "SELECT archived_at FROM " + ("person".equals(table) ? "person" : "company")
                + " WHERE id = ?",
            java.sql.Timestamp.class, id);
    }

    private int countRows(String sql, int personId) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, workspace.getId(), personId);
        return count == null ? 0 : count;
    }

    private int countAudit(String action, int entityId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_type = 'person' "
                + "AND entity_id = ? AND action = ?",
            Integer.class, workspace.getId(), entityId, action);
        return count == null ? 0 : count;
    }

    private void insertConsentWithHistory(Person person) {
        jdbcTemplate.update(
            "INSERT INTO contact_channel_consent "
                + "(workspace_id, person_id, channel, purpose, status, source) "
                + "VALUES (?, ?, 'email', 'marketing', 'granted', 'test')",
            workspace.getId(), person.getId());
        Integer consentId = jdbcTemplate.queryForObject(
            "SELECT id FROM contact_channel_consent WHERE workspace_id = ? AND person_id = ?",
            Integer.class, workspace.getId(), person.getId());
        jdbcTemplate.update(
            "INSERT INTO contact_channel_consent_event "
                + "(workspace_id, consent_id, person_id, channel, purpose, status, source) "
                + "VALUES (?, ?, ?, 'email', 'marketing', 'granted', 'test')",
            workspace.getId(), consentId, person.getId());
    }

    private Workspace newForeignWorkspace() {
        Workspace other = new Workspace();
        other.setName("Foreign Workspace");
        other.setSlug("foreign-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Person personIn(Workspace target) {
        String s = unique();
        Person person = new Person();
        person.setName("Foreign " + s);
        person.setEmail(s + ".foreign@example.com");
        person.setWorkspaceId(target.getId());
        personMapper.insert(person);
        return person;
    }

    private Company companyIn(Workspace target) {
        Company company = new Company();
        company.setName("Foreign Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }
}
