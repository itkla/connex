package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.PersonLifecycleStage;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DisqualificationReasonDto;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PersonLifecycleHistoryDto;
import ooo.klae.connex.backend.dto.PersonLifecycleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

/**
 * The contact lead-lifecycle state machine, its append-only history, and the browser filter it
 * feeds (#559). Verifies the transition rules, reason handling, tenant isolation, and the
 * restriction/archive fences documented in {@code docs/LEAD_LIFECYCLE.md}.
 */
class PersonLifecycleServiceTest extends AbstractServiceTest {

    @Autowired PersonLifecycleService lifecycleService;
    @Autowired PersonService personService;
    @Autowired DisqualificationReasonService reasonService;
    @Autowired ExportService exportService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ShareMapper shareMapper;
    @Autowired CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;
    @Autowired jakarta.validation.Validator beanValidator;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;
    private User provisioningOwner;

    @BeforeEach
    void useFreshWorkspaceWithCustomRoleMember() {
        provisioningOwner = currentUser;
        Organization organization = new Organization();
        organization.setName("Lifecycle service " + unique());
        organization.setSlug("lifecycle-service-" + unique());
        organizationMapper.insert(organization);
        Workspace fresh = new Workspace();
        fresh.setName("Lifecycle service " + unique());
        fresh.setSlug("lifecycle-service-" + unique());
        fresh.setOrgId(organization.getId());
        workspaceMapper.insert(fresh);
        workspace = fresh;
        workspaceMapper.addMember(workspace.getId(), provisioningOwner.getId(), "owner");
        User lifecycleMember = standaloneUser("lifecycle-member");
        workspaceMapper.addMember(workspace.getId(), lifecycleMember.getId(), "member");
        authenticateAs(provisioningOwner, workspace.getId());
        var lifecycleRole = roleService.createRole(
            workspace.getId(), provisioningOwner.getId(), "Lifecycle manager " + unique(),
            List.of("PERSON_UPDATE", "PERSON_DELETE", "WORKSPACE_SETTINGS"));
        workspaceService.assignCustomRole(
            workspace.getId(), provisioningOwner.getId(), lifecycleMember.getId(),
            lifecycleRole.getId());
        currentUser = lifecycleMember;
        authenticateAs(currentUser, workspace.getId());
    }

    @Test
    void enteringTheLifecycleRecordsTheStageAndAppendsOneHistoryRow() {
        Person person = newPerson(newCompany());
        assertNull(person.getLifecycleStage());

        Person after = lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.NEW, null, "Inbound referral"));

        assertEquals(PersonLifecycleStage.NEW, after.getLifecycleStage());
        assertNotNull(after.getLifecycleChangedAt());
        assertEquals("Inbound referral", after.getQualificationNotes());

        List<PersonLifecycleHistoryDto> history = lifecycleService.getHistory(person.getId());
        assertEquals(1, history.size());
        assertNull(history.getFirst().fromStage());
        assertEquals(PersonLifecycleStage.NEW, history.getFirst().toStage());
        assertEquals(currentUser.getId(), history.getFirst().changedById());
        verify(ruleTriggers).publish(
            workspace.getId(), "person", person.getId(), "person.lifecycle_changed");
    }

    @Test
    void theCurrentStateExposesOnlyThePermittedNextMoves() {
        Person person = newPerson(newCompany());

        assertEquals(List.of(PersonLifecycleStage.NEW),
            lifecycleService.getLifecycle(person.getId()).allowedTransitions());

        enterLifecycle(person);
        assertEquals(
            List.of(PersonLifecycleStage.WORKING, PersonLifecycleStage.NURTURING,
                PersonLifecycleStage.QUALIFIED, PersonLifecycleStage.DISQUALIFIED),
            lifecycleService.getLifecycle(person.getId()).allowedTransitions());
    }

    @Test
    void anImpossibleTransitionIsRejectedAndChangesNothing() {
        Person person = enterLifecycle(newPerson(newCompany()));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.CONVERTED, null, null)));

        assertEquals(PersonLifecycleStage.NEW, reload(person).getLifecycleStage());
        assertEquals(1, lifecycleService.getHistory(person.getId()).size());
    }

    @Test
    void disqualifyingRequiresAReasonAndRecyclingClearsIt() {
        Person person = enterLifecycle(newPerson(newCompany()));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.DISQUALIFIED, null, "no reason given")));

        Person disqualified = lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED, PersonDisqualificationReason.NO_BUDGET, "FY26 frozen"));
        assertEquals(PersonDisqualificationReason.NO_BUDGET, disqualified.getDisqualifiedReason());

        Person recycled = lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.RECYCLED, null, "budget unfrozen"));
        assertEquals(PersonLifecycleStage.RECYCLED, recycled.getLifecycleStage());
        assertNull(recycled.getDisqualifiedReason());

        List<PersonLifecycleHistoryDto> history = lifecycleService.getHistory(person.getId());
        assertEquals(3, history.size());
        assertEquals(PersonDisqualificationReason.NO_BUDGET,
            history.stream()
                .filter(row -> row.toStage() == PersonLifecycleStage.DISQUALIFIED)
                .findFirst().orElseThrow().reason());
    }

    @Test
    void aReasonIsRefusedWhenTheContactIsNotBeingDisqualified() {
        Person person = enterLifecycle(newPerson(newCompany()));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(),
            request(PersonLifecycleStage.WORKING, PersonDisqualificationReason.NO_FIT, null)));
    }

    @Test
    void convertingRequiresALinkedDeal() {
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.QUALIFIED, null, null));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.CONVERTED, null, null)));

        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 1), company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), "champion");

        Person converted = lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.CONVERTED, null, null));
        assertEquals(PersonLifecycleStage.CONVERTED, converted.getLifecycleStage());
    }

    @Test
    void reSelectingTheCurrentStageUpdatesNotesWithoutRecordingATransition() {
        Person person = enterLifecycle(newPerson(newCompany()));

        Person after = lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.NEW, null, "second pass"));

        assertEquals("second pass", after.getQualificationNotes());
        assertEquals(1, lifecycleService.getHistory(person.getId()).size());
    }

    @Test
    void withdrawingClearsTheContactStateButKeepsTheTimeline() {
        Person person = enterLifecycle(newPerson(newCompany()));

        Person withdrawn = lifecycleService.withdrawFromLifecycle(person.getId(), "not a prospect");

        assertNull(withdrawn.getLifecycleStage());
        assertNull(withdrawn.getQualificationNotes());
        List<PersonLifecycleHistoryDto> history = lifecycleService.getHistory(person.getId());
        assertEquals(2, history.size());
        assertNull(history.getFirst().toStage());
        assertEquals("not a prospect", history.getFirst().note());
    }

    @Test
    void aRestrictedOrArchivedContactCannotBeMoved() {
        Person suspended = newPerson(newCompany());
        jdbcTemplate.update(
            "UPDATE person SET suspended_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), suspended.getId());
        assertThrows(ResourceNotFoundException.class, () -> lifecycleService.updateLifecycle(
            suspended.getId(), request(PersonLifecycleStage.NEW, null, null)));

        Person archived = newPerson(newCompany());
        personService.archive(archived.getId());
        assertThrows(ResourceNotFoundException.class, () -> lifecycleService.updateLifecycle(
            archived.getId(), request(PersonLifecycleStage.NEW, null, null)));
    }

    @Test
    void anotherTenantCanNeitherMoveNorReadTheLifecycle() {
        Person person = enterLifecycle(newPerson(newCompany()));

        Workspace other = siblingWorkspace();
        User outsider = newUser();
        workspaceMapper.addMember(other.getId(), outsider.getId(), "owner");
        authenticateAs(outsider, other.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> lifecycleService.getHistory(person.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> lifecycleService.getLifecycle(person.getId()));
        assertThrows(ResourceNotFoundException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.WORKING, null, null)));
    }

    @Test
    void aSharedInContactKeepsItsLifecycleInsideTheOwningWorkspace() {
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED,
            PersonDisqualificationReason.NO_FIT,
            "competitor incumbent"));

        Workspace grantee = siblingWorkspace();
        shareMapper.sharePerson(
            person.getId(), workspace.getId(), grantee.getId(), currentUser.getId(), false);
        User outsider = newUser();
        workspaceMapper.addMember(grantee.getId(), outsider.getId(), "owner");
        authenticateAs(outsider, grantee.getId());

        Person shared = personMapper.getPersonById(grantee.getId(), person.getId());
        assertNotNull(shared, "the share itself must still be visible");
        assertNull(shared.getLifecycleStage());
        assertNull(shared.getDisqualifiedReason());
        assertNull(shared.getQualificationNotes());
        assertNull(shared.getLifecycleChangedAt());

        assertThrows(ResourceNotFoundException.class,
            () -> lifecycleService.getLifecycle(person.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> lifecycleService.getHistory(person.getId()));

        assertEquals(0L, personService.countPersons(null, null, null, false,
            MemberScope.allTeam(), List.of(PersonLifecycleStage.DISQUALIFIED), false, null, false, null, false, false, null));
    }

    @Test
    void disqualifyingForAnotherReasonRequiresANote() {
        Person person = enterLifecycle(newPerson(newCompany()));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(),
            request(PersonLifecycleStage.DISQUALIFIED, PersonDisqualificationReason.OTHER, "  ")));

        Person disqualified = lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED, PersonDisqualificationReason.OTHER, "acquired last week"));
        assertEquals(PersonDisqualificationReason.OTHER, disqualified.getDisqualifiedReason());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM disqualification_reason WHERE workspace_id = ?",
            Integer.class, workspace.getId()));
    }

    @Test
    void lifecycleReasonDtoAcceptsOnlyCanonicalUppercaseAscii() {
        PersonLifecycleRequest canonical = request(
            PersonLifecycleStage.DISQUALIFIED, PersonDisqualificationReason.OTHER, null);
        assertTrue(beanValidator.validate(canonical).isEmpty());

        for (String code : List.of("other", " OTHER ", "ÖTHER")) {
            PersonLifecycleRequest invalid = request(
                PersonLifecycleStage.DISQUALIFIED, code, null);
            assertTrue(beanValidator.validate(invalid).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("reason")));
        }
    }

    @Test
    void lifecycleServiceRejectsNoncanonicalReasonCodesWithoutStoringThem() {
        Person person = enterLifecycle(newPerson(newCompany()));

        for (String code : List.of("other", " OTHER ", "ÖTHER")) {
            assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
                person.getId(), request(PersonLifecycleStage.DISQUALIFIED, code, null)));
        }

        assertEquals(PersonLifecycleStage.NEW, reload(person).getLifecycleStage());
        assertNull(reload(person).getDisqualifiedReason());
    }

    @Test
    void customReasonIsValidatedStoredListedExportedAndLabeledInCurrentStateAndHistory() {
        DisqualificationReasonDto custom = reason(
            "NO_REGION", "Outside our region", false, 20);
        Person person = enterLifecycle(newPerson(newCompany()));

        Person disqualified = lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED, custom.code(), "Not served here"));

        assertEquals(custom.code(), disqualified.getDisqualifiedReason());
        assertEquals("Outside our region",
            lifecycleService.getLifecycle(person.getId()).reasonLabel());
        PersonLifecycleHistoryDto history = lifecycleService.getHistory(person.getId()).stream()
            .filter(row -> row.toStage() == PersonLifecycleStage.DISQUALIFIED)
            .findFirst().orElseThrow();
        assertEquals(custom.code(), history.reason());
        assertEquals("Outside our region", history.reasonLabel());
        createReasonInSiblingWorkspace(
            custom.code(), "Wrong workspace label", false, custom.position());
        CustomFieldDefinition accountTier = new CustomFieldDefinition();
        accountTier.setWorkspaceId(workspace.getId());
        accountTier.setEntityType("person");
        accountTier.setFieldKey("account_tier");
        accountTier.setLabel("Account tier");
        accountTier.setFieldType("text");
        accountTier.setPosition(0);
        customFieldDefinitionMapper.insert(accountTier);
        Tag tag = newTag();
        personMapper.addTag(workspace.getId(), person.getId(), tag.getId());
        assertTrue(personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), null, false, null, false, null, false, false, null, 100, 0)
            .stream().anyMatch(row -> custom.code().equals(row.getDisqualifiedReason())));
        String csv = exportService.exportPersons(
            null, null, null, false, MemberScope.allTeam(), null, false,
            null, false, null, false, null);
        List<String> rows = csv.lines().toList();
        assertEquals(
            "id,name,email,phone,title,company,Account tier,tags,lifecycleStage,"
                + "disqualificationReason,disqualificationReasonLabel",
            rows.getFirst());
        assertEquals(String.join(",",
            Integer.toString(person.getId()), person.getName(), person.getEmail(), "'" + person.getPhone(),
            person.getTitle(), person.getCompany().getName(), "", tag.getName(),
            "DISQUALIFIED", "NO_REGION", "Outside our region"), rows.get(1));
        assertTrue(!csv.contains("Wrong workspace label"));
    }

    @Test
    void contactCsvUsesTheLocalizedBuiltInLabelWhenNoWorkspaceLabelExists() {
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED, PersonDisqualificationReason.NO_FIT, null));
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        try {
            String csv = exportService.exportPersons(
                null, null, null, false, MemberScope.allTeam(), null, false,
                null, false, null, false, null);

            assertTrue(csv.contains(",DISQUALIFIED,NO_FIT,適合しない"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void contactCsvDoesNotResolveANoncanonicalStoredCodeThroughTheCatalogCollation() {
        DisqualificationReasonDto custom = reason(
            "NO_REGION", "Outside our region", false, 20);
        Person person = newPerson(newCompany());
        jdbcTemplate.update(
            "UPDATE person SET lifecycle_stage = 'DISQUALIFIED', disqualified_reason = 'no_region', "
                + "lifecycle_changed_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), person.getId());

        String csv = exportService.exportPersons(
            null, null, null, false, MemberScope.allTeam(), null, false,
            null, false, null, false, null);

        assertTrue(csv.contains(",DISQUALIFIED,no_region,no_region"));
        assertTrue(!csv.contains(custom.label()));
    }

    @Test
    void unavailableAndOtherWorkspaceReasonCodesAreRejectedTheSameWay() {
        Person person = enterLifecycle(newPerson(newCompany()));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.DISQUALIFIED, "UNKNOWN_CODE", null)));

        createReasonInSiblingWorkspace("FOREIGN_ONLY", "Foreign only", false, 20);

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.DISQUALIFIED, "FOREIGN_ONLY", null)));
    }

    @Test
    void noteRequirementComesFromTheConfiguredReason() {
        DisqualificationReasonDto custom = reason(
            "LEGAL_BLOCK", "Legal block", true, 20);
        Person person = enterLifecycle(newPerson(newCompany()));

        assertThrows(BadRequestException.class, () -> lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.DISQUALIFIED, custom.code(), "  ")));
        assertEquals(PersonLifecycleStage.DISQUALIFIED,
            lifecycleService.updateLifecycle(person.getId(), request(
                PersonLifecycleStage.DISQUALIFIED, custom.code(), "Counsel declined"))
                .getLifecycleStage());
    }

    @Test
    void archivedCurrentReasonCanBeResubmittedForANoteOnlyEdit() {
        DisqualificationReasonDto custom = reason(
            "NO_REGION", "Outside our region", false, 20);
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED, custom.code(), "Initial note"));
        reasonService.archive(custom.id());

        Person annotated = lifecycleService.updateLifecycle(person.getId(), request(
            PersonLifecycleStage.DISQUALIFIED, custom.code(), "More context"));

        assertEquals("More context", annotated.getQualificationNotes());
        assertEquals(2, lifecycleService.getHistory(person.getId()).size());
        assertEquals("Outside our region",
            lifecycleService.getLifecycle(person.getId()).reasonLabel());
    }

    @Test
    void aConvertedContactCanStillBeAnnotatedAfterItsDealLinkIsRemoved() {
        Person person = enterLifecycle(newPerson(newCompany()));
        lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.QUALIFIED, null, null));
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 1), newCompany());
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), "champion");
        lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.CONVERTED, null, null));
        dealMapper.removePerson(workspace.getId(), deal.getId(), person.getId());

        Person annotated = lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.CONVERTED, null, "deal link cleaned up"));

        assertEquals(PersonLifecycleStage.CONVERTED, annotated.getLifecycleStage());
        assertEquals("deal link cleaned up", annotated.getQualificationNotes());
    }

    @Test
    void theBrowserFilterAndFacetsSeparateStagedContactsFromTheRest() {
        Company company = newCompany();
        Person staged = enterLifecycle(newPerson(company));
        Person unstaged = newPerson(company);

        List<Integer> working = personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), List.of(PersonLifecycleStage.NEW), false, null, false, null, false, false, null, 100, 0)
            .stream().map(Person::getId).toList();
        assertTrue(working.contains(staged.getId()));
        assertTrue(!working.contains(unstaged.getId()));

        List<Integer> outside = personService.getPersonsPage(null, null, null, null, null, false,
                MemberScope.allTeam(), null, true, null, false, null, false, false, null, 100, 0)
            .stream().map(Person::getId).toList();
        assertTrue(outside.contains(unstaged.getId()));
        assertTrue(!outside.contains(staged.getId()));

        assertEquals(1L, personService.countPersons(null, null, null, false,
            MemberScope.allTeam(), List.of(PersonLifecycleStage.NEW), false, null, false, null, false, false, null));

        Map<String, Long> facets = personService.countsByLifecycleStage().stream()
            .collect(java.util.stream.Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
        assertEquals(1L, facets.get(PersonLifecycleStage.NEW.name()));
        assertTrue(facets.getOrDefault("__none__", 0L) >= 1L);

        jdbcTemplate.update(
            "UPDATE person SET suspended_at = CURRENT_TIMESTAMP WHERE workspace_id = ? AND id = ?",
            workspace.getId(), staged.getId());
        Map<String, Long> facetsWithSuspended = personService.countsByLifecycleStage().stream()
            .collect(java.util.stream.Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
        assertEquals(facets.get(PersonLifecycleStage.NEW.name()),
            facetsWithSuspended.get(PersonLifecycleStage.NEW.name()),
            "facet counts must match the page filter, which keeps suspended contacts visible");
    }

    private Workspace siblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }

    private void createReasonInSiblingWorkspace(
            String code, String label, boolean requiresNote, int position) {
        User originalActor = currentUser;
        int originalWorkspaceId = workspace.getId();
        Workspace sibling = siblingWorkspace();
        workspaceMapper.addMember(sibling.getId(), provisioningOwner.getId(), "owner");
        User siblingMember = standaloneUser("sibling-member");
        workspaceMapper.addMember(sibling.getId(), siblingMember.getId(), "member");
        authenticateAs(provisioningOwner, sibling.getId());
        var role = roleService.createRole(
            sibling.getId(), provisioningOwner.getId(), "Sibling reason manager " + unique(),
            List.of("WORKSPACE_SETTINGS"));
        workspaceService.assignCustomRole(
            sibling.getId(), provisioningOwner.getId(), siblingMember.getId(), role.getId());
        authenticateAs(siblingMember, sibling.getId());
        reason(code, label, requiresNote, position);
        authenticateAs(originalActor, originalWorkspaceId);
    }

    private User standaloneUser(String qualifier) {
        String value = unique();
        User user = new User();
        user.setUsername(qualifier + "-" + value);
        user.setDisplayName(qualifier + " " + value);
        user.setEmail(qualifier + "-" + value + "@example.com");
        user.setPasswordHash("hash-" + value);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private Person enterLifecycle(Person person) {
        lifecycleService.updateLifecycle(
            person.getId(), request(PersonLifecycleStage.NEW, null, null));
        return person;
    }

    private Person reload(Person person) {
        return personMapper.getPersonById(workspace.getId(), person.getId());
    }

    private DisqualificationReasonDto reason(
            String code, String label, boolean requiresNote, int position) {
        DisqualificationReasonRequest request = new DisqualificationReasonRequest();
        request.setCode(code);
        request.setLabel(label);
        request.setRequiresNote(requiresNote);
        request.setPosition(position);
        return reasonService.create(request);
    }

    private static PersonLifecycleRequest request(
            PersonLifecycleStage stage, String reason, String note) {
        PersonLifecycleRequest request = new PersonLifecycleRequest();
        request.setStage(stage);
        request.setReason(reason);
        request.setNote(note);
        return request;
    }
}
