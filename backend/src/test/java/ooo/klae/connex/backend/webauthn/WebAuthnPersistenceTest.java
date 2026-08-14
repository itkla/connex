package ooo.klae.connex.backend.webauthn;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;

/**
 * Verifies the MyBatis-backed WebAuthn repositories round-trip Spring Security's
 * {@code CredentialRecord} and {@code PublicKeyCredentialUserEntity} through MySQL,
 * and that the handle-to-account link survives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class WebAuthnPersistenceTest {

    @Autowired UserMapper userMapper;
    @Autowired WebauthnCredentialMapper credentialMapper;
    @Autowired WebauthnUserEntityMapper userEntityMapper;
    @Autowired UserCredentialRepository userCredentials;
    @Autowired PublicKeyCredentialUserEntityRepository userEntities;

    @Test
    void userEntity_roundTripsAndResolvesToAccount() {
        User user = insertUser();
        Bytes handle = Bytes.random();
        insertUserEntity(handle, user);

        PublicKeyCredentialUserEntity byUsername = userEntities.findByUsername(user.getUsername());
        PublicKeyCredentialUserEntity byId = userEntities.findById(handle);

        assertEquals(handle.toBase64UrlString(), byUsername.getId().toBase64UrlString());
        assertEquals(user.getUsername(), byUsername.getName());
        assertEquals(user.getDisplayName(), byUsername.getDisplayName());
        assertEquals(handle.toBase64UrlString(), byId.getId().toBase64UrlString());
        assertEquals(user.getId(), userEntityMapper.findUserIdByHandle(handle.toBase64UrlString()));
    }

    @Test
    void credential_roundTripsAllFields() {
        User user = insertUser();
        Bytes handle = Bytes.random();
        insertUserEntity(handle, user);

        Bytes credentialId = Bytes.random();
        byte[] cose = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        CredentialRecord record = ImmutableCredentialRecord.builder()
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(credentialId)
            .userEntityUserId(handle)
            .publicKey(new ImmutablePublicKeyCose(cose))
            .signatureCount(0)
            .uvInitialized(true)
            .backupEligible(true)
            .backupState(false)
            .transports(Set.of(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID))
            .label("My Passkey")
            .created(Instant.now())
            .build();

        userCredentials.save(record);

        CredentialRecord loaded = userCredentials.findByCredentialId(credentialId);
        assertArrayEquals(credentialId.getBytes(), loaded.getCredentialId().getBytes());
        assertArrayEquals(cose, loaded.getPublicKey().getBytes());
        assertEquals(handle.toBase64UrlString(), loaded.getUserEntityUserId().toBase64UrlString());
        assertEquals(0, loaded.getSignatureCount());
        assertTrue(loaded.isUvInitialized());
        assertTrue(loaded.isBackupEligible());
        assertEquals("My Passkey", loaded.getLabel());
        assertEquals(
            Set.of(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID),
            loaded.getTransports());

        List<CredentialRecord> byUser = userCredentials.findByUserId(handle);
        assertEquals(1, byUser.size());
        assertArrayEquals(credentialId.getBytes(), byUser.get(0).getCredentialId().getBytes());
    }

    @Test
    void save_onExistingCredential_updatesSignatureCounter() {
        User user = insertUser();
        Bytes handle = Bytes.random();
        insertUserEntity(handle, user);
        Bytes credentialId = Bytes.random();
        userCredentials.save(newRecord(credentialId, handle, 0));

        userCredentials.save(ImmutableCredentialRecord
            .fromCredentialRecord(userCredentials.findByCredentialId(credentialId))
            .signatureCount(7)
            .backupState(true)
            .lastUsed(Instant.now())
            .build());

        CredentialRecord loaded = userCredentials.findByCredentialId(credentialId);
        assertEquals(7, loaded.getSignatureCount());
        assertTrue(loaded.isBackupState());
        assertEquals(1, userCredentials.findByUserId(handle).size());
    }

    @Test
    void delete_removesCredential() {
        User user = insertUser();
        Bytes handle = Bytes.random();
        insertUserEntity(handle, user);
        Bytes credentialId = Bytes.random();
        userCredentials.save(newRecord(credentialId, handle, 0));

        userCredentials.delete(credentialId);

        assertNull(userCredentials.findByCredentialId(credentialId));
    }

    @Test
    void credentialExistenceTracksCurrentEnrollmentWithoutLoadingCredentialMaterial() {
        User user = insertUser();
        Bytes handle = Bytes.random();
        insertUserEntity(handle, user);
        Bytes credentialId = Bytes.random();

        assertFalse(credentialMapper.existsByUserId(user.getId()));

        userCredentials.save(newRecord(credentialId, handle, 0));

        assertTrue(credentialMapper.existsByUserId(user.getId()));

        userCredentials.delete(credentialId);

        assertFalse(credentialMapper.existsByUserId(user.getId()));
    }

    private CredentialRecord newRecord(Bytes credentialId, Bytes handle, long count) {
        return ImmutableCredentialRecord.builder()
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(credentialId)
            .userEntityUserId(handle)
            .publicKey(new ImmutablePublicKeyCose(new byte[] { 9, 9, 9 }))
            .signatureCount(count)
            .created(Instant.now())
            .build();
    }

    private User insertUser() {
        String s = java.util.UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("pk_" + s);
        user.setDisplayName("Passkey User " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private void insertUserEntity(Bytes handle, User user) {
        WebauthnUserEntityRow row = new WebauthnUserEntityRow();
        row.setId(handle.toBase64UrlString());
        row.setUserId(user.getId());
        row.setName(user.getUsername());
        row.setDisplayName(user.getDisplayName());
        userEntityMapper.insert(row);
    }
}
