package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.ProductImportColumnMapping;
import ooo.klae.connex.backend.dto.ProductImportPreviewResult;
import ooo.klae.connex.backend.dto.ProductImportRequest;
import ooo.klae.connex.backend.dto.ProductImportResult;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductImportPersistenceTest extends AbstractServiceTest {

    private static final String OVERWRITE = "overwrite";
    private static final String SKIP = "skip";

    @Autowired private ProductImportService importService;
    @Autowired private DealLineItemService dealLineItemService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoSpyBean private ProductMapper productMapper;

    private final List<Integer> createdUserIds = new ArrayList<>();
    private String skuPrefix;
    private Integer foreignWorkspaceId;
    private Integer persistedDealId;
    private Integer persistedPipelineId;
    private Integer persistedCompanyId;

    @AfterEach
    void cleanUpCommittedFixtures() {
        if (persistedDealId != null) {
            jdbcTemplate.update("DELETE FROM deal_line_item WHERE deal_id = ?", persistedDealId);
            jdbcTemplate.update("DELETE FROM deal WHERE id = ?", persistedDealId);
        }
        if (skuPrefix != null) {
            jdbcTemplate.update("DELETE FROM product WHERE sku LIKE ?", skuPrefix + "%");
        }
        if (persistedPipelineId != null) {
            jdbcTemplate.update("DELETE FROM stage WHERE pipeline_id = ?", persistedPipelineId);
            jdbcTemplate.update("DELETE FROM pipeline WHERE id = ?", persistedPipelineId);
        }
        if (persistedCompanyId != null) {
            jdbcTemplate.update("DELETE FROM company WHERE id = ?", persistedCompanyId);
        }
        if (foreignWorkspaceId != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id = ?", foreignWorkspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", foreignWorkspaceId);
        }
        if (workspace != null) {
            for (Integer userId : createdUserIds.reversed()) {
                jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(),
                    userId);
            }
        }
        for (Integer userId : createdUserIds.reversed()) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        createdUserIds.add(user.getId());
        return user;
    }

    @Test
    void previewThenCommitCreatesTheCatalogRows() {
        String prefix = prefix();
        ProductImportRequest request = catalogRequest(
            OVERWRITE,
            fullRow(prefix + "A", "Widget", "1,200.005", "10.0005"),
            fullRow(prefix + "B", "Gadget", "50.00", "8.000"));

        ProductImportPreviewResult preview = importService.previewProducts(request);

        assertEquals(2, preview.total());
        assertEquals(2, preview.toCreate());
        assertEquals(0, preview.toUpdate());
        assertTrue(preview.duplicateReviewProof().matches("^[0-9a-f]{64}$"));

        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        ProductImportResult result = importService.commitProducts(request);

        assertEquals(2, result.created());
        assertEquals(0, result.updated());
        assertTrue(result.failed().isEmpty());
        Product created = requireBySku(workspace.getId(), prefix + "A");
        assertEquals("Widget", created.getName());
        assertEquals(0, new BigDecimal("1200.01").compareTo(created.getUnitPrice()));
        assertEquals(0, new BigDecimal("10.001").compareTo(created.getTaxRate()));
        assertEquals("JPY", created.getCurrency());
        assertEquals("recurring", created.getBillingFrequency());
        assertEquals("seat", created.getUnit());
        assertEquals(LocalDate.parse("2026-01-01"), created.getEffectiveStart());
        assertEquals(LocalDate.parse("2026-12-31"), created.getEffectiveEnd());
        assertNotNull(requireBySku(workspace.getId(), prefix + "B"));
    }

    @Test
    void replayingTheSameFileWithSkipCreatesNothingNew() {
        String prefix = prefix();
        previewAndCommit(catalogRequest(
            SKIP, fullRow(prefix + "A", "Widget", "100.00", "10.000")));
        int originalId = requireBySku(workspace.getId(), prefix + "A").getId();

        ProductImportRequest replay = catalogRequest(
            SKIP, fullRow(prefix + "A", "Renamed", "999.00", "10.000"));
        ProductImportPreviewResult preview = importService.previewProducts(replay);
        assertEquals(1, preview.toSkip());
        assertEquals(0, preview.toCreate());
        assertEquals(0, preview.toUpdate());
        replay.setDuplicateReviewProof(preview.duplicateReviewProof());
        ProductImportResult result = importService.commitProducts(replay);

        assertEquals(0, result.created());
        assertEquals(0, result.updated());
        assertEquals(1, result.skipped());
        Product unchanged = requireBySku(workspace.getId(), prefix + "A");
        assertEquals(originalId, unchanged.getId());
        assertEquals("Widget", unchanged.getName());
        assertEquals(0, new BigDecimal("100.00").compareTo(unchanged.getUnitPrice()));
        assertEquals(1, countBySku(prefix + "A"));
    }

    @Test
    void replayingTheSameFileWithOverwriteUpdatesInPlaceAndKeepsProductIds() {
        String prefix = prefix();
        previewAndCommit(catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Widget", "100.00", "10.000")));
        int originalId = requireBySku(workspace.getId(), prefix + "A").getId();

        ProductImportResult result = previewAndCommit(catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Renamed widget", "250.50", "8.250")));

        assertEquals(0, result.created());
        assertEquals(1, result.updated());
        Product updated = requireBySku(workspace.getId(), prefix + "A");
        assertEquals(originalId, updated.getId());
        assertEquals("Renamed widget", updated.getName());
        assertEquals(0, new BigDecimal("250.50").compareTo(updated.getUnitPrice()));
        assertEquals(0, new BigDecimal("8.250").compareTo(updated.getTaxRate()));
        assertEquals(1, countBySku(prefix + "A"));
    }

    @Test
    void committingWithAConsumedProofIsAConflict() {
        String prefix = prefix();
        ProductImportRequest request = catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Widget", "100.00", "10.000"));
        ProductImportPreviewResult preview = importService.previewProducts(request);
        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        importService.commitProducts(request);

        ConflictException exception = assertThrows(
            ConflictException.class, () -> importService.commitProducts(request));

        assertEquals(
            "Import review is missing or expired; preview the import again",
            exception.getMessage());
        assertEquals(1, countBySku(prefix + "A"));
    }

    @Test
    void committingWithAProofFromADifferentRequestIsAConflict() {
        String prefix = prefix();
        ProductImportRequest reviewed = catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Widget", "100.00", "10.000"));
        ProductImportPreviewResult preview = importService.previewProducts(reviewed);

        ProductImportRequest tampered = catalogRequest(
            OVERWRITE, fullRow(prefix + "B", "Smuggled", "1.00", "0.000"));
        tampered.setDuplicateReviewProof(preview.duplicateReviewProof());
        assertThrows(ConflictException.class, () -> importService.commitProducts(tampered));
        assertEquals(0, countBySku(prefix + "B"));

        ProductImportRequest policySwap = catalogRequest(
            SKIP, fullRow(prefix + "A", "Widget", "100.00", "10.000"));
        policySwap.setDuplicateReviewProof(preview.duplicateReviewProof());
        assertThrows(ConflictException.class, () -> importService.commitProducts(policySwap));
        assertEquals(0, countBySku(prefix + "A"));

        assertEquals(1, previewAndCommit(reviewed).created());
    }

    @Test
    void updatingACatalogItemLeavesExistingDealLineItemSnapshotsUnchanged() {
        String prefix = prefix();
        previewAndCommit(catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Widget", "100.00", "10.000")));
        Product product = requireBySku(workspace.getId(), prefix + "A");

        Company company = newCompany();
        persistedCompanyId = company.getId();
        Pipeline pipeline = newPipeline();
        persistedPipelineId = pipeline.getId();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        persistedDealId = deal.getId();
        DealLineItemRequest line = new DealLineItemRequest();
        line.setProductId(product.getId());
        line.setQuantity(new BigDecimal("2"));
        dealLineItemService.create(deal.getId(), line);
        Map<String, Object> before = lineItemSnapshot(deal.getId());

        ProductImportResult result = previewAndCommit(catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Repriced widget", "999.99", "25.000")));

        assertEquals(1, result.updated());
        Product repriced = requireBySku(workspace.getId(), prefix + "A");
        assertEquals(product.getId(), repriced.getId());
        assertEquals("Repriced widget", repriced.getName());
        assertEquals(0, new BigDecimal("999.99").compareTo(repriced.getUnitPrice()));
        assertEquals(before, lineItemSnapshot(deal.getId()));
    }

    @Test
    void importIsIsolatedByWorkspace() {
        String prefix = prefix();
        Workspace foreign = new Workspace();
        foreign.setName("Foreign " + unique());
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        foreignWorkspaceId = foreign.getId();
        Product foreignProduct = new Product();
        foreignProduct.setWorkspaceId(foreign.getId());
        foreignProduct.setSku(prefix + "A");
        foreignProduct.setName("Foreign widget");
        foreignProduct.setActive(true);
        foreignProduct.setUnitPrice(new BigDecimal("7.00"));
        foreignProduct.setCurrency("USD");
        foreignProduct.setBillingFrequency("one_time");
        productMapper.insert(foreignProduct);

        ProductImportRequest request = catalogRequest(
            OVERWRITE, fullRow(prefix + "A", "Local widget", "100.00", "10.000"));
        ProductImportPreviewResult preview = importService.previewProducts(request);

        assertEquals(1, preview.toCreate());
        assertEquals(0, preview.toUpdate());
        assertNull(preview.rows().getFirst().matchedId());

        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        assertEquals(1, importService.commitProducts(request).created());

        Product local = requireBySku(workspace.getId(), prefix + "A");
        Product untouched = productMapper.getById(foreign.getId(), foreignProduct.getId());
        assertEquals("Local widget", local.getName());
        assertEquals("Foreign widget", untouched.getName());
        assertEquals(0, new BigDecimal("7.00").compareTo(untouched.getUnitPrice()));
    }

    @Test
    void duplicateSkuRaceRollsBackTheWholeCommit() throws Exception {
        String prefix = prefix();
        ProductImportRequest request = catalogRequest(
            OVERWRITE,
            fullRow(prefix + "A", "Widget", "100.00", "10.000"),
            fullRow(prefix + "B", "Gadget", "50.00", "8.000"));
        ProductImportPreviewResult preview = importService.previewProducts(request);
        assertEquals(2, preview.toCreate());
        request.setDuplicateReviewProof(preview.duplicateReviewProof());

        ProductMapper realProductMapper = sqlSessionTemplate.getMapper(ProductMapper.class);
        AtomicBoolean armed = new AtomicBoolean(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                List<Product> found = realProductMapper.findBySkus(
                    invocation.getArgument(0, Integer.class),
                    invocation.getArgument(1));
                if (armed.compareAndSet(true, false)) {
                    executor.submit(() -> insertRacingProduct(prefix + "A"))
                        .get(20, TimeUnit.SECONDS);
                }
                return found;
            }).when(productMapper).findBySkus(eq(workspace.getId()), anyList());

            assertThrows(
                DataIntegrityViolationException.class,
                () -> importService.commitProducts(request));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, countBySku(prefix + "A"));
        assertEquals("Raced in", requireBySku(workspace.getId(), prefix + "A").getName());
        assertEquals(0, countBySku(prefix + "B"));
    }

    @Test
    void concurrentSkuCreationBetweenPreviewAndCommitFailsClosed() {
        String prefix = prefix();
        ProductImportRequest request = catalogRequest(
            OVERWRITE,
            fullRow(prefix + "A", "Widget", "100.00", "10.000"),
            fullRow(prefix + "B", "Gadget", "50.00", "8.000"));
        ProductImportPreviewResult preview = importService.previewProducts(request);
        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        insertRacingProduct(prefix + "A");

        ConflictException exception = assertThrows(
            ConflictException.class, () -> importService.commitProducts(request));

        assertEquals(
            "Duplicate candidates changed before import; review them again",
            exception.getMessage());
        assertEquals(0, countBySku(prefix + "B"));
        assertEquals("Raced in", requireBySku(workspace.getId(), prefix + "A").getName());
    }

    private int insertRacingProduct(String sku) {
        return jdbcTemplate.update(
            "INSERT INTO product (workspace_id, sku, name, unit_price, currency, "
                + "billing_frequency) VALUES (?, ?, ?, ?, ?, ?)",
            workspace.getId(),
            sku,
            "Raced in",
            new BigDecimal("1.00"),
            "USD",
            "one_time");
    }

    private ProductImportResult previewAndCommit(ProductImportRequest request) {
        ProductImportPreviewResult preview = importService.previewProducts(request);
        request.setDuplicateReviewProof(preview.duplicateReviewProof());
        return importService.commitProducts(request);
    }

    private String prefix() {
        skuPrefix = "pimp-" + unique() + "-";
        return skuPrefix;
    }

    private Product requireBySku(int workspaceId, String sku) {
        List<Product> found = productMapper.findBySkus(workspaceId, List.of(sku));
        assertEquals(1, found.size(), "expected exactly one catalog row for " + sku);
        return found.getFirst();
    }

    private int countBySku(String sku) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM product WHERE workspace_id = ? AND sku = ?",
            Integer.class,
            workspace.getId(),
            sku);
        return count == null ? 0 : count;
    }

    private Map<String, Object> lineItemSnapshot(int dealId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT product_id, name, sku, unit, unit_price, tax_rate, billing_frequency, "
                + "quantity, currency, line_subtotal, line_tax, line_total "
                + "FROM deal_line_item WHERE deal_id = ? ORDER BY id",
            dealId);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    @SafeVarargs
    private static ProductImportRequest catalogRequest(
            String onConflict,
            Map<String, String>... rows) {
        return new ProductImportRequest(
            List.of(rows),
            List.of(
                new ProductImportColumnMapping("SKU", "sku"),
                new ProductImportColumnMapping("Name", "name"),
                new ProductImportColumnMapping("Description", "description"),
                new ProductImportColumnMapping("Active", "active"),
                new ProductImportColumnMapping("Unit", "unit"),
                new ProductImportColumnMapping("Price", "unitPrice"),
                new ProductImportColumnMapping("Currency", "currency"),
                new ProductImportColumnMapping("Tax", "taxRate"),
                new ProductImportColumnMapping("Frequency", "billingFrequency"),
                new ProductImportColumnMapping("Start", "effectiveStart"),
                new ProductImportColumnMapping("End", "effectiveEnd")),
            onConflict,
            null,
            null);
    }

    private static Map<String, String> fullRow(
            String sku,
            String name,
            String price,
            String taxRate) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("SKU", sku);
        row.put("Name", name);
        row.put("Description", "Imported " + name);
        row.put("Active", "yes");
        row.put("Unit", "seat");
        row.put("Price", price);
        row.put("Currency", "JPY");
        row.put("Tax", taxRate);
        row.put("Frequency", "recurring");
        row.put("Start", "2026-01-01");
        row.put("End", "2026-12-31");
        return row;
    }
}
