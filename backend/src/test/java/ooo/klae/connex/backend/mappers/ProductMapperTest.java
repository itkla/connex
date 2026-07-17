package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Workspace;

class ProductMapperTest extends AbstractMapperTest {

    @Autowired ProductMapper productMapper;

    private Product newProduct(int workspaceId, String name) {
        Product p = new Product();
        p.setWorkspaceId(workspaceId);
        p.setSku("sku_" + unique());
        p.setName(name);
        p.setActive(true);
        p.setUnitPrice(new BigDecimal("100.00"));
        p.setCurrency("JPY");
        p.setTaxRate(new BigDecimal("10.000"));
        p.setBillingFrequency("one_time");
        productMapper.insert(p);
        return p;
    }

    @Test
    void insert_assignsGeneratedIdAndRoundTrips() {
        Product p = newProduct(workspace.getId(), "Widget");
        assertNotEquals(0, p.getId());
        Product found = productMapper.getById(workspace.getId(), p.getId());
        assertNotNull(found);
        assertEquals("Widget", found.getName());
        assertEquals(0, new BigDecimal("100.00").compareTo(found.getUnitPrice()));
        assertEquals("JPY", found.getCurrency());
        assertTrue(found.isActive());
    }

    @Test
    void update_changesFields() {
        Product p = newProduct(workspace.getId(), "Widget");
        p.setName("Gadget");
        p.setUnitPrice(new BigDecimal("250.00"));
        p.setActive(false);
        productMapper.update(p);
        Product found = productMapper.getById(workspace.getId(), p.getId());
        assertEquals("Gadget", found.getName());
        assertEquals(0, new BigDecimal("250.00").compareTo(found.getUnitPrice()));
        assertFalse(found.isActive());
    }

    @Test
    void delete_removesRow() {
        Product p = newProduct(workspace.getId(), "Widget");
        productMapper.delete(workspace.getId(), p.getId());
        assertNull(productMapper.getById(workspace.getId(), p.getId()));
        assertFalse(productMapper.exists(workspace.getId(), p.getId()));
    }

    @Test
    void getById_fromAnotherWorkspace_returnsNull() {
        Workspace foreign = new Workspace();
        foreign.setName("Foreign " + unique());
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        Product foreignProduct = newProduct(foreign.getId(), "Foreign Widget");

        assertNull(productMapper.getById(workspace.getId(), foreignProduct.getId()));
        assertFalse(productMapper.exists(workspace.getId(), foreignProduct.getId()));
        assertNotNull(productMapper.getById(foreign.getId(), foreignProduct.getId()));
    }

    @Test
    void update_fromAnotherWorkspace_doesNotMutate() {
        Product p = newProduct(workspace.getId(), "Widget");
        Product spoof = new Product();
        spoof.setId(p.getId());
        spoof.setWorkspaceId(workspace.getId() + 99999);
        spoof.setName("Hijacked");
        spoof.setUnitPrice(new BigDecimal("1.00"));
        spoof.setCurrency("USD");
        spoof.setBillingFrequency("one_time");
        int affected = productMapper.update(spoof);
        assertEquals(0, affected);
        assertEquals("Widget", productMapper.getById(workspace.getId(), p.getId()).getName());
    }
}
