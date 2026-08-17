/**
 * Apportions each weight to a whole-percent share of its dimension so the displayed shares always
 * total exactly 100 (issue #559).
 *
 * Naive rounding does not: three equal weights each round to 33% and the column reads 99%. A user
 * who can see the shares not adding up stops trusting the score they add up to, so this uses the
 * largest-remainder method — floor every share, then hand the leftover points to the entries with
 * the largest discarded fractions, breaking ties by the earlier entry so the result is stable
 * across renders.
 *
 * @param weights weights in display order
 * @returns whole-percent shares in the same order, summing to 100 (or all zero when there is no weight)
 */
export function apportionShares(weights: number[]): number[] {
    const total = weights.reduce((sum, weight) => sum + Math.max(0, weight), 0);
    if (total <= 0) {
        return weights.map(() => 0);
    }
    const exact = weights.map((weight) => (Math.max(0, weight) * 100) / total);
    const shares = exact.map((value) => Math.floor(value));
    let remaining = 100 - shares.reduce((sum, share) => sum + share, 0);
    const byRemainder = exact
        .map((value, index) => ({ index, remainder: value - Math.floor(value) }))
        .sort((a, b) => (b.remainder - a.remainder) || (a.index - b.index));
    for (const entry of byRemainder) {
        if (remaining <= 0) break;
        shares[entry.index] += 1;
        remaining -= 1;
    }
    return shares;
}
