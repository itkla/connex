package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;

@ExtendWith(MockitoExtension.class)
class UserImageReplacementAdmissionServiceTest {
    @Mock UserObjectDeletionQueueMapper deletionQueueMapper;

    private ObjectStorageProperties properties;
    private UserImageReplacementAdmissionService service;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        service = new UserImageReplacementAdmissionService(
            deletionQueueMapper,
            properties,
            Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void allowsReplacementBelowSharedBacklogAndHourlyLimits() {
        when(deletionQueueMapper.countPendingForPrefix("users/7/profile-images/"))
            .thenReturn(1L);

        assertDoesNotThrow(() -> service.requireAllowed(7));

        verify(deletionQueueMapper).countPendingForPrefix("users/7/profile-images/");
    }

    @Test
    void blocksReplacementAtSharedDeletionBacklogCap() {
        when(deletionQueueMapper.countPendingForPrefix("users/7/profile-images/"))
            .thenReturn(2L);

        assertThrows(ServiceUnavailableException.class, () -> service.requireAllowed(7));
    }

    @Test
    void blocksReplacementAtPerProcessHourlyLimit() {
        properties.setMaxUserImageReplacementsPerHour(2);

        service.requireAllowed(7);
        service.requireAllowed(7);

        assertThrows(TooManyRequestsException.class, () -> service.requireAllowed(7));
    }
}
