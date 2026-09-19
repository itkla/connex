package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Business logic for the workspace-scoped product/service catalog. Every read/write is scoped to
 * the active workspace; mutations require {@link Permission#PRODUCT_MANAGE}.
 *
 * <p>The SKU is canonicalized on every write: surrounding whitespace is removed and a blank SKU
 * becomes null. It is the catalog's conflict key — {@code uq_product_workspace_sku}, CSV import
 * classification, and line-item lookups all compare it — and CSV cells cannot carry surrounding
 * whitespace, so a stored {@code " A-1"} would export as {@code "A-1"} and reimport as a second
 * product. Canonicalizing here rather than preserving the whitespace also keeps the unique index
 * meaningful: it is NULL-tolerant but not blank-tolerant, and trailing spaces are significant
 * under {@code NO PAD} collations and ignored under {@code PAD SPACE} ones.
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
        validateAmounts(product);
        product.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        product.setSku(canonicalSku(product.getSku()));
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
        validateAmounts(product);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Product before = requireProduct(workspaceId, id);
        product.setId(id);
        product.setWorkspaceId(workspaceId);
        product.setSku(canonicalSku(product.getSku()));
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

    /**
     * Refuses a unit price or tax rate that its {@code DECIMAL(15,2)} or {@code DECIMAL(6,3)}
     * column cannot hold. The request body declares the same bounds, but they apply only where the
     * controller validates the body; refusing here keeps an unbounded exponent such as
     * {@code 1E300000000} from reaching the JDBC bind, which renders the value as a plain string
     * and would exhaust the heap on the request thread.
     *
     * @throws BadRequestException when either amount is negative or outside its column's range
     */
    private static void validateAmounts(Product product) {
        if (!fitsColumn(product.getUnitPrice(), 13, 2)) {
            throw new BadRequestException("unitPrice must be a non-negative DECIMAL(15,2) value");
        }
        if (!fitsColumn(product.getTaxRate(), 3, 3)) {
            throw new BadRequestException("taxRate must be a non-negative DECIMAL(6,3) value");
        }
    }

    /**
     * Whether an optional non-negative amount fits a column with the given integer and fractional
     * digits. Bounds are read off the parsed value rather than a rescaled one, and the integer-digit
     * count widens to {@code long} so an extreme exponent cannot wrap it into a passing value.
     */
    private static boolean fitsColumn(BigDecimal value, int integerDigits, int scale) {
        return value == null || value.signum() >= 0 && value.scale() <= scale
            && (long) value.precision() - value.scale() <= integerDigits;
    }

    /** Trims the catalog conflict key and treats a blank SKU as absent. */
    private static String canonicalSku(String sku) {
        if (sku == null) return null;
        String trimmed = sku.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Loads a product that must exist in the given workspace, else 404. */
    private Product requireProduct(int workspaceId, int id) {
        Product product = productMapper.getById(workspaceId, id);
        if (product == null) throw new ResourceNotFoundException("Product not found with id: " + id);
        return product;
    }
}
