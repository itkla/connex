package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.ProductSkuResolution;
import ooo.klae.connex.backend.dto.ProductImportColumnMapping;
import ooo.klae.connex.backend.dto.ProductImportPreviewResult;
import ooo.klae.connex.backend.dto.ProductImportRequest;
import ooo.klae.connex.backend.dto.ProductImportResult;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ProductMapper;

@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    private static final int WORKSPACE_ID = 7;
    private static final String PROOF = "a".repeat(64);
    private static final String OVERWRITE = "overwrite";
    private static final String SKIP = "skip";
    private static final String UPDATE = "update";
    private static final String CREATE = "create";

    @Mock private WorkspaceService workspaceService;
    @Mock private DuplicatePreflightService duplicatePreflightService;
    @Mock private ProductMapper productMapper;
    @Mock private AuditService auditService;

    private final Deque<DuplicatePreflightService.ImportPreviewSession> previews =
        new ArrayDeque<>();
    private final Deque<DuplicatePreflightService.ImportCommitSession> commits =
        new ArrayDeque<>();
    private final Map<Integer, Product> catalogById = new LinkedHashMap<>();
    private final List<Product> inserted = new ArrayList<>();

    private DuplicatePreflightService.ImportCommitAdmission admission;
    private ProductImportService service;

    @BeforeEach
    void setUp() {
        admission = mock(DuplicatePreflightService.ImportCommitAdmission.class);
        service = new ProductImportService(
            workspaceService, duplicatePreflightService, productMapper, auditService);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(duplicatePreflightService.beginImportPreview(
                anyList(), anyList(), anyString()))
            .thenAnswer(invocation -> previews.removeFirst());
        lenient().when(duplicatePreflightService.claimImportCommit(any(), anyString()))
            .thenReturn(admission);
        lenient().when(duplicatePreflightService.beginImportCommit(
                anyList(), anyList(), anyString(), eq(admission)))
            .thenAnswer(invocation -> commits.removeFirst());
        lenient().when(productMapper.resolveImportSkus(eq(WORKSPACE_ID), anyList()))
            .thenAnswer(invocation -> collationResolutions(invocation.getArgument(1)));
        lenient().when(productMapper.getByIdForUpdate(eq(WORKSPACE_ID), anyInt()))
            .thenAnswer(invocation -> catalogById.get(invocation.getArgument(1, Integer.class)));
        lenient().when(productMapper.insertBatch(anyList()))
            .thenAnswer(invocation -> {
                List<Product> batch = invocation.getArgument(0);
                inserted.addAll(batch);
                return batch.size();
            });
    }

    @Test
    void previewClassifiesNewSkusAsCreateAndExistingSkusAsUpdate() {
        catalog(31, "A-1", "Existing widget");
        queuePreview();

        ProductImportPreviewResult preview = service.previewProducts(
            request(OVERWRITE, row("A-1", "Widget"), row("B-2", "Gadget")));

        assertEquals(2, preview.total());
        assertEquals(1, preview.toCreate());
        assertEquals(1, preview.toUpdate());
        assertEquals(0, preview.toSkip());
        assertEquals(0, preview.invalid());
        assertEquals(UPDATE, preview.rows().getFirst().status());
        assertEquals(31, preview.rows().getFirst().matchedId());
        assertEquals("Existing widget", preview.rows().getFirst().matchedLabel());
        assertEquals("A-1", preview.rows().getFirst().sku());
        assertEquals(CREATE, preview.rows().getLast().status());
        assertNull(preview.rows().getLast().matchedId());
        assertEquals(PROOF, preview.duplicateReviewProof());
    }

    @Test
    void previewSkipsExistingSkusUnderTheDefaultSkipPolicy() {
        catalog(31, "A-1", "Existing widget");
        queuePreview();

        ProductImportPreviewResult preview = service.previewProducts(
            request(null, row("A-1", "Widget"), row("B-2", "Gadget")));

        assertEquals(1, preview.toCreate());
        assertEquals(0, preview.toUpdate());
        assertEquals(1, preview.toSkip());
        assertEquals(SKIP, preview.rows().getFirst().status());
    }

    @Test
    void previewMarksExistingSkusForUpdateUnderOverwrite() {
        catalog(31, "A-1", "Existing widget");
        queuePreview();

        ProductImportPreviewResult preview = service.previewProducts(
            request(OVERWRITE, row("A-1", "Widget")));

        assertEquals(1, preview.toUpdate());
        assertEquals(0, preview.toSkip());
    }

    @Test
    void rowDecisionOverridesThePolicyPerRow() {
        catalog(31, "A-1", "Existing widget");
        catalog(32, "C-3", "Existing gadget");
        queuePreview();
        ProductImportRequest underSkip =
            request(SKIP, row("A-1", "Widget"), row("C-3", "Gadget"));
        underSkip.setRowDecisions(Map.of(0, UPDATE));

        ProductImportPreviewResult skipPolicy = service.previewProducts(underSkip);

        assertEquals(UPDATE, skipPolicy.rows().getFirst().status());
        assertEquals(SKIP, skipPolicy.rows().getLast().status());

        queuePreview();
        ProductImportRequest underOverwrite =
            request(OVERWRITE, row("A-1", "Widget"), row("C-3", "Gadget"));
        underOverwrite.setRowDecisions(Map.of(0, SKIP));

        ProductImportPreviewResult overwritePolicy = service.previewProducts(underOverwrite);

        assertEquals(SKIP, overwritePolicy.rows().getFirst().status());
        assertEquals(UPDATE, overwritePolicy.rows().getLast().status());
    }

    @Test
    void rowDecisionUpdateOnANewSkuIsInvalid() {
        queuePreview();
        ProductImportRequest request = request(OVERWRITE, row("B-2", "Gadget"));
        request.setRowDecisions(Map.of(0, UPDATE));

        ProductImportPreviewResult preview = service.previewProducts(request);

        assertEquals(1, preview.invalid());
        assertEquals("invalid", preview.rows().getFirst().status());
        assertEquals(
            List.of("Row 1 has no existing SKU to update"),
            preview.rows().getFirst().errors());
    }

    @Test
    void rowDecisionCreateOnAnExistingSkuIsInvalid() {
        catalog(31, "A-1", "Existing widget");
        queuePreview();
        ProductImportRequest request = request(OVERWRITE, row("A-1", "Widget"));
        request.setRowDecisions(Map.of(0, CREATE));

        ProductImportPreviewResult preview = service.previewProducts(request);

        assertEquals(1, preview.invalid());
        assertEquals(
            List.of("SKU A-1 already exists; choose update or skip"),
            preview.rows().getFirst().errors());
    }

    @Test
    void blankSkuRowIsInvalid() {
        queuePreview();

        ProductImportPreviewResult preview = service.previewProducts(
            request(OVERWRITE, row("   ", "Gadget"), row("B-2", "Widget")));

        assertEquals(1, preview.invalid());
        assertEquals(
            List.of("A SKU is required to import a catalog row"),
            preview.rows().getFirst().errors());
        assertNull(preview.rows().getFirst().sku());
        assertEquals(1, preview.toCreate());
    }

    @Test
    void repeatedSkuWithinTheFileInvalidatesEveryOccurrence() {
        queuePreview();

        ProductImportPreviewResult preview = service.previewProducts(
            request(OVERWRITE, row("A-1", "Widget"), row("a-1", "Gadget"), row("B-2", "Other")));

        assertEquals(2, preview.invalid());
        assertEquals(1, preview.toCreate());
        assertTrue(preview.rows().getFirst().errors().getFirst()
            .startsWith("Row 1 repeats SKU A-1"));
        assertTrue(preview.rows().get(1).errors().getFirst()
            .startsWith("Row 2 repeats SKU a-1"));
        verify(productMapper).resolveImportSkus(
            eq(WORKSPACE_ID), eq(List.of("A-1", "a-1", "B-2")));
    }

    @Test
    void invalidUnitPriceTaxRateActiveBillingFrequencyAndDateRowsAreInvalid() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("Price=not-a-number", List.of("unitPrice must be a decimal number"));
        expected.put("Price=-1.00", List.of("unitPrice must not be negative"));
        expected.put("Tax=ten percent", List.of("taxRate must be a decimal number"));
        expected.put("Active=maybe", List.of("active must be true, false, yes, no, 1, or 0"));
        expected.put("Frequency=annually", List.of("billingFrequency must be one_time or recurring"));
        expected.put("Start=01/02/2026", List.of("effectiveStart must use YYYY-MM-DD"));
        expected.put("End=2026-13-45", List.of("effectiveEnd must use YYYY-MM-DD"));

        for (Map.Entry<String, List<String>> scenario : expected.entrySet()) {
            queuePreview();
            String[] override = scenario.getKey().split("=", 2);
            Map<String, String> row = fullRow("A-1");
            row.put(override[0], override[1]);

            ProductImportPreviewResult preview =
                service.previewProducts(fullRequest(OVERWRITE, row));

            assertEquals(1, preview.invalid(), scenario.getKey());
            assertEquals(0, preview.toCreate(), scenario.getKey());
            assertEquals(
                scenario.getValue(), preview.rows().getFirst().errors(), scenario.getKey());
        }
    }

    @Test
    void unitPriceAndTaxRateAreHalfUpRoundedToColumnScale() {
        Map<String, String> row = fullRow("A-1");
        row.put("Price", "1,200.005");
        row.put("Tax", "10.0005");

        ProductImportResult result = commit(fullRequest(OVERWRITE, row));

        assertEquals(1, result.created());
        assertEquals(new BigDecimal("1200.01"), inserted.getFirst().getUnitPrice());
        assertEquals(new BigDecimal("10.001"), inserted.getFirst().getTaxRate());
    }

    @Test
    void createDefaultsMatchProductDtoToBean() {
        commit(request(OVERWRITE, row("A-1", "Widget")));

        Product created = inserted.getFirst();
        assertEquals(WORKSPACE_ID, created.getWorkspaceId());
        assertEquals("A-1", created.getSku());
        assertEquals("Widget", created.getName());
        assertTrue(created.isActive());
        assertEquals(BigDecimal.ZERO, created.getUnitPrice());
        assertEquals("USD", created.getCurrency());
        assertEquals("one_time", created.getBillingFrequency());
        assertNull(created.getTaxRate());
        assertNull(created.getDescription());
    }

    @Test
    void aCreateRowWithoutANameIsInvalid() {
        queuePreview();
        Map<String, String> row = row("A-1", "  ");

        ProductImportPreviewResult preview =
            service.previewProducts(request(OVERWRITE, row));

        assertEquals(1, preview.invalid());
        assertEquals(
            List.of("A name is required to create catalog SKU A-1"),
            preview.rows().getFirst().errors());
    }

    @Test
    void blankCellNeverClearsAnExistingValue() {
        Product existing = catalog(31, "A-1", "Existing widget");
        existing.setDescription("Keep me");
        existing.setUnit("seat");
        existing.setEffectiveEnd(LocalDate.parse("2027-01-01"));
        Map<String, String> row = fullRow("A-1");
        row.put("Description", "  ");
        row.put("Unit", "");
        row.put("End", "");
        row.put("Name", "Renamed widget");

        commit(fullRequest(OVERWRITE, row));

        assertEquals("Renamed widget", existing.getName());
        assertEquals("Keep me", existing.getDescription());
        assertEquals("seat", existing.getUnit());
        assertEquals(LocalDate.parse("2027-01-01"), existing.getEffectiveEnd());
        verify(productMapper).update(existing);
    }

    @Test
    void csvFormulaPrefixIsStrippedFromEveryCell() {
        Map<String, String> row = fullRow("'=A-1");
        row.put("Name", "'+Widget");
        row.put("Description", "'-Injected");

        commit(fullRequest(OVERWRITE, row));

        assertEquals("=A-1", inserted.getFirst().getSku());
        assertEquals("+Widget", inserted.getFirst().getName());
        assertEquals("-Injected", inserted.getFirst().getDescription());
    }

    @Test
    void unknownFieldDuplicateFieldAndMissingSkuMappingAreBadRequests() {
        ProductImportRequest unknown = new ProductImportRequest(
            List.of(row("A-1", "Widget")),
            List.of(mapping("SKU", "sku"), mapping("Secret", "internalCost")),
            OVERWRITE, null, null);
        assertEquals(
            "Unsupported catalog field: internalCost",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(unknown)).getMessage());

        ProductImportRequest identifier = new ProductImportRequest(
            List.of(row("A-1", "Widget")),
            List.of(mapping("SKU", "sku"), mapping("id", "id")),
            OVERWRITE, null, null);
        assertEquals(
            "Unsupported catalog field: id",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(identifier)).getMessage());

        ProductImportRequest duplicatedField = new ProductImportRequest(
            List.of(row("A-1", "Widget")),
            List.of(mapping("SKU", "sku"), mapping("Name", "name"), mapping("Title", "name")),
            OVERWRITE, null, null);
        assertEquals(
            "Catalog field is mapped more than once: name",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(duplicatedField)).getMessage());

        ProductImportRequest duplicatedColumn = new ProductImportRequest(
            List.of(row("A-1", "Widget")),
            List.of(mapping("SKU", "sku"), mapping("SKU", "name")),
            OVERWRITE, null, null);
        assertEquals(
            "CSV column is mapped more than once: SKU",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(duplicatedColumn)).getMessage());

        ProductImportRequest noSku = new ProductImportRequest(
            List.of(row("A-1", "Widget")),
            List.of(mapping("Name", "name")),
            OVERWRITE, null, null);
        assertEquals(
            "A column must be mapped to sku to import a catalog",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(noSku)).getMessage());

        verify(duplicatePreflightService, never())
            .beginImportPreview(anyList(), anyList(), anyString());
    }

    @Test
    void rowsOrMappingOverCapAreBadRequests() {
        List<Map<String, String>> tooManyRows = IntStream.rangeClosed(0, 5000)
            .mapToObj(index -> row("SKU-" + index, "Widget " + index))
            .toList();
        ProductImportRequest oversized = new ProductImportRequest(
            tooManyRows,
            List.of(mapping("SKU", "sku"), mapping("Name", "name")),
            OVERWRITE, null, null);
        assertEquals(
            "At most 5000 catalog rows may be imported",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(oversized)).getMessage());

        ProductImportRequest noMapping = new ProductImportRequest(
            List.of(row("A-1", "Widget")), List.of(), OVERWRITE, null, null);
        assertEquals(
            "Between 1 and 64 column mappings are required",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(noMapping)).getMessage());

        ProductImportRequest badDecision = request(OVERWRITE, row("A-1", "Widget"));
        badDecision.setRowDecisions(Map.of(4, UPDATE));
        assertEquals(
            "Row decisions contain an invalid row or action",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(badDecision)).getMessage());

        ProductImportRequest badPolicy = request("fill_empty", row("A-1", "Widget"));
        assertEquals(
            "onConflict must be overwrite or skip",
            assertThrows(BadRequestException.class,
                () -> service.previewProducts(badPolicy)).getMessage());
    }

    @Test
    void failedPreviewCancelsItsReservedProof() {
        queuePreview();
        ProductImportRequest request = request(OVERWRITE, row("A-1", "Widget"));
        when(productMapper.resolveImportSkus(eq(WORKSPACE_ID), anyList()))
            .thenThrow(new IllegalStateException("resolution failed"));

        assertThrows(IllegalStateException.class, () -> service.previewProducts(request));

        verify(duplicatePreflightService).cancelImportPreview(PROOF);
    }

    @Test
    void commitClaimsTheProofBeforeAnyProductLock() {
        catalog(31, "A-1", "Existing widget");

        commit(request(OVERWRITE, row("A-1", "Widget")));

        InOrder order = inOrder(duplicatePreflightService, productMapper);
        order.verify(duplicatePreflightService).claimImportCommit(eq(PROOF), anyString());
        order.verify(productMapper).getByIdForUpdate(WORKSPACE_ID, 31);
    }

    @Test
    void commitWithoutAProofIsAConflict() {
        when(duplicatePreflightService.claimImportCommit(isNull(), anyString()))
            .thenThrow(new ConflictException(
                "Import review is missing or expired; preview the import again"));
        ProductImportRequest request = request(OVERWRITE, row("A-1", "Widget"));

        assertThrows(ConflictException.class, () -> service.commitProducts(request));

        verify(productMapper, never()).insertBatch(anyList());
        verify(productMapper, never()).update(any());
        verify(productMapper, never()).getByIdForUpdate(anyInt(), anyInt());
    }

    @Test
    void commitLocksMatchedProductsInAscendingIdOrder() {
        catalog(44, "D-4", "Fourth");
        catalog(12, "B-2", "Second");
        catalog(31, "C-3", "Third");

        commit(request(OVERWRITE,
            row("D-4", "Fourth edit"), row("B-2", "Second edit"), row("C-3", "Third edit")));

        InOrder order = inOrder(productMapper);
        order.verify(productMapper).getByIdForUpdate(WORKSPACE_ID, 12);
        order.verify(productMapper).getByIdForUpdate(WORKSPACE_ID, 31);
        order.verify(productMapper).getByIdForUpdate(WORKSPACE_ID, 44);
    }

    @Test
    void commitFailsOnlyTheRowsWhoseLockedTargetVanished() {
        catalog(31, "A-1", "Existing widget");
        catalog(32, "C-3", "Existing gadget");
        queuePreview();
        ProductImportRequest request = request(OVERWRITE,
            row("A-1", "Widget"), row("C-3", "Gadget"), row("B-2", "New"));
        service.previewProducts(request);
        request.setDuplicateReviewProof(PROOF);
        queueCommit();
        when(productMapper.getByIdForUpdate(WORKSPACE_ID, 31)).thenReturn(null);

        ProductImportResult result = service.commitProducts(request);

        assertEquals(1, result.updated());
        assertEquals(1, result.created());
        assertEquals(1, result.failed().size());
        assertEquals(0, result.failed().getFirst().getRowIndex());
        assertEquals(
            "Catalog row for SKU A-1 no longer exists",
            result.failed().getFirst().getReason());
        assertEquals(1, inserted.size());
        assertEquals("B-2", inserted.getFirst().getSku());
    }

    @Test
    void commitFailsOnlyTheRowsWhoseLockedTargetChangedItsSku() {
        catalog(31, "A-1", "Existing widget");
        Product survivor = catalog(32, "C-3", "Existing gadget");
        queuePreview();
        ProductImportRequest request =
            request(OVERWRITE, row("A-1", "Widget"), row("C-3", "Gadget"));
        service.previewProducts(request);
        request.setDuplicateReviewProof(PROOF);
        queueCommit();
        Product renamed = catalogSnapshot(31, "RENAMED-1", "Existing widget");
        when(productMapper.getByIdForUpdate(WORKSPACE_ID, 31)).thenAnswer(invocation -> {
            catalogById.put(31, renamed);
            return renamed;
        });

        ProductImportResult result = service.commitProducts(request);

        assertEquals(1, result.updated());
        assertEquals(1, result.failed().size());
        assertEquals(
            "Catalog row for SKU A-1 changed before the import committed",
            result.failed().getFirst().getReason());
        assertEquals("Existing widget", renamed.getName());
        assertEquals("Gadget", survivor.getName());
        verify(productMapper, never()).update(renamed);
    }

    @Test
    void commitUpdatesOnlyWhenAMappedColumnActuallyChanged() {
        Product existing = catalog(31, "A-1", "Widget");
        existing.setUnitPrice(new BigDecimal("100.00"));
        Map<String, String> unchanged = new LinkedHashMap<>();
        unchanged.put("SKU", "A-1");
        unchanged.put("Name", "Widget");
        unchanged.put("Price", "100.000");
        ProductImportRequest request = new ProductImportRequest(
            List.of(unchanged),
            List.of(mapping("SKU", "sku"), mapping("Name", "name"), mapping("Price", "unitPrice")),
            OVERWRITE, null, null);

        ProductImportResult result = commit(request);

        assertEquals(1, result.updated());
        verify(productMapper, never()).update(any());
    }

    @Test
    void commitInsertsCreatesInAscendingSkuOrderAndBatchesAt250() {
        List<Map<String, String>> rows = IntStream.range(0, 260)
            .mapToObj(index -> row(String.format("SKU-%03d", 259 - index), "Widget " + index))
            .toList();
        ProductImportRequest request = new ProductImportRequest(
            rows,
            List.of(mapping("SKU", "sku"), mapping("Name", "name")),
            OVERWRITE, null, null);

        ProductImportResult result = commit(request);

        assertEquals(260, result.created());
        ArgumentCaptor<List<Product>> batches = ArgumentCaptor.captor();
        verify(productMapper, times(2)).insertBatch(batches.capture());
        assertEquals(250, batches.getAllValues().getFirst().size());
        assertEquals(10, batches.getAllValues().getLast().size());
        assertEquals("SKU-000", inserted.getFirst().getSku());
        assertEquals("SKU-259", inserted.getLast().getSku());
    }

    @Test
    void commitWritesOneSummaryAuditAndNeverPerRow() {
        catalog(31, "A-1", "Existing widget");

        commit(request(OVERWRITE,
            row("A-1", "Widget"), row("B-2", "Gadget"), row("C-3", "Other")));

        ArgumentCaptor<Object> changes = ArgumentCaptor.captor();
        verify(auditService).record(
            eq("import.product"),
            eq("product"),
            isNull(),
            eq("CSV import"),
            eq("Imported products: 2 created, 1 updated, 0 skipped, 0 failed"),
            changes.capture());
        assertEquals(
            Map.of("created", 2, "updated", 1, "skipped", 0, "failed", 0),
            changes.getValue());
    }

    @Test
    void commitNeverTouchesDealLineItems() {
        Set<String> dependencies = Arrays.stream(
                ProductImportService.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getType)
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());

        assertTrue(
            Collections.disjoint(
                dependencies,
                Set.of("DealLineItemMapper", "DealLineItemService", "DealService", "ProductService")),
            "Catalog imports must not reach deal line items: " + dependencies);
        assertEquals(
            Set.of(
                "WorkspaceService",
                "DuplicatePreflightService",
                "ProductMapper",
                "AuditService"),
            dependencies);
    }

    @Test
    void decisionFingerprintChangesWhenAnySkuFlipsBetweenCreateAndUpdate() {
        queuePreview();
        service.previewProducts(request(OVERWRITE, row("A-1", "Widget")));
        String whenNew = latestDecisionFingerprint();

        catalog(31, "A-1", "Existing widget");
        queuePreview();
        service.previewProducts(request(OVERWRITE, row("A-1", "Widget")));

        assertNotEquals(whenNew, latestDecisionFingerprint());
    }

    @Test
    void reviewContextChangesWithOnConflictAndWithRowDecisions() {
        queuePreview();
        service.previewProducts(request(OVERWRITE, row("A-1", "Widget")));
        queuePreview();
        service.previewProducts(request(SKIP, row("A-1", "Widget")));
        ProductImportRequest withDecision = request(OVERWRITE, row("A-1", "Widget"));
        withDecision.setRowDecisions(Map.of(0, SKIP));
        queuePreview();
        service.previewProducts(withDecision);

        ArgumentCaptor<String> contexts = ArgumentCaptor.captor();
        verify(duplicatePreflightService, times(3))
            .beginImportPreview(anyList(), anyList(), contexts.capture());
        assertEquals(3, Set.copyOf(contexts.getAllValues()).size());
        assertTrue(contexts.getAllValues().stream()
            .allMatch(context -> context.matches("^[0-9a-f]{64}$")));
    }

    @Test
    void collationEquivalentExistingSkuIsClassifiedAsAConflict() {
        catalog(31, "café", "Accented widget");
        queuePreview();
        ProductImportRequest request = request(OVERWRITE, row("cafe", "Widget"));

        ProductImportPreviewResult preview = service.previewProducts(request);

        assertEquals(1, preview.toUpdate());
        assertEquals(31, preview.rows().getFirst().matchedId());
        assertEquals("Accented widget", preview.rows().getFirst().matchedLabel());
        verify(duplicatePreflightService, never()).cancelImportPreview(PROOF);
    }

    private String latestDecisionFingerprint() {
        ArgumentCaptor<String> fingerprints = ArgumentCaptor.captor();
        verify(duplicatePreflightService, atLeastOnce())
            .completeImportPreview(any(), fingerprints.capture());
        return fingerprints.getValue();
    }

    private ProductImportResult commit(ProductImportRequest request) {
        queuePreview();
        ProductImportPreviewResult preview = service.previewProducts(request);
        assertEquals(PROOF, preview.duplicateReviewProof());
        request.setDuplicateReviewProof(PROOF);
        queueCommit();
        return service.commitProducts(request);
    }

    private void queuePreview() {
        DuplicatePreflightService.ImportPreviewSession session =
            mock(DuplicatePreflightService.ImportPreviewSession.class);
        when(session.reviewProof()).thenReturn(PROOF);
        previews.addLast(session);
    }

    private void queueCommit() {
        commits.addLast(mock(DuplicatePreflightService.ImportCommitSession.class));
    }

    private Product catalog(int id, String sku, String name) {
        Product product = catalogSnapshot(id, sku, name);
        catalogById.put(id, product);
        return product;
    }

    private static Product catalogSnapshot(int id, String sku, String name) {
        Product product = new Product();
        product.setId(id);
        product.setWorkspaceId(WORKSPACE_ID);
        product.setSku(sku);
        product.setName(name);
        product.setActive(true);
        product.setUnitPrice(new BigDecimal("1.00"));
        product.setCurrency("USD");
        product.setBillingFrequency("one_time");
        return product;
    }

    /** Stands in for the mapper's database-collated result; real equivalence is integration-tested. */
    private List<ProductSkuResolution> collationResolutions(List<String> skus) {
        Map<String, Long> counts = skus.stream()
            .collect(Collectors.groupingBy(
                ProductImportServiceTest::collationKey,
                LinkedHashMap::new,
                Collectors.counting()));
        List<String> orderedKeys = counts.keySet().stream().sorted().toList();
        List<ProductSkuResolution> resolutions = new ArrayList<>();
        for (int index = 0; index < skus.size(); index++) {
            String key = collationKey(skus.get(index));
            ProductSkuResolution resolution = new ProductSkuResolution();
            resolution.setCandidateIndex(index);
            resolution.setEquivalentCount(Math.toIntExact(counts.get(key)));
            resolution.setCollationOrder(orderedKeys.indexOf(key) + 1);
            catalogById.values().stream()
                .filter(product -> key.equals(collationKey(product.getSku())))
                .min(Comparator.comparingInt(Product::getId))
                .ifPresent(product -> {
                    resolution.setProductId(product.getId());
                    resolution.setProductName(product.getName());
                });
            resolutions.add(resolution);
        }
        return resolutions;
    }

    private static String collationKey(String sku) {
        return Normalizer.normalize(sku, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replace("ß", "ss");
    }

    private static Map<String, String> row(String sku, String name) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("SKU", sku);
        row.put("Name", name);
        return row;
    }

    private static Map<String, String> fullRow(String sku) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("SKU", sku);
        row.put("Name", "Widget");
        row.put("Description", "A widget");
        row.put("Active", "yes");
        row.put("Unit", "seat");
        row.put("Price", "10.00");
        row.put("Currency", "JPY");
        row.put("Tax", "10.000");
        row.put("Frequency", "recurring");
        row.put("Start", "2026-01-01");
        row.put("End", "2026-12-31");
        return row;
    }

    @SafeVarargs
    private static ProductImportRequest request(
            String onConflict,
            Map<String, String>... rows) {
        return new ProductImportRequest(
            List.of(rows),
            List.of(mapping("SKU", "sku"), mapping("Name", "name")),
            onConflict,
            null,
            null);
    }

    @SafeVarargs
    private static ProductImportRequest fullRequest(
            String onConflict,
            Map<String, String>... rows) {
        return new ProductImportRequest(
            List.of(rows),
            List.of(
                mapping("SKU", "sku"),
                mapping("Name", "name"),
                mapping("Description", "description"),
                mapping("Active", "active"),
                mapping("Unit", "unit"),
                mapping("Price", "unitPrice"),
                mapping("Currency", "currency"),
                mapping("Tax", "taxRate"),
                mapping("Frequency", "billingFrequency"),
                mapping("Start", "effectiveStart"),
                mapping("End", "effectiveEnd")),
            onConflict,
            null,
            null);
    }

    private static ProductImportColumnMapping mapping(String column, String field) {
        return new ProductImportColumnMapping(column, field);
    }
}
