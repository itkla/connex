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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    @Autowired private ObjectMapper objectMapper;
    private final List<Integer> createdUserIds = new ArrayList<>();
    private final List<Integer> createdOrganizationIds = new ArrayList<>();
    private final List<Integer> createdWorkspaceIds = new ArrayList<>();
    private final List<Integer> createdPersonIds = new ArrayList<>();
    private final List<Integer> createdCompanyIds = new ArrayList<>();

    @AfterEach
    void cleanUpCommittedFixtures() {
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM campaign_audience_export WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM campaign_audience_member WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM campaign_audience_snapshot WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM campaign WHERE workspace_id = ?", id));
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
    void disclosureIncludesConsentStateAndHistoryOnlyForTheSubject() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        Person otherSubject = newPerson(subjectWorkspace.getId());
        Workspace foreignWorkspace = newWorkspace(orgOwnedByCurrentUser().getId());
        Person foreignSubject = newPerson(foreignWorkspace.getId());
        seedConsentHistory(subjectWorkspace, subject, "consent-evidence");
        seedConsentHistory(subjectWorkspace, otherSubject, "other-subject");
        seedConsentHistory(foreignWorkspace, foreignSubject, "other-org");
        jdbcTemplate.update(
            "UPDATE person SET suspended_at = CURRENT_TIMESTAMP, provision_ceased_at = CURRENT_TIMESTAMP "
                + "WHERE workspace_id = ? AND id = ?", subjectWorkspace.getId(), subject.getId());

        JsonNode disclosure = disclosureFor(org, subjectWorkspace, subject);
        JsonNode state = disclosure.path("consentState");
        JsonNode history = disclosure.path("consentHistory");

        assertEquals(1, state.size());
        assertEquals("revoked", state.get(0).path("status").asString());
        assertEquals("consent-evidence-revoke", state.get(0).path("evidenceRef").asString());
        assertEquals("email", state.get(0).path("channel").asString());
        assertEquals("marketing", state.get(0).path("purpose").asString());
        assertEquals("self_service", state.get(0).path("source").asString());
        assertTrue(state.get(0).hasNonNull("capturedAt"));
        assertEquals(2, history.size());
        assertEquals("revoked", history.get(0).path("status").asString());
        assertEquals("consent-evidence-revoke", history.get(0).path("evidenceRef").asString());
        assertEquals("granted", history.get(1).path("status").asString());
        assertEquals("consent-evidence-grant", history.get(1).path("evidenceRef").asString());
        for (JsonNode event : history) {
            assertEquals(subjectWorkspace.getId(), event.path("workspaceId").asInt());
            assertEquals(subject.getId(), event.path("personId").asInt());
            assertEquals(state.get(0).path("id").asInt(), event.path("consentId").asInt());
            assertEquals(currentUser.getId(), event.path("createdById").asInt());
            assertEquals("self_service", event.path("source").asString());
            assertTrue(event.hasNonNull("createdAt"));
        }
    }

    @Test
    void disclosureIncludesSubjectExportMembershipAndConservativeOutcomes() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        Person otherSubject = newPerson(subjectWorkspace.getId());
        List<Integer> both = List.of(subject.getId(), otherSubject.getId());
        List<Integer> otherOnly = List.of(otherSubject.getId());
        AudienceFixture audience = seedAudience(subjectWorkspace.getId(), both);
        List<ExportScenario> scenarios = List.of(
            new ExportScenario("confirmed", "completed", "confirmed_delivery", null,
                both, both, 2, 0, "confirmed"),
            new ExportScenario("partial", "completed", "confirmed_delivery", null,
                both, both, 1, 1, "unconfirmed"),
            new ExportScenario("operator", "completed", "operator_delivered", null,
                both, both, 2, 0, "confirmed"),
            new ExportScenario("ambiguous", "running", "ambiguous", null,
                both, both, 0, 0, "unconfirmed"),
            new ExportScenario("staged", "running", null, null,
                both, both, 0, 0, "unconfirmed"),
            new ExportScenario("failed", "failed", "definite_no_side_effect", null,
                both, both, 0, 2, "not_delivered"),
            new ExportScenario("none-delivered", "failed", "confirmed_no_delivery", null,
                both, both, 0, 2, "not_delivered"),
            new ExportScenario("operator-not-delivered", "failed", "operator_not_delivered", null,
                both, both, 0, 2, "not_delivered"),
            new ExportScenario("late-conflict", "completed", "operator_delivered", "confirmed_no_delivery",
                both, both, 2, 0, "unconfirmed"),
            new ExportScenario("legacy", "completed", null, null,
                null, null, 2, 0, "unknown"),
            new ExportScenario("legacy-unknown-counts", "completed", "operator_delivered", null,
                null, null, null, null, "unknown"),
            new ExportScenario("excluded-before-push", "completed", "confirmed_delivery", null,
                both, otherOnly, 1, 1, "not_staged"),
            new ExportScenario("snapshot-only", "failed", "no_eligible_members", null,
                List.of(), List.of(), 0, 0, "not_staged"));
        scenarios.forEach(scenario -> seedAudienceExport(audience, scenario));
        AudienceFixture otherAudience = seedAudience(subjectWorkspace.getId(), otherOnly);
        seedAudienceExport(otherAudience, new ExportScenario("other-subject", "completed",
            "confirmed_delivery", null, otherOnly, otherOnly, 1, 0, "confirmed"));
        Workspace foreignWorkspace = newWorkspace(orgOwnedByCurrentUser().getId());
        AudienceFixture foreignAudience = seedAudience(foreignWorkspace.getId(), both);
        seedAudienceExport(foreignAudience, new ExportScenario("other-org", "completed",
            "confirmed_delivery", null, both, both, 2, 0, "confirmed"));

        JsonNode exports = disclosureFor(org, subjectWorkspace, subject).path("audienceExportEvidence");

        assertEquals(scenarios.size(), exports.size());
        for (int index = 0; index < scenarios.size(); index++) {
            ExportScenario expected = scenarios.get(scenarios.size() - index - 1);
            JsonNode evidence = exports.get(index);
            assertEquals(expected.destination(), evidence.path("externalListId").asString());
            assertEquals("http_list", evidence.path("connector").asString());
            assertEquals(audience.campaignId(), evidence.path("campaignId").asInt());
            assertEquals(audience.snapshotId(), evidence.path("snapshotId").asInt());
            assertEquals(subjectWorkspace.getId(), evidence.path("workspaceId").asInt());
            assertEquals("email", evidence.path("channel").asString());
            assertEquals("marketing", evidence.path("purpose").asString());
            assertEquals("included", evidence.path("snapshotMemberStatus").asString());
            assertEquals(expected.subjectOutcome(), evidence.path("subjectProvisionOutcome").asString());
            assertEquals(expected.status(), evidence.path("status").asString());
            assertJsonField(evidence, "outcomeClassification", expected.classification());
            assertJsonField(evidence, "lateOutcome", expected.lateOutcome());
            assertJsonField(evidence, "pushedCount", expected.pushedCount());
            assertJsonField(evidence, "failedCount", expected.failedCount());
            assertJsonField(evidence, "frozenMember", expected.frozen() == null
                ? null : expected.frozen().contains(subject.getId()));
            assertJsonField(evidence, "stagedForPush", expected.staged() == null
                ? null : expected.staged().contains(subject.getId()));
            assertEquals(currentUser.getId(), evidence.path("createdById").asInt());
            assertEquals(1, evidence.path("attempt").asInt());
            if ("running".equals(expected.status())) {
                assertTrue(evidence.hasNonNull("reconciliationRequiredAt"));
            }
            assertFalse(evidence.has("frozenMemberIdsJson"));
            assertFalse(evidence.has("pushedMemberIdsJson"));
        }
    }

    @Test
    void disclosureFindsExportMemberEvidenceWithoutDisclosingUnrelatedSnapshotRecords() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        Person otherSubject = newPerson(subjectWorkspace.getId());
        AudienceFixture audience = seedAudience(subjectWorkspace.getId(), List.of(otherSubject.getId()));
        List<Integer> subjectOnly = List.of(subject.getId());
        seedAudienceExport(audience, new ExportScenario("retained-request", "completed",
            "confirmed_delivery", null, null, subjectOnly, 1, 0, "confirmed"));
        seedAudienceExport(audience, new ExportScenario("retained-frozen", "failed",
            "no_eligible_members", null, subjectOnly, List.of(), 0, 1, "not_staged"));
        AudienceFixture unrelated = seedAudience(subjectWorkspace.getId(), subjectOnly);
        jdbcTemplate.update("UPDATE campaign_audience_snapshot SET record_type = 'company' WHERE id = ?",
            unrelated.snapshotId());
        jdbcTemplate.update("UPDATE campaign_audience_member SET record_type = 'company' WHERE snapshot_id = ?",
            unrelated.snapshotId());
        seedAudienceExport(unrelated, new ExportScenario("unrelated-company", "completed",
            null, null, null, null, 1, 0, "unknown"));

        JsonNode exports = disclosureFor(org, subjectWorkspace, subject).path("audienceExportEvidence");

        assertEquals(2, exports.size());
        assertEquals("retained-frozen", exports.get(0).path("externalListId").asString());
        assertEquals("not_staged", exports.get(0).path("subjectProvisionOutcome").asString());
        assertTrue(exports.get(0).path("frozenMember").asBoolean());
        assertFalse(exports.get(0).path("stagedForPush").asBoolean());
        assertEquals("retained-request", exports.get(1).path("externalListId").asString());
        assertEquals("confirmed", exports.get(1).path("subjectProvisionOutcome").asString());
        assertFalse(exports.get(1).has("frozenMember"));
        assertTrue(exports.get(1).path("stagedForPush").asBoolean());
        for (JsonNode evidence : exports) {
            assertFalse(evidence.has("snapshotMemberStatus"));
        }
    }

    @Test
    void disclosureIncludesExportEvidenceAfterAnOrganizationWorkspaceShareIsRevoked() {
        Organization org = orgOwnedByCurrentUser();
        Workspace subjectWorkspace = newWorkspace(org.getId());
        Person subject = newPerson(subjectWorkspace.getId());
        Workspace exportWorkspace = newWorkspace(org.getId());
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
            subject.getId(), exportWorkspace.getId(), currentUser.getId());
        List<Integer> subjectOnly = List.of(subject.getId());
        AudienceFixture audience = seedAudience(exportWorkspace.getId(), subjectOnly);
        seedAudienceExport(audience, new ExportScenario("shared-subject-list", "completed",
            "confirmed_delivery", null, subjectOnly, subjectOnly, 1, 0, "confirmed"));
        jdbcTemplate.update("DELETE FROM person_share WHERE person_id = ? AND workspace_id = ?",
            subject.getId(), exportWorkspace.getId());

        JsonNode disclosure = disclosureFor(org, subjectWorkspace, subject);
        JsonNode exports = disclosure.path("audienceExportEvidence");

        assertEquals(0, disclosure.path("thirdPartyProvisions").size());
        assertEquals(1, exports.size());
        assertEquals(exportWorkspace.getId(), exports.get(0).path("workspaceId").asInt());
        assertEquals("shared-subject-list", exports.get(0).path("externalListId").asString());
        assertEquals("confirmed", exports.get(0).path("subjectProvisionOutcome").asString());
        assertTrue(exports.get(0).path("stagedForPush").asBoolean());
    }

    private void assertJsonField(JsonNode evidence, String field, Object expected) {
        assertEquals(expected == null ? null : objectMapper.valueToTree(expected), evidence.get(field), field);
    }

    private JsonNode disclosureFor(Organization org, Workspace subjectWorkspace, Person subject) {
        DataSubjectRequestDto request = dataSubjectRequestService.create(
            org.getId(), currentUser.getId(), linkedRequest(subjectWorkspace.getId(), subject.getId()));
        return objectMapper.valueToTree(dataSubjectRequestService.disclosure(
            org.getId(), request.getId(), currentUser.getId()));
    }

    private void seedConsentHistory(Workspace subjectWorkspace, Person subject, String evidencePrefix) {
        jdbcTemplate.update("""
            INSERT INTO contact_channel_consent
              (workspace_id, person_id, channel, purpose, status, source, evidence_ref, captured_at)
            VALUES (?, ?, 'email', 'marketing', 'revoked', 'self_service', ?, '2026-01-03 00:00:00')
            """, subjectWorkspace.getId(), subject.getId(), evidencePrefix + "-revoke");
        for (String status : List.of("granted", "revoked")) {
            jdbcTemplate.update("""
                INSERT INTO contact_channel_consent_event
                  (workspace_id, consent_id, person_id, channel, purpose, status, source,
                   evidence_ref, created_by_id, created_at)
                SELECT workspace_id, id, person_id, channel, purpose, ?, source, ?, ?, ?
                FROM contact_channel_consent WHERE workspace_id = ? AND person_id = ?
                """, status, evidencePrefix + ("granted".equals(status) ? "-grant" : "-revoke"),
                currentUser.getId(), "granted".equals(status) ? "2026-01-02 00:00:00" : "2026-01-03 00:00:00",
                subjectWorkspace.getId(), subject.getId());
        }
    }

    private AudienceFixture seedAudience(int workspaceId, List<Integer> personIds) {
        String campaignName = "Disclosure campaign " + unique();
        jdbcTemplate.update("INSERT INTO campaign (workspace_id, name, type) VALUES (?, ?, 'email')",
            workspaceId, campaignName);
        Integer campaignId = jdbcTemplate.queryForObject(
            "SELECT id FROM campaign WHERE workspace_id = ? AND name = ?", Integer.class,
            workspaceId, campaignName);
        assertNotNull(campaignId);
        jdbcTemplate.update("""
            INSERT INTO campaign_audience_snapshot
              (workspace_id, campaign_id, version, record_type, definition_json, estimated_included,
               excluded_total, excluded_consent, excluded_suppressed, excluded_restricted)
            VALUES (?, ?, 1, 'person', '{}', ?, 0, 0, 0, 0)
            """, workspaceId, campaignId, personIds.size());
        Integer snapshotId = jdbcTemplate.queryForObject(
            "SELECT id FROM campaign_audience_snapshot WHERE workspace_id = ? AND campaign_id = ?",
            Integer.class, workspaceId, campaignId);
        assertNotNull(snapshotId);
        personIds.forEach(personId -> jdbcTemplate.update("""
            INSERT INTO campaign_audience_member (workspace_id, snapshot_id, record_type, record_id, status)
            VALUES (?, ?, 'person', ?, 'included')
            """, workspaceId, snapshotId, personId));
        return new AudienceFixture(workspaceId, campaignId, snapshotId, personIds.size());
    }

    private void seedAudienceExport(AudienceFixture audience, ExportScenario scenario) {
        jdbcTemplate.update("""
            INSERT INTO campaign_audience_export
              (workspace_id, campaign_id, snapshot_id, connector, external_list_id,
               frozen_member_ids_json, pushed_member_ids_json, status, total_members,
               pushed_count, failed_count, outcome_classification, late_outcome,
               reconciliation_required_at, lease_until, created_by_id)
            VALUES (?, ?, ?, 'http_list', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, audience.workspaceId(), audience.campaignId(), audience.snapshotId(), scenario.destination(),
            scenario.frozen() == null ? null : objectMapper.writeValueAsString(scenario.frozen()),
            scenario.staged() == null ? null : objectMapper.writeValueAsString(scenario.staged()),
            scenario.status(), scenario.frozen() == null ? audience.members() : scenario.frozen().size(),
            scenario.pushedCount(), scenario.failedCount(), scenario.classification(), scenario.lateOutcome(),
            "ambiguous".equals(scenario.classification()) ? "2026-01-03 00:00:00" : null,
            "staged".equals(scenario.destination()) ? "2026-01-03 00:00:00" : null,
            currentUser.getId());
    }

    private record AudienceFixture(int workspaceId, int campaignId, int snapshotId, int members) {}

    private record ExportScenario(String destination, String status, String classification, String lateOutcome,
            List<Integer> frozen, List<Integer> staged, Integer pushedCount, Integer failedCount,
            String subjectOutcome) {}

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
