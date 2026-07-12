package ooo.klae.connex.backend.webauthn;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;

class WebAuthnServiceTest {

    @Test
    void finishRegistrationRejectsOptionsIssuedForAnotherAccount() {
        WebAuthnRelyingPartyOperations relyingParty = mock(WebAuthnRelyingPartyOperations.class);
        UserCredentialRepository credentials = mock(UserCredentialRepository.class);
        WebauthnUserEntityMapper userEntities = mock(WebauthnUserEntityMapper.class);
        WebAuthnService service = new WebAuthnService(
            relyingParty, credentials, userEntities,
            mock(WebauthnCredentialMapper.class), mock(UserMapper.class));
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
}
