package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DisqualificationReasonDto;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/** Resolved fallback, materialisation, archival, uniqueness, audit, and RBAC behavior (#559). */
class DisqualificationReasonServiceTest extends AbstractServiceTest {
    @Autowired DisqualificationReasonService reasonService;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired jakarta.validation.Validator beanValidator;
    @Autowired GlobalExceptionHandler exceptionHandler;
    private User settingsOwner;

    @BeforeEach
    void useFreshWorkspaceWithCustomRoleMember() {
        settingsOwner = currentUser;
        Organization organization = new Organization();
        organization.setName("Reason service " + unique());
        organization.setSlug("reason-service-" + unique());
        organizationMapper.insert(organization);
        Workspace fresh = new Workspace();
        fresh.setName("Reason service " + unique());
        fresh.setSlug("reason-service-" + unique());
        fresh.setOrgId(organization.getId());
        workspaceMapper.insert(fresh);
        workspace = fresh;
        workspaceMapper.addMember(workspace.getId(), settingsOwner.getId(), "owner");
        User settingsMember = newUser();
        authenticateAs(settingsOwner, workspace.getId());
        var settingsRole = roleService.createRole(
            workspace.getId(), settingsOwner.getId(), "Reason manager " + unique(),
            List.of("WORKSPACE_SETTINGS"));
        workspaceService.assignCustomRole(
            workspace.getId(), settingsOwner.getId(), settingsMember.getId(), settingsRole.getId());
        currentUser = settingsMember;
        authenticateAs(currentUser, workspace.getId());
    }

    @Test
    void untouchedWorkspaceGetsExactlyTheNineBuiltInsWithoutPersistingRows() {
        List<DisqualificationReasonDto> reasons = reasonService.getActive();

        assertEquals(9, reasons.size());
        assertEquals(PersonDisqualificationReason.NO_BUDGET, reasons.getFirst().code());
        assertTrue(reasons.stream().allMatch(DisqualificationReasonDto::builtIn));
        assertTrue(reasons.stream().allMatch(reason -> reason.label() == null));
        assertTrue(reasons.stream()
            .filter(reason -> reason.code().equals(PersonDisqualificationReason.OTHER))
            .findFirst().orElseThrow().requiresNote());
        assertEquals(0, countRows());
    }

    @Test
    void firstEditMaterializesEveryBuiltInAndRelabelsTheSelectedOne() {
        DisqualificationReasonDto fallback = reasonService.getAll().getFirst();
        DisqualificationReasonDto updated = reasonService.update(
            fallback.id(), request(fallback.code(), "Budget unavailable", false, 4));

        assertEquals(9, countRows());
        assertEquals("Budget unavailable", updated.label());
        assertEquals(4, updated.position());
        assertTrue(updated.builtIn());
        assertEquals(8, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM disqualification_reason "
                + "WHERE workspace_id = ? AND code <> ? AND label IS NULL",
            Integer.class, workspace.getId(), fallback.code()));
        assertEquals(1, auditCount("disqualification.reason.update"));
    }

    @Test
    void customCodesAreUniqueWithinTheWorkspaceAtTheServiceAndDatabaseAndCannotBeRenamed() {
        DisqualificationReasonDto custom = reasonService.create(
            request("LEGAL_BLOCK", "Legal block", true, 20));

        DuplicateResourceException serviceDuplicate = assertThrows(
            DuplicateResourceException.class,
            () -> reasonService.create(request("LEGAL_BLOCK", "Duplicate", false, 21)));
        assertEquals(HttpStatus.CONFLICT,
            exceptionHandler.duplicate(serviceDuplicate).getStatusCode());
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
            "INSERT INTO disqualification_reason "
                + "(workspace_id, code, label, requires_note, position, built_in) "
                + "VALUES (?, ?, ?, FALSE, ?, FALSE)",
            workspace.getId(), "LEGAL_BLOCK", "Direct duplicate", 22));
        assertThrows(BadRequestException.class, () -> reasonService.update(
            custom.id(), request("NEW_CODE", "Legal block", true, 20)));
        assertEquals(1, auditCount("disqualification.reason.create"));
    }

    @Test
    void archivedReasonsLeaveTheirLabelsResolvableAndCanBeRestored() {
        DisqualificationReasonDto custom = reasonService.create(
            request("NO_REGION", "Outside our region", false, 30));

        reasonService.archive(custom.id());

        assertTrue(reasonService.getActive().stream()
            .noneMatch(reason -> reason.code().equals(custom.code())));
        DisqualificationReasonDto archived = reasonService.resolve(workspace.getId(), custom.code());
        assertEquals("Outside our region", archived.label());
        assertTrue(archived.archivedAt() != null);

        reasonService.restore(custom.id());
        assertNull(reasonService.resolve(workspace.getId(), custom.code()).archivedAt());
        assertEquals(1, auditCount("disqualification.reason.archive"));
        assertEquals(1, auditCount("disqualification.reason.restore"));
    }

    @Test
    void memberWithoutWorkspaceSettingsCanReadButCannotUseAnyMutator() {
        DisqualificationReasonDto active = reasonService.create(
            request("ACTIVE_REASON", "Active reason", false, 20));
        DisqualificationReasonDto archived = reasonService.create(
            request("ARCHIVED_REASON", "Archived reason", false, 21));
        reasonService.archive(archived.id());
        User member = newUser();
        authenticateAs(settingsOwner, workspace.getId());
        var role = roleService.createRole(
            workspace.getId(), settingsOwner.getId(), "Reason reader " + unique(), List.of("PERSON_UPDATE"));
        workspaceService.assignCustomRole(
            workspace.getId(), settingsOwner.getId(), member.getId(), role.getId());
        authenticateAs(member, workspace.getId());

        assertEquals(10, reasonService.getActive().size());
        assertThrows(ForbiddenException.class, () -> reasonService.create(
            request("BLOCKED", "Blocked", false, 10)));
        assertThrows(ForbiddenException.class, () -> reasonService.update(
            active.id(), request(active.code(), "Changed", false, 20)));
        assertThrows(ForbiddenException.class, () -> reasonService.archive(active.id()));
        assertThrows(ForbiddenException.class, () -> reasonService.restore(archived.id()));
        assertEquals("Active reason", reasonService.resolve(workspace.getId(), active.code()).label());
        assertTrue(reasonService.resolve(workspace.getId(), active.code()).archivedAt() == null);
        assertTrue(reasonService.resolve(workspace.getId(), archived.code()).archivedAt() != null);
    }

    @Test
    void auditPayloadKeepsCallerAuthoredLabelsOutOfEveryReasonEvent() {
        String sensitiveLabel = "alex.personal@example.com";
        DisqualificationReasonDto reason = reasonService.create(
            request("PRIVATE_CONTEXT", sensitiveLabel, false, 20));
        reasonService.update(
            reason.id(), request(reason.code(), "Jordan private account", true, 21));
        reasonService.archive(reason.id());
        reasonService.restore(reason.id());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT target_label, summary, changes FROM audit_log "
                + "WHERE workspace_id = ? AND action LIKE 'disqualification.reason.%'",
            workspace.getId());
        assertEquals(4, rows.size());
        for (Map<String, Object> row : rows) {
            String serialized = row.values().toString();
            assertTrue(serialized.contains("PRIVATE_CONTEXT"));
            assertTrue(!serialized.contains(sensitiveLabel));
            assertTrue(!serialized.contains("Jordan private account"));
        }
    }

    @Test
    void anotherWorkspaceCannotUpdateArchiveOrRestoreAReasonById() {
        DisqualificationReasonDto local = reasonService.create(
            request("NO_REGION", "Outside our region", false, 20));
        Workspace other = siblingWorkspace();
        User otherOwner = newUser();
        workspaceMapper.addMember(other.getId(), otherOwner.getId(), "owner");
        authenticateAs(otherOwner, other.getId());

        assertThrows(ResourceNotFoundException.class, () -> reasonService.update(
            local.id(), request(local.code(), "Changed", false, 20)));
        assertThrows(ResourceNotFoundException.class, () -> reasonService.archive(local.id()));
        assertThrows(ResourceNotFoundException.class, () -> reasonService.restore(local.id()));
    }

    @Test
    void codeAndPositionValidationRejectsValuesThatCannotBeStoredSafely() {
        DisqualificationReasonRequest canonicalRequest =
            request("OTHER_REASON", "Canonical", false, 1);
        assertTrue(beanValidator.validate(canonicalRequest).isEmpty());
        for (String code : List.of("other", " OTHER ", "ÖTHER")) {
            assertTrue(beanValidator.validate(request(code, "Invalid", false, 1)).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("code")));
        }

        DisqualificationReasonDto canonical = reasonService.create(canonicalRequest);
        assertEquals("OTHER_REASON", canonical.code());
        assertThrows(BadRequestException.class, () -> reasonService.create(
            request("other", "Lower case", false, 1)));
        assertThrows(BadRequestException.class, () -> reasonService.update(
            canonical.id(), request("other_reason", "Lower case update", false, 1)));
        assertThrows(BadRequestException.class, () -> reasonService.create(
            request(" OTHER ", "Padded", false, 1)));
        assertThrows(BadRequestException.class, () -> reasonService.create(
            request("ÖTHER", "Accented", false, 1)));
        assertThrows(BadRequestException.class, () -> reasonService.create(
            request("NOT-VALID", "Invalid", false, 1)));
        assertThrows(BadRequestException.class, () -> reasonService.create(
            request("VALID", "Invalid position", false, -1)));
        assertThrows(BadRequestException.class, () -> reasonService.create(
            request("CUSTOM", "   ", false, 1)));
    }

    private int countRows() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM disqualification_reason WHERE workspace_id = ?",
            Integer.class, workspace.getId());
    }

    private int auditCount(String action) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = ?",
            Integer.class, workspace.getId(), action);
    }

    private Workspace siblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }

    private static DisqualificationReasonRequest request(
            String code, String label, boolean requiresNote, int position) {
        DisqualificationReasonRequest request = new DisqualificationReasonRequest();
        request.setCode(code);
        request.setLabel(label);
        request.setRequiresNote(requiresNote);
        request.setPosition(position);
        return request;
    }
}
