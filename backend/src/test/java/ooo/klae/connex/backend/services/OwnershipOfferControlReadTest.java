package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.controllers.OwnershipOfferController;
import ooo.klae.connex.backend.controllers.OrgMemberController;
import ooo.klae.connex.backend.controllers.WorkspaceController;
import ooo.klae.connex.backend.dto.AddOrgMemberRequest;
import ooo.klae.connex.backend.dto.OrgMemberRequest;
import ooo.klae.connex.backend.dto.UpdateMemberRoleRequest;
import ooo.klae.connex.backend.dto.MemberRoleChange;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

/** Pins pure ownership preflight reads to control while writes retain the destination catalog. */
@ExtendWith(MockitoExtension.class)
class OwnershipOfferControlReadTest {
    @Mock private OwnershipOfferMapper mapper;
    @Mock private OrgMemberMapper orgMemberMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private UserMapper userMapper;
    @Mock private OwnershipOfferTransaction transaction;
    @Mock private OwnershipOfferEligibility eligibility;
    @Mock private TenantLifecycleControlMapper lifecycleMapper;
    @Mock private TenantLifecycleAccess lifecycleAccess;
    @Mock private PrivilegedAccountService privilegedAccountService;
    @Mock private AuditService auditService;
    @Mock private WorkspaceService workspaceService;
    @Mock private OrgMemberService orgMemberService;
    @Mock private TenantCatalogResolver catalogResolver;
    @Mock private AuthService authService;
    private final TenantContext context = new TenantContext();
    private TenantWorkScope workScope;
    private OwnershipOfferService service;

    @BeforeEach void selectedCatalog() {
        context.set(3, 4, 5, "member", "foreign_catalog");
        workScope = new TenantWorkScope(context, catalogResolver, workspaceMapper);
        service = new OwnershipOfferService(mapper, orgMemberMapper, workspaceMapper, organizationMapper,
            userMapper, transaction, eligibility, workScope, context, lifecycleMapper, lifecycleAccess,
            privilegedAccountService, auditService, workspaceService, orgMemberService);
    }

    @AfterEach void clearContext() {
        context.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"built-in", "custom"})
    void workspacePreflightReadsControlAndWritesThePathCatalog(String assignment) {
        workspacePreflight();
        if ("custom".equals(assignment)) {
            when(workspaceService.assignCustomRole(13, 1, 2, 8, false)).thenAnswer(invocation -> {
                assertEquals("destination_catalog", context.getCatalog());
                return null;
            });
            service.changeWorkspaceRole(13, 1, 2, null, 8, false);
        } else {
            when(workspaceService.changeMemberRole(13, 1, 2, "admin", false)).thenAnswer(invocation -> {
                assertEquals("destination_catalog", context.getCatalog());
                return MemberRoleChange.direct(null);
            });
            service.changeWorkspaceRole(13, 1, 2, "admin", null, false);
        }
        verify(workspaceMapper).getRole(13, 1);
        assertOriginalSelection();
    }

    @Test void workspacePreflightRefusalRestoresSelectionAndPerformsNoWrite() {
        when(workspaceMapper.getRole(13, 1)).thenAnswer(invocation -> {
            assertNull(context.getCatalog());
            return null;
        });
        assertThrows(ForbiddenException.class, () -> service.changeWorkspaceRole(13, 1, 2, "owner", null, false));
        verifyNoInteractions(workspaceService);
        assertOriginalSelection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"grant-id", "grant-email", "remove", "leave"})
    void organizationPreflightReadsControlAndWritesTheChosenCatalog(String operation) {
        organizationRoute();
        if ("leave".equals(operation)) {
            when(orgMemberMapper.getRole(7, 1)).thenAnswer(invocation -> {
                assertNull(context.getCatalog());
                return "admin";
            });
            doAnswer(invocation -> { assertEquals("destination_catalog", context.getCatalog()); return null; })
                .when(orgMemberService).leave(7, 1);
            service.leaveOrganization(7, 1);
        } else {
            organizationOwnerCheck();
            switch (operation) {
                case "grant-id" -> {
                    when(orgMemberService.setMember(7, 1, 2, "admin", false)).thenAnswer(invocation -> {
                        assertEquals("destination_catalog", context.getCatalog());
                        return MemberRoleChange.direct(null);
                    });
                    service.changeOrganizationRole(7, 1, 2, null, "admin", false);
                }
                case "grant-email" -> {
                    when(orgMemberService.setMemberByEmail(eq(7), eq(1), eq("offeree@example.test"), eq("admin"), eq(false),
                            any(IntConsumer.class))).thenAnswer(invocation -> {
                        assertEquals("destination_catalog", context.getCatalog());
                        invocation.getArgument(5, IntConsumer.class).accept(2);
                        return MemberRoleChange.direct(null);
                    });
                    service.changeOrganizationRole(7, 1, null, "offeree@example.test", "admin", false);
                }
                case "remove" -> {
                    doAnswer(invocation -> { assertEquals("destination_catalog", context.getCatalog()); return null; })
                        .when(orgMemberService).removeMember(7, 1, 2);
                    service.removeOrganizationMember(7, 1, 2);
                }
                default -> fail("Unknown operation");
            }
            verify(orgMemberService).requireOrgOwner(7, 1);
        }
        verify(workspaceMapper).findByOrgId(7);
        assertOriginalSelection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolved", "unresolved", "retry-unresolved"})
    void byEmailRefusalNamesOnlyTheFinalAttemptResolvedTarget(String resolution) {
        organizationRoute(); organizationOwnerCheck();
        AtomicInteger attempts = new AtomicInteger();
        BadRequestException failure = new BadRequestException("Eligibility refused");
        when(orgMemberService.setMemberByEmail(eq(7), eq(1), eq("offeree@example.test"), eq("owner"), eq(false),
                any(IntConsumer.class))).thenAnswer(invocation -> {
            assertEquals("destination_catalog", context.getCatalog());
            int attempt = attempts.incrementAndGet();
            if ("resolved".equals(resolution) || ("retry-unresolved".equals(resolution) && attempt == 1)) {
                invocation.getArgument(5, IntConsumer.class).accept(2);
            }
            if ("retry-unresolved".equals(resolution) && attempt == 1) {
                throw new RuntimeException(new SQLException("Deadlock", "40001", 1213));
            }
            throw failure;
        });
        assertSame(failure, assertThrows(BadRequestException.class,
            () -> service.changeOrganizationRole(7, 1, null, "offeree@example.test", "owner", false)));
        boolean resolved = "resolved".equals(resolution);
        verify(auditService).recordStrictFailureIndependentScoped("ownership.offer.refused", "user",
            resolved ? 2 : null, null, 7, null, "Ownership offer create refused", "BadRequestException");
        assertEquals("retry-unresolved".equals(resolution) ? 2 : 1, attempts.get());
        assertOriginalSelection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKSPACE", "ORGANIZATION"})
    void creationLabelFailureAfterTheWriteDoesNotAuditARefusal(String scope) {
        OwnershipOffer offer = new OwnershipOffer();
        offer.setId(9L); offer.setOrgId(7); offer.setOfferorUserId(1); offer.setOffereeUserId(2);
        offer.setScopeKind(scope); offer.setState("OPEN");
        IllegalStateException failure = new IllegalStateException("Label read failed");
        if ("WORKSPACE".equals(scope)) {
            offer.setWorkspaceId(13);
            workspacePreflight();
            when(workspaceService.changeMemberRole(13, 1, 2, "owner", false)).thenReturn(
                new MemberRoleChange<>(null, OwnershipOfferDto.from(offer)));
            when(workspaceMapper.getActiveById(13)).thenAnswer(invocation -> {
                assertNull(context.getCatalog()); throw failure;
            });
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.changeWorkspaceRole(13, 1, 2, "owner", null, false)));
            verify(workspaceService).changeMemberRole(13, 1, 2, "owner", false);
        } else {
            organizationRoute(); organizationOwnerCheck();
            when(orgMemberService.setMember(7, 1, 2, "owner", false)).thenReturn(
                new MemberRoleChange<>(null, OwnershipOfferDto.from(offer)));
            when(organizationMapper.getById(7)).thenAnswer(invocation -> {
                assertNull(context.getCatalog()); throw failure;
            });
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.changeOrganizationRole(7, 1, 2, null, "owner", false)));
            verify(orgMemberService).setMember(7, 1, 2, "owner", false);
        }
        verifyNoInteractions(auditService);
        assertOriginalSelection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"self", "workspace", "organization", "accept", "decline", "revoke-workspace", "revoke-organization"})
    void controllerRefreshesTheAuthenticatedAccountOnControl(String route) {
        User user = new User(); user.setId(1);
        when(authService.getCurrentUser()).thenAnswer(invocation -> {
            assertNull(context.getCatalog()); return user;
        });
        OwnershipOfferService delegated = mock(OwnershipOfferService.class);
        OwnershipOfferController controller = new OwnershipOfferController(delegated, authService, workScope);
        switch (route) {
            case "self" -> { controller.self(); verify(delegated).listForSelf(1); }
            case "workspace" -> { controller.workspace(13); verify(delegated).listForScope("WORKSPACE", 13, 1); }
            case "organization" -> { controller.organization(7); verify(delegated).listForScope("ORGANIZATION", 7, 1); }
            case "accept" -> { controller.accept(9L, null); verify(delegated).accept(9L, 1, false); }
            case "decline" -> { controller.decline(9L); verify(delegated).decline(9L, 1); }
            case "revoke-workspace" -> { controller.revokeWorkspace(13, 9L); verify(delegated).revoke("WORKSPACE", 13, 9L, 1); }
            case "revoke-organization" -> { controller.revokeOrganization(7, 9L); verify(delegated).revoke("ORGANIZATION", 7, 9L, 1); }
            default -> fail("Unknown route");
        }
        verify(authService).getCurrentUser();
        assertOriginalSelection();
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace-grant", "organization-post", "organization-put", "organization-remove", "organization-leave"})
    void existingGrantAndDepartureControllersRefreshTheActorOnControl(String route) {
        User user = new User(); user.setId(1);
        when(authService.getCurrentUser()).thenAnswer(invocation -> {
            assertNull(context.getCatalog()); return user;
        });
        OwnershipOfferService delegated = mock(OwnershipOfferService.class);
        OrgMemberController organization = new OrgMemberController(orgMemberService, delegated,
            mock(PrivilegedMfaAttestationService.class), authService, workScope);
        switch (route) {
            case "workspace-grant" -> {
                WorkspaceController workspace = new WorkspaceController(workspaceService, delegated,
                    mock(PrivilegedMfaAttestationService.class), mock(InviteService.class), mock(InviteLinkService.class),
                    mock(AllowedDomainService.class), authService, workScope, mock(WorkspaceCookie.class));
                UpdateMemberRoleRequest request = new UpdateMemberRoleRequest(); request.setRole("owner");
                when(delegated.changeWorkspaceRole(13, 1, 2, "owner", null, false)).thenReturn(MemberRoleChange.direct(null));
                workspace.updateMemberRole(13, 2, request);
                verify(delegated).changeWorkspaceRole(13, 1, 2, "owner", null, false);
            }
            case "organization-post" -> {
                AddOrgMemberRequest request = new AddOrgMemberRequest();
                request.setEmail("offeree@example.test"); request.setOrgRole("owner");
                when(delegated.changeOrganizationRole(7, 1, null, "offeree@example.test", "owner", false))
                    .thenReturn(MemberRoleChange.direct(null));
                organization.addMember(7, request);
                verify(delegated).changeOrganizationRole(7, 1, null, "offeree@example.test", "owner", false);
            }
            case "organization-put" -> {
                OrgMemberRequest request = new OrgMemberRequest(); request.setOrgRole("owner");
                when(delegated.changeOrganizationRole(7, 1, 2, null, "owner", false)).thenReturn(MemberRoleChange.direct(null));
                organization.setMember(7, 2, request);
                verify(delegated).changeOrganizationRole(7, 1, 2, null, "owner", false);
            }
            case "organization-remove" -> {
                organization.removeMember(7, 2); verify(delegated).removeOrganizationMember(7, 1, 2);
            }
            case "organization-leave" -> {
                organization.leave(7); verify(delegated).leaveOrganization(7, 1);
            }
            default -> fail("Unknown route");
        }
        verify(authService).getCurrentUser();
        assertOriginalSelection();
    }

    @Test void aDeletedAuthenticatedAccountStillRefusesBeforeAnyOfferOperation() {
        when(authService.getCurrentUser()).thenAnswer(invocation -> {
            assertNull(context.getCatalog()); throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        });
        OwnershipOfferService delegated = mock(OwnershipOfferService.class);
        OwnershipOfferController controller = new OwnershipOfferController(delegated, authService, workScope);
        assertThrows(AuthenticationCredentialsNotFoundException.class, controller::self);
        verifyNoInteractions(delegated);
        assertOriginalSelection();
    }

    private void workspacePreflight() {
        when(workspaceMapper.getRole(13, 1)).thenAnswer(invocation -> {
            assertNull(context.getCatalog()); return "owner";
        });
        destination();
    }

    private void organizationRoute() {
        Workspace workspace = new Workspace(); workspace.setId(13); workspace.setOrgId(7);
        when(workspaceMapper.findByOrgId(7)).thenAnswer(invocation -> {
            assertNull(context.getCatalog()); return List.of(workspace);
        });
        destination();
    }

    private void organizationOwnerCheck() {
        doAnswer(invocation -> { assertNull(context.getCatalog()); return null; })
            .when(orgMemberService).requireOrgOwner(7, 1);
    }

    private void destination() {
        when(workspaceMapper.getOrgId(13)).thenAnswer(invocation -> {
            assertNull(context.getCatalog()); return 7;
        });
        when(catalogResolver.resolveCatalog(7)).thenReturn("destination_catalog");
    }

    private void assertOriginalSelection() {
        assertEquals("foreign_catalog", context.getCatalog());
        assertEquals(3, context.getWorkspaceId());
        assertEquals(4, context.getOrgId());
        assertEquals(5, context.getUserId());
    }
}
