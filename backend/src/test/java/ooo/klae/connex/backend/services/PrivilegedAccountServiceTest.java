package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ooo.klae.connex.backend.beans.PasskeyApprovalGap;
import ooo.klae.connex.backend.beans.PrivilegedMfaStandingRow;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyApprovalGapDto;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaConfinementAcknowledgementRequiredException;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Pins how privileged-MFA standing is derived and relaxed while attestation is off (#1534). */
class PrivilegedAccountServiceTest {
    private static final int USER_ID = 7;
    private static final int PASSKEY_ROW_ID = 41;
    private static final int ORG_ID = 3;

    private final UserMapper userMapper = mock(UserMapper.class);

    @Test
    void standingIsDerivedFromTheThreeFactsReadTogether() {
        PrivilegedAccountService service = service("false");

        assertStanding(service, new PrivilegedMfaStandingRow(false, false, false), PrivilegedMfaStanding.UNPRIVILEGED);
        assertStanding(service, new PrivilegedMfaStandingRow(false, true, true), PrivilegedMfaStanding.UNPRIVILEGED);
        assertStanding(service, new PrivilegedMfaStandingRow(true, false, false), PrivilegedMfaStanding.ENROLLMENT_REQUIRED);
        assertStanding(service, new PrivilegedMfaStandingRow(true, true, false), PrivilegedMfaStanding.ATTESTATION_REQUIRED);
        assertStanding(service, new PrivilegedMfaStandingRow(true, true, true), PrivilegedMfaStanding.SATISFIED);
    }

    @Test
    void aMissingStandingRowFailsInsteadOfPassing() {
        PrivilegedAccountService service = service("true");

        assertThrows(IllegalStateException.class, () -> service.standing(USER_ID));
        assertThrows(IllegalStateException.class, () -> service.enforcedStanding(USER_ID));
    }

    /** Until attestation is enforced, an enrolled privileged account stands as it did before. */
    @Test
    void anUnapprovedAccountStandsSatisfiedOnlyWhileAttestationIsOff() {
        when(userMapper.privilegedMfaStanding(USER_ID)).thenReturn(new PrivilegedMfaStandingRow(true, true, false));

        assertEquals(PrivilegedMfaStanding.SATISFIED, service("false").enforcedStanding(USER_ID));
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, service("true").enforcedStanding(USER_ID));
        assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED, service("TRUE").enforcedStanding(USER_ID));
    }

    @ParameterizedTest
    @EnumSource(value = PrivilegedMfaStanding.class, names = {"UNPRIVILEGED", "SATISFIED", "ENROLLMENT_REQUIRED"})
    void everyOtherStandingIsEnforcedUnchangedWhateverTheSwitch(PrivilegedMfaStanding expected) {
        when(userMapper.privilegedMfaStanding(USER_ID)).thenReturn(rowFor(expected));

        assertEquals(expected, service("false").enforcedStanding(USER_ID));
        assertEquals(expected, service("true").enforcedStanding(USER_ID));
    }

    @Test
    void onlyEnrollmentAndAttestationConfine() {
        assertFalse(PrivilegedMfaStanding.UNPRIVILEGED.confines());
        assertFalse(PrivilegedMfaStanding.SATISFIED.confines());
        assertTrue(PrivilegedMfaStanding.ENROLLMENT_REQUIRED.confines());
        assertTrue(PrivilegedMfaStanding.ATTESTATION_REQUIRED.confines());
    }

    /** The last-sufficient-passkey rule is dormant until attestation is enforced. */
    @Test
    void theLastSufficientPasskeyRuleReadsNothingWhileAttestationIsOff() {
        assertFalse(service("false").isLastSufficientPasskey(USER_ID, PASSKEY_ROW_ID));

        verify(userMapper, never()).isPrivilegedAccount(anyInt());
        verify(userMapper, never()).isSufficientPasskey(anyInt(), anyInt());
        verify(userMapper, never()).hasOtherSufficientPasskey(anyInt(), anyInt());
    }

    @Test
    void onlyAPrivilegedAccountsSoleSufficientPasskeyIsTheLastOne() {
        PrivilegedAccountService service = service("true");
        when(userMapper.isPrivilegedAccount(USER_ID)).thenReturn(true);
        when(userMapper.isSufficientPasskey(USER_ID, PASSKEY_ROW_ID)).thenReturn(true);
        when(userMapper.hasOtherSufficientPasskey(USER_ID, PASSKEY_ROW_ID)).thenReturn(false);
        assertTrue(service.isLastSufficientPasskey(USER_ID, PASSKEY_ROW_ID));

        when(userMapper.hasOtherSufficientPasskey(USER_ID, PASSKEY_ROW_ID)).thenReturn(true);
        assertFalse(service.isLastSufficientPasskey(USER_ID, PASSKEY_ROW_ID));

        when(userMapper.hasOtherSufficientPasskey(USER_ID, PASSKEY_ROW_ID)).thenReturn(false);
        when(userMapper.isSufficientPasskey(USER_ID, PASSKEY_ROW_ID)).thenReturn(false);
        assertFalse(service.isLastSufficientPasskey(USER_ID, PASSKEY_ROW_ID));

        when(userMapper.isSufficientPasskey(USER_ID, PASSKEY_ROW_ID)).thenReturn(true);
        when(userMapper.isPrivilegedAccount(USER_ID)).thenReturn(false);
        assertFalse(service.isLastSufficientPasskey(USER_ID, PASSKEY_ROW_ID));
    }

    @Test
    void approvalGapsCarryTheBase64UrlCredentialIdThePasskeyListUses() {
        byte[] credentialId = "cred?>x".getBytes(StandardCharsets.UTF_8);
        when(userMapper.passkeyApprovalGaps(USER_ID)).thenReturn(List.of(
                new PasskeyApprovalGap(PASSKEY_ROW_ID, credentialId, 3, "Acme")));

        assertEquals(List.of(new PasskeyApprovalGapDto("Y3JlZD8-eA", 3, "Acme")),
                service("true").passkeyApprovalGaps(USER_ID));
    }

    /** While attestation is off, a role edit reads nothing for the check, not even its organization. */
    @Test
    void theConfinementCheckReadsNothingWhileAttestationIsOff() {
        PrivilegedAccountService service = service("false");

        ConfinementBaseline baseline = service.confinementBaseline(() -> {
            throw new AssertionError("The organization must not be read");
        }, List.of(USER_ID));
        service.requireConfinementAcknowledged(baseline, false);

        assertTrue(baseline.watchesNothing());
        verifyNoInteractions(userMapper);
    }

    @Test
    void anEditAffectingNobodyReadsNothing() {
        PrivilegedAccountService service = service("true");

        ConfinementBaseline baseline = service.confinementBaseline(() -> ORG_ID, List.of());
        service.requireConfinementAcknowledged(baseline, false);

        assertTrue(baseline.watchesNothing());
        verifyNoInteractions(userMapper);
    }

    /**
     * Only the accounts an edit makes newly privileged in the organization, and that hold no passkey
     * covered there, are counted (#1534). Repeated ids are read and counted once.
     */
    @Test
    void onlyNewlyPrivilegedAccountsWithoutCoverageHereAreCounted() {
        PrivilegedAccountService service = service("true");
        when(userMapper.privilegedInOrganizationAmong(ORG_ID, List.of(1, 2, 3, 4)))
                .thenReturn(List.of(1))
                .thenReturn(List.of(1, 2, 3, 4));
        when(userMapper.coveredInOrganizationAmong(eq(ORG_ID), argThat(ids -> Set.copyOf(ids).equals(Set.of(2, 3, 4)))))
                .thenReturn(List.of(3));

        ConfinementBaseline baseline = service.confinementBaseline(() -> ORG_ID, List.of(1, 2, 3, 4, 2));
        PrivilegedMfaConfinementAcknowledgementRequiredException refusal = assertThrows(
                PrivilegedMfaConfinementAcknowledgementRequiredException.class,
                () -> service.requireConfinementAcknowledged(baseline, false));

        assertEquals(2, refusal.getAffectedCount());
        assertEquals("PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED", refusal.getCode());
    }

    @Test
    void anEditWhoseNewlyPrivilegedAccountsAreAllCoveredHerePasses() {
        PrivilegedAccountService service = service("true");
        when(userMapper.privilegedInOrganizationAmong(ORG_ID, List.of(1, 2)))
                .thenReturn(List.of())
                .thenReturn(List.of(1, 2));
        when(userMapper.coveredInOrganizationAmong(eq(ORG_ID), argThat(ids -> Set.copyOf(ids).equals(Set.of(1, 2)))))
                .thenReturn(List.of(1, 2));

        service.requireConfinementAcknowledged(service.confinementBaseline(() -> ORG_ID, List.of(1, 2)), false);
    }

    @Test
    void anEditThatMakesNobodyNewlyPrivilegedSkipsTheCoverageRead() {
        PrivilegedAccountService service = service("true");
        when(userMapper.privilegedInOrganizationAmong(ORG_ID, List.of(1, 2))).thenReturn(List.of(1));

        service.requireConfinementAcknowledged(service.confinementBaseline(() -> ORG_ID, List.of(1, 2)), false);

        verify(userMapper, never()).coveredInOrganizationAmong(anyInt(), any());
    }

    @Test
    void anAcknowledgedEditReadsNothingAfterTheWrite() {
        PrivilegedAccountService service = service("true");
        when(userMapper.privilegedInOrganizationAmong(ORG_ID, List.of(USER_ID))).thenReturn(List.of());

        service.requireConfinementAcknowledged(service.confinementBaseline(() -> ORG_ID, List.of(USER_ID)), true);

        verify(userMapper).privilegedInOrganizationAmong(ORG_ID, List.of(USER_ID));
        verifyNoMoreInteractions(userMapper);
    }

    /**
     * A role with many assignees is read in bounded chunks, before the write and after it, rather
     * than in one unbounded list.
     */
    @Test
    void manyAccountsAreReadInBoundedChunksBeforeAndAfterTheWrite() {
        PrivilegedAccountService service = service("true");
        List<Integer> ids = IntStream.rangeClosed(1, 1201).boxed().toList();
        when(userMapper.privilegedInOrganizationAmong(eq(ORG_ID), any()))
                .thenReturn(List.of(), List.of(), List.of())
                .thenAnswer(invocation -> List.copyOf(invocation.<Collection<Integer>>getArgument(1)));
        when(userMapper.coveredInOrganizationAmong(eq(ORG_ID), any())).thenReturn(List.of());

        ConfinementBaseline baseline = service.confinementBaseline(() -> ORG_ID, ids);
        PrivilegedMfaConfinementAcknowledgementRequiredException refusal = assertThrows(
                PrivilegedMfaConfinementAcknowledgementRequiredException.class,
                () -> service.requireConfinementAcknowledged(baseline, false));

        assertEquals(1201, refusal.getAffectedCount());
        verify(userMapper, times(2)).privilegedInOrganizationAmong(ORG_ID, ids.subList(0, 500));
        verify(userMapper, times(2)).privilegedInOrganizationAmong(ORG_ID, ids.subList(500, 1000));
        verify(userMapper, times(2)).privilegedInOrganizationAmong(ORG_ID, ids.subList(1000, 1201));
        verify(userMapper, times(3)).coveredInOrganizationAmong(eq(ORG_ID), argThat(chunk -> chunk.size() <= 500));
        verifyNoMoreInteractions(userMapper);
    }

    private void assertStanding(
            PrivilegedAccountService service, PrivilegedMfaStandingRow row, PrivilegedMfaStanding expected) {
        when(userMapper.privilegedMfaStanding(USER_ID)).thenReturn(row);
        assertEquals(expected, service.standing(USER_ID));
    }

    private PrivilegedAccountService service(String attestationEnforced) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setAttestationEnforced(attestationEnforced);
        return new PrivilegedAccountService(userMapper, properties);
    }

    private static PrivilegedMfaStandingRow rowFor(PrivilegedMfaStanding standing) {
        return switch (standing) {
            case UNPRIVILEGED -> new PrivilegedMfaStandingRow(false, false, false);
            case SATISFIED -> new PrivilegedMfaStandingRow(true, true, true);
            case ENROLLMENT_REQUIRED -> new PrivilegedMfaStandingRow(true, false, false);
            case ATTESTATION_REQUIRED -> new PrivilegedMfaStandingRow(true, true, false);
        };
    }
}
