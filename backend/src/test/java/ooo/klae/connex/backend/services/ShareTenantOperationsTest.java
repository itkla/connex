package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.services.ShareService.Type;

@ExtendWith(MockitoExtension.class)
class ShareTenantOperationsTest {
    @Mock private ShareMapper shareMapper;

    private ShareTenantOperations operations;

    @BeforeEach
    void setUp() {
        operations = new ShareTenantOperations(shareMapper);
    }

    @Test
    void refusesAWorkspaceMissingFromTheTrustedOrganizationSnapshot() {
        ForbiddenException exception = assertThrows(ForbiddenException.class,
            () -> operations.share(Type.COMPANY, 101, 7, 12, List.of(7, 8), 42, false));

        assertEquals(
            "A record can only be shared by its owning workspace within its organization",
            exception.getMessage());
        verifyNoInteractions(shareMapper);
    }

    @Test
    void passesTheCompleteSnapshotToTheTenantGrant() {
        List<Integer> workspaceIds = List.of(7, 8, 10);
        when(shareMapper.shareCompany(101, 7, 8, 42, true, workspaceIds)).thenReturn(1);

        operations.share(Type.COMPANY, 101, 7, 8, workspaceIds, 42, true);

        verify(shareMapper).shareCompany(101, 7, 8, 42, true, workspaceIds);
    }

    @Test
    void provisionCessationRemainsFailClosedBeforeGranting() {
        Person person = new Person();
        person.setId(202);
        person.setProvisionCeasedAt(LocalDateTime.of(2026, 7, 21, 12, 0));
        when(shareMapper.ownsPerson(7, 202)).thenReturn(true);
        when(shareMapper.getOwnedPersonProvisionState(7, 202)).thenReturn(person);

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> operations.requireShareableOwned(Type.PERSON, 7, 202));

        assertEquals("Third-party provision has been ceased for this contact", exception.getMessage());
    }
}
