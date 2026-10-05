package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

class PersonServiceUnitTest {
    @Test
    void getMatchingPersonIdsRejectsTooManyMatchesBeforeFetchingIds() {
        PersonMapper mapper = mock(PersonMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        PersonService service = new PersonService(
            mapper,
            mock(ShareMapper.class),
            mock(AiOutputCacheMapper.class),
            mock(CompanyMapper.class),
            mock(TagMapper.class),
            mock(DealMapper.class),
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            mock(ooo.klae.connex.backend.mappers.WorkspaceMapper.class),
            mock(AuthService.class),
            mock(AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            workspaceService,
            mock(EmploymentService.class),
            mock(CustomFieldValueService.class),
            mock(ReferenceService.class),
            mock(RuleTriggerPublisher.class),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(IdentityIntakeService.class),
            mock(DuplicatePreflightService.class),
            mock(DuplicateDecisionLockService.class),
            mock(RecordCreationAugmentationService.class),
            mock(ooo.klae.connex.backend.mappers.ProviderCaptureMapper.class),
            mock(AiRestrictionEpoch.class)
        );
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countPersons(
            7, "Security", null, null, false, MemberScope.allTeam(),
            null, false, null, false, null, false, false, null)).thenReturn(1001L);

        assertThrows(BadRequestException.class,
            () -> service.getMatchingPersonIds(
                "Security", null, null, false, MemberScope.allTeam(), null, false, null, false,
                null, false, false, null));

        verify(mapper, never()).getPersonIdsFiltered(
            7, "Security", null, null, false, MemberScope.allTeam(), null, false, null, false,
            null, false, false, null, 1000);
    }
}
