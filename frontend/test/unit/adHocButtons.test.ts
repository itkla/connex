import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import {
    BASELINE_HIGH_WATER_MARK,
    CONTEXT_SIZES,
    describeFile,
    loadBaseline,
    scanAdHocButtons,
    scanFile,
    SYSTEM_SURFACES,
} from "@/lint/adHocButtons.mjs";

const found = scanAdHocButtons();
const baseline: Record<string, number> = loadBaseline();

const BURN_DOWN =
    "The button system is the primitive: Button, IconButton, SplitButton, SegmentedControl in components/ui. " +
    "The ledger only shrinks: lower or delete the entry in frontend/lint/ad-hoc-button-baseline.json.";

/**
 * Runs the scanner over a synthetic source file. The gate reads files from disk, so falsifying it
 * means handing it a real file rather than a string.
 */
function scanProbe(source: string) {
    const directory = mkdtempSync(join(tmpdir(), "ad-hoc-button-probe-"));
    const file = join(directory, "Probe.tsx");
    writeFileSync(file, source);
    try {
        return scanFile(file);
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
}

describe("button system", () => {
    it("finds no ad-hoc button styling in a file outside the committed ledger", () => {
        const added = Object.keys(found)
            .filter((file) => !(file in baseline))
            .flatMap((file) => describeFile(file));

        expect(added, `${added.length} ad-hoc button decision(s). ${BURN_DOWN}`).toEqual([]);
    });

    it("holds no ledger entry that grew", () => {
        const grown = Object.entries(baseline)
            .filter(([file, allowed]) => (found[file] ?? 0) > allowed)
            .map(([file, allowed]) => `${file}: ${allowed} → ${found[file]}`);

        expect(grown, `${grown.length} file(s) added ad-hoc button styling. ${BURN_DOWN}`).toEqual([]);
    });

    it("holds no ledger entry that is already clean", () => {
        const stale = Object.keys(baseline).filter((file) => (found[file] ?? 0) === 0);

        expect(stale, `${stale.length} ledger entry(s) are fixed. ${BURN_DOWN}`).toEqual([]);
    });

    it("keeps the ledger sorted and scoped to files that still exist", () => {
        const files = Object.keys(baseline);

        expect(files).toEqual([...files].sort());
        expect(files.filter((file) => !existsSync(join(process.cwd(), file)))).toEqual([]);
    });

    it("never grows past the committed high-water mark", () => {
        const total = Object.values(baseline).reduce((sum, count) => sum + count, 0);

        expect(total).toBeLessThanOrEqual(BASELINE_HIGH_WATER_MARK);
    });
});

describe("the primitives that own the shape", () => {
    it("never appears in the shrinking ledger", () => {
        expect(SYSTEM_SURFACES.filter((file) => file in baseline)).toEqual([]);
    });

    it("names each primitive once, in order, and only if it exists", () => {
        expect(SYSTEM_SURFACES).toEqual([...new Set(SYSTEM_SURFACES)].sort());
        expect(SYSTEM_SURFACES.filter((file) => !existsSync(join(process.cwd(), file)))).toEqual([]);
    });

    it("offers a context tier for every height the product uses", () => {
        expect(CONTEXT_SIZES).toEqual([
            "page",
            "dialog",
            "toolbar",
            "inline",
            "icon-page",
            "icon-dialog",
            "icon-toolbar",
            "icon-inline",
        ]);
    });
});

describe("the scanner itself", () => {
    it("reports nothing for the surfaces this workstream swept clean", () => {
        for (const file of [
            "app/components/import/RecordsActions.tsx",
            "app/components/dashboard/QuickCreate.tsx",
            "app/components/records/contacts/ContactsBrowser.tsx",
            "app/components/records/companies/CompaniesBrowser.tsx",
        ]) {
            expect(scanFile(file), file).toEqual([]);
        }
    });

    it("catches each idiom it claims to catch", () => {
        const probes = [
            {
                idiom: "handRolled",
                source: '<button type="button" className="rounded-full bg-muted px-3 py-1.5">x</button>',
            },
            {
                idiom: "handRolled",
                source: '<div role="button" className="rounded-full bg-muted px-3 h-9">x</div>',
            },
            {
                idiom: "linkAsButton",
                source: '<Link href="/x" className="rounded-full bg-brand px-3 py-2">x</Link>',
            },
            {
                idiom: "hoistedClass",
                source:
                    'const chipClass = "inline-flex h-9 rounded-full bg-muted ring-1";\nexport function X() { return <span className={chipClass} />; }',
            },
            {
                idiom: "shapeOverride",
                source: '<Button variant="brand" className="h-11 rounded-md">x</Button>',
            },
            { idiom: "legacySize", source: '<Button variant="brand" size="sm">x</Button>' },
            {
                idiom: "iconOnlyWithoutTooltip",
                source: '<Button variant="ghost" size="icon-toolbar" aria-label="Close"><X /></Button>',
            },
        ];

        for (const probe of probes) {
            const idioms = scanProbe(probe.source).map((match) => match.idiom);
            expect(idioms, probe.idiom).toContain(probe.idiom);
        }
    });

    it("leaves a bare button and a context-tier call site alone", () => {
        expect(
            scanProbe('<button type="button" className="text-muted-foreground">x</button>')
        ).toEqual([]);
        expect(scanProbe('<Button variant="brand" size="toolbar" menu>x</Button>')).toEqual([]);
    });

    it("reads only the className attribute, not the rest of the tag", () => {
        expect(
            scanProbe(
                '<button type="button" title="rounded-full bg-muted px-3 py-2" className="text-muted-foreground">x</button>'
            )
        ).toEqual([]);
    });

    it("holds a chip, a tab, and a nav item to be something other than a button", () => {
        expect(
            scanProbe('<button type="button" className="rounded-full bg-muted px-2 py-0.5 ring-1">x</button>')
        ).toEqual([]);
        expect(
            scanProbe(
                '<button type="button" aria-current="page" className="rounded-full bg-muted px-3 py-2">x</button>'
            )
        ).toEqual([]);
        expect(
            scanProbe('<div role="tab" className="rounded-full bg-muted px-3 py-2">x</div>')
        ).toEqual([]);
    });

    it("clears an icon-only button that a tooltip trigger wraps", () => {
        expect(
            scanProbe(
                '<TooltipTrigger asChild>\n  <Button variant="ghost" size="icon-toolbar" aria-label="Close"><X /></Button>\n</TooltipTrigger>'
            )
        ).toEqual([]);
        expect(
            scanProbe('<IconButton variant="ghost" size="icon-toolbar" label="Close"><X /></IconButton>')
        ).toEqual([]);
    });

    it("ignores a hoisted class string that never reaches a className", () => {
        expect(scanProbe('const unusedClass = "inline-flex h-9 rounded-full bg-muted ring-1";')).toEqual(
            []
        );
    });
});
