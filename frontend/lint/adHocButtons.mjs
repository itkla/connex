import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

/**
 * The button-system gate and its burndown inventory (WS8 / issue #1344, executing #509).
 *
 * One rule: a button is the primitive. `docs/PRODUCT.md` §6 "Buttons" is the law — pill-shaped,
 * one height per context, chevroned menu triggers, circular tooltipped icon buttons, one capsule
 * for a split — and `components/ui/button.tsx` is the only place that shape is allowed to be
 * decided. Anything that paints its own pill, overrides the primitive's shape, or names a height
 * outside the context scale is debt, and this scanner is its denominator.
 *
 * Three idioms, listed with their remediation in {@link REMEDIATION}:
 *
 * 1. `handRolled` — a raw `<button>` that paints a button surface (a radius plus a fill, ring,
 *    border, or shadow, plus its own padding or size). A bare `<button>` carrying only colour, or
 *    a wrapper around a card or row, is not a button in the D4 sense and does not report.
 * 2. `shapeOverride` — a `<Button>` or `<IconButton>` whose `className` re-decides the radius or
 *    the height (`rounded-*`, `h-8`, `size-9`). The variant is the height scale; a call site that
 *    types a number has left the scale.
 * 3. `legacySize` — a `size` outside the four context tiers (`page`, `dialog`, `toolbar`,
 *    `inline`, and their `icon-` forms). `default`/`sm`/`xs`/`lg`/`icon*` still resolve to the same
 *    heights so nothing broke when the tiers landed, but they name a size instead of a context,
 *    which is exactly what D4 replaced.
 *
 * **The burndown contract**, deliberately identical to `lint/motionDurations.mjs` so the two gates
 * read the same way. `loadBaseline()` returns the committed inventory of files that still carry
 * ad-hoc button styling, with the count each one carries. A file's count may only fall; a file that
 * reaches zero is deleted from the ledger, never left at `0`. `BASELINE_HIGH_WATER_MARK` records
 * the total when the gate landed and must never be raised — it rises only in a commit that widens
 * what the scanner catches, never to make room for new debt. `test/unit/adHocButtons.test.ts`
 * fails the build if a file outside the ledger reports, if a ledger count grows, if a ledger entry
 * is already clean, or if the ledger names a file that no longer exists.
 *
 * `SYSTEM_SURFACES` is the opposite list and is permanent: the primitives that *are* the button
 * system. They decide the shape for every other surface, so they are exempt from the hand-rolled
 * rule — and they must never appear in the ledger.
 */

const PACKAGE_ROOT = resolve(import.meta.dirname, "..");
const BASELINE_PATH = resolve(import.meta.dirname, "ad-hoc-button-baseline.json");

/** Directories scanned for ad-hoc button styling, relative to the frontend package root. */
export const SCAN_ROOTS = ["app", "components"];

/** File kinds that can carry JSX. */
export const SCANNED_EXTENSIONS = [".jsx", ".tsx"];

/**
 * The primitives that define the button system. They are the only files allowed to paint a button
 * surface, because every other surface inherits its shape from them.
 */
export const SYSTEM_SURFACES = [
    "components/ui/button-group.tsx",
    "components/ui/button.tsx",
    "components/ui/icon-button.tsx",
    "components/ui/segmented-control.tsx",
    "components/ui/split-button.tsx",
];

/** What to write instead, per idiom. */
export const REMEDIATION = {
    handRolled:
        "hand-rolled button surface → `<Button>` / `<IconButton>` from `components/ui`, or `buttonVariants` for a link.",
    shapeOverride:
        "`className` re-deciding radius or height → drop it and pick the `size` context tier (`page`, `dialog`, `toolbar`, `inline`).",
    legacySize:
        "`size` outside the context scale → `page` | `dialog` | `toolbar` | `inline` (or their `icon-` forms).",
};

/** The context tiers D4 defines. Everything else on `size` is legacy naming. */
export const CONTEXT_SIZES = [
    "page",
    "dialog",
    "toolbar",
    "inline",
    "icon-page",
    "icon-dialog",
    "icon-toolbar",
    "icon-inline",
];

/** A radius utility, in any variant or arbitrary form. */
const RADIUS = /(?<![\w-])(?:[a-z-]+:)*rounded(?:-[a-z]+)*(?:-(?:none|full|xs|sm|md|lg|xl|\d?xl|\[[^\]]+\]))?(?![\w-])/;
/** A fill, ring, border, or shadow — what makes a radius read as a surface rather than a clip. */
const SURFACE = /(?<![\w-])(?:[a-z-]+:)*(?:bg-(?!transparent(?![\w-]))|ring-\d|ring-1|ring-2|border(?![\w-])|border-[a-z]|shadow)/;
/** An explicit box: the element sizes itself instead of inheriting a scale. */
const BOX = /(?<![\w-])(?:[a-z-]+:)*(?:p[xy]?-(?:\d|\[)|size-\d|h-\d|w-\d)/;
/** A height or radius decision taken at a `<Button>` call site. */
const SHAPE_OVERRIDE = /(?<![\w-])(?:[a-z-]+:)*(?:rounded(?:-[a-z]+)*-|h-\d|size-\d)/;

function tagBodies(source, name) {
    const bodies = [];
    const opener = new RegExp(`<${name}(?=[\\s/>])`, "g");
    for (const match of source.matchAll(opener)) {
        let index = match.index + match[0].length;
        let depth = 0;
        let quote = null;
        while (index < source.length) {
            const character = source[index];
            if (quote) {
                if (character === quote) quote = null;
            } else if (character === '"' || character === "'" || character === "`") {
                quote = character;
            } else if (character === "{") {
                depth += 1;
            } else if (character === "}") {
                depth -= 1;
            } else if (character === ">" && depth === 0) {
                break;
            }
            index += 1;
        }
        bodies.push({ text: source.slice(match.index, index + 1), index: match.index });
    }
    return bodies;
}

function classNameOf(tag) {
    const at = tag.indexOf("className");
    return at === -1 ? "" : tag.slice(at);
}

function fragment(tag) {
    const collapsed = tag.replace(/\s+/g, " ").trim();
    return collapsed.length > 110 ? `${collapsed.slice(0, 110)}…` : collapsed;
}

/**
 * The ad-hoc button styling a single file still carries.
 *
 * @returns {{ text: string, idiom: keyof typeof REMEDIATION }[]}
 */
export function scanFile(file) {
    const source = readFileSync(resolve(PACKAGE_ROOT, file), "utf8");
    /** @type {{ text: string, idiom: keyof typeof REMEDIATION }[]} */
    const found = [];
    const isSystemSurface = SYSTEM_SURFACES.includes(file);

    if (!isSystemSurface) {
        for (const tag of tagBodies(source, "button")) {
            const className = classNameOf(tag.text);
            if (RADIUS.test(className) && SURFACE.test(className) && BOX.test(className)) {
                found.push({ text: fragment(tag.text), idiom: "handRolled" });
            }
        }
    }

    for (const name of ["Button", "IconButton"]) {
        for (const tag of tagBodies(source, name)) {
            if (isSystemSurface) continue;
            const className = classNameOf(tag.text);
            if (SHAPE_OVERRIDE.test(className)) {
                found.push({ text: fragment(tag.text), idiom: "shapeOverride" });
            }
            const size = /\bsize="([a-z-]+)"/.exec(tag.text);
            if (size && !CONTEXT_SIZES.includes(size[1])) {
                found.push({ text: fragment(tag.text), idiom: "legacySize" });
            }
        }
    }

    return found;
}

function* walk(directory) {
    let entries;
    try {
        entries = readdirSync(directory, { withFileTypes: true });
    } catch {
        return;
    }
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
        if (entry.name.startsWith(".") || entry.name === "node_modules") continue;
        const path = join(directory, entry.name);
        if (entry.isDirectory()) {
            yield* walk(path);
        } else if (SCANNED_EXTENSIONS.some((extension) => entry.name.endsWith(extension))) {
            yield path;
        }
    }
}

/** Every scanned source file, as a package-root-relative POSIX path. */
export function scannedFiles() {
    const files = [];
    for (const root of SCAN_ROOTS) {
        for (const path of walk(resolve(PACKAGE_ROOT, root))) {
            files.push(relative(PACKAGE_ROOT, path).split(sep).join("/"));
        }
    }
    return files.sort();
}

/** One `path (fragment → remediation)` line per finding, for a gate failure message. */
export function describeFile(file) {
    return scanFile(file).map(({ text, idiom }) => `${file} (${text}) — ${REMEDIATION[idiom]}`);
}

/**
 * Every file that still carries ad-hoc button styling, keyed by path, with the count it carries.
 *
 * @returns {Record<string, number>}
 */
export function scanAdHocButtons() {
    /** @type {Record<string, number>} */
    const found = {};
    for (const file of scannedFiles()) {
        const matches = scanFile(file);
        if (matches.length > 0) found[file] = matches.length;
    }
    return found;
}

/**
 * The committed burndown ledger: file path to the number of ad-hoc button decisions it still
 * carries.
 *
 * @returns {Record<string, number>}
 */
export function loadBaseline() {
    return JSON.parse(readFileSync(BASELINE_PATH, "utf8"));
}

/**
 * The ledger's total when the gate landed, after WS8 swept the reference pages, the records
 * browsers, and the surfaces the chevron, icon-button, and segmented-control laws touch. It may
 * fall. It rises only in a commit that widens what the scanner catches.
 */
export const BASELINE_HIGH_WATER_MARK = 390;
