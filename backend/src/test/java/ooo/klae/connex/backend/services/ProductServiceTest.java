package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.mappers.ProductMapper;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final int WORKSPACE_ID = 7;
    private static final int PRODUCT_ID = 31;

    @Mock private ProductMapper productMapper;
    @Mock private AuditService auditService;
    @Mock private WorkspaceService workspaceService;

    private ProductService service;

    @BeforeEach
    void setUp() {
        service = new ProductService(productMapper, auditService, workspaceService);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(productMapper.getById(eq(WORKSPACE_ID), anyInt()))
            .thenAnswer(invocation -> {
                Product stored = new Product();
                stored.setId(invocation.getArgument(1, Integer.class));
                stored.setWorkspaceId(WORKSPACE_ID);
                stored.setName("Widget");
                return stored;
            });
    }

    @Test
    void createStoresTheSkuThatTheCsvExportWillEmit() {
        Product product = product("  A-1  ");
        when(productMapper.insert(product)).thenAnswer(invocation -> {
            product.setId(PRODUCT_ID);
            return 1;
        });

        service.create(product);

        verify(productMapper).insert(product);
        assertEquals("A-1", product.getSku());
    }

    @Test
    void updateStoresTheSkuThatTheCsvExportWillEmit() {
        Product product = product("\tA-1\n");

        service.update(PRODUCT_ID, product);

        verify(productMapper).update(product);
        assertEquals("A-1", product.getSku());
    }

    @Test
    void aBlankSkuBecomesNullSoItNeverOccupiesTheUniqueIndex() {
        Product product = product("   ");
        when(productMapper.insert(product)).thenAnswer(invocation -> {
            product.setId(PRODUCT_ID);
            return 1;
        });

        service.create(product);

        assertNull(product.getSku());
    }

    private static Product product(String sku) {
        Product product = new Product();
        product.setSku(sku);
        product.setName("Widget");
        product.setUnitPrice(BigDecimal.ZERO);
        product.setCurrency("USD");
        product.setBillingFrequency("one_time");
        return product;
    }
}
