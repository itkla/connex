package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    @ParameterizedTest
    @ValueSource(strings = {"managed", "stored_password", "no_auth", "unusable_override", "instance_default"})
    void descriptionMatchesResolutionWithoutDecrypting(String branch) {
        enableInstance();
        properties.setPassword("instance-secret");
        properties.setManaged(branch.equals("managed"));
        if (!branch.equals("managed") && !branch.equals("instance_default")) {
            WorkspaceMailConfig override = authenticatingOverride(!branch.equals("unusable_override"), "secret:v1:5");
            override.setAuth(!branch.equals("no_auth"));
            when(mailConfigMapper.findByWorkspace(7)).thenReturn(override);
        }
        if (branch.equals("stored_password")) {
            when(secretCipher.canResolveForWorkspace(7, "secret:v1:5")).thenReturn(true);
        }

        MailConfigDescription description = resolver.describeForWorkspace(7);

        assertNotNull(description);
        assertTrue(description.usable());
        verify(secretCipher, never()).decryptForWorkspace(anyInt(), anyString());
        if (branch.equals("stored_password")) {
            when(secretCipher.decryptForWorkspace(7, "secret:v1:5")).thenReturn("workspace-secret");
        }
        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);
        assertNotNull(resolved);
        assertEquals(resolved.description(), description);
    }

    @Test
    void descriptionRefusesUnresolvableOverrideWithoutFallback() {
        enableInstance();
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(true, "secret:v1:5"));
        when(secretCipher.canResolveForWorkspace(7, "secret:v1:5")).thenReturn(false);

        assertNull(resolver.describeForWorkspace(7));
        verify(secretCipher, never()).decryptForWorkspace(anyInt(), anyString());
    }

    @Test
    void descriptionReturnsNullWithoutAUsableTransport() {
        assertNull(resolver.describeForWorkspace(7));
        verifyNoInteractions(secretCipher);
    }

    private static void authenticateActor() {
        User actor = new User();
        actor.setId(9);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    private static WorkspaceMailConfig authenticatingOverride(boolean usable, String passwordEnc) {
        WorkspaceMailConfig ws = new WorkspaceMailConfig();
        ws.setWorkspaceId(7);
        ws.setEnabled(true);
        ws.setHost(usable ? "smtp.workspace.test" : null);
        ws.setFromAddress("team@workspace.test");
        ws.setAuth(true);
        ws.setPasswordEnc(passwordEnc);
        return ws;
    }

    /**
     * Readiness decides exactly as resolution does but never decrypts: asking whether a workspace can
     * send used to write an audited secret use for every check, and the decrypted password was never
     * part of the answer (#1932).
     */
    @Test
    void readinessSelectsAUsableOverrideWithoutDecryptingItsPassword() {
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(true, "secret:v1:5"));
        when(secretCipher.canResolveForWorkspace(7, "secret:v1:5")).thenReturn(true);

        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("workspace_override", true),
                resolver.readinessForWorkspace(7));
        assertTrue(resolver.canSendForWorkspace(7));
        verify(secretCipher, never()).decryptForWorkspace(anyInt(), anyString());

        when(secretCipher.decryptForWorkspace(7, "secret:v1:5")).thenReturn("password");
        ResolvedMailConfig resolved = resolver.resolveForWorkspace(7);
        assertNotNull(resolved);
        assertTrue(resolved.workspaceSupplied());
        assertEquals("password", resolved.password());
    }

    /**
     * Resolving an override whose stored password no longer resolves fails rather than falling back to
     * the instance default, so readiness reports it as not ready instead of selecting the instance.
     */
    @Test
    void readinessReportsAnOverrideWhosePasswordNoLongerResolvesAsNotReady() {
        enableInstance();
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(true, "secret:v1:6"));
        when(secretCipher.canResolveForWorkspace(7, "secret:v1:6")).thenReturn(false);

        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("unconfigured", false),
                resolver.readinessForWorkspace(7));
        verify(secretCipher, never()).decryptForWorkspace(anyInt(), anyString());

        when(secretCipher.decryptForWorkspace(7, "secret:v1:6"))
                .thenThrow(new IllegalStateException("Secret reference not found"));
        assertThrows(IllegalStateException.class, () -> resolver.resolveForWorkspace(7));
    }

    /** Usability never depended on the password, so an override with none stored is ready. */
    @Test
    void readinessIsReadyForAnAuthenticatingOverrideWithNoStoredPassword() {
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(true, " "));

        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("workspace_override", true),
                resolver.readinessForWorkspace(7));
        verifyNoInteractions(secretCipher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnusableOverrideFallsBackToTheInstanceWithoutBeingDecrypted(boolean instanceEnabled) {
        if (instanceEnabled) {
            enableInstance();
        }
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(false, "secret:v1:5"));

        assertEquals(instanceEnabled
                        ? new MailConfigResolver.WorkspaceMailReadiness("instance_default", true)
                        : new MailConfigResolver.WorkspaceMailReadiness("unconfigured", false),
                resolver.readinessForWorkspace(7));
        assertEquals(instanceEnabled, resolver.resolveForWorkspace(7) != null);
        verifyNoInteractions(secretCipher);
    }

    @Test
    void readinessIgnoresADisabledOverride() {
        enableInstance();
        WorkspaceMailConfig ws = authenticatingOverride(true, "secret:v1:5");
        ws.setEnabled(false);
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(ws);

        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("instance_default", true),
                resolver.readinessForWorkspace(7));
        verifyNoInteractions(secretCipher);
    }

    @Test
    void readinessWithNothingConfiguredIsNotReady() {
        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("unconfigured", false),
                resolver.readinessForWorkspace(7));
        assertFalse(resolver.canSendForWorkspace(7));
        verifyNoInteractions(secretCipher);
    }

    @Test
    void workspaceOnlyResolutionDoesNotDecryptAnOverrideItDiscards() {
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(false, "secret:v1:5"));

        assertNull(resolver.resolveWorkspaceOnly(7));
        verifyNoInteractions(secretCipher);
    }

    @Test
    void readinessForAWorkspaceThatIsGoneIsNotReady() {
        enableInstance();
        when(workspaceMapper.lockWorkspaceForShare(7)).thenReturn(null);

        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("unconfigured", false),
                resolver.readinessForWorkspace(7));
        verifyNoInteractions(mailConfigMapper, secretCipher);
    }

    @Test
    void readinessForAnActorWhoIsGoneIsNotReady() {
        authenticateActor();
        when(userMapper.lockByIdForShare(9)).thenReturn(null);

        assertFalse(resolver.canSendForWorkspace(7));
        verifyNoInteractions(workspaceMapper, mailConfigMapper, secretCipher);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readinessInManagedModeReportsTheInstance(boolean instanceEnabled) {
        properties.setManaged(true);
        if (instanceEnabled) {
            enableInstance();
        }

        assertEquals(new MailConfigResolver.WorkspaceMailReadiness("managed", instanceEnabled),
                resolver.readinessForWorkspace(7));
        verifyNoInteractions(workspaceMapper, mailConfigMapper, secretCipher);
    }

    @Test
    void readinessTakesTheResolutionLocksInTheSameOrderAndNeverDecrypts() {
        authenticateActor();
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(mailConfigMapper.findByWorkspace(7)).thenReturn(authenticatingOverride(true, "secret:v1:5"));
        when(secretCipher.canResolveForWorkspace(7, "secret:v1:5")).thenReturn(true);

        assertTrue(resolver.canSendForWorkspace(7));

        InOrder order = inOrder(userMapper, workspaceMapper, mailConfigMapper, secretCipher);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(workspaceMapper).lockWorkspaceForShare(7);
        order.verify(mailConfigMapper).findByWorkspace(7);
        order.verify(secretCipher).canResolveForWorkspace(7, "secret:v1:5");
        verify(secretCipher, never()).decryptForWorkspace(anyInt(), anyString());
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
