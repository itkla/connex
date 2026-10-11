package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ooo.klae.connex.backend.beans.PasskeyApprovalGap;
import ooo.klae.connex.backend.beans.PrivilegedMfaStandingRow;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyApprovalGapDto;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Pins how privileged-MFA standing is derived and relaxed while attestation is off (#1534). */
class PrivilegedAccountServiceTest {
    private static final int USER_ID = 7;
    private static final int PASSKEY_ROW_ID = 41;

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
