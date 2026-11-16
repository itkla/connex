package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyService;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Pins fresh purge scope discovery after the unchanged authority, person and privacy locks. */
@ExtendWith(MockitoExtension.class)
class PersonRestrictionScopeTest {
    @Mock private PersonMapper persons;
    @Mock private AiOutputCacheMapper cache;
    @Mock private WorkspaceMapper workspaces;
    @Mock private WorkspaceService workspaceService;
    @Mock private DuplicateDecisionLockService duplicateLocks;
    @Mock private WorkspaceService.LockedPermissionSnapshot authority;
    @Mock private AiAgentPrivacyService privacy;
    private final RecordVisibilityScopeControlAccess scopes =
        ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    @Mock private ProviderCaptureMapper capture;
    @Mock private ShareMapper shares;
    @Mock private AuditService audits;
    @Mock private IdentityIntakeService identities;
    @InjectMocks private PersonService service;

    private Person person;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", scopes);
        when(scopes.getForWorkspace(7)).thenReturn(
            new RecordVisibilityScopeControlAccess.RecordVisibilityScope(3, List.of(7, 8, 9)));
        person = new Person();
        person.setId(29);
        person.setName("Restriction scope fixture");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(duplicateLocks.lockCurrentOrganization(Permission.PERSON_UPDATE)).thenReturn(
            new DuplicateDecisionLockService.LockedOrganization(3, authority));
        when(persons.getOwnedPersonByIdForUpdate(7, 29)).thenReturn(person);
    }

    @Test
    void freshUnfilteredScopeIsReadOnceImmediatelyBeforeTheSingleDelete() {
        Person restricted = new Person();
        restricted.setId(29);
        restricted.setSuspendedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        Workspace active = new Workspace();
        active.setId(7);
        when(workspaces.findByOrgId(3)).thenReturn(List.of(active));
        when(scopes.getFreshForWorkspace(7)).thenReturn(
            new RecordVisibilityScopeControlAccess.RecordVisibilityScope(3, List.of(7, 11, 13)));
        when(persons.existsOwned(7, 29)).thenReturn(true);
        when(persons.getPersonById(7, "[7,8,9]", 29)).thenReturn(restricted);

        assertSame(restricted, service.updateProcessingRestrictions(29, true, false));

        var order = inOrder(duplicateLocks, persons, authority, workspaces, identities, privacy, scopes, cache);
        order.verify(scopes).getForWorkspace(7);
        order.verify(duplicateLocks).lockCurrentOrganization(Permission.PERSON_UPDATE);
        order.verify(persons).getOwnedPersonByIdForUpdate(7, 29);
        order.verify(authority).revalidate();
        order.verify(workspaces).findByOrgId(3);
        order.verify(persons).updateProcessingRestrictions(7, 29, true, false);
        order.verify(persons).getPersonById(7, "[7,8,9]", 29);
        order.verify(identities).recordPersonVisibility(7, 29);
        order.verify(privacy).invalidateAssistantOrganization(org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.eq(List.of(7)),
            any(), org.mockito.ArgumentMatchers.eq("restriction"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        order.verify(scopes).getFreshForWorkspace(7);
        order.verify(cache).deleteForPerson(7, "[7,11,13]", 29);
        verify(scopes).getForWorkspace(7);
        verify(cache).deleteForPerson(anyInt(), anyString(), anyInt());
    }

    @Test
    void clearingBothRestrictionsDoesNotResolveAScopeOrDeleteCache() {
        when(persons.existsOwned(7, 29)).thenReturn(true);
        when(persons.getPersonById(7, "[7,8,9]", 29)).thenReturn(person);

        assertSame(person, service.updateProcessingRestrictions(29, false, false));

        verify(scopes).getForWorkspace(7);
        verify(scopes, never()).getFreshForWorkspace(anyInt());
        verifyNoInteractions(cache, privacy, workspaces);
    }

    @Test
    void missingPreparedScopeCannotReachTheDestructiveStatement() {
        Workspace active = new Workspace();
        active.setId(7);
        when(workspaces.findByOrgId(3)).thenReturn(List.of(active));
        when(scopes.getFreshForWorkspace(7)).thenThrow(new TenantScopeSnapshotMissingException("workspace 7"));
        when(persons.existsOwned(7, 29)).thenReturn(true);
        when(persons.getPersonById(7, "[7,8,9]", 29)).thenReturn(person);

        assertThrows(TenantScopeSnapshotMissingException.class,
            () -> service.updateProcessingRestrictions(29, false, true));

        verify(shares).revokePersonShares(29, 7);
        verifyNoInteractions(cache);
    }
}
