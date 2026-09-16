package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.dto.MailConfigDto;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Verifies sender resolution precedence: instance default gated on
 * {@code connex.mail.enabled}, and workspace override winning only when enabled
 * and usable, otherwise falling back to the instance default.
 */
@ExtendWith(MockitoExtension.class)
class MailConfigResolverTest {

    @Mock private MailConfigMapper mailConfigMapper;
    @Mock private SecretCipher secretCipher;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private UserMapper userMapper;

    private MailProperties properties;
    private MailConfigResolver resolver;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        properties = new MailProperties();
        resolver = new MailConfigResolver(properties, mailConfigMapper, secretCipher, workspaceMapper, userMapper);
        lenient().when(workspaceMapper.lockWorkspaceForShare(7)).thenReturn(1);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workspaceResolutionLocksActorBeforeWorkspaceAndSecret(boolean workspaceOnly) {
        authenticateActor();
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setWorkspaceId(7);
        ws.setEnabled(true);
        ws.setHost("smtp.workspace.test");
        ws.setFromAddress("team@workspace.test");
        ws.setAuth(true);
        ws.setPasswordEnc("ENC");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);
        when(secretCipher.decryptForWorkspace(7, "ENC")).thenReturn("password");

        ResolvedMailConfig resolved = workspaceOnly
                ? resolver.resolveWorkspaceOnly(7) : resolver.resolveForWorkspace(7);

        assertNotNull(resolved);
        assertEquals("password", resolved.password());
        InOrder order = inOrder(userMapper, workspaceMapper, mailConfigMapper, secretCipher);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(workspaceMapper).lockWorkspaceForShare(7);
        order.verify(mailConfigMapper).findByWorkspace(7);
        order.verify(secretCipher).decryptForWorkspace(7, "ENC");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workspaceResolutionMissingActorStopsBeforeWorkspaceOrSecret(boolean workspaceOnly) {
        authenticateActor();
        when(userMapper.lockByIdForShare(9)).thenReturn(null);

        assertNull(workspaceOnly ? resolver.resolveWorkspaceOnly(7) : resolver.resolveForWorkspace(7));

        verifyNoInteractions(workspaceMapper, mailConfigMapper, secretCipher);
    }

    private static void authenticateActor() {
        User actor = new User();
        actor.setId(9);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    private void enableInstance() {
        properties.setEnabled(true);
        properties.setHost("smtp.instance.test");
        properties.setFrom("no-reply@instance.test");
    }

    @Test
    void resolveInstance_disabled_returnsNull() {
        assertNull(resolver.resolveInstance());
    }

    @Test
    void resolveInstance_enabledButNoHost_returnsNull() {
        properties.setEnabled(true);
        properties.setFrom("no-reply@instance.test");
        assertNull(resolver.resolveInstance());
    }

    @Test
    void resolveInstance_enabledAndUsable_returnsInstance() {
        enableInstance();
        ResolvedMailConfig resolved = resolver.resolveInstance();
        assertEquals("smtp.instance.test", resolved.host());
        assertEquals("no-reply@instance.test", resolved.fromAddress());
    }

    @Test
    void resolveForWorkspace_enabledUsable_winsAndDecryptsPassword() {
        enableInstance();
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(true);
        ws.setWorkspaceId(7);
        ws.setHost("smtp.workspace.test");
        ws.setFromAddress("team@workspace.test");
        ws.setPort(2525);
        ws.setAuth(true);
        ws.setPasswordEnc("ENC");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);
        when(secretCipher.decryptForWorkspace(7, "ENC")).thenReturn("decrypted-pw");

        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);
        assertEquals("smtp.workspace.test", resolved.host());
        assertEquals(2525, resolved.port());
        assertEquals("decrypted-pw", resolved.password());
    }

    @Test
    void resolveForWorkspace_managed_returnsInstanceWithoutWorkspaceLookup() {
        enableInstance();
        properties.setManaged(true);
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(true);
        ws.setWorkspaceId(7);
        ws.setHost("smtp.workspace.test");
        ws.setFromAddress("team@workspace.test");
        lenient().when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);

        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);

        assertEquals("smtp.instance.test", resolved.host());
        assertEquals("no-reply@instance.test", resolved.fromAddress());
        verify(mailConfigMapper, never()).findByWorkspace(7);
        verify(secretCipher, never()).decryptForWorkspace(7, ws.getPasswordEnc());
    }

    @Test
    void resolveForWorkspace_authDisabledDoesNotDecryptStalePassword() {
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(true);
        ws.setAuth(false);
        ws.setWorkspaceId(7);
        ws.setHost("smtp.workspace.test");
        ws.setFromAddress("team@workspace.test");
        ws.setPort(2525);
        ws.setPasswordEnc("ENC");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);

        ResolvedMailConfig resolved = resolver.resolveWorkspaceOnly(7);

        assertNull(resolved.password());
        verify(secretCipher, never()).decryptForWorkspace(7, "ENC");
        assertFalse(MailConfigDto.from(ws, properties.getPort()).isHasPassword());
    }

    @Test
    void resolveForWorkspace_disabledRow_fallsBackToInstance() {
        enableInstance();
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(false);
        ws.setHost("smtp.workspace.test");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);

        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);
        assertEquals("smtp.instance.test", resolved.host());
    }

    @Test
    void resolveForWorkspace_noRow_fallsBackToInstance() {
        enableInstance();
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(null);
        assertEquals("smtp.instance.test", resolver.resolveForWorkspace(7).host());
        verifyNoInteractions(userMapper);
    }

    @Test
    void resolveForWorkspace_enabledButNotUsable_fallsBackToInstance() {
        enableInstance();
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(true);
        ws.setFromAddress("team@workspace.test");
        lenient().when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);

        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);
        assertEquals("smtp.instance.test", resolved.host());
    }

    @Test
    void resolveForWorkspace_noWorkspaceOrInstance_returnsNull() {
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(null);
        assertNull(resolver.resolveForWorkspace(7));
    }

    @Test
    void resolveForWorkspace_absentWorkspaceRow_returnsNullWithoutReadingConfigOrSecret() {
        enableInstance();
        when(workspaceMapper.lockWorkspaceForShare(7)).thenReturn(null);

        assertNull(resolver.resolveForWorkspace(7));

        verify(mailConfigMapper, never()).findByWorkspace(7);
        verifyNoInteractions(secretCipher);
    }

    @Test
    void resolveWorkspaceOnly_absentWorkspaceRow_returnsNullWithoutReadingConfigOrSecret() {
        enableInstance();
        when(workspaceMapper.lockWorkspaceForShare(7)).thenReturn(null);

        assertNull(resolver.resolveWorkspaceOnly(7));

        verify(mailConfigMapper, never()).findByWorkspace(7);
        verifyNoInteractions(secretCipher);
    }

    @Test
    void resolveWorkspaceOnly_enabledUsable_returnsWorkspaceNeverInstance() {
        enableInstance();
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(true);
        ws.setHost("smtp.workspace.test");
        ws.setFromAddress("team@workspace.test");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);
        assertEquals("smtp.workspace.test", resolver.resolveWorkspaceOnly(7).host());
    }

    @Test
    void resolveWorkspaceOnly_noEnabledConfig_returnsNullNotInstanceFallback() {
        enableInstance();
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(null);
        assertNull(resolver.resolveWorkspaceOnly(7));
    }

    @Test
    void resolveWorkspaceOnly_managed_returnsNullWithoutWorkspaceLookup() {
        properties.setManaged(true);
        lenient().when(mailConfigMapper.findByWorkspace(7)).thenReturn(new WorkspaceMailConfig());

        assertNull(resolver.resolveWorkspaceOnly(7));

        verify(mailConfigMapper, never()).findByWorkspace(7);
    }

    @Test
    void resolveWorkspaceOnly_disabledRow_returnsNull() {
        enableInstance();
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setEnabled(false);
        ws.setHost("smtp.workspace.test");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);
        assertNull(resolver.resolveWorkspaceOnly(7));
    }
}
