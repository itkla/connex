package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SsoConnection;

/**
 * Data access for per-organization SSO connections. Control-plane: every statement
 * is scoped by an explicit, permission-gated {@code orgId} in
 * {@code SsoConnectionService}, so it is intentionally NOT in
 * {@code TenantScopeInterceptor.SCOPED_NAMESPACES} (mirrors MailConfigMapper).
 * SQL lives in {@code resources/mappers/SsoConnectionMapper.xml}.
 */
public interface SsoConnectionMapper {

    SsoConnection findByOrg(int orgId);

    SsoConnection findById(int id);

    List<SsoConnection> listLegacySecretConnections();

    int upsert(SsoConnection connection);

    int updateOidcClientSecretReference(@Param("orgId") int orgId,
            @Param("oidcClientSecretEnc") String oidcClientSecretEnc);

    int updateSamlSpPrivateKeyReference(@Param("orgId") int orgId,
            @Param("samlSpPrivateKeyEnc") String samlSpPrivateKeyEnc);

    int deleteByOrg(int orgId);
}
