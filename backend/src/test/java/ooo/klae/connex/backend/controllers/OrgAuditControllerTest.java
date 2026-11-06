package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;

@ExtendWith(MockitoExtension.class)
class OrgAuditControllerTest {
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    @Mock private AuditService auditService;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AuthService authService;

    private OrgAuditController controller;

    @BeforeEach
    void setUp() {
        controller = new OrgAuditController(auditService, orgMemberService, authService);
        when(authService.getCurrentUser()).thenReturn(user(7));
    }

    @Test
    void orgAuditRequiresOrgAdminAndForwardsLimitAndOffset() {
        when(auditService.recentForOrg(3, 50, 10)).thenReturn(List.of());
        controller.orgAudit(3, 50, 10);
        verify(orgMemberService).requireOrgAdmin(3, 7);
        verify(auditService).recentForOrg(3, 50, 10);
    }

    @Test
    void exportOrgAuditRequiresOrgAdminAndForwardsLimitAndOffset() {
        when(auditService.exportRecentForOrg(3, 500, 10)).thenReturn("csv");
        ResponseEntity<byte[]> response = controller.exportOrgAudit(3, 500, 10, null);
        verify(orgMemberService).requireOrgAdmin(3, 7);
        verify(auditService).exportRecentForOrg(3, 500, 10);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/csv;charset=UTF-8", String.valueOf(response.getHeaders().getContentType()));
        assertEquals("attachment; filename=\"org-audit-log.csv\"",
            response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        byte[] body = response.getBody();
        assertArrayEquals(UTF8_BOM, new byte[] { body[0], body[1], body[2] });
    }

    @Test
    void selectedOrgExportRequiresOrgAdminAndUsesTheAuthorizedOrganization() {
        when(auditService.exportSelectedForOrg(3, List.of(91, 92), 200, 0)).thenReturn("csv");

        ResponseEntity<byte[]> response = controller.exportOrgAudit(3, 200, 0, List.of(91, 92));

        verify(orgMemberService).requireOrgAdmin(3, 7);
        verify(auditService).exportSelectedForOrg(3, List.of(91, 92), 200, 0);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void selectedOrgExportCannotBypassOrganizationAuthority() {
        doThrow(new ForbiddenException("Requires organization administration")).when(orgMemberService)
            .requireOrgAdmin(3, 7);

        assertThrows(ForbiddenException.class, () -> controller.exportOrgAudit(3, 200, 0, List.of(91)));

        verifyNoInteractions(auditService);
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
