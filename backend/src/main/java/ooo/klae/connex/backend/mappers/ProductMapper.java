package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Product;

/**
 * Mapper for {@code Product} persistence. SQL lives in {@code resources/mappers/ProductMapper.xml}.
 * Every statement is scoped to a workspace.
 */
public interface ProductMapper {
    List<Product> getAll(int workspaceId);
    Product getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(Product product);
    int update(Product product);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
