package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;
import java.util.List;

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
import ooo.klae.connex.backend.util.LikePattern;

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

    @Test
    void getFilteredMatchesTheCatalogSearchAndKeepsWorkspaceBoundary() {
        Product byName = newProduct(workspace.getId(), "Alpha Needle");
        Product bySku = newProduct(workspace.getId(), "Beta product");
        bySku.setSku("NEEDLE-SKU");
        productMapper.update(bySku);
        Product byDescription = newProduct(workspace.getId(), "Gamma product");
        byDescription.setDescription("Includes a needle in its description");
        productMapper.update(byDescription);
        Product inactive = newProduct(workspace.getId(), "Omega needle");
        inactive.setActive(false);
        productMapper.update(inactive);
        Product unrelated = newProduct(workspace.getId(), "Unrelated product");

        Workspace foreign = new Workspace();
        foreign.setName("Foreign " + unique());
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        newProduct(foreign.getId(), "Foreign Needle");

        List<Product> products = productMapper.getFiltered(
            workspace.getId(), LikePattern.containing("needle"));

        assertEquals(
            List.of(byName.getId(), bySku.getId(), byDescription.getId(), inactive.getId()),
            products.stream().map(Product::getId).toList());
        assertFalse(products.getLast().isActive());
        assertEquals(
            List.of(byName.getId(), bySku.getId(), byDescription.getId(), inactive.getId(), unrelated.getId()),
            productMapper.getFiltered(workspace.getId(), null).stream().map(Product::getId).toList());
    }

    @Test
    void getFilteredMatchesTheBrowserCaseAndAccentSemantics() {
        Product accented = newProduct(workspace.getId(), "Café plan");

        assertTrue(productMapper.getFiltered(
            workspace.getId(), LikePattern.containing("cafe")).isEmpty());
        assertEquals(
            List.of(accented.getId()),
            productMapper.getFiltered(
                workspace.getId(), LikePattern.containing("CAFÉ")).stream().map(Product::getId).toList());
    }

    @Test
    void findBySkus_returnsOnlyTheCurrentWorkspaceRows() {
        Product mine = newProduct(workspace.getId(), "Widget");
        Product other = newProduct(workspace.getId(), "Gadget");
        Workspace foreign = new Workspace();
        foreign.setName("Foreign " + unique());
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        Product foreignProduct = newProduct(foreign.getId(), "Foreign Widget");
        foreignProduct.setSku(mine.getSku());
        productMapper.update(foreignProduct);

        List<Product> found = productMapper.findBySkus(
            workspace.getId(), List.of(mine.getSku(), other.getSku(), "absent-" + unique()));

        assertEquals(
            List.of(mine.getId(), other.getId()),
            found.stream().map(Product::getId).sorted().toList());
        assertEquals(
            List.of(foreignProduct.getId()),
            productMapper.findBySkus(foreign.getId(), List.of(mine.getSku()))
                .stream().map(Product::getId).toList());
    }

    @Test
    void findBySkus_matchesUnderTheColumnCollation() {
        Product product = newProduct(workspace.getId(), "Widget");
        product.setSku("A-1-" + unique());
        productMapper.update(product);

        assertEquals(
            List.of(product.getId()),
            productMapper.findBySkus(
                    workspace.getId(), List.of(product.getSku().toUpperCase(java.util.Locale.ROOT)))
                .stream().map(Product::getId).toList());
    }

    @Test
    void getByIdForUpdate_returnsTheRowAndIsWorkspaceScoped() {
        Product product = newProduct(workspace.getId(), "Widget");
        Workspace foreign = new Workspace();
        foreign.setName("Foreign " + unique());
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);

        Product locked = productMapper.getByIdForUpdate(workspace.getId(), product.getId());

        assertNotNull(locked);
        assertEquals(product.getSku(), locked.getSku());
        assertNull(productMapper.getByIdForUpdate(foreign.getId(), product.getId()));
    }

    @Test
    void insertBatch_roundTripsEveryColumnIncludingNullables() {
        Product full = new Product();
        full.setWorkspaceId(workspace.getId());
        full.setSku("batch-full-" + unique());
        full.setName("Full widget");
        full.setDescription("Everything set");
        full.setActive(false);
        full.setUnit("seat");
        full.setUnitPrice(new BigDecimal("1200.01"));
        full.setCurrency("JPY");
        full.setTaxRate(new BigDecimal("10.001"));
        full.setBillingFrequency("recurring");
        full.setEffectiveStart(java.time.LocalDate.parse("2026-01-01"));
        full.setEffectiveEnd(java.time.LocalDate.parse("2026-12-31"));

        Product sparse = new Product();
        sparse.setWorkspaceId(workspace.getId());
        sparse.setSku("batch-sparse-" + unique());
        sparse.setName("Sparse widget");
        sparse.setActive(true);
        sparse.setUnitPrice(BigDecimal.ZERO);
        sparse.setCurrency("USD");
        sparse.setBillingFrequency("one_time");

        assertEquals(2, productMapper.insertBatch(List.of(full, sparse)));

        Product storedFull = productMapper.getById(workspace.getId(), full.getId());
        assertNotNull(storedFull);
        assertEquals("Full widget", storedFull.getName());
        assertEquals("Everything set", storedFull.getDescription());
        assertFalse(storedFull.isActive());
        assertEquals("seat", storedFull.getUnit());
        assertEquals(0, new BigDecimal("1200.01").compareTo(storedFull.getUnitPrice()));
        assertEquals("JPY", storedFull.getCurrency());
        assertEquals(0, new BigDecimal("10.001").compareTo(storedFull.getTaxRate()));
        assertEquals("recurring", storedFull.getBillingFrequency());
        assertEquals(java.time.LocalDate.parse("2026-01-01"), storedFull.getEffectiveStart());
        assertEquals(java.time.LocalDate.parse("2026-12-31"), storedFull.getEffectiveEnd());

        Product storedSparse = productMapper.getById(workspace.getId(), sparse.getId());
        assertNotNull(storedSparse);
        assertNull(storedSparse.getDescription());
        assertNull(storedSparse.getUnit());
        assertNull(storedSparse.getTaxRate());
        assertNull(storedSparse.getEffectiveStart());
        assertNull(storedSparse.getEffectiveEnd());
        assertNotEquals(0, storedSparse.getId());
        assertNotEquals(storedFull.getId(), storedSparse.getId());
    }
}
