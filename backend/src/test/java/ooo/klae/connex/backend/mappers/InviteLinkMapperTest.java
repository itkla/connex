package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceInviteLink;
import ooo.klae.connex.backend.dto.InviteLinkPreviewDto;

/**
 * Validity computation (revoked / expired / exhausted), the atomic single-claim of
 * {@code incrementUsedCount}, and workspace-scoped revocation for shareable invite links (#81 Phase 3).
 */
class InviteLinkMapperTest extends AbstractMapperTest {

    @Autowired private InviteLinkMapper inviteLinkMapper;

    private String createLink(Integer expiresInDays, Integer maxUses) {
        String token = "tok_" + unique();
        User creator = newUser();
        inviteLinkMapper.insert(workspace.getId(), token, "member", expiresInDays, maxUses, creator.getId());
        return token;
    }

    @Test
    void findPreviewByToken_freshLinkIsValid() {
        String token = createLink(14, null);

        InviteLinkPreviewDto preview = inviteLinkMapper.findPreviewByToken(token);

        assertNotNull(preview);
        assertEquals(workspace.getId(), preview.getWorkspaceId());
        assertEquals("member", preview.getRole());
        assertTrue(preview.isValid());
    }

    @Test
    void findPreviewByToken_expiredLinkIsInvalid() {
        String token = createLink(-1, null);

        assertFalse(inviteLinkMapper.findPreviewByToken(token).isValid());
    }

    @Test
    void incrementUsedCount_stopsAtMaxUses() {
        String token = createLink(14, 1);
        WorkspaceInviteLink link = inviteLinkMapper.findByToken(token);

        assertEquals(1, inviteLinkMapper.incrementUsedCount(link.getId()));
        assertEquals(0, inviteLinkMapper.incrementUsedCount(link.getId()));
        assertFalse(inviteLinkMapper.findPreviewByToken(token).isValid());
    }

    @Test
    void markRevoked_isScopedToWorkspace() {
        String token = createLink(14, null);
        WorkspaceInviteLink link = inviteLinkMapper.findByToken(token);
        Workspace other = newWorkspace();

        assertEquals(0, inviteLinkMapper.markRevoked(link.getId(), other.getId()));
        assertEquals(1, inviteLinkMapper.markRevoked(link.getId(), workspace.getId()));
        assertFalse(inviteLinkMapper.findPreviewByToken(token).isValid());
    }

    @Test
    void redemptionTracking_recordAndDetect() {
        String token = createLink(14, null);
        WorkspaceInviteLink link = inviteLinkMapper.findByToken(token);
        User user = newUser();

        assertFalse(inviteLinkMapper.hasRedeemed(link.getId(), user.getId()));
        inviteLinkMapper.recordRedemption(link.getId(), user.getId());
        assertTrue(inviteLinkMapper.hasRedeemed(link.getId(), user.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
