package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ProviderConnection;

/**
 * Mapper for {@code provider_connection}. Connections are user-owned control-plane rows;
 * every statement is scoped to the owning user id, which callers must resolve from the
 * authenticated session — never from request input.
 */
public interface ProviderConnectionMapper {
    List<ProviderConnection> getByUserId(@Param("userId") int userId);
    ProviderConnection getByUserAndProvider(@Param("userId") int userId, @Param("provider") String provider);
    int insert(ProviderConnection connection);
    int update(ProviderConnection connection);
    int delete(@Param("userId") int userId, @Param("provider") String provider);
}
