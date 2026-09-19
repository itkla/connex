package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ProductMapper;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final int WORKSPACE_ID = 7;
    private static final int PRODUCT_ID = 31;
    private static final String UNIT_PRICE_REFUSAL = "unitPrice must be a non-negative DECIMAL(15,2) value";
    private static final String TAX_RATE_REFUSAL = "taxRate must be a non-negative DECIMAL(6,3) value";

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

    @Test
    void theLargestAmountsTheColumnsHoldAreAccepted() {
        Product product = priced("9999999999999.99", "999.999");
        when(productMapper.insert(product)).thenAnswer(invocation -> {
            product.setId(PRODUCT_ID);
            return 1;
        });

        service.create(product);

        verify(productMapper).insert(product);
    }

    @ParameterizedTest
    @MethodSource("amountsOutsideTheirColumns")
    void createRefusesAnAmountItsColumnCannotHold(String unitPrice, String taxRate, String refusal) {
        Product product = priced(unitPrice, taxRate);

        BadRequestException exception = assertThrows(BadRequestException.class, () -> service.create(product));

        assertEquals(refusal, exception.getMessage());
        verify(productMapper, never()).insert(any(Product.class));
    }

    @ParameterizedTest
    @MethodSource("amountsOutsideTheirColumns")
    void updateRefusesAnAmountItsColumnCannotHold(String unitPrice, String taxRate, String refusal) {
        Product product = priced(unitPrice, taxRate);

        BadRequestException exception =
            assertThrows(BadRequestException.class, () -> service.update(PRODUCT_ID, product));

        assertEquals(refusal, exception.getMessage());
        verify(productMapper, never()).update(any(Product.class));
    }

    static Stream<Arguments> amountsOutsideTheirColumns() {
        return Stream.of(
            Arguments.of("1E2147483647", null, UNIT_PRICE_REFUSAL),
            Arguments.of("1E300000000", null, UNIT_PRICE_REFUSAL),
            Arguments.of("123456789E2147483639", null, UNIT_PRICE_REFUSAL),
            Arguments.of("1E-2147483647", null, UNIT_PRICE_REFUSAL),
            Arguments.of("1E+13", null, UNIT_PRICE_REFUSAL),
            Arguments.of("0.001", null, UNIT_PRICE_REFUSAL),
            Arguments.of("-0.01", null, UNIT_PRICE_REFUSAL),
            Arguments.of("0", "1E2147483647", TAX_RATE_REFUSAL),
            Arguments.of("0", "1E300000000", TAX_RATE_REFUSAL),
            Arguments.of("0", "1E-2147483647", TAX_RATE_REFUSAL),
            Arguments.of("0", "1E+3", TAX_RATE_REFUSAL),
            Arguments.of("0", "0.0001", TAX_RATE_REFUSAL),
            Arguments.of("0", "-0.001", TAX_RATE_REFUSAL));
    }

    private static Product priced(String unitPrice, String taxRate) {
        Product product = product("A-1");
        product.setUnitPrice(new BigDecimal(unitPrice));
        product.setTaxRate(taxRate == null ? null : new BigDecimal(taxRate));
        return product;
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
