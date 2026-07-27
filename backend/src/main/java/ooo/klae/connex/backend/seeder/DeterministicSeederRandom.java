package ooo.klae.connex.backend.seeder;

/**
 * Stateless deterministic value derivation for seeder records.
 *
 * <p>Every value is derived from a workspace seed, an entity salt, a logical row
 * index, and a field lane. Adding an unrelated field therefore cannot shift any
 * existing generated sequence.
 */
final class DeterministicSeederRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long INDEX_GAMMA = 0xD1B54A32D192ED03L;
    private static final long LANE_GAMMA = 0x94D049BB133111EBL;

    private DeterministicSeederRandom() {
    }

    static long workspaceSeed(long seed, int workspaceIndex) {
        return mix(seed + GOLDEN_GAMMA * (workspaceIndex + 1L));
    }

    static long value(long workspaceSeed, long entitySalt, int index, int lane) {
        return mix(workspaceSeed ^ entitySalt ^ INDEX_GAMMA * (index + 1L) ^ LANE_GAMMA * (lane + 1L));
    }

    static int bounded(long workspaceSeed, long entitySalt, int index, int lane, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Deterministic bound must be positive");
        }
        return (int) Long.remainderUnsigned(value(workspaceSeed, entitySalt, index, lane), bound);
    }

    private static long mix(long value) {
        long mixed = value;
        mixed = (mixed ^ mixed >>> 30) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ mixed >>> 27) * 0x94D049BB133111EBL;
        return mixed ^ mixed >>> 31;
    }
}
