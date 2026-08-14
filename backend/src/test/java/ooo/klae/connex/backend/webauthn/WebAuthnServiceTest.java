package ooo.klae.connex.backend.webauthn;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.webauthn.api.Bytes;
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
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.beans.User;

import java.util.List;

class WebAuthnServiceTest {

    @Test
    void finishRegistrationRejectsOptionsIssuedForAnotherAccount() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebAuthnService service = new WebAuthnService(
            relyingParty, credentials, userEntities,
            mock(WebauthnCredentialMapper.class), mock(UserMapper.class),
            mock(PrivilegedAccountService.class), mock(AuditService.class));
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        PublicKeyCredentialUserEntity optionUser = mock(PublicKeyCredentialUserEntity.class);
        Bytes handle = new Bytes(new byte[] {1, 2, 3});
        when(options.getUser()).thenReturn(optionUser);
        when(optionUser.getId()).thenReturn(handle);
        when(userEntities.findUserIdByHandle(handle.toBase64UrlString())).thenReturn(7);

        assertThrows(BadCredentialsException.class, () -> service.finishRegistration(
            8, options, null, "work key"));

        verify(relyingParty, never()).registerCredential(org.mockito.ArgumentMatchers.any());
        verify(credentials, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void finishRegistrationPersistsOnlyWithStrictEnrollmentAudit() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        AuditService auditService = mock(AuditService.class);
        WebAuthnService service = new WebAuthnService(
                relyingParty, credentials, userEntities, mock(WebauthnCredentialMapper.class),
                userMapper, mock(PrivilegedAccountService.class), auditService);
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        PublicKeyCredentialUserEntity optionUser = mock(PublicKeyCredentialUserEntity.class);
        PublicKeyCredential<AuthenticatorAttestationResponse> credential = mock();
        CredentialRecord record = mock(CredentialRecord.class);
        Bytes handle = Bytes.random();
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        when(options.getUser()).thenReturn(optionUser);
        when(optionUser.getId()).thenReturn(handle);
        when(userEntities.findUserIdByHandle(handle.toBase64UrlString())).thenReturn(7);
        when(relyingParty.registerCredential(any())).thenReturn(record);
        when(userMapper.getUserById(7)).thenReturn(user);

        service.finishRegistration(7, options, credential, "Work key");

        verify(credentials).save(record);
        verify(auditService).recordStrict(
                org.mockito.ArgumentMatchers.eq("auth.passkey.register"),
                org.mockito.ArgumentMatchers.eq("user"),
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq("Admin"),
                org.mockito.ArgumentMatchers.eq("Passkey registered"),
                any());
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
                relyingParty, credentials, userEntities, credentialMapper, userMapper, privilegedAccounts,
                mock(AuditService.class));
        Bytes credentialId = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        WebauthnCredentialRow credential = new WebauthnCredentialRow();
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
                credentialMapper, userMapper, privilegedAccounts, auditService);
        Bytes credentialId = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId("handle");
        WebauthnCredentialRow target = new WebauthnCredentialRow();
        target.setCredentialId(credentialId.getBytes());
        target.setLabel("Work key");
        WebauthnCredentialRow retained = new WebauthnCredentialRow();
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
}
