package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.webauthn.WebauthnUserEntityRow;

/**
 * MyBatis mapper for {@code webauthn_user_entity} (WebAuthn user handles).
 * SQL is defined in {@code resources/mappers/WebauthnUserEntityMapper.xml}.
 */
public interface WebauthnUserEntityMapper {
    WebauthnUserEntityRow findById(String id);
    WebauthnUserEntityRow findByUserId(int userId);
    WebauthnUserEntityRow findByUsername(String username);
    /** Resolves the owning {@code app_user} id for a user handle, or null if unknown. */
    Integer findUserIdByHandle(String id);
    int insert(WebauthnUserEntityRow row);
    int updateProfile(@Param("id") String id, @Param("name") String name, @Param("displayName") String displayName);
    int delete(String id);
}
