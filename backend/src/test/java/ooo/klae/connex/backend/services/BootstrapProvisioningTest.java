package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RegisterDto;

/**
 * {@code AuthService.provisionBootstrapOwner} (used by the startup bootstrap) must create the user
 * and an owned workspace. Unlike self-service registration it is ungated by the
 * self-service-creation flag — verified structurally by {@code createWorkspaceForBootstrap} (#81 Phase 2).
 */
class BootstrapProvisioningTest extends AbstractServiceTest {

    @Autowired private AuthService authService;

    @Test
    void provisionBootstrapOwner_createsUserAndOwnedWorkspace() {
        String s = unique();
        RegisterDto request = new RegisterDto();
        request.setUsername("boot_" + s);
        request.setEmail(s + "@example.com");
        request.setDisplayName("Boot " + s);
        request.setPassword("Aa1!aaaa");
        request.setTimezone("UTC");

        User owner = authService.provisionBootstrapOwner(request);

        assertNotEquals(0, owner.getId());
        List<Workspace> owned = workspaceMapper.getWorkspacesForUser(owner.getId());
        assertFalse(owned.isEmpty(), "the bootstrap owner must get a workspace");
        assertEquals("owner", workspaceMapper.getRole(owned.getFirst().getId(), owner.getId()));
    }
}
