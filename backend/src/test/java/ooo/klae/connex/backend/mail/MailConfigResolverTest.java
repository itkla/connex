package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mappers.MailConfigMapper;

/**
 * Verifies sender resolution precedence: instance default gated on
 * {@code connex.mail.enabled}, and workspace override winning only when enabled
 * and usable, otherwise falling back to the instance default.
 */
@ExtendWith(MockitoExtension.class)
class MailConfigResolverTest {

    @Mock private MailConfigMapper mailConfigMapper;
    @Mock private SecretCipher secretCipher;

    private MailProperties properties;
    private MailConfigResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new MailProperties();
        resolver = new MailConfigResolver(properties, mailConfigMapper, secretCipher);
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
        ws.setHost("smtp.workspace.test");
        ws.setFromAddress("team@workspace.test");
        ws.setPort(2525);
        ws.setPasswordEnc("ENC");
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);
        when(secretCipher.decrypt("ENC")).thenReturn("decrypted-pw");

        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);
        assertEquals("smtp.workspace.test", resolved.host());
        assertEquals(2525, resolved.port());
        assertEquals("decrypted-pw", resolved.password());
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
}
