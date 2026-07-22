package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.services.ProductService;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {
    @Mock private ProductService productService;

    @Test
    void listNormalizesTheSameSearchPatternAsExport() {
        Product product = new Product();
        product.setId(7);
        product.setName("Product");
        when(productService.getAll("%100\\%\\_ready%"))
            .thenReturn(List.of(product));

        List<?> products = new ProductController(productService).getAll("  100%_ready  ");

        assertEquals(1, products.size());
        verify(productService).getAll("%100\\%\\_ready%");
    }
}
