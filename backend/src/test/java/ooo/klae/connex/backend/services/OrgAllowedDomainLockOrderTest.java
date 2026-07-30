package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.OrgAllowedDomainMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Pins allowed-domain mutations to the shared lifecycle and SSO lock order. */
@ExtendWith(MockitoExtension.class)
class OrgAllowedDomainLockOrderTest {
    private static final int ORG_ID = 3;
    private static final int ACTOR_ID = 7;

    @Mock private OrgAllowedDomainMapper domainMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private UserMapper userMapper;
    @Mock private OrganizationMapper organizationMapper;

    private OrgAllowedDomainService service;

    @BeforeEach
    void setUp() {
        service = new OrgAllowedDomainService(
            domainMapper,
            orgMemberService,
            auditService,
            sessionSecurityService,
            userMapper,
            organizationMapper);
    }

    @Test
    void addLocksActorOrganizationAndMembershipBeforeMutationAndAudit() {
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(organizationMapper.lockById(ORG_ID)).thenReturn(ORG_ID);
        when(domainMapper.findByOrg(ORG_ID)).thenReturn(List.of("example.com"));

        service.addDomain(ORG_ID, ACTOR_ID, "Example.com");

        InOrder order = inOrder(
            orgMemberService,
            sessionSecurityService,
            userMapper,
            organizationMapper,
            domainMapper,
            auditService);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(organizationMapper).lockById(ORG_ID);
        order.verify(orgMemberService).requireOrgAdminForUpdate(ORG_ID, ACTOR_ID);
        order.verify(domainMapper).add(ORG_ID, "example.com");
        order.verify(auditService).record(
            eq("org.allowed_domain.add"),
            eq("organization"),
            eq(ORG_ID),
            eq("example.com"),
            any(),
            eq(null));
        order.verify(domainMapper).findByOrg(ORG_ID);
    }

    @Test
    void removeLocksActorOrganizationAndMembershipBeforeMutationAndAudit() {
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(organizationMapper.lockById(ORG_ID)).thenReturn(ORG_ID);

        service.removeDomain(ORG_ID, ACTOR_ID, "Example.com");

        InOrder order = inOrder(
            orgMemberService,
            sessionSecurityService,
            userMapper,
            organizationMapper,
            domainMapper,
            auditService);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(userMapper).lockByIdForShare(ACTOR_ID);
        order.verify(organizationMapper).lockById(ORG_ID);
        order.verify(orgMemberService).requireOrgAdminForUpdate(ORG_ID, ACTOR_ID);
        order.verify(domainMapper).remove(ORG_ID, "example.com");
        order.verify(auditService).record(
            eq("org.allowed_domain.remove"),
            eq("organization"),
            eq(ORG_ID),
            eq("example.com"),
            any(),
            eq(null));
    }

    @Test
    void missingActorRefusesBeforeOrganizationOrDomainMutation() {
        when(userMapper.lockByIdForShare(ACTOR_ID)).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(
            ooo.klae.connex.backend.exceptions.ForbiddenException.class,
            () -> service.addDomain(ORG_ID, ACTOR_ID, "example.com"));

        verify(organizationMapper, never()).lockById(ORG_ID);
        verify(domainMapper, never()).add(any(Integer.class), any(String.class));
        verify(auditService, never()).record(
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    }

    @Test
    void finalJoinDecisionUsesOneCurrentLockingRead() {
        when(domainMapper.findByOrgForShare(ORG_ID))
            .thenReturn(List.of(), List.of("example.com"));

        assertTrue(service.isJoinAllowedForShare(ORG_ID, "person@other.com"));
        assertFalse(service.isJoinAllowedForShare(ORG_ID, "person@other.com"));
    }

    @Test
    void bothMutationsOwnATransactionBoundary() throws Exception {
        Method add = OrgAllowedDomainService.class.getMethod(
            "addDomain",
            int.class,
            int.class,
            String.class);
        Method remove = OrgAllowedDomainService.class.getMethod(
            "removeDomain",
            int.class,
            int.class,
            String.class);

        assertTrue(add.isAnnotationPresent(Transactional.class));
        assertTrue(remove.isAnnotationPresent(Transactional.class));
    }
}
