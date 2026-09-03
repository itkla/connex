package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataSubjectRequestServiceTest extends AbstractServiceTest {
    @Autowired private DataSubjectRequestService dataSubjectRequestService;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DisqualificationReasonService reasonService;
    @Autowired private RoleService roleService;
    @Autowired private WorkspaceService workspaceService;
    private final List<Integer> createdUserIds = new ArrayList<>();
    private final List<Integer> createdOrganizationIds = new ArrayList<>();
    private final List<Integer> createdWorkspaceIds = new ArrayList<>();
    private final List<Integer> createdPersonIds = new ArrayList<>();
    private final List<Integer> createdCompanyIds = new ArrayList<>();

    @AfterEach
    void cleanUpCommittedFixtures() {
        createdPersonIds.forEach(id -> jdbcTemplate.update("DELETE FROM person WHERE id = ?", id));
        createdCompanyIds.forEach(id -> jdbcTemplate.update("DELETE FROM company WHERE id = ?", id));
        createdOrganizationIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM data_subject_request WHERE org_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM disqualification_reason WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM workspace_role_permission WHERE workspace_role_id IN "
                + "(SELECT id FROM workspace_role WHERE workspace_id = ?)", id));
        createdWorkspaceIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM workspace_role WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", id));
        createdOrganizationIds.forEach(id -> jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", id));
        createdOrganizationIds.forEach(id -> jdbcTemplate.update("DELETE FROM organization WHERE id = ?", id));
        createdUserIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", id));
        createdUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id));
        LocaleContextHolder.resetLocaleContext();
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        createdUserIds.add(user.getId());
        return user;
    }

    @Test
    void createDefaultsRequiresOrgAdminAndStepUpAndWritesMetadataOnlyAudit() {
        Organization org = orgOwnedByCurrentUser();
        DataSubjectRequestUpsertRequest request = request("disclosure");
        request.setSubjectEmail("subject@example.com");

        DataSubjectRequestDto created = dataSubjectRequestService.create(org.getId(), currentUser.getId(), request);

        assertNotNull(created.getId());
        assertEquals("received", created.getStatus());
        assertEquals(currentUser.getId(), created.getCreatedBy());
        assertNotNull(created.getReceivedAt());

        List<AuditLog> audit = auditLogMapper.findRecentByOrg(org.getId(), 50, 0);
        AuditLog createAudit = audit.stream()
            .filter(entry -> "appi.subject_request.create".equals(entry.getAction()))
            .findFirst()
            .orElseThrow();
        assertTrue(createAudit.getChanges().contains("requestId"));
        assertTrue(createAudit.getChanges().contains("requestType"));
        assertFalse(createAudit.getChanges().contains("Subject Name"));
        assertFalse(createAudit.getChanges().contains("subject@example.com"));

        assertThrows(ForbiddenException.class,
            () -> dataSubjectRequestService.create(org.getId(), newUser().getId(), request("disclosure")));

        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        attributes.getRequest().getSession().removeAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR);
        assertThrows(ForbiddenException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), request("disclosure")));
    }

    @Test
    void validatesClosedSetsRequiredNamesAndTimestampOrder() {
        Organization org = orgOwnedByCurrentUser();

        DataSubjectRequestUpsertRequest badType = request("portable-copy");
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), badType));

        DataSubjectRequestUpsertRequest badStatus = request("disclosure");
        badStatus.setStatus("pending");
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), badStatus));

        DataSubjectRequestUpsertRequest blankRequester = request("disclosure");
        blankRequester.setRequesterName(" ");
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), blankRequester));

        DataSubjectRequestUpsertRequest blankSubject = request("disclosure");
        blankSubject.setSubjectName("");
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), blankSubject));

        DataSubjectRequestUpsertRequest badOrder = request("disclosure");
        badOrder.setReceivedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        badOrder.setRespondedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), badOrder));

        DataSubjectRequestUpsertRequest badCloseOrder = request("disclosure");
        badCloseOrder.setReceivedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        badCloseOrder.setClosedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(org.getId(), currentUser.getId(), badCloseOrder));
    }

    @Test
    void validatesSubjectLinkAgainstTheOrganization() {
        Organization mine = orgOwnedByCurrentUser();
        Workspace mineWorkspace = newWorkspace(mine.getId());
        Person minePerson = newPerson(mineWorkspace.getId());

        DataSubjectRequestUpsertRequest oneSided = request("disclosure");
        oneSided.setSubjectWorkspaceId(mineWorkspace.getId());
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(mine.getId(), currentUser.getId(), oneSided));

        DataSubjectRequestUpsertRequest missingPerson = linkedRequest(mineWorkspace.getId(), Integer.MAX_VALUE);
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(mine.getId(), currentUser.getId(), missingPerson));

        Organization other = orgOwnedByCurrentUser();
        Workspace otherWorkspace = newWorkspace(other.getId());
        Person otherPerson = newPerson(otherWorkspace.getId());
        DataSubjectRequestUpsertRequest foreign = linkedRequest(otherWorkspace.getId(), otherPerson.getId());
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.create(mine.getId(), currentUser.getId(), foreign));

        jdbcTemplate.update(
            "UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?",
            mineWorkspace.getId());
        assertThrows(
            BadRequestException.class,
            () -> dataSubjectRequestService.create(
                mine.getId(),
                currentUser.getId(),
                linkedRequest(mineWorkspace.getId(), minePerson.getId())));
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_subject_request WHERE org_id = ?",
                Integer.class,
                mine.getId()));
        jdbcTemplate.update(
            "UPDATE workspace SET lifecycle_state = 'active' WHERE id = ?",
            mineWorkspace.getId());

        DataSubjectRequestDto linked = dataSubjectRequestService.create(
            mine.getId(), currentUser.getId(), linkedRequest(mineWorkspace.getId(), minePerson.getId()));
        assertEquals(minePerson.getId(), linked.getSubjectPersonId());
    }

    @Test
    void updateRejectsAnInitiallyInactiveLinkedWorkspaceWithoutWriting() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        DataSubjectRequestDto created = dataSubjectRequestService.create(
            org.getId(),
            currentUser.getId(),
            linkedRequest(subjectWorkspace.getId(), subject.getId()));
        jdbcTemplate.update(
            "UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?",
            subjectWorkspace.getId());
        DataSubjectRequestUpsertRequest update = request("disclosure");
        update.setStatus("closed");

        assertThrows(
            BadRequestException.class,
            () -> dataSubjectRequestService.update(
                org.getId(),
                created.getId(),
                currentUser.getId(),
                update));

        assertEquals(
            "received",
            jdbcTemplate.queryForObject(
                "SELECT status FROM data_subject_request WHERE org_id = ? AND id = ?",
                String.class,
                org.getId(),
                created.getId()));
    }

    @Test
    void disclosureRequiresALiveSubjectLink() {
        Organization org = orgOwnedByCurrentUser();
        DataSubjectRequestDto unlinked = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), verifiedRequest("disclosure"));
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.disclosure(org.getId(), unlinked.getId(), currentUser.getId()));

        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        DataSubjectRequestDto linked = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), linkedRequest(subjectWorkspace.getId(), subject.getId()));
        jdbcTemplate.update("DELETE FROM person WHERE workspace_id = ? AND id = ?",
            subjectWorkspace.getId(), subject.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> dataSubjectRequestService.disclosure(org.getId(), linked.getId(), currentUser.getId()));
    }

    @Test
    void disclosureRequiresADisclosureTypeRequestWithRecordedIdentityVerification() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());

        DataSubjectRequestUpsertRequest correction = linkedRequest(subjectWorkspace.getId(), subject.getId());
        correction.setRequestType("correction");
        DataSubjectRequestDto wrongType = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), correction);
        DataSubjectRequestUpsertRequest unverifiedBody = linkedRequest(subjectWorkspace.getId(), subject.getId());
        unverifiedBody.setIdentityVerifiedAt(null);
        DataSubjectRequestDto unverified = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), unverifiedBody);
        DataSubjectRequestDto verified = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), linkedRequest(subjectWorkspace.getId(), subject.getId()));

        assertEquals(subject.getId(), dataSubjectRequestService.disclosure(
            org.getId(), verified.getId(), currentUser.getId()).getPerson().getId());
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.disclosure(org.getId(), wrongType.getId(), currentUser.getId()));
        assertThrows(BadRequestException.class,
            () -> dataSubjectRequestService.disclosure(org.getId(), unverified.getId(), currentUser.getId()));
    }

    @Test
    void disclosureIncludesCurrentAndHistoricalIdentityProvenance() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, source_channel, source_external_id, source_row_ref,
              acquired_at, purpose_of_use_code, superseded_at
            )
            VALUES
              (?, ?, 'email', 'old@example.com', 'old@example.com',
               'csv_import', 'person.email', 'crm-17', 'csv-row:4',
               '2025-01-02 03:04:05', 'relationship_management', '2026-01-02 03:04:05'),
              (?, ?, 'email', 'current@example.com', 'current@example.com',
               'interactive_update', 'person.email', NULL, NULL,
               '2026-01-02 03:04:05', NULL, NULL)
            """,
            subjectWorkspace.getId(),
            subject.getId(),
            subjectWorkspace.getId(),
            subject.getId());
        DataSubjectRequestDto request = dataSubjectRequestService.create(
            org.getId(),
            currentUser.getId(),
            linkedRequest(subjectWorkspace.getId(), subject.getId()));

        var identities = dataSubjectRequestService.disclosure(
            org.getId(), request.getId(), currentUser.getId()).getIdentities();

        assertEquals(2, identities.size());
        assertEquals("current@example.com", identities.getFirst().getValue());
        assertNull(identities.getFirst().getSupersededAt());
        assertEquals("old@example.com", identities.getLast().getValue());
        assertEquals("csv_import", identities.getLast().getSourceSystem());
        assertEquals("person.email", identities.getLast().getSourceChannel());
        assertEquals("crm-17", identities.getLast().getSourceExternalId());
        assertEquals("csv-row:4", identities.getLast().getSourceRowRef());
        assertEquals(
            "relationship_management",
            identities.getLast().getPurposeOfUseCode());
        assertNotNull(identities.getLast().getSupersededAt());
    }

    @Test
    void disclosureIncludesConfiguredAndLocalizedDisqualificationLabels() {
        Organization org = orgOwnedByCurrentUser();
        ReasonWorkspace subjectFixture = reasonWorkspace(
            org.getId(), "NO_REGION", "Outside our region", true);
        ReasonWorkspace collisionFixture = reasonWorkspace(
            org.getId(), "NO_REGION", "Different workspace label", false);
        Workspace subjectWorkspace = subjectFixture.workspace();
        Person customSubject = newPerson(subjectWorkspace.getId());
        setDisqualification(
            subjectWorkspace, customSubject, "NO_REGION", subjectFixture.actor().getId());
        authenticateAs(currentUser, subjectWorkspace.getId());
        DataSubjectRequestDto customRequest = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(),
            linkedRequest(subjectWorkspace.getId(), customSubject.getId()));

        var customDisclosure = dataSubjectRequestService.disclosure(
            org.getId(), customRequest.getId(), currentUser.getId());
        assertEquals("Outside our region", customDisclosure.getPerson().getDisqualifiedReasonLabel());
        assertEquals("Outside our region", customDisclosure.getLifecycleHistory().getFirst().getReasonLabel());
        assertTrue(!"Different workspace label".equals(
            customDisclosure.getPerson().getDisqualifiedReasonLabel()));
        assertEquals("Different workspace label",
            jdbcTemplate.queryForObject(
                "SELECT label FROM disqualification_reason WHERE workspace_id = ? AND code = ?",
                String.class, collisionFixture.workspace().getId(), "NO_REGION"));

        Person builtInSubject = newPerson(subjectWorkspace.getId());
        setDisqualification(
            subjectWorkspace, builtInSubject, "NO_FIT", subjectFixture.actor().getId());
        DataSubjectRequestDto builtInRequest = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(),
            linkedRequest(subjectWorkspace.getId(), builtInSubject.getId()));
        LocaleContextHolder.setLocale(Locale.JAPANESE);

        var builtInDisclosure = dataSubjectRequestService.disclosure(
            org.getId(), builtInRequest.getId(), currentUser.getId());
        assertEquals("適合しない", builtInDisclosure.getPerson().getDisqualifiedReasonLabel());
        assertEquals("適合しない", builtInDisclosure.getLifecycleHistory().getFirst().getReasonLabel());
    }

    private void setDisqualification(
            Workspace subjectWorkspace, Person subject, String code, int changedById) {
        jdbcTemplate.update(
            "UPDATE person SET lifecycle_stage = 'DISQUALIFIED', disqualified_reason = ? "
                + "WHERE workspace_id = ? AND id = ?",
            code, subjectWorkspace.getId(), subject.getId());
        jdbcTemplate.update(
            "INSERT INTO person_lifecycle_history "
                + "(workspace_id, person_id, from_stage, to_stage, reason, changed_by_id) "
                + "VALUES (?, ?, 'WORKING', 'DISQUALIFIED', ?, ?)",
            subjectWorkspace.getId(), subject.getId(), code, changedById);
    }

    private ReasonWorkspace reasonWorkspace(
            int orgId, String code, String label, boolean archive) {
        Workspace reasonWorkspace = newWorkspace(orgId);
        workspaceMapper.addMember(reasonWorkspace.getId(), currentUser.getId(), "owner");
        User actor = standaloneUser("subject-reason-member");
        workspaceMapper.addMember(reasonWorkspace.getId(), actor.getId(), "member");
        authenticateAs(currentUser, reasonWorkspace.getId());
        var role = roleService.createRole(
            reasonWorkspace.getId(), currentUser.getId(), "Subject reason manager " + unique(),
            List.of("WORKSPACE_SETTINGS", "PERSON_UPDATE"));
        workspaceService.assignCustomRole(
            reasonWorkspace.getId(), currentUser.getId(), actor.getId(), role.getId());
        authenticateAs(actor, reasonWorkspace.getId());
        DisqualificationReasonRequest request = new DisqualificationReasonRequest();
        request.setCode(code);
        request.setLabel(label);
        request.setRequiresNote(false);
        request.setPosition(20);
        var reason = reasonService.create(request);
        if (archive) {
            reasonService.archive(reason.id());
        }
        return new ReasonWorkspace(reasonWorkspace, actor);
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
        createdUserIds.add(user.getId());
        return user;
    }

    private record ReasonWorkspace(Workspace workspace, User actor) {}

    @Test
    void disclosureAuditCommitsOutsideAnAmbientCallerTransaction() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        DataSubjectRequestDto request = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), linkedRequest(subjectWorkspace.getId(), subject.getId()));
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(status -> {
            dataSubjectRequestService.disclosure(org.getId(), request.getId(), currentUser.getId());
            status.setRollbackOnly();
        });

        long disclosureAudits = auditLogMapper.findRecentByOrg(org.getId(), 50, 0).stream()
            .filter(entry -> "appi.subject_request.disclosure".equals(entry.getAction()))
            .count();
        assertEquals(1, disclosureAudits);
    }

    @Test
    void updateAuditsFieldLevelChangesWithoutSubjectPii() {
        Organization org = orgOwnedByCurrentUser();
        DataSubjectRequestDto created = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), request("disclosure"));

        DataSubjectRequestUpsertRequest update = request("disclosure");
        update.setStatus("in_progress");
        update.setSubjectEmail("subject@example.com");
        dataSubjectRequestService.update(org.getId(), created.getId(), currentUser.getId(), update);

        AuditLog updateAudit = auditLogMapper.findRecentByOrg(org.getId(), 50, 0).stream()
            .filter(entry -> "appi.subject_request.update".equals(entry.getAction()))
            .findFirst()
            .orElseThrow();
        assertTrue(updateAudit.getChanges().contains("fields"));
        assertTrue(updateAudit.getChanges().contains("in_progress"));
        assertFalse(updateAudit.getChanges().contains("Subject Name"));
        assertFalse(updateAudit.getChanges().contains("subject@example.com"));
    }

    @Test
    void listGetAndStatusFilterStayOrgScoped() {
        Organization mine = orgOwnedByCurrentUser();
        Organization other = orgOwnedByCurrentUser();
        DataSubjectRequestDto mineRequest = dataSubjectRequestService.create(
            mine.getId(), currentUser.getId(), request("correction"));
        DataSubjectRequestUpsertRequest otherBody = request("cease_use");
        otherBody.setStatus("in_progress");
        DataSubjectRequestDto otherRequest = dataSubjectRequestService.create(
            other.getId(), currentUser.getId(), otherBody);

        assertEquals(mineRequest.getId(), dataSubjectRequestService.get(
            mine.getId(), mineRequest.getId(), currentUser.getId()).getId());
        assertThrows(ResourceNotFoundException.class, () -> dataSubjectRequestService.get(
            mine.getId(), otherRequest.getId(), currentUser.getId()));
        assertEquals(List.of(mineRequest.getId()), dataSubjectRequestService.list(
            mine.getId(), currentUser.getId(), "received", 50, 0).stream()
            .map(DataSubjectRequestDto::getId)
            .toList());
        assertTrue(dataSubjectRequestService.list(
            mine.getId(), currentUser.getId(), "closed", 50, 0).isEmpty());
    }

    private Organization orgOwnedByCurrentUser() {
        Organization org = new Organization();
        org.setName("Subject Request Org " + unique());
        org.setSlug("subject-request-org-" + unique());
        organizationMapper.insert(org);
        createdOrganizationIds.add(org.getId());
        orgMemberService.addFoundingOwner(org.getId(), currentUser.getId());
        return org;
    }

    private Workspace newWorkspace(int orgId) {
        Workspace subjectWorkspace = new Workspace();
        subjectWorkspace.setOrgId(orgId);
        subjectWorkspace.setName("Subject Workspace " + unique());
        subjectWorkspace.setSlug("subject-workspace-" + unique());
        workspaceMapper.insert(subjectWorkspace);
        createdWorkspaceIds.add(subjectWorkspace.getId());
        return subjectWorkspace;
    }

    private Person newPerson(int workspaceId) {
        Company company = new Company();
        company.setWorkspaceId(workspaceId);
        company.setName("Subject Company " + unique());
        companyMapper.insert(company);
        createdCompanyIds.add(company.getId());
        Person person = new Person();
        person.setWorkspaceId(workspaceId);
        person.setName("Subject Name " + unique());
        person.setEmail(unique() + "@example.com");
        person.setCompany(company);
        personMapper.insert(person);
        createdPersonIds.add(person.getId());
        return person;
    }

    private static DataSubjectRequestUpsertRequest linkedRequest(int workspaceId, int personId) {
        DataSubjectRequestUpsertRequest request = verifiedRequest("disclosure");
        request.setSubjectWorkspaceId(workspaceId);
        request.setSubjectPersonId(personId);
        return request;
    }

    private static DataSubjectRequestUpsertRequest verifiedRequest(String requestType) {
        DataSubjectRequestUpsertRequest request = request(requestType);
        request.setReceivedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        request.setIdentityVerifiedAt(LocalDateTime.of(2026, 1, 3, 0, 0));
        return request;
    }

    private static DataSubjectRequestUpsertRequest request(String requestType) {
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        request.setRequestType(requestType);
        request.setRequesterName("Requester Name");
        request.setSubjectName("Subject Name");
        return request;
    }
}
