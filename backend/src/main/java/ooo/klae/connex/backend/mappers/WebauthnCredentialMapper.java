package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.webauthn.WebauthnCredentialRow;

/**
 * MyBatis mapper for {@code webauthn_credential} (enrolled passkeys).
 * SQL is defined in {@code resources/mappers/WebauthnCredentialMapper.xml}.
 */
public interface WebauthnCredentialMapper {
    WebauthnCredentialRow findByCredentialId(byte[] credentialId);
    List<WebauthnCredentialRow> findByUserEntityUserId(String userEntityUserId);
    int insert(WebauthnCredentialRow row);
    /** Updates the mutable fields written on each successful assertion (counter, backup/uv state, last used, label). */
    int updateMutable(WebauthnCredentialRow row);
    int updateLabel(@Param("credentialId") byte[] credentialId, @Param("label") String label);
    int delete(byte[] credentialId);
}
