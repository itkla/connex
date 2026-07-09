package ooo.klae.connex.backend.util;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Normalized page, size, and offset values for bounded list endpoints.
 */
public record PageBounds(int page, int size, int offset) {
    public static final int MAX_SIZE = 100;
    public static final int MAX_OFFSET = 100_000;

    public static PageBounds of(int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), MAX_SIZE);
        long requestedOffset = (long) (normalizedPage - 1) * normalizedSize;
        if (requestedOffset > MAX_OFFSET) {
            throw new BadRequestException("Page offset exceeds the maximum allowed window");
        }
        int normalizedOffset = (int) requestedOffset;
        return new PageBounds(normalizedPage, normalizedSize, normalizedOffset);
    }
}
