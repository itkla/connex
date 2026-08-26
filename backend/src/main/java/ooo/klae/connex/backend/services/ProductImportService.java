package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Product;
import ooo.klae.connex.backend.beans.ProductSkuResolution;
import ooo.klae.connex.backend.dto.ProductImportColumnMapping;
import ooo.klae.connex.backend.dto.ProductImportPreviewResult;
import ooo.klae.connex.backend.dto.ProductImportRequest;
import ooo.klae.connex.backend.dto.ProductImportResult;
import ooo.klae.connex.backend.dto.ProductImportRowAnalysis;
import ooo.klae.connex.backend.dto.RowError;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.util.CsvFormulaGuard;

/**
 * Proof-bound CSV import of the workspace product catalog.
 *
 * <p>The SKU is the only conflict key and is required on every row, so classification, replay
 * idempotence, and the {@code uq_product_workspace_sku} unique index all agree. A row whose SKU
 * already exists follows {@code onConflict} ({@code overwrite} or {@code skip}, default
 * {@code skip}) unless an explicit per-row decision overrides it; both are digested into the
 * review context, so a proof issued for one policy cannot commit another.
 *
 * <p>A blank cell means "not supplied", never "clear the field", and no row is ever deleted or
 * deactivated because it is absent from the file — deal line items snapshot catalog values, and
 * deleting a product would null out {@code deal_line_item.product_id}. Writes go straight to
 * {@link ProductMapper} with one summary audit event, never per row.
 *
 * <p><strong>Money and tax rates accept exactly two formats:</strong> a plain decimal with an
 * optional period separator ({@code 1234.56}, {@code 1234}), or ASCII digit grouping in strict
 * three-digit groups that also carries a period decimal separator ({@code 1,234.56}). Every other
 * shape fails the row. A comma without a period is ambiguous — {@code 12,50} is 1250 to a
 * thousands-separator reader and 12.50 to a decimal-comma reader, and a tax rate {@code 10,5} is
 * either 105 or 10.5 — so it is rejected rather than guessed; the same applies to space grouping
 * ({@code 1 000}) and to the decimal-comma form ({@code 1.000,50}). Silently normalizing those
 * would corrupt a catalog price by a factor of one hundred, so the importer refuses them and asks
 * the operator to restate the value.
 *
 * <p>SKUs are compared after trimming, matching {@link ProductService}, which stores a trimmed
 * SKU. Exporting the catalog and reimporting it therefore matches the same rows instead of
 * inserting whitespace-only variants.
 */
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private static final String VERSION = "connex-product-import-v1";
    private static final String ENTITY_TYPE = "product";
    private static final String CREATE = "create";
    private static final String UPDATE = "update";
    private static final String SKIP = "skip";
    private static final String INVALID = "invalid";
    private static final String OVERWRITE = "overwrite";
    private static final String DEFAULT_CURRENCY = "USD";
    private static final String DEFAULT_BILLING_FREQUENCY = "one_time";
    private static final String RECURRING_BILLING_FREQUENCY = "recurring";
    private static final int MAX_ROWS = 5000;
    private static final int MAX_MAPPINGS = 64;
    private static final int INSERT_BATCH = 250;
    private static final int MAX_DECIMAL_LENGTH = 32;
    private static final int UNIT_PRICE_INTEGER_DIGITS = 13;
    private static final int UNIT_PRICE_SCALE = 2;
    private static final int TAX_RATE_INTEGER_DIGITS = 3;
    private static final int TAX_RATE_SCALE = 3;
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$");
    private static final Pattern GROUPED_DECIMAL =
        Pattern.compile("^[+-]?\\d{1,3}(?:,\\d{3})+\\.\\d+$");
    private static final String DECIMAL_FORMAT_ERROR =
        " must be a decimal number such as 1234.56 or 1,234.56;"
            + " a decimal comma is not supported";
    private static final Set<String> FIELDS = Set.of(
        "sku", "name", "description", "active", "unit", "unitPrice", "currency",
        "taxRate", "billingFrequency", "effectiveStart", "effectiveEnd");

    private final WorkspaceService workspaceService;
    private final DuplicatePreflightService duplicatePreflightService;
    private final ProductMapper productMapper;
    private final AuditService auditService;

    /**
     * Renders the decided catalog import without writing anything and reserves its one-use proof.
     *
     * @param request bounded rows, mapping, conflict policy, and per-row overrides
     * @return per-row decisions plus the proof the matching commit must present
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PRODUCT_MANAGE)
    public ProductImportPreviewResult previewProducts(ProductImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Mapping mapping = validateRequest(request);
        String reviewContext = reviewContext(workspaceId, request);
        DuplicatePreflightService.ImportPreviewSession session =
            duplicatePreflightService.beginImportPreview(List.of(), List.of(), reviewContext);
        try {
            List<PlanRow> plan = parseRows(request, mapping);
            classify(workspaceId, request, plan);
            duplicatePreflightService.completeImportPreview(
                session, decisionFingerprint(plan));
            return previewResult(plan, session.reviewProof());
        } catch (RuntimeException exception) {
            duplicatePreflightService.cancelImportPreview(session.reviewProof());
            throw exception;
        }
    }

    /**
     * Commits the exact reviewed catalog import. The one-use proof is claimed before any database
     * lock; matched rows are then locked by exact key in ascending id order and revalidated.
     *
     * @param request the previewed request plus its {@code duplicateReviewProof}
     * @return created, updated, and skipped counts with per-row failures
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PRODUCT_MANAGE)
    public ProductImportResult commitProducts(ProductImportRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String reviewContext = reviewContext(workspaceId, request);
        DuplicatePreflightService.ImportCommitAdmission admission =
            duplicatePreflightService.claimImportCommit(
                request == null ? null : request.getDuplicateReviewProof(), reviewContext);
        Mapping mapping = validateRequest(request);
        List<PlanRow> plan = parseRows(request, mapping);
        DuplicatePreflightService.ImportCommitSession session =
            duplicatePreflightService.beginImportCommit(
                List.of(), List.of(), reviewContext, admission);
        classify(workspaceId, request, plan);
        duplicatePreflightService.completeImportCommit(session, decisionFingerprint(plan));
        Map<Integer, Product> locked = lockMatchedProducts(workspaceId, plan);
        int updated = applyUpdates(locked, plan);
        int created = insertCreates(workspaceId, plan);
        int skipped = count(plan, SKIP);
        List<RowError> failed = failedRows(plan);
        audit(created, updated, skipped, failed.size());
        return new ProductImportResult(created, updated, skipped, failed);
    }

    private static Mapping validateRequest(ProductImportRequest request) {
        if (request == null || request.getRows() == null || request.getMapping() == null) {
            throw new BadRequestException("Import rows and mapping are required");
        }
        if (request.getRows().size() > MAX_ROWS) {
            throw new BadRequestException(
                "At most " + MAX_ROWS + " catalog rows may be imported");
        }
        if (request.getMapping().isEmpty() || request.getMapping().size() > MAX_MAPPINGS) {
            throw new BadRequestException(
                "Between 1 and " + MAX_MAPPINGS + " column mappings are required");
        }
        Map<String, String> byField = new LinkedHashMap<>();
        Set<String> mappedColumns = new LinkedHashSet<>();
        for (ProductImportColumnMapping entry : request.getMapping()) {
            if (entry == null
                    || entry.column() == null
                    || entry.column().isBlank()
                    || entry.field() == null
                    || entry.field().isBlank()) {
                throw new BadRequestException("Every catalog column mapping is required");
            }
            if (!FIELDS.contains(entry.field())) {
                throw new BadRequestException("Unsupported catalog field: " + entry.field());
            }
            if (!mappedColumns.add(entry.column())) {
                throw new BadRequestException(
                    "CSV column is mapped more than once: " + entry.column());
            }
            if (byField.put(entry.field(), entry.column()) != null) {
                throw new BadRequestException(
                    "Catalog field is mapped more than once: " + entry.field());
            }
        }
        if (!byField.containsKey("sku")) {
            throw new BadRequestException("A column must be mapped to sku to import a catalog");
        }
        validateRowDecisions(request);
        validateConflictPolicy(request);
        return new Mapping(Map.copyOf(byField));
    }

    private static void validateRowDecisions(ProductImportRequest request) {
        Map<Integer, String> decisions = rowDecisions(request);
        if (decisions.size() > request.getRows().size()) {
            throw new BadRequestException("Row decisions exceed the imported row count");
        }
        for (Map.Entry<Integer, String> decision : decisions.entrySet()) {
            Integer rowIndex = decision.getKey();
            String action = decision.getValue();
            if (rowIndex == null
                    || rowIndex < 0
                    || rowIndex >= request.getRows().size()
                    || !(CREATE.equals(action) || UPDATE.equals(action) || SKIP.equals(action))) {
                throw new BadRequestException("Row decisions contain an invalid row or action");
            }
        }
    }

    private static void validateConflictPolicy(ProductImportRequest request) {
        String onConflict = request.getOnConflict();
        if (onConflict != null
                && !onConflict.isBlank()
                && !OVERWRITE.equals(onConflict)
                && !SKIP.equals(onConflict)) {
            throw new BadRequestException("onConflict must be overwrite or skip");
        }
    }

    private static List<PlanRow> parseRows(
            ProductImportRequest request,
            Mapping mapping) {
        List<PlanRow> plan = new ArrayList<>(request.getRows().size());
        for (int index = 0; index < request.getRows().size(); index++) {
            Map<String, String> source = request.getRows().get(index);
            if (source == null) {
                throw new BadRequestException("Catalog import rows cannot be null");
            }
            PlanRow row = new PlanRow(index);
            parseRow(source, mapping, row);
            if (!row.errors.isEmpty()) {
                row.status = INVALID;
            }
            plan.add(row);
        }
        return plan;
    }

    private static void parseRow(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        parseSku(source, mapping, row);
        row.name = bounded(source, mapping, "name", 255, row);
        row.description = bounded(source, mapping, "description", 1024, row);
        row.unit = bounded(source, mapping, "unit", 32, row);
        row.currency = bounded(source, mapping, "currency", 8, row);
        parseActive(source, mapping, row);
        row.unitPrice = parseDecimal(
            value(source, mapping, "unitPrice"),
            "unitPrice",
            UNIT_PRICE_INTEGER_DIGITS,
            UNIT_PRICE_SCALE,
            row);
        row.taxRate = parseDecimal(
            value(source, mapping, "taxRate"),
            "taxRate",
            TAX_RATE_INTEGER_DIGITS,
            TAX_RATE_SCALE,
            row);
        parseBillingFrequency(source, mapping, row);
        row.effectiveStart = parseDate(
            value(source, mapping, "effectiveStart"), "effectiveStart", row);
        row.effectiveEnd = parseDate(
            value(source, mapping, "effectiveEnd"), "effectiveEnd", row);
    }

    private static void parseSku(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        String sku = value(source, mapping, "sku");
        if (sku == null) {
            row.errors.add("A SKU is required to import a catalog row");
            return;
        }
        if (sku.codePointCount(0, sku.length()) > 64) {
            row.errors.add("sku must be at most 64 characters");
            return;
        }
        row.skuRaw = sku;
    }

    private static String bounded(
            Map<String, String> source,
            Mapping mapping,
            String field,
            int maxCodePoints,
            PlanRow row) {
        String value = value(source, mapping, field);
        if (value != null && value.codePointCount(0, value.length()) > maxCodePoints) {
            row.errors.add(field + " must be at most " + maxCodePoints + " characters");
            return null;
        }
        return value;
    }

    private static void parseActive(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        String raw = value(source, mapping, "active");
        if (raw == null) {
            return;
        }
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> row.active = Boolean.TRUE;
            case "false", "no", "0" -> row.active = Boolean.FALSE;
            default -> row.errors.add("active must be true, false, yes, no, 1, or 0");
        }
    }

    private static BigDecimal parseDecimal(
            String raw,
            String field,
            int maxIntegerDigits,
            int scale,
            PlanRow row) {
        if (raw == null) {
            return null;
        }
        String candidate = canonicalDecimal(raw);
        if (candidate == null) {
            row.errors.add(field + DECIMAL_FORMAT_ERROR);
            return null;
        }
        BigDecimal parsed = new BigDecimal(candidate);
        if (parsed.signum() < 0) {
            row.errors.add(field + " must not be negative");
            return null;
        }
        if (integerDigits(parsed) > maxIntegerDigits + 1L) {
            row.errors.add(field + " has more than " + maxIntegerDigits + " digits before the decimal point");
            return null;
        }
        BigDecimal scaled = parsed.setScale(scale, RoundingMode.HALF_UP);
        if (integerDigits(scaled) > maxIntegerDigits) {
            row.errors.add(field + " has more than " + maxIntegerDigits + " digits before the decimal point");
            return null;
        }
        return scaled;
    }

    private static String canonicalDecimal(String raw) {
        if (raw.length() > MAX_DECIMAL_LENGTH) {
            return null;
        }
        if (PLAIN_DECIMAL.matcher(raw).matches()) {
            return raw;
        }
        return GROUPED_DECIMAL.matcher(raw).matches() ? raw.replace(",", "") : null;
    }

    private static long integerDigits(BigDecimal value) {
        return (long) value.precision() - value.scale();
    }

    private static void parseBillingFrequency(
            Map<String, String> source,
            Mapping mapping,
            PlanRow row) {
        String raw = value(source, mapping, "billingFrequency");
        if (raw == null) {
            return;
        }
        if (!DEFAULT_BILLING_FREQUENCY.equals(raw) && !RECURRING_BILLING_FREQUENCY.equals(raw)) {
            row.errors.add("billingFrequency must be one_time or recurring");
            return;
        }
        row.billingFrequency = raw;
    }

    private static LocalDate parseDate(
            String raw,
            String field,
            PlanRow row) {
        if (raw == null) {
            return null;
        }
        try {
            if (!raw.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                throw new DateTimeException("Invalid date shape");
            }
            LocalDate parsed = LocalDate.parse(raw);
            if (parsed.getYear() < 1000 || parsed.getYear() > 9999) {
                throw new DateTimeException("Date is outside the MySQL range");
            }
            return parsed;
        } catch (DateTimeException exception) {
            row.errors.add(field + " must use YYYY-MM-DD");
            return null;
        }
    }

    private void classify(
            int workspaceId,
            ProductImportRequest request,
            List<PlanRow> plan) {
        Map<PlanRow, ProductSkuResolution> resolutions = resolveSkus(workspaceId, plan);
        markWithinFileDuplicates(plan, resolutions);
        boolean overwrite = OVERWRITE.equals(policy(request));
        Map<Integer, String> decisions = rowDecisions(request);
        for (PlanRow row : plan) {
            if (INVALID.equals(row.status)) {
                continue;
            }
            ProductSkuResolution resolution = resolutions.get(row);
            if (resolution == null || resolution.getProductId() == null) {
                row.status = CREATE;
            } else {
                row.matchedId = resolution.getProductId();
                row.matchedLabel = resolution.getProductName();
                row.status = overwrite ? UPDATE : SKIP;
            }
            applyDecision(row, decisions.get(row.rowIndex), row.matchedId != null);
        }
        for (PlanRow row : plan) {
            if (CREATE.equals(row.status) && row.name == null) {
                fail(row, "A name is required to create catalog SKU " + row.skuRaw);
            }
        }
    }

    private static void markWithinFileDuplicates(
            List<PlanRow> plan,
            Map<PlanRow, ProductSkuResolution> resolutions) {
        for (PlanRow row : plan) {
            ProductSkuResolution resolution = resolutions.get(row);
            if (INVALID.equals(row.status)
                    || resolution == null
                    || resolution.getEquivalentCount() < 2) {
                continue;
            }
            fail(row, "Row " + (row.rowIndex + 1) + " repeats SKU " + row.skuRaw
                + "; a SKU may appear once per import");
        }
    }

    private Map<PlanRow, ProductSkuResolution> resolveSkus(
            int workspaceId,
            List<PlanRow> plan) {
        List<PlanRow> candidates = plan.stream()
            .filter(row -> !INVALID.equals(row.status) && row.skuRaw != null)
            .toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<String> skus = candidates.stream().map(row -> row.skuRaw).toList();
        List<ProductSkuResolution> resolved = productMapper.resolveImportSkus(workspaceId, skus);
        if (resolved.size() != candidates.size()) {
            throw new IllegalStateException("Database did not resolve every catalog SKU candidate");
        }
        Map<PlanRow, ProductSkuResolution> byRow = new LinkedHashMap<>();
        boolean[] seen = new boolean[candidates.size()];
        for (ProductSkuResolution resolution : resolved) {
            int candidateIndex = resolution.getCandidateIndex();
            if (candidateIndex < 0
                    || candidateIndex >= candidates.size()
                    || seen[candidateIndex]
                    || resolution.getEquivalentCount() < 1
                    || resolution.getCollationOrder() < 1
                    || (resolution.getProductId() != null
                        && resolution.getProductName() == null)) {
                throw new IllegalStateException("Database returned an invalid catalog SKU resolution");
            }
            seen[candidateIndex] = true;
            PlanRow row = candidates.get(candidateIndex);
            row.collationOrder = resolution.getCollationOrder();
            byRow.put(row, resolution);
        }
        return byRow;
    }

    private static void applyDecision(
            PlanRow row,
            String decision,
            boolean matched) {
        if (decision == null) {
            return;
        }
        if (SKIP.equals(decision)) {
            row.status = SKIP;
            return;
        }
        if (UPDATE.equals(decision)) {
            if (matched) {
                row.status = UPDATE;
            } else {
                fail(row, "Row " + (row.rowIndex + 1) + " has no existing SKU to update");
            }
            return;
        }
        if (matched) {
            fail(row, "SKU " + row.skuRaw + " already exists; choose update or skip");
        } else {
            row.status = CREATE;
        }
    }

    private Map<Integer, Product> lockMatchedProducts(
            int workspaceId,
            List<PlanRow> plan) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (PlanRow row : plan) {
            if (UPDATE.equals(row.status) && row.matchedId != null) {
                ids.add(row.matchedId);
            }
        }
        Map<Integer, Product> locked = new LinkedHashMap<>();
        for (int id : ids) {
            Product product = productMapper.getByIdForUpdate(workspaceId, id);
            if (product != null) {
                locked.put(id, product);
            }
        }
        for (PlanRow row : plan) {
            if (!UPDATE.equals(row.status) || row.matchedId == null) {
                continue;
            }
            Product product = locked.get(row.matchedId);
            if (product == null) {
                fail(row, "Catalog row for SKU " + row.skuRaw + " no longer exists");
            }
        }
        Map<PlanRow, ProductSkuResolution> current = resolveSkus(
            workspaceId,
            plan.stream().filter(row -> UPDATE.equals(row.status)).toList());
        for (PlanRow row : plan) {
            if (!UPDATE.equals(row.status) || row.matchedId == null) {
                continue;
            }
            ProductSkuResolution resolution = current.get(row);
            Product product = locked.get(row.matchedId);
            if (resolution == null
                    || !row.matchedId.equals(resolution.getProductId())
                    || product == null
                    || !Objects.equals(row.matchedLabel, product.getName())) {
                fail(row, "Catalog row for SKU " + row.skuRaw
                    + " changed before the import committed");
            }
        }
        return locked;
    }

    private int applyUpdates(
            Map<Integer, Product> locked,
            List<PlanRow> plan) {
        int updated = 0;
        for (PlanRow row : plan) {
            if (!UPDATE.equals(row.status) || row.matchedId == null) {
                continue;
            }
            Product product = locked.get(row.matchedId);
            if (product == null) {
                continue;
            }
            if (applyValues(product, row)) {
                productMapper.update(product);
            }
            updated++;
        }
        return updated;
    }

    private static boolean applyValues(
            Product product,
            PlanRow row) {
        boolean changed = false;
        if (row.name != null && !row.name.equals(product.getName())) {
            product.setName(row.name);
            changed = true;
        }
        if (row.description != null && !row.description.equals(product.getDescription())) {
            product.setDescription(row.description);
            changed = true;
        }
        if (row.active != null && row.active != product.isActive()) {
            product.setActive(row.active);
            changed = true;
        }
        if (row.unit != null && !row.unit.equals(product.getUnit())) {
            product.setUnit(row.unit);
            changed = true;
        }
        if (row.unitPrice != null && !sameAmount(row.unitPrice, product.getUnitPrice())) {
            product.setUnitPrice(row.unitPrice);
            changed = true;
        }
        if (row.currency != null && !row.currency.equals(product.getCurrency())) {
            product.setCurrency(row.currency);
            changed = true;
        }
        if (row.taxRate != null && !sameAmount(row.taxRate, product.getTaxRate())) {
            product.setTaxRate(row.taxRate);
            changed = true;
        }
        if (row.billingFrequency != null
                && !row.billingFrequency.equals(product.getBillingFrequency())) {
            product.setBillingFrequency(row.billingFrequency);
            changed = true;
        }
        if (row.effectiveStart != null
                && !row.effectiveStart.equals(product.getEffectiveStart())) {
            product.setEffectiveStart(row.effectiveStart);
            changed = true;
        }
        if (row.effectiveEnd != null && !row.effectiveEnd.equals(product.getEffectiveEnd())) {
            product.setEffectiveEnd(row.effectiveEnd);
            changed = true;
        }
        return changed;
    }

    private static boolean sameAmount(
            BigDecimal incoming,
            BigDecimal current) {
        return current != null && incoming.compareTo(current) == 0;
    }

    private int insertCreates(
            int workspaceId,
            List<PlanRow> plan) {
        List<Product> creates = plan.stream()
            .filter(row -> CREATE.equals(row.status))
            .sorted(Comparator.comparingInt((PlanRow row) -> row.collationOrder)
                .thenComparingInt(row -> row.rowIndex))
            .map(row -> newProduct(workspaceId, row))
            .toList();
        for (int offset = 0; offset < creates.size(); offset += INSERT_BATCH) {
            productMapper.insertBatch(creates.subList(
                offset, Math.min(offset + INSERT_BATCH, creates.size())));
        }
        return creates.size();
    }

    private static Product newProduct(
            int workspaceId,
            PlanRow row) {
        Product product = new Product();
        product.setWorkspaceId(workspaceId);
        product.setSku(row.skuRaw);
        product.setName(Objects.requireNonNull(row.name, "catalog name"));
        product.setDescription(row.description);
        product.setActive(row.active == null || row.active);
        product.setUnit(row.unit);
        product.setUnitPrice(row.unitPrice == null ? BigDecimal.ZERO : row.unitPrice);
        product.setCurrency(row.currency == null ? DEFAULT_CURRENCY : row.currency);
        product.setTaxRate(row.taxRate);
        product.setBillingFrequency(
            row.billingFrequency == null ? DEFAULT_BILLING_FREQUENCY : row.billingFrequency);
        product.setEffectiveStart(row.effectiveStart);
        product.setEffectiveEnd(row.effectiveEnd);
        return product;
    }

    private void audit(
            int created,
            int updated,
            int skipped,
            int failed) {
        auditService.record(
            "import.product",
            ENTITY_TYPE,
            null,
            "CSV import",
            "Imported products: " + created + " created, " + updated + " updated, "
                + skipped + " skipped, " + failed + " failed",
            Map.of(
                "created", created,
                "updated", updated,
                "skipped", skipped,
                "failed", failed));
    }

    private static ProductImportPreviewResult previewResult(
            List<PlanRow> plan,
            String proof) {
        List<ProductImportRowAnalysis> rows = plan.stream()
            .map(row -> new ProductImportRowAnalysis(
                row.rowIndex,
                row.status,
                row.skuRaw,
                row.matchedId,
                row.matchedLabel,
                row.errors))
            .toList();
        return new ProductImportPreviewResult(
            plan.size(),
            count(plan, CREATE),
            count(plan, UPDATE),
            count(plan, SKIP),
            count(plan, INVALID),
            rows,
            proof);
    }

    private static int count(
            List<PlanRow> plan,
            String status) {
        return Math.toIntExact(plan.stream()
            .filter(row -> status.equals(row.status))
            .count());
    }

    private static List<RowError> failedRows(List<PlanRow> plan) {
        return plan.stream()
            .filter(row -> INVALID.equals(row.status))
            .map(row -> new RowError(row.rowIndex, String.join("; ", row.errors)))
            .toList();
    }

    private static String reviewContext(
            int workspaceId,
            ProductImportRequest request) {
        MessageDigest digest = sha256();
        updateDigest(digest, VERSION);
        updateDigest(digest, ENTITY_TYPE);
        updateDigest(digest, Integer.toString(workspaceId));
        updateDigest(digest, request == null ? SKIP : policy(request));
        List<Map<String, String>> rows =
            request == null || request.getRows() == null ? List.of() : request.getRows();
        updateDigest(digest, Integer.toString(rows.size()));
        for (Map<String, String> row : rows) {
            updateDigest(digest, "row");
            if (row == null) {
                updateDigest(digest, null);
                continue;
            }
            updateDigest(digest, Integer.toString(row.size()));
            for (Map.Entry<String, String> entry : new TreeMap<>(row).entrySet()) {
                updateDigest(digest, entry.getKey());
                updateDigest(digest, entry.getValue());
            }
        }
        List<ProductImportColumnMapping> mapping =
            request == null || request.getMapping() == null ? List.of() : request.getMapping();
        updateDigest(digest, Integer.toString(mapping.size()));
        for (ProductImportColumnMapping entry : mapping) {
            updateDigest(digest, entry == null ? null : entry.column());
            updateDigest(digest, entry == null ? null : entry.field());
        }
        Map<Integer, String> decisions = request == null ? Map.of() : rowDecisions(request);
        updateDigest(digest, Integer.toString(decisions.size()));
        for (Map.Entry<Integer, String> entry : new TreeMap<>(decisions).entrySet()) {
            updateDigest(digest, Objects.toString(entry.getKey(), null));
            updateDigest(digest, entry.getValue());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String decisionFingerprint(List<PlanRow> plan) {
        MessageDigest digest = sha256();
        updateDigest(digest, VERSION);
        updateDigest(digest, "decision");
        for (PlanRow row : plan) {
            updateDigest(digest, Integer.toString(row.rowIndex));
            updateDigest(digest, row.status);
            updateDigest(digest, row.skuRaw);
            updateDigest(
                digest,
                row.matchedId == null ? null : Integer.toString(row.matchedId));
            updateDigest(digest, row.matchedLabel);
            for (String error : row.errors) {
                updateDigest(digest, error);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String policy(ProductImportRequest request) {
        String onConflict = request.getOnConflict();
        return onConflict == null || onConflict.isBlank() ? SKIP : onConflict;
    }

    private static Map<Integer, String> rowDecisions(ProductImportRequest request) {
        return request.getRowDecisions() == null ? Map.of() : request.getRowDecisions();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(
            MessageDigest digest,
            String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String value(
            Map<String, String> source,
            Mapping mapping,
            String field) {
        String column = mapping.byField().get(field);
        return column == null ? null : cell(source, column);
    }

    private static String cell(
            Map<String, String> row,
            String column) {
        String value = row.get(column);
        if (value == null) {
            return null;
        }
        String trimmed = CsvFormulaGuard.unguard(value.trim());
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void fail(
            PlanRow row,
            String error) {
        row.status = INVALID;
        if (!row.errors.contains(error)) {
            row.errors.add(error);
        }
    }

    private record Mapping(
            Map<String, String> byField) {
    }

    private static final class PlanRow {
        private final int rowIndex;
        private final List<String> errors = new ArrayList<>();
        private String status;
        private String skuRaw;
        private int collationOrder;
        private Integer matchedId;
        private String matchedLabel;
        private String name;
        private String description;
        private Boolean active;
        private String unit;
        private BigDecimal unitPrice;
        private String currency;
        private BigDecimal taxRate;
        private String billingFrequency;
        private LocalDate effectiveStart;
        private LocalDate effectiveEnd;

        private PlanRow(int rowIndex) {
            this.rowIndex = rowIndex;
        }
    }
}
