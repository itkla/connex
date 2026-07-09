package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.dto.AppiIncidentDto;
import ooo.klae.connex.backend.dto.AppiIncidentRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

class AppiIncidentServiceTest extends AbstractServiceTest {
    @Autowired private AppiIncidentService appiIncidentService;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private OrganizationMapper organizationMapper;

    @Test
    void createDefaultsAndRequiresOrgAdmin() {
        Organization org = orgOwnedByCurrentUser();
        AppiIncidentRequest request = request("Suspected export leak");

        AppiIncidentDto created = appiIncidentService.create(org.getId(), currentUser.getId(), request);

        assertNotNull(created.getId());
        assertEquals("triage", created.getStatus());
        assertEquals("undetermined", created.getSeverity());
        assertFalse(created.isReportable());
        assertEquals(currentUser.getId(), created.getCreatedBy());

        assertThrows(ForbiddenException.class,
            () -> appiIncidentService.create(org.getId(), newUser().getId(), request("Blocked")));

        AppiIncidentRequest blankTitle = request(" ");
        assertThrows(BadRequestException.class,
            () -> appiIncidentService.create(org.getId(), currentUser.getId(), blankTitle));
    }

    @Test
    void updateValidatesStatusSeverityAndWindow() {
        Organization org = orgOwnedByCurrentUser();
        AppiIncidentDto created = appiIncidentService.create(org.getId(), currentUser.getId(), request("Initial"));
        AppiIncidentRequest update = request("Contained");
        update.setStatus("contained");
        update.setSeverity("high");
        update.setReportable(true);

        AppiIncidentDto updated = appiIncidentService.update(org.getId(), created.getId(), currentUser.getId(), update);

        assertEquals("contained", updated.getStatus());
        assertEquals("high", updated.getSeverity());
        assertEquals(currentUser.getId(), updated.getUpdatedBy());

        AppiIncidentRequest badStatus = request("Bad");
        badStatus.setStatus("open-ended");
        assertThrows(BadRequestException.class,
            () -> appiIncidentService.update(org.getId(), created.getId(), currentUser.getId(), badStatus));

        AppiIncidentRequest badWindow = request("Bad window");
        badWindow.setOccurredFrom(LocalDateTime.now());
        badWindow.setOccurredTo(LocalDateTime.now().minusHours(1));
        assertThrows(BadRequestException.class,
            () -> appiIncidentService.create(org.getId(), currentUser.getId(), badWindow));
    }

    @Test
    void listAndGetAreOrgScoped() {
        Organization mine = orgOwnedByCurrentUser();
        Organization other = orgOwnedByCurrentUser();
        AppiIncidentDto mineIncident = appiIncidentService.create(mine.getId(), currentUser.getId(), request("Mine"));
        AppiIncidentDto otherIncident = appiIncidentService.create(other.getId(), currentUser.getId(), request("Other"));

        assertEquals(mineIncident.getId(), appiIncidentService.get(mine.getId(), mineIncident.getId(),
            currentUser.getId()).getId());
        assertThrows(ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class,
            () -> appiIncidentService.get(mine.getId(), otherIncident.getId(), currentUser.getId()));
    }

    private Organization orgOwnedByCurrentUser() {
        Organization org = new Organization();
        org.setName("Incident Org " + unique());
        org.setSlug("incident-org-" + unique());
        organizationMapper.insert(org);
        orgMemberService.addFoundingOwner(org.getId(), currentUser.getId());
        return org;
    }

    private static AppiIncidentRequest request(String title) {
        AppiIncidentRequest request = new AppiIncidentRequest();
        request.setTitle(title);
        return request;
    }
}
