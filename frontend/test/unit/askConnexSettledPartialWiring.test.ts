import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const provider = readFileSync(
    path.resolve(process.cwd(), 'app/components/ask-connex/AskConnexProvider.tsx'), 'utf8');

describe("the provider adopts a settled turn's durable partial exactly", () => {
    it('adopts it when a poll observes a settled turn, even when it is empty', () => {
        const absorb = provider.slice(
            provider.indexOf('const absorbTurnPartial = useCallback'),
            provider.indexOf('const canPersist = useAccountStorageGeneration'));

        expect(absorb).toMatch(/if \(!isActiveTurnStatus\(durable\.status\)\) \{[\s\S]*adoptSettledAskConnexStreamPartial\(/);
        expect(absorb.indexOf('adoptSettledAskConnexStreamPartial('))
            .toBeLessThan(absorb.indexOf('if (partial.length === 0) return;'));
    });

    it('adopts it when a hydration response reports a settled turn', () => {
        const hydrate = provider.slice(
            provider.indexOf('const hydrateStream = useCallback'),
            provider.indexOf('const absorbTurnPartial = useCallback'));

        expect(hydrate).toContain('settledTurn = !isActiveTurnStatus(durable.status);');
        expect(hydrate).toMatch(/if \(settledTurn\) \{\s*streamRef\.current = adoptSettledAskConnexStreamPartial\(current, partial\);/);
    });
});
