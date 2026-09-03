package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.IdentityKeyRow;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;

@ExtendWith(MockitoExtension.class)
class IdentityIntakeServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 12, 0);

    @Mock private IdentityMapper identityMapper;
    @Mock private IdentityCollisionMapper identityCollisionMapper;
    @Mock private DuplicateReviewService duplicateReviewService;
    @Mock private MatchingService matchingService;

    private IdentityIntakeService service;

    @BeforeEach
    void setUp() {
        service = new IdentityIntakeService(
            identityMapper,
            identityCollisionMapper,
            duplicateReviewService,
            matchingService,
            CLOCK);
    }

    @Test
    void hiddenPersonRefreshIncludesExternalIdAndNeverRebuildsWholeGroups() {
        when(identityMapper.lockCurrentPersonIdentityKeysForRecord(7, 41))
            .thenReturn(List.of(
                key(41, "email", "person@example.com"),
                key(41, "external_id", "source:person-41")));

        service.recordPersonVisibility(7, 41);

        verify(identityMapper).lockCurrentPersonIdentityGroupPrefix(
            7, "email", "person@example.com", 21);
        verify(identityMapper).lockCurrentPersonIdentityGroupPrefix(
            7, "external_id", "source:person-41", 21);
        verify(identityCollisionMapper).deletePersonCollisionMembershipsForRecord(7, 41);
        verify(identityCollisionMapper).ensurePersonCollisionPairForRecord(
            7, 41, "external_id", "source:person-41", NOW);
        verify(identityCollisionMapper).deletePersonSingletonCollisionMember(
            7, "external_id", "source:person-41");
        verify(duplicateReviewService).refreshPersonEvidence(
            7, "external_id", "source:person-41", NOW);
        verify(identityCollisionMapper, never()).deletePersonCollisionGroup(
            org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString());
        verify(identityCollisionMapper, never()).insertPersonCollisionGroup(
            org.mockito.ArgumentMatchers.anyInt(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void restoredCompanyRefreshIncludesExternalIdAndAddsOnlyTargetCollisionPair() {
        when(identityMapper.lockCurrentCompanyIdentityKeysForRecord(7, 51))
            .thenReturn(List.of(key(51, "external_id", "source:company-51")));

        service.recordCompanyVisibility(7, 51);

        verify(identityMapper).lockCurrentCompanyIdentityGroupPrefix(
            7, "external_id", "source:company-51", 21);
        verify(identityCollisionMapper).deleteCompanyCollisionMembershipsForRecord(7, 51);
        verify(identityCollisionMapper).ensureCompanyCollisionPairForRecord(
            7, 51, "external_id", "source:company-51", NOW);
        verify(identityCollisionMapper).deleteCompanySingletonCollisionMember(
            7, "external_id", "source:company-51");
        verify(duplicateReviewService).refreshCompanyEvidence(
            7, "external_id", "source:company-51", NOW);
        verify(identityCollisionMapper, never()).deleteCompanyCollisionGroup(
            org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString());
        verify(identityCollisionMapper, never()).insertCompanyCollisionGroup(
            org.mockito.ArgumentMatchers.anyInt(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hiddenCompanyRefreshIncludesExternalIdAndRemovesOnlyAffectedMemberships() {
        when(identityMapper.lockCurrentCompanyIdentityKeysForRecord(7, 51))
            .thenReturn(List.of(key(51, "external_id", "source:company-51")));

        service.recordCompanyVisibility(7, 51);

        verify(identityMapper).lockCurrentCompanyIdentityGroupPrefix(
            7, "external_id", "source:company-51", 21);
        verify(identityCollisionMapper).deleteCompanyCollisionMembershipsForRecord(7, 51);
        verify(identityCollisionMapper).ensureCompanyCollisionPairForRecord(
            7, 51, "external_id", "source:company-51", NOW);
        verify(identityCollisionMapper).deleteCompanySingletonCollisionMember(
            7, "external_id", "source:company-51");
        verify(duplicateReviewService).refreshCompanyEvidence(
            7, "external_id", "source:company-51", NOW);
        verify(identityCollisionMapper, never()).deleteCompanyCollisionGroup(
            org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString());
        verify(identityCollisionMapper, never()).insertCompanyCollisionGroup(
            org.mockito.ArgumentMatchers.anyInt(),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.any());
    }

    private static IdentityKeyRow key(int recordId, String kind, String normalizedValue) {
        IdentityKeyRow row = new IdentityKeyRow();
        row.setRecordId(recordId);
        row.setKind(kind);
        row.setNormalizedValue(normalizedValue);
        return row;
    }
}
