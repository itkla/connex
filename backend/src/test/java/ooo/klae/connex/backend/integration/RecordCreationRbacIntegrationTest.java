package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonCreateRequestDto;
import ooo.klae.connex.backend.dto.recordcreation.GuidedPersonRecordDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateUseDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.services.AbstractServiceTest;
import ooo.klae.connex.backend.services.GuidedRecordCreationService;
import ooo.klae.connex.backend.services.RecordCreationPresetService;

@UnenrolledPrivilegedFixture
class RecordCreationRbacIntegrationTest extends AbstractServiceTest {
    @Autowired private RecordCreationPresetService presetService;
    @Autowired private GuidedRecordCreationService guidedService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void permissionRevocationBetweenCatalogAndSubmitFailsUnderLockedMembership() {
        var catalog = presetService.persons(RecordCreationEntryPoint.quick_create, null);
        int before = personCount();
        assertEquals(1, workspaceMapper.removeMember(workspace.getId(), currentUser.getId()));
        GuidedPersonCreateRequestDto request = new GuidedPersonCreateRequestDto(
            new GuidedPersonRecordDto(
                "Revoked", null, null, null, null, null, null, null, null),
            new RecordCreationTemplateUseDto(
                catalog.selectedTemplateId(),
                catalog.templates().stream()
                    .filter(template -> template.id().equals(catalog.selectedTemplateId()))
                    .findFirst().orElseThrow().version(),
                catalog.setRevision(),
                RecordCreationEntryPoint.quick_create,
                new RecordCreationContextDto(null)),
            Map.of(),
            List.of());

        assertThrows(ForbiddenException.class, () -> guidedService.createPerson(request));

        assertEquals(before, personCount());
    }

    @Test
    void presetAlsoRequiresTheCurrentMembershipPermission() {
        assertEquals(1, workspaceMapper.removeMember(workspace.getId(), currentUser.getId()));

        assertThrows(
            ForbiddenException.class,
            () -> presetService.persons(RecordCreationEntryPoint.record_list, null));
    }

    private int personCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());
    }
}
