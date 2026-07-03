package ooo.klae.connex.backend.mappers;

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

    int upsert(SsoConnection connection);

    int deleteByOrg(int orgId);
}
