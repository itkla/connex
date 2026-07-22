package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Business logic for the workspace-scoped product/service catalog. Every read/write is scoped to
 * the active workspace; mutations require {@link Permission#PRODUCT_MANAGE}.
 */
@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductMapper productMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS = Set.of(
        "sku", "name", "description", "active", "unit", "unitPrice", "currency",
        "taxRate", "billingFrequency", "effectiveStart", "effectiveEnd");

    /** Products matching the current catalog search in the active workspace, ordered by name. */
    public List<Product> getAll(String query) {
        return productMapper.getFiltered(workspaceService.getCurrentWorkspaceId(), query);
    }

    /** A single product in the active workspace, or 404. */
    public Product getById(int id) {
        return requireProduct(workspaceService.getCurrentWorkspaceId(), id);
    }

    /** Creates a product in the active workspace. */
    @RequirePermission(Permission.PRODUCT_MANAGE)
    public Product create(Product product) {
        product.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        productMapper.insert(product);
        Product saved = requireProduct(product.getWorkspaceId(), product.getId());
        auditService.record("product.create", "product", saved.getId(), saved.getName(),
            "Created product " + saved.getName(),
            auditService.diff(null, saved, AUDIT_FIELDS));
        return saved;
    }

    /** Updates a product in the active workspace. */
    @RequirePermission(Permission.PRODUCT_MANAGE)
    public Product update(int id, Product product) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Product before = requireProduct(workspaceId, id);
        product.setId(id);
        product.setWorkspaceId(workspaceId);
        productMapper.update(product);
        Product after = requireProduct(workspaceId, id);
        auditService.record("product.update", "product", id, after.getName(),
            "Updated product " + after.getName(),
            auditService.diff(before, after, AUDIT_FIELDS));
        return after;
    }

    /** Deletes a product in the active workspace; existing line items keep their snapshot. */
    @RequirePermission(Permission.PRODUCT_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Product before = requireProduct(workspaceId, id);
        productMapper.delete(workspaceId, id);
        auditService.record("product.delete", "product", id, before.getName(),
            "Deleted product " + before.getName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /** Loads a product that must exist in the given workspace, else 404. */
    private Product requireProduct(int workspaceId, int id) {
        Product product = productMapper.getById(workspaceId, id);
        if (product == null) throw new ResourceNotFoundException("Product not found with id: " + id);
        return product;
    }
}
