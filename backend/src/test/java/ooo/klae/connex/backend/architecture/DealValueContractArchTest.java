package ooo.klae.connex.backend.architecture;

import static ooo.klae.connex.backend.architecture.ArchitectureMapperXml.collectSql;
import static ooo.klae.connex.backend.architecture.ArchitectureMapperXml.resolve;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.DealRiskCurrencySummaryDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealSummaryDto;

/** Enforces the canonical BigDecimal deal-value write and reporting boundary. */
class DealValueContractArchTest {

    private static final Pattern MONEY_ASSIGNMENT = Pattern.compile(
        "\\b(?:value|actual_value|value_source)\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEAL_MAPPER_REFERENCE =
        Pattern.compile("\\bDealMapper\\s+(\\w+)\\b");
    private static final Pattern DEAL_MAPPER_LOOKUP =
        Pattern.compile("getMapper\\s*\\(\\s*DealMapper\\.class\\s*\\)");
    private static final List<String> DEAL_ROW_WRITE_CALLS =
        List.of(".update(", ".updateOutcome(", ".insert(", ".insertBatch(");
    private static final Set<String> DEAL_ROW_WRITERS =
        Set.of("DealMapper.java", "DealOutcomeWriter.java", "SeederBatchWriter.java");

    @Test
    void dealMoneyCarriersNeverUseFloatingPointTypes() throws Exception {
        assertMoneyField(Deal.class, "value");
        assertMoneyField(Deal.class, "actualValue");
        assertMoneyField(DealDto.class, "value");
        assertMoneyField(DealDto.class, "actualValue");
        assertMoneyField(DealSummaryDto.class, "value");
        assertMoneyField(DealSummaryDto.class, "actualValue");
        assertMoneyField(DealRiskDto.class, "value");

        RecordComponent value = Stream.of(DealRiskCurrencySummaryDto.class.getRecordComponents())
            .filter(component -> "value".equals(component.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals(BigDecimal.class, value.getType());
    }

    @Test
    void onlyDealValueServiceReferencesCanonicalValueWriteMethods() throws Exception {
        Path sourceRoot = repoRoot().resolve("backend/src/main/java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if ("DealMapper.java".equals(name) || "DealValueService.java".equals(name)) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.contains("updateValueAndSource(")
                        || source.contains("updateValueSource(")
                        || source.contains("updateActualValue(")) {
                    violations.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Canonical deal-value writes escaped DealValueService: " + violations);
    }

    /**
     * Deal rows may only be written through {@code DealOutcomeWriter}, which reconciles realized
     * value in the same step. A file-level "also calls reconcile" rule would not do: it can never
     * fire inside {@code DealService} or {@code ImportService}, the only two files that write deal
     * outcomes, because each already reconciles somewhere else in the file. Containment is checked
     * instead, so adding an unreconciled route anywhere fails the build.
     *
     * <p>The reference is resolved from each file's own {@code DealMapper} declarations rather than
     * from the conventional {@code dealMapper} name, because a rule keyed to one spelling holds only
     * while every author picks that spelling: injecting the same mapper as {@code deals} would write
     * deal rows unreconciled and still pass.
     */
    @Test
    void onlyDealOutcomeWriterWritesDealRows() throws Exception {
        Path sourceRoot = repoRoot().resolve("backend/src/main/java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (DEAL_ROW_WRITERS.contains(name)) {
                    continue;
                }
                String source = Files.readString(file);
                if (writesDealRows(source)) {
                    violations.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Deal rows must be written through DealOutcomeWriter so realized value is reconciled in"
                + " the same step; a won-to-lost transition that skipped it would keep the won"
                + " figure and inflate closed revenue: " + violations);
    }

    /**
     * The generated demo deal is the one deal row not written through {@code DealOutcomeWriter}, so
     * its compliance is asserted directly rather than assumed: a seeded deal that is not won must
     * carry zero realized value.
     */
    @Test
    void seededDealsNeverGiveAnUnwonDealRealizedValue() throws Exception {
        Path generator = repoRoot().resolve(
            "backend/src/main/java/ooo/klae/connex/backend/seeder/SeedDataGenerator.java");
        String source = Files.readString(generator);

        assertTrue(source.contains("deal.setWon(outcome < 2);"),
            "SeedDataGenerator no longer decides won from 'outcome < 2'; re-verify the pairing");
        assertTrue(source.contains("deal.setActualValue(outcome < 2"),
            "SeedDataGenerator must gate realized value on the same predicate as won, so a seeded"
                + " lost deal records zero");
    }

    @Test
    void broadDealUpdateCannotClobberMoneyOrItsSource() throws Exception {
        MapperXml mapper = parse(mapperXml("DealMapper"));
        Statement update = mapper.statements().stream()
            .filter(statement -> "update".equals(statement.id()))
            .findFirst()
            .orElseThrow();

        assertFalse(MONEY_ASSIGNMENT.matcher(update.sql()).find(),
            "DealMapper.update must remain a details/status-only update: " + update.sql());
    }

    /**
     * The outcome-only update exists so a route that reopens or closes a deal cannot write anything
     * else. Asserting the exact assigned column set — rather than only the absence of currency —
     * means widening the statement later fails here instead of silently restoring a stale field.
     */
    @Test
    void outcomeUpdateCannotOverwriteCurrencyOrUnrelatedFields() throws Exception {
        Statement update = parse(mapperXml("DealMapper")).statements().stream()
            .filter(statement -> "updateOutcome".equals(statement.id()))
            .findFirst()
            .orElseThrow();
        Matcher assignments = Pattern.compile("([a-z_]+)\\s*=", Pattern.CASE_INSENSITIVE)
            .matcher(update.sql().split("(?i)WHERE")[0]);
        Set<String> columns = new HashSet<>();
        while (assignments.find()) {
            columns.add(assignments.group(1).toLowerCase(Locale.ROOT));
        }
        assertEquals(Set.of("stage_id", "position", "closed_at", "closed_reason", "won"), columns);
    }

    @Test
    void revenueStatementsNeverReadDealLineItems() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String mapperName : List.of("DealMapper", "ReportMapper")) {
            MapperXml mapper = parse(mapperXml(mapperName));
            for (Statement statement : mapper.statements()) {
                if (statement.revenue()
                        && statement.sql().toLowerCase().contains("deal_line_item")) {
                    violations.add(mapperName + "." + statement.id());
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "Revenue SQL must read canonical deal values, not deal_line_item: " + violations);
    }

    /**
     * Whether a source file writes deal rows through any name it binds {@code DealMapper} to. A
     * runtime lookup counts on its own: code that resolves the mapper from a session names nothing
     * this scan could follow, so obtaining it at all outside the writer is treated as the write.
     */
    private static boolean writesDealRows(String source) {
        if (DEAL_MAPPER_LOOKUP.matcher(source).find()) {
            return true;
        }
        Matcher declaration = DEAL_MAPPER_REFERENCE.matcher(source);
        while (declaration.find()) {
            String reference = declaration.group(1);
            for (String call : DEAL_ROW_WRITE_CALLS) {
                if (source.contains(reference + call)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertMoneyField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        assertEquals(BigDecimal.class, field.getType(), type.getSimpleName() + "." + name);
    }

    private String mapperXml(String mapper) throws IOException {
        String resource = "mappers/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Mapper XML not found on the classpath: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MapperXml parse(String xml) throws Exception {
        ArchitectureMapperXml.Parsed parsed = ArchitectureMapperXml.parse(xml);
        Map<String, String> fragments = parsed.fragments();
        List<Element> statementElements = parsed.statements();

        List<Statement> statements = new ArrayList<>();
        for (Element element : statementElements) {
            String sql = resolve(collectSql(element), fragments, 0);
            statements.add(new Statement(
                element.getAttribute("id"), sql,
                mentionsRevenue(element) || sql.toLowerCase().contains("revenue")));
        }
        return new MapperXml(statements);
    }

    private static boolean mentionsRevenue(Element element) {
        if (element.getAttribute("id").toLowerCase().contains("revenue")
                || element.getAttribute("test").toLowerCase().contains("revenue")) {
            return true;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && mentionsRevenue((Element) node)) {
                return true;
            }
        }
        return false;
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("backend/settings.gradle"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate the repository root");
        return current;
    }

    private record MapperXml(List<Statement> statements) {}

    private record Statement(String id, String sql, boolean revenue) {}
}
