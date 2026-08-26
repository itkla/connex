package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Database-collated resolution of one product-import SKU candidate.
 */
@Data
@NoArgsConstructor
public class ProductSkuResolution {
    private int candidateIndex;
    private int equivalentCount;
    private int collationOrder;
    private Integer productId;
    private String productName;
}
