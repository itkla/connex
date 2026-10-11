package ooo.klae.connex.backend.webauthn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.session.SessionEpochRestampGrant;
import ooo.klae.connex.backend.session.StepUpProof;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.LastSufficientPasskeyRemovalForbiddenException;
import ooo.klae.connex.backend.exceptions.PasskeyEnrollmentRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.beans.User;

import java.time.Duration;
import java.util.List;

class WebAuthnServiceTest {
    private static final EnrollmentEvidence NO_EVIDENCE = new EnrollmentEvidence(null, null);
    private static final int NEW_PASSKEY_ROW_ID = 55;
    private static final int STEP_UP_PASSKEY_ROW_ID = 41;

    @Test
    void passkeyPresenceUsesExistenceQueryWithoutLoadingCredentialMaterial() {
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class),
                mock(UserCredentialRepository.class),
                mock(WebauthnUserEntityMapper.class),
                credentialMapper,
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                mock(UserMapper.class),
                mock(PrivilegedAccountService.class),
                mock(PasskeyBootstrapConfirmationPolicy.class),
                mock(AuditService.class));
        when(credentialMapper.existsByUserId(7)).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertTrue(service.hasPasskey(7));

        verify(credentialMapper).existsByUserId(7);
        verify(credentialMapper, never()).findByUserEntityUserId(any());
    }

    /**
     * The operator break-glass ceremony is the documented route back for a privileged account that
     * cannot satisfy the first-enrollment confirmation, and such an account has never enrolled.
     * Refusing it here would leave that route unexecutable.
     */
    /**
     * Recovery takes an account id the controller already authenticated. If that row is gone by the
     * time the lock runs, the caller is no longer authenticated — not looking at something missing.
     */
    @Test
    void recoverForAnAccountThatNoLongerExistsFailsAuthentication() {
        UserMapper userMapper = mock(UserMapper.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class),
                mock(UserCredentialRepository.class),
                mock(WebauthnUserEntityMapper.class),
                mock(WebauthnCredentialMapper.class),
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                userMapper,
                mock(PrivilegedAccountService.class),
                mock(PasskeyBootstrapConfirmationPolicy.class),
                mock(AuditService.class));
        when(userMapper.lockById(7)).thenReturn(null);

        assertThrows(AuthenticationException.class, () -> service.recover(7));
    }

    /** The audit subject lookup answers the same way when the account vanished mid-flow. */
    @Test
    void deletingAPasskeyForAnAccountThatVanishedMidFlowFailsAuthentication() {
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class), credentials, userEntities,
                credentialMapper, mock(PrivilegedCredentialAttestationMapper.class), mock(SessionSecurityService.class), userMapper, privilegedAccounts,
                mock(PasskeyBootstrapConfirmationPolicy.class), mock(AuditService.class));
        Bytes credentialId = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        WebauthnCredentialRow target = new WebauthnCredentialRow();
        target.setId(11);
        target.setCredentialId(credentialId.getBytes());
        target.setLabel("Work key");
        WebauthnCredentialRow retained = new WebauthnCredentialRow();
        retained.setId(12);
        retained.setCredentialId(Bytes.random().getBytes());
        when(userMapper.lockById(7)).thenReturn(7);
        when(userEntities.findByUserId(7)).thenReturn(entity);
        when(credentialMapper.findByUserEntityUserIdForUpdate("handle"))
                .thenReturn(List.of(target, retained));

        assertThrows(AuthenticationException.class,
                () -> service.delete(7, credentialId.toBase64UrlString()));
    }

    @Test
    void recoverRemovesNothingWhenTheAccountHasNoCredentialEntity() {
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class),
                mock(UserCredentialRepository.class),
                userEntities,
                mock(WebauthnCredentialMapper.class),
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                userMapper,
                mock(PrivilegedAccountService.class),
                mock(PasskeyBootstrapConfirmationPolicy.class),
                mock(AuditService.class));
        when(userMapper.lockById(7)).thenReturn(7);
        when(userEntities.findByUserId(7)).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertEquals(0, service.recover(7));
    }

    @Test
    void recoverRemovesNothingWhenTheEntityHoldsNoCredential() {
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        UserMapper userMapper = mock(UserMapper.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class),
                credentials,
                userEntities,
                credentialMapper,
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                userMapper,
                mock(PrivilegedAccountService.class),
                mock(PasskeyBootstrapConfirmationPolicy.class),
                mock(AuditService.class));
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        entity.setUserId(7);
        when(userMapper.lockById(7)).thenReturn(7);
        when(userEntities.findByUserId(7)).thenReturn(entity);
        when(credentialMapper.findByUserEntityUserIdForUpdate("handle")).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertEquals(0, service.recover(7));

        verify(credentials, never()).delete(any());
    }

    @Test
    void finishRegistrationRejectsOptionsIssuedForAnotherAccount() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WebAuthnService service = new WebAuthnService(
            relyingParty, credentials, userEntities,
            mock(WebauthnCredentialMapper.class), mock(PrivilegedCredentialAttestationMapper.class), mock(SessionSecurityService.class), userMapper,
            mock(PrivilegedAccountService.class),
            mock(PasskeyBootstrapConfirmationPolicy.class), mock(AuditService.class));
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        PublicKeyCredentialUserEntity optionUser = mock(PublicKeyCredentialUserEntity.class);
        Bytes handle = new Bytes(new byte[] {1, 2, 3});
        when(options.getUser()).thenReturn(optionUser);
        when(optionUser.getId()).thenReturn(handle);
        when(userEntities.findUserIdByHandle(handle.toBase64UrlString())).thenReturn(7);
        when(userMapper.lockById(8)).thenReturn(8);
        when(userMapper.currentSessionEpoch(8)).thenReturn(0);

        assertThrows(BadCredentialsException.class, () -> service.finishRegistration(
            8, 0, true, NO_EVIDENCE, options, null, "work key"));

        verify(relyingParty, never()).registerCredential(org.mockito.ArgumentMatchers.any());
        verify(credentials, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finishRegistrationPersistsOnlyWithStrictEnrollmentAudit() {
        RegistrationFixture fixture = successfulRegistration();

        RegisteredPasskey registered = fixture.service().finishRegistration(
                7, 3, true, NO_EVIDENCE, fixture.options(), fixture.credential(), "Work key");

        assertEquals(NEW_PASSKEY_ROW_ID, registered.credentialRowId());
        verify(fixture.relyingParty()).registerCredential(any());
        verify(fixture.credentials(), never()).save(any());
        verify(fixture.userMapper()).clearEpochRestampGrant(7);
        verify(fixture.auditService()).recordStrict(
                org.mockito.ArgumentMatchers.eq("auth.passkey.register"),
                org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq("Admin"),
                org.mockito.ArgumentMatchers.eq("Passkey registered"),
                any());
    }

    /**
     * A new passkey takes its founder coverage first and only then inherits the coverage and
     * assurance of the passkey whose fresh step-up authorized it, so a direct source wins (#1534).
     */
    @Test
    void finishRegistrationRecordsFounderCoverageBeforeInheritingFromAFreshOwnedStepUp() {
        RegistrationFixture fixture = successfulRegistration();
        StepUpProof proof = new StepUpProof(STEP_UP_PASSKEY_ROW_ID, 1_000L);
        when(fixture.sessionSecurity().isFresh(proof)).thenReturn(true);
        when(fixture.credentialMapper().findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7))
                .thenReturn(STEP_UP_PASSKEY_ROW_ID);

        fixture.service().finishRegistration(7, 3, false, new EnrollmentEvidence("session-a", proof),
                fixture.options(), fixture.credential(), "Work key");

        InOrder order = inOrder(fixture.userMapper(), fixture.attestationMapper(), fixture.credentialMapper());
        order.verify(fixture.userMapper()).lockById(7);
        order.verify(fixture.userMapper()).lockAssignedCustomRoleIds(7);
        order.verify(fixture.attestationMapper()).insertFounderCoverage(NEW_PASSKEY_ROW_ID, 7);
        order.verify(fixture.attestationMapper())
                .insertInheritedCoverage(NEW_PASSKEY_ROW_ID, STEP_UP_PASSKEY_ROW_ID);
        order.verify(fixture.credentialMapper())
                .copyPrivilegedAssurance(NEW_PASSKEY_ROW_ID, STEP_UP_PASSKEY_ROW_ID);
        verify(fixture.credentialMapper(), never()).markBreakGlassAssurance(NEW_PASSKEY_ROW_ID);
    }

    @Test
    void finishRegistrationInheritsNothingFromAStaleStepUp() {
        RegistrationFixture fixture = successfulRegistration();
        StepUpProof proof = new StepUpProof(STEP_UP_PASSKEY_ROW_ID, 1_000L);
        when(fixture.sessionSecurity().isFresh(proof)).thenReturn(false);
        when(fixture.credentialMapper().findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7))
                .thenReturn(STEP_UP_PASSKEY_ROW_ID);

        fixture.service().finishRegistration(7, 3, false, new EnrollmentEvidence("session-a", proof),
                fixture.options(), fixture.credential(), "Work key");

        verify(fixture.attestationMapper()).insertFounderCoverage(NEW_PASSKEY_ROW_ID, 7);
        verify(fixture.attestationMapper(), never()).insertInheritedCoverage(anyInt(), anyInt());
        verify(fixture.credentialMapper(), never()).copyPrivilegedAssurance(anyInt(), anyInt());
    }

    @Test
    void finishRegistrationInheritsNothingFromAStepUpPasskeyThatIsGoneOrAnotherAccounts() {
        RegistrationFixture fixture = successfulRegistration();
        StepUpProof proof = new StepUpProof(STEP_UP_PASSKEY_ROW_ID, 1_000L);
        when(fixture.sessionSecurity().isFresh(proof)).thenReturn(true);
        when(fixture.credentialMapper().findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7)).thenReturn(null);

        fixture.service().finishRegistration(7, 3, false, new EnrollmentEvidence("session-a", proof),
                fixture.options(), fixture.credential(), "Work key");

        verify(fixture.attestationMapper(), never()).insertInheritedCoverage(anyInt(), anyInt());
        verify(fixture.credentialMapper(), never()).copyPrivilegedAssurance(anyInt(), anyInt());
    }

    /**
     * Only the session the operator recovery granted the restamp to, at the epoch that recovery
     * committed, earns break-glass assurance. Another password session of the same account racing
     * the owner's re-enrollment does not (#1534).
     */
    @Test
    void finishRegistrationGrantsBreakGlassOnlyToTheRecoverySessionAtItsEpoch() {
        RegistrationFixture recovering = successfulRegistration();
        when(recovering.userMapper().epochRestampGrant(7))
                .thenReturn(new SessionEpochRestampGrant("session-a", 3));
        recovering.service().finishRegistration(7, 3, true, new EnrollmentEvidence("session-a", null),
                recovering.options(), recovering.credential(), "Work key");
        verify(recovering.credentialMapper()).markBreakGlassAssurance(NEW_PASSKEY_ROW_ID);
        InOrder order = inOrder(recovering.userMapper());
        order.verify(recovering.userMapper()).epochRestampGrant(7);
        order.verify(recovering.userMapper()).clearEpochRestampGrant(7);

        RegistrationFixture racing = successfulRegistration();
        when(racing.userMapper().epochRestampGrant(7))
                .thenReturn(new SessionEpochRestampGrant("session-a", 3));
        racing.service().finishRegistration(7, 3, true, new EnrollmentEvidence("session-b", null),
                racing.options(), racing.credential(), "Work key");
        verify(racing.credentialMapper(), never()).markBreakGlassAssurance(anyInt());

        RegistrationFixture staleEpoch = successfulRegistration();
        when(staleEpoch.userMapper().epochRestampGrant(7))
                .thenReturn(new SessionEpochRestampGrant("session-a", 2));
        staleEpoch.service().finishRegistration(7, 3, true, new EnrollmentEvidence("session-a", null),
                staleEpoch.options(), staleEpoch.credential(), "Work key");
        verify(staleEpoch.credentialMapper(), never()).markBreakGlassAssurance(anyInt());
    }

    /**
     * An enrollment that cannot name its session while a recovery grant is outstanding is refused
     * before anything is registered, so the grant survives for the retry instead of being spent
     * without the assurance it carries (#1534).
     */
    @Test
    void finishRegistrationRefusesAnUnnamedSessionWhileARecoveryGrantIsOutstanding() {
        RegistrationFixture fixture = successfulRegistration();
        when(fixture.userMapper().epochRestampGrant(7))
                .thenReturn(new SessionEpochRestampGrant("session-a", 3));

        assertThrows(ConflictException.class, () -> fixture.service().finishRegistration(
                7, 3, true, NO_EVIDENCE, fixture.options(), fixture.credential(), "Work key"));

        verify(fixture.relyingParty(), never()).registerCredential(any());
        verify(fixture.userMapper(), never()).clearEpochRestampGrant(7);
        verify(fixture.credentialMapper(), never()).markBreakGlassAssurance(anyInt());
    }

    /** Break-glass assurance takes precedence over assurance the step-up passkey would pass on. */
    @Test
    void finishRegistrationPrefersBreakGlassOverInheritedAssurance() {
        RegistrationFixture fixture = successfulRegistration();
        StepUpProof proof = new StepUpProof(STEP_UP_PASSKEY_ROW_ID, 1_000L);
        when(fixture.sessionSecurity().isFresh(proof)).thenReturn(true);
        when(fixture.credentialMapper().findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7))
                .thenReturn(STEP_UP_PASSKEY_ROW_ID);
        when(fixture.userMapper().epochRestampGrant(7))
                .thenReturn(new SessionEpochRestampGrant("session-a", 3));

        fixture.service().finishRegistration(7, 3, true, new EnrollmentEvidence("session-a", proof),
                fixture.options(), fixture.credential(), "Work key");

        verify(fixture.attestationMapper())
                .insertInheritedCoverage(NEW_PASSKEY_ROW_ID, STEP_UP_PASSKEY_ROW_ID);
        verify(fixture.credentialMapper(), never()).copyPrivilegedAssurance(anyInt(), anyInt());
        verify(fixture.credentialMapper()).markBreakGlassAssurance(NEW_PASSKEY_ROW_ID);
    }

    /**
     * A first-passkey confirmation required only under the account lock is refused there. The refusal
     * is recorded with the controller's row but deferred until the transaction completes, because an
     * immediate independent append would wait on this transaction's own account lock (#1995).
     */
    @Test
    void finishRegistrationDefersTheAuditOfABootstrapConfirmationRefusedUnderTheLock() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        UserMapper userMapper = mock(UserMapper.class);
        PasskeyBootstrapConfirmationPolicy policy = mock(PasskeyBootstrapConfirmationPolicy.class);
        AuditService auditService = mock(AuditService.class);
        WebAuthnService service = new WebAuthnService(
                relyingParty,
                credentials,
                mock(WebauthnUserEntityMapper.class),
                mock(WebauthnCredentialMapper.class),
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                userMapper,
                mock(PrivilegedAccountService.class),
                policy,
                auditService);
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.currentSessionEpoch(7)).thenReturn(3);
        when(userMapper.getUserById(7)).thenReturn(user);
        when(policy.requiresConfirmation(7)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> service.finishRegistration(
                7, 3, false, NO_EVIDENCE, mock(PublicKeyCredentialCreationOptions.class), null, "Work key"));

        InOrder order = inOrder(userMapper, policy, auditService);
        order.verify(userMapper).lockById(7);
        order.verify(userMapper).lockAssignedCustomRoleIds(7);
        order.verify(policy).requiresConfirmation(7);
        order.verify(auditService).deferFailureScoped(
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_ACTION,
                "user",
                7,
                null,
                null,
                "Admin",
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_SUMMARY,
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_REASON);
        verify(auditService, never()).recordStrictFailureIndependentScoped(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditService, never()).recordFailureScoped(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(relyingParty, never()).registerCredential(any());
        verify(credentials, never()).save(any());
    }

    /**
     * A failed read of the refused account's label leaves the audit label empty; it never turns the
     * refusal into a different error for the caller.
     */
    @Test
    void finishRegistrationKeepsTheBootstrapRefusalWhenTheAuditLabelCannotBeRead() {
        UserMapper userMapper = mock(UserMapper.class);
        PasskeyBootstrapConfirmationPolicy policy = mock(PasskeyBootstrapConfirmationPolicy.class);
        AuditService auditService = mock(AuditService.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class),
                mock(UserCredentialRepository.class),
                mock(WebauthnUserEntityMapper.class),
                mock(WebauthnCredentialMapper.class),
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                userMapper,
                mock(PrivilegedAccountService.class),
                policy,
                auditService);
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.currentSessionEpoch(7)).thenReturn(3);
        when(userMapper.getUserById(7)).thenThrow(new DataRetrievalFailureException("unavailable"));
        when(policy.requiresConfirmation(7)).thenReturn(true);

        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> service.finishRegistration(
                        7, 3, false, NO_EVIDENCE, mock(PublicKeyCredentialCreationOptions.class), null, "Work key"));

        assertEquals("Confirm the emailed enrollment link before adding the first passkey",
                refusal.getMessage());
        verify(auditService).deferFailureScoped(
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_ACTION,
                "user",
                7,
                null,
                null,
                null,
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_SUMMARY,
                AuditService.PASSKEY_BOOTSTRAP_CONFIRMATION_REQUIRED_REASON);
    }

    @Test
    void finishRegistrationRefusesAnEpochThatChangedBeforeTheAccountLock() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        UserMapper userMapper = mock(UserMapper.class);
        WebAuthnService service = new WebAuthnService(
                relyingParty,
                credentials,
                mock(WebauthnUserEntityMapper.class),
                mock(WebauthnCredentialMapper.class),
                mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class),
                userMapper,
                mock(PrivilegedAccountService.class),
                mock(PasskeyBootstrapConfirmationPolicy.class),
                mock(AuditService.class));
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.currentSessionEpoch(7)).thenReturn(4);

        assertThrows(ForbiddenException.class, () -> service.finishRegistration(
                7, 3, true, NO_EVIDENCE, mock(PublicKeyCredentialCreationOptions.class), null, "Work key"));

        verify(relyingParty, never()).registerCredential(any());
        verify(credentials, never()).save(any());
        verify(userMapper, never()).clearEpochRestampGrant(7);
    }

    @Test
    void deleteRefusesLastCredentialForCurrentlyPrivilegedAccount() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        WebAuthnService service = new WebAuthnService(
                relyingParty, credentials, userEntities, credentialMapper, mock(PrivilegedCredentialAttestationMapper.class), mock(SessionSecurityService.class), userMapper, privilegedAccounts,
                mock(PasskeyBootstrapConfirmationPolicy.class), mock(AuditService.class));
        Bytes credentialId = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        WebauthnCredentialRow credential = new WebauthnCredentialRow();
        credential.setId(11);
        credential.setCredentialId(credentialId.getBytes());
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.getUserById(7)).thenReturn(user);
        when(userEntities.findByUserId(7)).thenReturn(entity);
        when(credentialMapper.findByUserEntityUserIdForUpdate("handle"))
                .thenReturn(List.of(credential));
        when(privilegedAccounts.isPrivileged(7)).thenReturn(true);

        assertThrows(BadRequestException.class,
                () -> service.delete(7, credentialId.toBase64UrlString()));

        verify(credentials, never()).delete(any());
    }

    @Test
    void deleteAllowsPrivilegedAccountToKeepAnotherCredentialAndAuditsStrictly() {
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        AuditService auditService = mock(AuditService.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class), credentials, userEntities,
                credentialMapper, mock(PrivilegedCredentialAttestationMapper.class), mock(SessionSecurityService.class), userMapper, privilegedAccounts,
                mock(PasskeyBootstrapConfirmationPolicy.class), auditService);
        Bytes credentialId = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        WebauthnCredentialRow target = new WebauthnCredentialRow();
        target.setId(11);
        target.setCredentialId(credentialId.getBytes());
        target.setLabel("Work key");
        WebauthnCredentialRow retained = new WebauthnCredentialRow();
        retained.setId(12);
        retained.setCredentialId(Bytes.random().getBytes());
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.getUserById(7)).thenReturn(user);
        when(userEntities.findByUserId(7)).thenReturn(entity);
        when(credentialMapper.findByUserEntityUserIdForUpdate("handle"))
                .thenReturn(List.of(target, retained));
        when(privilegedAccounts.isPrivileged(7)).thenReturn(true);

        service.delete(7, credentialId.toBase64UrlString());

        verify(credentials).delete(any());
        verify(auditService).recordStrict(
                org.mockito.ArgumentMatchers.eq("auth.passkey.delete"),
                org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq("Admin"),
                org.mockito.ArgumentMatchers.eq("Passkey removed"),
                any());
    }

    /**
     * Once attestation is enforced, a privileged account keeps its last sufficient passkey even
     * while another, unapproved passkey would remain (#1534). The decision is read after the
     * account, role and credential locks, and nothing is removed.
     */
    @Test
    void deleteRefusesThePrivilegedAccountsLastSufficientCredentialEvenWithAnotherLeft() {
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        AuditService auditService = mock(AuditService.class);
        WebAuthnService service = new WebAuthnService(
                mock(WebAuthnRelyingPartyOperations.class), credentials, userEntities,
                credentialMapper, mock(PrivilegedCredentialAttestationMapper.class), mock(SessionSecurityService.class), userMapper, privilegedAccounts,
                mock(PasskeyBootstrapConfirmationPolicy.class), auditService);
        Bytes credentialId = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        WebauthnCredentialRow target = new WebauthnCredentialRow();
        target.setId(11);
        target.setCredentialId(credentialId.getBytes());
        WebauthnCredentialRow unapproved = new WebauthnCredentialRow();
        unapproved.setId(12);
        unapproved.setCredentialId(Bytes.random().getBytes());
        when(userMapper.lockById(7)).thenReturn(7);
        when(userEntities.findByUserId(7)).thenReturn(entity);
        when(credentialMapper.findByUserEntityUserIdForUpdate("handle"))
                .thenReturn(List.of(target, unapproved));
        when(privilegedAccounts.isPrivileged(7)).thenReturn(true);
        when(privilegedAccounts.isLastSufficientPasskey(7, 11)).thenReturn(true);

        assertThrows(LastSufficientPasskeyRemovalForbiddenException.class,
                () -> service.delete(7, credentialId.toBase64UrlString()));

        InOrder order = inOrder(userMapper, credentialMapper, privilegedAccounts);
        order.verify(userMapper).lockById(7);
        order.verify(userMapper).lockAssignedCustomRoleIds(7);
        order.verify(credentialMapper).findByUserEntityUserIdForUpdate("handle");
        order.verify(privilegedAccounts).isLastSufficientPasskey(7, 11);
        verify(credentials, never()).delete(any());
        verifyNoInteractions(auditService);
    }

    /**
     * Once attestation is enforced, adding a passkey to an account that already holds one is
     * re-authorized under the account locks (#1534): the step-up behind the ceremony must still be
     * fresh and signed by a passkey the account still owns. Nothing is registered otherwise.
     */
    @Test
    void anAdditionalPasskeyNeedsALiveOwnedProofUnderTheLockOnceEnforced() {
        StepUpProof proof = new StepUpProof(STEP_UP_PASSKEY_ROW_ID, 1_000L);
        EnrollmentEvidence signed = new EnrollmentEvidence("session-a", proof);
        record Attempt(EnrollmentEvidence evidence, boolean fresh, Integer ownedRowId) {
        }
        for (Attempt attempt : List.of(
                new Attempt(NO_EVIDENCE, true, STEP_UP_PASSKEY_ROW_ID),
                new Attempt(signed, false, STEP_UP_PASSKEY_ROW_ID),
                new Attempt(signed, true, null))) {
            RegistrationFixture fixture = successfulRegistration();
            when(fixture.credentialMapper().existsByUserId(7)).thenReturn(true);
            when(fixture.privilegedAccounts().isAttestationEnforced()).thenReturn(true);
            when(fixture.sessionSecurity().isFresh(proof)).thenReturn(attempt.fresh());
            when(fixture.credentialMapper().findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7))
                    .thenReturn(attempt.ownedRowId());

            assertThrows(RecentAuthenticationRequiredException.class, () -> fixture.service().finishRegistration(
                    7, 3, false, attempt.evidence(), fixture.options(), fixture.credential(), "Work key"),
                    attempt.toString());

            InOrder order = inOrder(fixture.userMapper(), fixture.relyingParty());
            order.verify(fixture.userMapper()).lockById(7);
            order.verify(fixture.userMapper()).lockAssignedCustomRoleIds(7);
            verify(fixture.relyingParty(), never()).registerCredential(any());
        }
    }

    @Test
    void aPrivilegedAccountCanAddAPasskeyOnlyWithAStillSufficientProofOnceEnforced() {
        StepUpProof proof = new StepUpProof(STEP_UP_PASSKEY_ROW_ID, 1_000L);
        RegistrationFixture insufficient = additionalPasskeyRegistration(proof);
        when(insufficient.privilegedAccounts().requiresSufficientPasskey(7)).thenReturn(true);

        assertThrows(RecentAuthenticationRequiredException.class, () -> insufficient.service().finishRegistration(
                7, 3, false, new EnrollmentEvidence("session-a", proof),
                insufficient.options(), insufficient.credential(), "Work key"));
        verify(insufficient.relyingParty(), never()).registerCredential(any());

        RegistrationFixture sufficient = additionalPasskeyRegistration(proof);
        when(sufficient.privilegedAccounts().requiresSufficientPasskey(7)).thenReturn(true);
        when(sufficient.privilegedAccounts().isSufficientPasskey(7, STEP_UP_PASSKEY_ROW_ID)).thenReturn(true);

        assertEquals(NEW_PASSKEY_ROW_ID, sufficient.service().finishRegistration(
                7, 3, false, new EnrollmentEvidence("session-a", proof),
                sufficient.options(), sufficient.credential(), "Work key").credentialRowId());

        InOrder order = inOrder(sufficient.userMapper(), sufficient.credentialMapper(),
                sufficient.privilegedAccounts(), sufficient.relyingParty());
        order.verify(sufficient.userMapper()).lockById(7);
        order.verify(sufficient.userMapper()).lockAssignedCustomRoleIds(7);
        order.verify(sufficient.credentialMapper()).findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7);
        order.verify(sufficient.privilegedAccounts()).isSufficientPasskey(7, STEP_UP_PASSKEY_ROW_ID);
        order.verify(sufficient.relyingParty()).registerCredential(any());
    }

    /** The first passkey, and every passkey while attestation is off, are not re-authorized (#1534). */
    @Test
    void theFirstPasskeyAndEveryPasskeyWhileAttestationIsOffNeedNoProof() {
        RegistrationFixture first = successfulRegistration();
        when(first.privilegedAccounts().isAttestationEnforced()).thenReturn(true);
        assertEquals(NEW_PASSKEY_ROW_ID, first.service().finishRegistration(
                7, 3, true, NO_EVIDENCE, first.options(), first.credential(), "Work key").credentialRowId());

        RegistrationFixture additional = successfulRegistration();
        when(additional.credentialMapper().existsByUserId(7)).thenReturn(true);
        assertEquals(NEW_PASSKEY_ROW_ID, additional.service().finishRegistration(
                7, 3, false, NO_EVIDENCE, additional.options(), additional.credential(), "Work key")
                .credentialRowId());
        verify(additional.privilegedAccounts(), never()).requiresSufficientPasskey(anyInt());
    }

    /**
     * Once attestation is enforced, a privileged account steps up only with its sufficient passkeys
     * (#1534), and is refused rather than offered an empty list that would admit any discoverable
     * credential.
     */
    @Test
    void stepUpOffersAPrivilegedAccountOnlyItsSufficientPasskeysOnceEnforced() {
        Bytes sufficient = Bytes.random();
        Bytes unapproved = Bytes.random();
        OptionsFixture fixture = optionsFixture(List.of(unapproved, sufficient));
        when(fixture.privilegedAccounts().requiresSufficientPasskey(7)).thenReturn(true);
        when(fixture.privilegedAccounts().sufficientPasskeyCredentialIds(7))
                .thenReturn(List.of(sufficient.getBytes()));

        PublicKeyCredentialRequestOptions options = fixture.service().createStepUpOptions(fixture.auth());

        assertEquals(List.of(sufficient), options.getAllowCredentials().stream()
                .map(PublicKeyCredentialDescriptor::getId).toList());
        assertEquals(fixture.issued().getChallenge(), options.getChallenge());
        assertEquals(fixture.issued().getRpId(), options.getRpId());
        assertEquals(fixture.issued().getTimeout(), options.getTimeout());
        assertEquals(fixture.issued().getUserVerification(), options.getUserVerification());

        when(fixture.privilegedAccounts().sufficientPasskeyCredentialIds(7)).thenReturn(List.of());
        assertThrows(PrivilegedMfaAttestationRequiredException.class,
                () -> fixture.service().createStepUpOptions(fixture.auth()));
    }

    @Test
    void stepUpOffersEveryOwnPasskeyWhenNoSufficiencyIsRequired() {
        Bytes first = Bytes.random();
        Bytes second = Bytes.random();
        OptionsFixture fixture = optionsFixture(List.of(first, second));

        PublicKeyCredentialRequestOptions options = fixture.service().createStepUpOptions(fixture.auth());

        assertEquals(fixture.issued(), options);
        verify(fixture.privilegedAccounts(), never()).sufficientPasskeyCredentialIds(anyInt());
    }

    /**
     * Redeeming an approval code may use any passkey the account owns, since an unapproved passkey is
     * what the code approves (#1534).
     */
    @Test
    void approvalCodeOptionsOfferEveryOwnPasskeyEvenWhenSufficiencyIsRequired() {
        Bytes unapproved = Bytes.random();
        OptionsFixture fixture = optionsFixture(List.of(unapproved));
        when(fixture.privilegedAccounts().requiresSufficientPasskey(7)).thenReturn(true);

        assertEquals(fixture.issued(), fixture.service().createAttestationOptions(fixture.auth()));
        verify(fixture.privilegedAccounts(), never()).sufficientPasskeyCredentialIds(anyInt());
    }

    /**
     * Recovery can remove every passkey between the existence check and the enumeration. Both
     * authenticated ceremonies then refuse instead of issuing an empty list, whatever the switch.
     */
    @Test
    void authenticatedOptionsRefuseAnEmptyListEvenAfterAPasskeyWasSeen() {
        OptionsFixture fixture = optionsFixture(List.of());

        assertThrows(PasskeyEnrollmentRequiredException.class,
                () -> fixture.service().createStepUpOptions(fixture.auth()));
        assertThrows(PasskeyEnrollmentRequiredException.class,
                () -> fixture.service().createAttestationOptions(fixture.auth()));
    }

    private static RegistrationFixture additionalPasskeyRegistration(StepUpProof proof) {
        RegistrationFixture fixture = successfulRegistration();
        when(fixture.credentialMapper().existsByUserId(7)).thenReturn(true);
        when(fixture.privilegedAccounts().isAttestationEnforced()).thenReturn(true);
        when(fixture.sessionSecurity().isFresh(proof)).thenReturn(true);
        when(fixture.credentialMapper().findOwnedRowId(STEP_UP_PASSKEY_ROW_ID, 7))
                .thenReturn(STEP_UP_PASSKEY_ROW_ID);
        return fixture;
    }

    private record OptionsFixture(
            WebAuthnService service,
            PrivilegedAccountService privilegedAccounts,
            Authentication auth,
            PublicKeyCredentialRequestOptions issued) {
    }

    private static OptionsFixture optionsFixture(List<Bytes> ownCredentialIds) {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        WebAuthnService service = new WebAuthnService(
                relyingParty, mock(UserCredentialRepository.class), mock(WebauthnUserEntityMapper.class),
                credentialMapper, mock(PrivilegedCredentialAttestationMapper.class),
                mock(SessionSecurityService.class), mock(UserMapper.class), privilegedAccounts,
                mock(PasskeyBootstrapConfirmationPolicy.class), mock(AuditService.class));
        User user = new User();
        user.setId(7);
        Authentication auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        PublicKeyCredentialRequestOptions issued = PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random())
                .timeout(Duration.ofMinutes(5))
                .rpId("connex.test")
                .allowCredentials(ownCredentialIds.stream()
                        .map(id -> PublicKeyCredentialDescriptor.builder().id(id).build())
                        .toList())
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build();
        when(credentialMapper.existsByUserId(7)).thenReturn(true);
        when(relyingParty.createCredentialRequestOptions(any())).thenReturn(issued);
        return new OptionsFixture(service, privilegedAccounts, auth, issued);
    }

    private record RegistrationFixture(
            WebAuthnService service,
            WebAuthnRelyingPartyOperations relyingParty,
            UserCredentialRepository credentials,
            WebauthnCredentialMapper credentialMapper,
            PrivilegedCredentialAttestationMapper attestationMapper,
            SessionSecurityService sessionSecurity,
            UserMapper userMapper,
            PrivilegedAccountService privilegedAccounts,
            AuditService auditService,
            PublicKeyCredentialCreationOptions options,
            PublicKeyCredential<AuthenticatorAttestationResponse> credential) {
    }

    private static RegistrationFixture successfulRegistration() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebauthnCredentialMapper credentialMapper = mock(WebauthnCredentialMapper.class);
        PrivilegedCredentialAttestationMapper attestationMapper = mock(PrivilegedCredentialAttestationMapper.class);
        SessionSecurityService sessionSecurity = mock(SessionSecurityService.class);
        UserMapper userMapper = mock(UserMapper.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        AuditService auditService = mock(AuditService.class);
        WebAuthnService service = new WebAuthnService(
                relyingParty, credentials, userEntities, credentialMapper, attestationMapper,
                sessionSecurity, userMapper, privilegedAccounts,
                mock(PasskeyBootstrapConfirmationPolicy.class), auditService);
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        PublicKeyCredentialUserEntity optionUser = mock(PublicKeyCredentialUserEntity.class);
        PublicKeyCredential<AuthenticatorAttestationResponse> credential = mock();
        CredentialRecord record = mock(CredentialRecord.class);
        Bytes handle = Bytes.random();
        WebauthnCredentialRow stored = new WebauthnCredentialRow();
        stored.setId(NEW_PASSKEY_ROW_ID);
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        when(options.getUser()).thenReturn(optionUser);
        when(optionUser.getId()).thenReturn(handle);
        when(userEntities.findUserIdByHandle(handle.toBase64UrlString())).thenReturn(7);
        when(relyingParty.registerCredential(any())).thenReturn(record);
        when(record.getCredentialId()).thenReturn(Bytes.random());
        when(credentialMapper.findByCredentialId(any())).thenReturn(stored);
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.currentSessionEpoch(7)).thenReturn(3);
        when(userMapper.getUserById(7)).thenReturn(user);
        return new RegistrationFixture(service, relyingParty, credentials, credentialMapper,
                attestationMapper, sessionSecurity, userMapper, privilegedAccounts, auditService, options, credential);
    }
}
