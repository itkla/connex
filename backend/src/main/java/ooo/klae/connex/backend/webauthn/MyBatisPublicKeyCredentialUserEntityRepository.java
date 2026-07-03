package ooo.klae.connex.backend.webauthn;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Repository;

import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;

import lombok.RequiredArgsConstructor;

/**
 * MyBatis-backed {@link PublicKeyCredentialUserEntityRepository}. Stores the stable WebAuthn
 * user handle for each account. The handle&rarr;{@code app_user} link ({@code user_id}) is owned
 * by {@code WebAuthnService}, which persists the row before registration begins; {@link #save}
 * therefore only refreshes the mutable WebAuthn profile fields and never inserts without an owner.
 */
@Repository
@RequiredArgsConstructor
public class MyBatisPublicKeyCredentialUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    private final WebauthnUserEntityMapper mapper;

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        return toEntity(mapper.findById(id.toBase64UrlString()));
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        return toEntity(mapper.findByUsername(username));
    }

    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        mapper.updateProfile(userEntity.getId().toBase64UrlString(), userEntity.getName(), userEntity.getDisplayName());
    }

    @Override
    public void delete(Bytes id) {
        mapper.delete(id.toBase64UrlString());
    }

    private PublicKeyCredentialUserEntity toEntity(WebauthnUserEntityRow row) {
        if (row == null) {
            return null;
        }
        return ImmutablePublicKeyCredentialUserEntity.builder()
            .id(Bytes.fromBase64(row.getId()))
            .name(row.getName())
            .displayName(row.getDisplayName())
            .build();
    }
}
