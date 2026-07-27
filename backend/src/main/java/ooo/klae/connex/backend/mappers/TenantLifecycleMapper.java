package ooo.klae.connex.backend.mappers;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.dto.TenantStorageResidual;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

/**
 * Registry-driven tenant export and teardown persistence. Every interpolated
 * identifier and join fragment must come from the sealed
 * {@code TenantLifecycleRegistry} and be validated by object identity before a
 * caller reaches this mapper; request input must never reach a {@code ${...}}
 * substitution.
 */
public interface TenantLifecycleMapper {

    Cursor<Map<String, Object>> streamRows(
        @Param("workspaceId") int workspaceId,
        @Param("declaration") TableLifecycle declaration);

    long countRows(
        @Param("workspaceId") int workspaceId,
        @Param("declaration") TableLifecycle declaration);

    int nullifyReference(
        @Param("workspaceId") int workspaceId,
        @Param("declaration") TableLifecycle declaration,
        @Param("preparation") NullifyReference preparation);

    int deleteDirectBatch(
        @Param("workspaceId") int workspaceId,
        @Param("declaration") TableLifecycle declaration,
        @Param("limit") int limit);

    List<ActiveObjectReference> findActiveObjectReferencesAfter(
        @Param("workspaceId") int workspaceId,
        @Param("afterKey") String afterKey,
        @Param("limit") int limit);

    ActiveObjectReference findActiveObjectReference(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey);

    List<String> findLifecycleObjectKeysAfter(
        @Param("workspaceId") int workspaceId,
        @Param("afterKey") String afterKey,
        @Param("limit") int limit);

    TenantStorageResidual findStorageResidual(@Param("workspaceId") int workspaceId);
}
