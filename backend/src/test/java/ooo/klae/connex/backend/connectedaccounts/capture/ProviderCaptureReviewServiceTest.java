package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewDto;
import ooo.klae.connex.backend.dto.ProviderCaptureReviewRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderCaptureReviewServiceTest {

    @Test
    void disabledCaptureRejectsParticipantDecisionsBeforeMutation() {
        Fixture fixture = fixture();

        assertThrows(
            ConflictException.class,
            () -> fixture.service().decide(
                "google",
                41,
                new ProviderCaptureReviewRequest(
                    "ignore", 1, false, null, null, null)));

        verify(fixture.captureMapper(), never()).getParticipant(
            7, 9, "google", 41);
    }

    @Test
    void disabledCaptureRejectsInteractionApprovalBeforeMutation() {
        Fixture fixture = fixture();

        assertThrows(
            ConflictException.class,
            () -> fixture.service().approve("google", 51, 3));

        verify(fixture.captureMapper(), never()).getInteractionForUpdate(
            7, 9, "google", 51);
    }

    @Test
    void changedCredentialGenerationRejectsAdmissionBeforeMutation() {
        Fixture fixture = fixture();
        ProviderConnection changed = new ProviderConnection();
        changed.setStatus("connected");
        changed.setCredentialGeneration(5);
        when(fixture.connectionMapper().getByUserAndProviderForShare(9, "google"))
            .thenReturn(changed);

        assertThrows(
            ConflictException.class,
            () -> fixture.service().approve("google", 51, 3));

        verify(fixture.captureMapper(), never()).getInteractionForUpdate(
            7, 9, "google", 51);
    }

    private static Fixture fixture() {
        ProviderCaptureMapper captureMapper = mock(ProviderCaptureMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        ProviderCapturePolicyService policyService =
            mock(ProviderCapturePolicyService.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        ProviderConnection connection = new ProviderConnection();
        connection.setStatus("connected");
        connection.setCredentialGeneration(4);
        when(connectionMapper.getByUserAndProvider(9, "google"))
            .thenReturn(connection);
        when(connectionMapper.getByUserAndProviderForShare(9, "google"))
            .thenReturn(connection);
        when(policyService.getCurrentOverview("google"))
            .thenReturn(mock(ProviderCaptureOverviewDto.class));
        when(policyService.effectivePolicy(7, 9, "google", connection))
            .thenReturn(new CaptureExecutionPolicy(
                false,
                false,
                false,
                false,
                90,
                false,
                "review",
                true,
                false,
                List.of(),
                List.of(),
                List.of(),
                1));
        ProviderCaptureReviewService service =
            new ProviderCaptureReviewService(
                captureMapper,
                connectionMapper,
                mock(IdentityMapper.class),
                mock(MatchingService.class),
                mock(PersonService.class),
                workspaceService,
                policyService,
                mock(ProviderCapturePagePersistence.class),
                mock(AuditService.class),
                transactionManager,
                tenantWorkScope,
                mock(DuplicateDecisionLockService.class));
        return new Fixture(service, captureMapper, connectionMapper);
    }

    private record Fixture(
        ProviderCaptureReviewService service,
        ProviderCaptureMapper captureMapper,
        ProviderConnectionMapper connectionMapper
    ) {
    }
}
