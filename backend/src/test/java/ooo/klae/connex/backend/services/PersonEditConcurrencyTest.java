package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.StaleRecordException;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.tenant.Permission;

class PersonEditConcurrencyTest {
    private final PersonMapper mapper = mock(PersonMapper.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final DuplicateDecisionLockService duplicateLocks = mock(DuplicateDecisionLockService.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final EmploymentService employmentService = mock(EmploymentService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final RuleTriggerPublisher ruleTriggers = mock(RuleTriggerPublisher.class);
    private final IdentityIntakeService identityIntakeService = mock(IdentityIntakeService.class);
    private PersonService service;

    @BeforeEach
    void setUp() {
        service = new PersonService(
            mapper,
            mock(ShareMapper.class),
            mock(AiOutputCacheMapper.class),
            companyMapper,
            mock(TagMapper.class),
            mock(DealMapper.class),
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            mock(ooo.klae.connex.backend.mappers.WorkspaceMapper.class),
            mock(AuthService.class),
            auditService,
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            workspaceService,
            employmentService,
            mock(CustomFieldValueService.class),
            mock(ReferenceService.class),
            ruleTriggers,
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            identityIntakeService,
            mock(DuplicatePreflightService.class),
            duplicateLocks,
            mock(RecordCreationAugmentationService.class),
            mock(ooo.klae.connex.backend.mappers.ProviderCaptureMapper.class),
            mock(ooo.klae.connex.backend.ai.mission.AiAgentPrivacyService.class),
            ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered()
        );
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(duplicateLocks.lockCurrentOrganization(Permission.PERSON_UPDATE)).thenReturn(
            new DuplicateDecisionLockService.LockedOrganization(1, mock(WorkspaceService.LockedPermissionSnapshot.class)));
    }

    @Test
    void knownStaleEditRefusesBeforeCompanyValidationAndEveryMutation() {
        Person before = person(2);
        Person edit = person(1);
        edit.setExpectedVersion(1L);
        Company missingCompany = new Company();
        missingCompany.setId(99);
        edit.setCompany(missingCompany);
        when(mapper.getOwnedPersonByIdForUpdate(7, 13)).thenReturn(before);

        StaleRecordException error = assertThrows(StaleRecordException.class, () -> service.update(13, edit));

        assertEquals(2L, error.getCurrentVersion());
        verify(mapper, never()).update(any(Person.class));
        verifyNoInteractions(companyMapper, employmentService, identityIntakeService, auditService, ruleTriggers);
    }

    @Test
    void atomicZeroRowRefusalReportsTheNewVersionAndHasNoDependentEffects() {
        when(mapper.getOwnedPersonByIdForUpdate(7, 13)).thenReturn(person(1), person(2));
        Person edit = person(1);
        edit.setExpectedVersion(1L);

        StaleRecordException error = assertThrows(StaleRecordException.class, () -> service.update(13, edit));

        assertEquals(2L, error.getCurrentVersion());
        verify(mapper).update(edit);
        verifyNoInteractions(employmentService, identityIntakeService, auditService, ruleTriggers);
    }

    @Test
    void aMissingRowAtTheAtomicFenceRemainsNotFound() {
        when(mapper.getOwnedPersonByIdForUpdate(7, 13)).thenReturn(person(1)).thenReturn(null);
        Person edit = person(1);
        edit.setExpectedVersion(1L);

        assertThrows(ResourceNotFoundException.class, () -> service.update(13, edit));

        verifyNoInteractions(employmentService, identityIntakeService, auditService, ruleTriggers);
    }

    private static Person person(long version) {
        Person person = new Person();
        person.setId(13);
        person.setWorkspaceId(7);
        person.setName("Contact");
        person.setVersion(version);
        return person;
    }
}
