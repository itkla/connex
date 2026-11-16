package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.ProductSkuResolution;
import ooo.klae.connex.backend.dto.ProductFacetFilter;
import ooo.klae.connex.backend.dto.ProductSummaryDto;

/**
 * Mapper for {@code Product} persistence. SQL lives in {@code resources/mappers/ProductMapper.xml}.
 * Every statement is scoped to a workspace.
 */
public interface ProductMapper {
    /**
     * Products in the workspace matching the catalog search and the browser's facets, ordered by
     * name.
     *
     * @param workspaceId owning workspace
     * @param query LIKE pattern over name, SKU, and description, or null for no search
     * @param facets Billing, Availability, and Effective dates selections, never null
     * @return matching products
     */
    List<Product> getFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("facets") ProductFacetFilter facets);

    /**
     * Bounded global-search slice of the catalog, matched on name and SKU.
     *
     * @param workspaceId the resolved tenant
     * @param query the escaped {@code LIKE} pattern
     * @return at most ten matching products, ordered by name
     */
    List<ProductSummaryDto> search(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query);

    Product getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Product> findBySkus(@Param("workspaceId") int workspaceId, @Param("skus") List<String> skus);

    /**
     * Classifies catalog-import SKU candidates under the {@code product.sku} column collation.
     *
     * @param workspaceId the resolved tenant
     * @param skus the ordered, non-empty candidate SKUs; each resolution's {@code candidateIndex}
     *     is the candidate's position in this list
     * @return one resolution per candidate
     */
    List<ProductSkuResolution> resolveImportSkus(
        @Param("workspaceId") int workspaceId,
        @Param("skus") List<String> skus);

    Product getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(Product product);
    int insertBatch(List<Product> products);
    int update(Product product);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
