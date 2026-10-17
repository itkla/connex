package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.PrivilegedMfaRequestPolicy;
import ooo.klae.connex.backend.config.RequestPathNormalizer;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.util.ClientIpResolver;

class PrivilegedMfaLockedAuthorizationTest {
    private final PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AutomationScope automationScope = new AutomationScope();
    private final DenialAuditRateLimiter limiter = new DenialAuditRateLimiter(
        3600, Clock.systemUTC(), new SimpleMeterRegistry());
    private final PrivilegedMfaLockedAuthorization guard = new PrivilegedMfaLockedAuthorization(
        properties, userMapper, automationScope, auditService, limiter, new ClientIpResolver(""));
    private User user;
    private WorkspaceMember membership;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7);
        user.setUsername("locked-mfa-actor");
        user.setDisplayName("Actor");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
        membership = new WorkspaceMember();
        membership.setWorkspaceId(11);
        membership.setUserId(7);
        membership.setStatus("active");
        membership.setRole("admin");
        bind("POST", "/api/companies");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void organizationPrivilegeConfinesAnOrdinaryWorkspaceMemberWithoutAnOrganizationLock() {
        membership.setRole("member");
        when(userMapper.hasWorkspaceOrganizationPrivilege(7, 11)).thenReturn(true);
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of());
        assertThrows(PrivilegedMfaEnrollmentRequiredException.class,
            () -> guard.requireAllowed(guard.requestActor(Set.of(7)), 11, membership, Set.of()));

        var order = inOrder(userMapper);
        order.verify(userMapper).hasWorkspaceOrganizationPrivilege(7, 11);
        order.verify(userMapper).lockedWorkspaceMfaPasskeySufficiency(7, 11, true);
    }

    @Test
    void workspacePrivilegeNeedsNoOrganizationMembershipLookup() {
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of(true));
        guard.requireAllowed(guard.requestActor(Set.of(7)), 11, membership, Set.of());

        verify(userMapper).lockedWorkspaceMfaPasskeySufficiency(7, 11, true);
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    void enrollmentAndApprovalKeepTheFilterCodesAndMessages() {
        Integer actor = guard.requestActor(Set.of(7));
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of());
        var enrollment = assertThrows(PrivilegedMfaEnrollmentRequiredException.class,
            () -> guard.requireAllowed(actor, 11, membership, Set.of()));
        assertEquals("PRIVILEGED_MFA_ENROLLMENT_REQUIRED", enrollment.getCode());
        assertEquals("A passkey must be enrolled before this privileged account can continue", enrollment.getMessage());
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of(false));
        var attestation = assertThrows(PrivilegedMfaAttestationRequiredException.class,
            () -> guard.requireAllowed(actor, 11, membership, Set.of()));
        assertEquals("PRIVILEGED_MFA_ATTESTATION_REQUIRED", attestation.getCode());
        assertEquals("A passkey must be approved before this privileged account can continue", attestation.getMessage());
        verify(userMapper, times(2)).lockedWorkspaceMfaPasskeySufficiency(7, 11, true);
    }

    @Test
    void customRolePrivilegeMatchesTheClosedSqlListAndOverridesTheBuiltInRole() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mappers/UserMapper.xml"));
        String list = xml.split("AND wrp.permission IN \\(")[1].split("\\)")[0];
        Set<Permission> privileged = new HashSet<>();
        Pattern.compile("'([A-Z_]+)'").matcher(list).results()
            .forEach(match -> privileged.add(Permission.valueOf(match.group(1))));
        membership.setRoleId(23);
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of());
        Integer actor = guard.requestActor(Set.of(7));
        for (Permission permission : Permission.values()) {
            if (privileged.contains(permission)) {
                assertThrows(PrivilegedMfaEnrollmentRequiredException.class,
                    () -> guard.requireAllowed(actor, 11, membership, Set.of(permission)), permission.name());
            } else {
                assertDoesNotThrow(() -> guard.requireAllowed(actor, 11, membership, Set.of(permission)), permission.name());
            }
        }
    }

    @Test
    void ordinaryMembersAndApprovedPrivilegedMembersRemainAdmitted() {
        Integer actor = guard.requestActor(Set.of(7));
        membership.setRole("member");
        assertDoesNotThrow(() -> guard.requireAllowed(actor, 11, membership, Set.of(Permission.PERSON_UPDATE)));
        verify(userMapper).hasWorkspaceOrganizationPrivilege(7, 11);
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(auditService);
        membership.setRole("owner");
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of(false, true));
        assertDoesNotThrow(() -> guard.requireAllowed(actor, 11, membership, Set.of()));
        properties.setAttestationEnforced("false");
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, false)).thenReturn(List.of(false));
        assertDoesNotThrow(() -> guard.requireAllowed(actor, 11, membership, Set.of()));
    }

    @Test
    void enforcementDisabledBackgroundAutomationAndTargetsAreNotSubjects() {
        properties.setEnforced("false");
        assertNull(guard.requestActor(Set.of(7)));
        properties.setEnforced("true");
        assertNull(guard.requestActor(Set.of(8)));
        boolean previous = automationScope.enter();
        assertNull(guard.requestActor(Set.of(7)));
        automationScope.restore(previous);
        var state = automationScope.attribute(AutomationScope.Origin.task(AutomationScope.Source.BACKGROUND_TASK, "user"));
        assertNull(guard.requestActor(Set.of(7)));
        automationScope.restore(state);
        user.setUsername("__connex_system__");
        assertNull(guard.requestActor(Set.of(7)));
        user.setUsername("actor");
        RequestContextHolder.resetRequestAttributes();
        assertNull(guard.requestActor(Set.of(7)));
        verifyNoInteractions(userMapper, auditService);
    }

    @ParameterizedTest
    @MethodSource("exemptions")
    void sharesTheNormalizedBrowserExemptionDecision(String method, String path, boolean exempt) {
        bind(method, path);
        assertEquals(exempt, guard.requestActor(Set.of(7)) == null);
        if (exempt) {
            verifyNoInteractions(userMapper);
        }
    }

    static Stream<Arguments> exemptions() {
        return Stream.of(
            Arguments.of("POST", "/api/auth/webauthn/register", true),
            Arguments.of("POST", "/api/auth/webauthn/attestation", true),
            Arguments.of("POST", "/api/auth/logout", true),
            Arguments.of("POST", "/api//workspaces/123;v=x/leave", true),
            Arguments.of("POST", "/api/orgs/123/leave", true),
            Arguments.of("POST", "/api/ownership-offers/123/decline", true),
            Arguments.of("POST", "/api/document-acceptance/exchange", true),
            Arguments.of("DELETE", "/api/delivery/unsubscribe/exchange", true),
            Arguments.of("GET", "/api/workspaces", true),
            Arguments.of("HEAD", "/api/workspaces", false),
            Arguments.of("GET", "/api/workspaces/123/leave", false),
            Arguments.of("POST", "/api/workspaces/0x1/leave", false),
            Arguments.of("POST", "/api/document-acceptance-extra", false));
    }

    @Test
    void patHasNoBrowserExemptionAndDenialsShareTheRateLimitWindow() {
        bind("POST", "/api/auth/webauthn/register");
        PrivilegedMfaRequestPolicy.bindPublicApi(request, 7);
        Integer actor = guard.requestActor(Set.of(7));
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of());
        for (int i = 0; i < 2; i++) {
            assertThrows(PrivilegedMfaEnrollmentRequiredException.class,
                () -> guard.requireAllowed(actor, 11, membership, Set.of()));
        }
        verify(auditService, times(1)).deferStrictFailureIndependentScoped(eq("auth.mfa.policy.denied"),
            eq("user"), eq(7), isNull(), isNull(), eq("Actor"),
            eq("Public API privileged account confined pending MFA enrollment"),
            eq("enrollment_required"), eq(request), anyString(), any(Runnable.class));
    }

    @Test
    void failedAuditAppendReleasesAdmissionAndNeverChangesRefusal() {
        Integer actor = guard.requestActor(Set.of(7));
        when(userMapper.lockedWorkspaceMfaPasskeySufficiency(7, 11, true)).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(10, Runnable.class).run();
            return null;
        }).when(auditService).deferStrictFailureIndependentScoped(anyString(), anyString(), anyInt(),
            isNull(), isNull(), anyString(), anyString(), anyString(), any(), anyString(), any());
        for (int i = 0; i < 2; i++) {
            assertThrows(PrivilegedMfaEnrollmentRequiredException.class,
                () -> guard.requireAllowed(actor, 11, membership, Set.of()));
        }
        verify(auditService, times(2)).deferStrictFailureIndependentScoped(anyString(), anyString(), anyInt(),
            isNull(), isNull(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    private void bind(String method, String path) {
        request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("192.0.2.19");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        PrivilegedMfaRequestPolicy.bindBrowser(request, 7, RequestPathNormalizer.apiPath(request));
    }
}
