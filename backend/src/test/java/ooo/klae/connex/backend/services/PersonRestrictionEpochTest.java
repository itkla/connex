package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.storage.ManagedObjectService;

class PersonRestrictionEpochTest {
    private static final int WORKSPACE_ID = 7;
    private static final int OTHER_WORKSPACE_ID = 11;
    private static final int ORG_ID = 3;
    private static final int PERSON_ID = 29;

    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final AiOutputCacheMapper aiOutputCacheMapper = mock(AiOutputCacheMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final DuplicateDecisionLockService duplicateDecisionLockService =
            mock(DuplicateDecisionLockService.class);
    private final ProviderCaptureMapper providerCaptureMapper = mock(ProviderCaptureMapper.class);
    private final AiRestrictionEpoch restrictionEpoch = mock(AiRestrictionEpoch.class);
    private final PersonService personService = new PersonService(
            personMapper,
            mock(ShareMapper.class),
            aiOutputCacheMapper,
            mock(CompanyMapper.class),
            mock(TagMapper.class),
            mock(DealMapper.class),
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            workspaceMapper,
            mock(AuthService.class),
            mock(AuditService.class),
            mock(NotificationChangePublisher.class),
            workspaceService,
            mock(EmploymentService.class),
            mock(CustomFieldValueService.class),
            mock(ReferenceService.class),
            mock(RuleTriggerPublisher.class),
            mock(ManagedObjectService.class),
            mock(IdentityIntakeService.class),
            mock(DuplicatePreflightService.class),
            duplicateDecisionLockService,
            providerCaptureMapper,
            restrictionEpoch);

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(duplicateDecisionLockService.lockCurrentOrganization()).thenReturn(ORG_ID);
        when(workspaceMapper.findByOrgId(ORG_ID)).thenReturn(List.of(
                workspace(OTHER_WORKSPACE_ID), workspace(WORKSPACE_ID)));
    }

    @Test
    void processingRestrictionBumpsEveryAffectedWorkspaceBeforePurge() {
        Person before = person(null);
        Person after = person(LocalDateTime.parse("2026-08-01T12:00:00"));
        when(personMapper.getOwnedPersonByIdForUpdate(WORKSPACE_ID, PERSON_ID)).thenReturn(before);
        when(personMapper.existsOwned(WORKSPACE_ID, PERSON_ID)).thenReturn(true);
        when(personMapper.getPersonById(WORKSPACE_ID, PERSON_ID)).thenReturn(after);
        when(aiOutputCacheMapper.deleteForPerson(WORKSPACE_ID, PERSON_ID)).thenReturn(4);

        Person result = personService.updateProcessingRestrictions(PERSON_ID, true, false);

        assertSame(after, result);
        InOrder fence = inOrder(restrictionEpoch, aiOutputCacheMapper);
        fence.verify(restrictionEpoch).bump(WORKSPACE_ID);
        fence.verify(restrictionEpoch).bump(OTHER_WORKSPACE_ID);
        fence.verify(aiOutputCacheMapper).deleteForPerson(WORKSPACE_ID, PERSON_ID);
    }

    private static Workspace workspace(int id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        return workspace;
    }

    private static Person person(LocalDateTime suspendedAt) {
        Person person = new Person();
        person.setId(PERSON_ID);
        person.setName("Mina Patel");
        person.setSuspendedAt(suspendedAt);
        return person;
    }
}
