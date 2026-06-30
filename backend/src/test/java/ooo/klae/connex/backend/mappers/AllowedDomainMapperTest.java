package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Workspace;

/**
 * Per-workspace allowed-domain storage: idempotent add, membership query, removal, and strict
 * workspace scoping (a domain allowed in one workspace is not allowed in another) (#81 Phase 4).
 */
class AllowedDomainMapperTest extends AbstractMapperTest {

    @Autowired private AllowedDomainMapper allowedDomainMapper;

    @Test
    void add_isIdempotentAndQueryable() {
        allowedDomainMapper.add(workspace.getId(), "acme.com");
        allowedDomainMapper.add(workspace.getId(), "acme.com");

        assertEquals(1, allowedDomainMapper.countByWorkspace(workspace.getId()));
        assertTrue(allowedDomainMapper.isAllowed(workspace.getId(), "acme.com"));
        assertFalse(allowedDomainMapper.isAllowed(workspace.getId(), "other.com"));
        assertTrue(allowedDomainMapper.findByWorkspace(workspace.getId()).contains("acme.com"));
    }

    @Test
    void remove_dropsDomain() {
        allowedDomainMapper.add(workspace.getId(), "acme.com");
        allowedDomainMapper.remove(workspace.getId(), "acme.com");

        assertFalse(allowedDomainMapper.isAllowed(workspace.getId(), "acme.com"));
        assertEquals(0, allowedDomainMapper.countByWorkspace(workspace.getId()));
    }

    @Test
    void allowlist_isScopedToWorkspace() {
        allowedDomainMapper.add(workspace.getId(), "acme.com");
        Workspace other = newWorkspace();

        assertFalse(allowedDomainMapper.isAllowed(other.getId(), "acme.com"));
        assertEquals(0, allowedDomainMapper.countByWorkspace(other.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
