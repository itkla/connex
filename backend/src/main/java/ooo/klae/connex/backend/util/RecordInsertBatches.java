package ooo.klae.connex.backend.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;

/** Ordered slices of the original beans; callers retain their transaction and generated-id mapping. */
public final class RecordInsertBatches {
    /** Bounds parameters and SQL tuple overhead as well as payload. */
    public static final int MAX_ROWS = 100;
    /** Leaves substantial headroom below MySQL's 64 MiB packet for protocol and statement overhead. */
    public static final long MAX_BYTES = 1_048_576;
    private static final long KEY_BYTES = 2L * AiIdentifierLookupKeys.MAX_KEY_CODE_POINTS * 4;

    private RecordInsertBatches() {
    }

    /** Bounds all variable-width bindings in the person insert, including its repeated source name. */
    public static List<List<Person>> people(List<Person> rows) {
        return partition(rows, person -> estimate(person.getName(), person.getName(), person.getEmail(),
                person.getPhone(), person.getTitle(), person.getImageUrl(), person.getLeadSourceDetail(),
                person.getCreatedAt()));
    }

    /** Bounds all variable-width bindings in the company insert. */
    public static List<List<Company>> companies(List<Company> rows) {
        return partition(rows, company -> estimate(company.getName(), company.getName(), company.getWebsite(),
                company.getIndustry(), company.getPhone(), company.getAddress(), company.getLogoUrl(),
                company.getCreatedAt()));
    }

    /** Bounds all variable-width bindings in the deal insert. */
    public static List<List<Deal>> deals(List<Deal> rows) {
        return partition(rows, deal -> estimate(deal.getName(), deal.getName(), deal.getDuplicateNormalizedName(),
                deal.getValueSource(), deal.getCurrency(), deal.getExpectedCloseDate(), deal.getClosedAt(),
                deal.getClosedReason(), deal.getCreatedAt()));
    }

    /** Four UTF-8 bytes per UTF-16 unit and doubled escaping conservatively cover string bindings. */
    private static long estimate(String... values) {
        long bytes = KEY_BYTES;
        for (String value : values) {
            if (value != null) {
                bytes += 4L * value.length();
            }
        }
        return 1024 + 2 * bytes;
    }

    private static <T> List<List<T>> partition(List<T> rows, ToLongFunction<T> size) {
        List<List<T>> batches = new ArrayList<>();
        int start = 0;
        long bytes = 0;
        for (int index = 0; index < rows.size(); index++) {
            long rowBytes = size.applyAsLong(rows.get(index));
            if (rowBytes > MAX_BYTES) {
                throw new IllegalArgumentException("Record insert exceeds its bounded payload");
            }
            if (index > start && (index - start == MAX_ROWS || bytes + rowBytes > MAX_BYTES)) {
                batches.add(rows.subList(start, index));
                start = index;
                bytes = 0;
            }
            bytes += rowBytes;
        }
        if (start < rows.size()) {
            batches.add(rows.subList(start, rows.size()));
        }
        return List.copyOf(batches);
    }
}
