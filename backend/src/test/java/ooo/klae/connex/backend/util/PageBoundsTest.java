package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;

class PageBoundsTest {
    @Test
    void ofNormalizesPageAndSize() {
        PageBounds bounds = PageBounds.of(0, 500);

        assertEquals(1, bounds.page());
        assertEquals(100, bounds.size());
        assertEquals(0, bounds.offset());
    }

    @Test
    void ofComputesOffset() {
        PageBounds bounds = PageBounds.of(3, 25);

        assertEquals(50, bounds.offset());
    }

    @Test
    void ofRejectsHugeOffset() {
        assertThrows(BadRequestException.class, () -> PageBounds.of(Integer.MAX_VALUE, 100));
    }
}
