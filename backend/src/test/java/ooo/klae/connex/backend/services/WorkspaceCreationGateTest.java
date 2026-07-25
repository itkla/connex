package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * The invite-only workspace-creation seam (the production default for the guided-pilot GTM):
 * when {@code connex.workspaces.allow-self-service-creation} is {@code false}, the create path
 * is refused and registration still succeeds but provisions no workspace — users onboard by
 * accepting an invite instead. The enabled path is covered by {@code WorkspaceServiceTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "connex.workspaces.allow-self-service-creation=false")
@Transactional
class WorkspaceCreationGateTest {

    @Autowired private AuthService authService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceMapper workspaceMapper;

    @Test
    void createWorkspace_refusedWhenSelfServiceCreationDisabled() {
        User created = authService.register(dto("gate_user", "gate.user@example.com"), true);

        assertThrows(ForbiddenException.class,
            () -> workspaceService.createWorkspace("Gated WS", created.getId()));
    }

    @Test
    void register_succeedsWithoutProvisioningAWorkspace() {
        User created = authService.register(dto("gate_nows", "gate.nows@example.com"), true);

        assertNotNull(created.getId());
        assertTrue(workspaceMapper.getMembershipsForUser(created.getId()).isEmpty(),
            "invite-only instances must not auto-provision a workspace on registration");
    }

    private static RegisterDto dto(String username, String email) {
        RegisterDto request = new RegisterDto();
        request.setUsername(username);
        request.setEmail(email);
        request.setDisplayName("T " + username);
        request.setPassword("Aa1!aaaa");
        request.setTimezone("UTC");
        return request;
    }
}
