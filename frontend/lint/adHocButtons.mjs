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
 * Six idioms, listed with their remediation in {@link REMEDIATION}:
 *
 * 1. `handRolled` — a `<button>`, or an element with `role="button"`, that paints a button surface:
 *    a radius, plus a fill/ring/border/shadow, plus a **reserved control height**. The height is
 *    what separates a button from a chip — D4's law is one height per context, so a pill that hugs
 *    its text on `py-0.5` is a tag and belongs to #509 Phase 3, not here. An element declaring a
 *    foreign semantic (`role` other than `button`, `aria-current`, `aria-selected`) is a tab, nav
 *    item, or option, and never reports.
 * 2. `linkAsButton` — an `<a>` or `<Link>` painting the same surface. #509 Phase 2 routes these
 *    through `buttonVariants` rather than leaving each one to redraw the button.
 * 3. `hoistedClass` — a module-scope class string, or a helper returning one, that paints a button
 *    surface and is spent on a `className`. `pillClass` is the reason this rule exists: one
 *    function draws the whole filter-pill layer, and a tag-shaped scanner scores all of its call
 *    sites clean.
 * 4. `shapeOverride` — a `<Button>` or `<IconButton>` whose `className` re-decides the radius or
 *    the height (`rounded-*`, `h-8`, `size-9`). The variant is the height scale; a call site that
 *    types a number has left the scale.
 * 5. `legacySize` — a `size` outside the four context tiers (`page`, `dialog`, `toolbar`,
 *    `inline`, and their `icon-` forms). `default`/`sm`/`xs`/`lg`/`icon*` still resolve to the same
 *    heights so nothing broke when the tiers landed, but they name a size instead of a context,
 *    which is exactly what D4 replaced.
 * 6. `iconOnlyWithoutTooltip` — a raw `<Button>` at an icon size that no `TooltipTrigger` wraps.
 *    D4 says icon-only buttons are always tooltipped; `IconButton` is what makes that structural,
 *    because there the accessible name and the tooltip are one string.
 *
 * **What it does not measure.** The chevron law and one-primary-action-per-region are review-
 * enforced, not scanned: whether a button opens a menu, and whether two primaries share a region,
 * are facts about a render tree that a text scanner cannot read without lying about its confidence.
 *
 * **High-water-mark history**, newest last:
 *
 * 1. 390 → 461 (raised). The gate landed measuring shape and height only, over `<button>` tags and
 *    `<Button>` call sites. Three holes: button-shaped links and hoisted class strings were
 *    invisible however much surface they painted, and the tooltip half of the D4 law went unmeasured
 *    entirely while the guide called this list the D4 denominator. Widening added 34 `linkAsButton`,
 *    15 `hoistedClass`, and 63 `iconOnlyWithoutTooltip` findings; requiring a reserved control
 *    height and rejecting foreign semantics removed 41 tabs, chips, and nav rows that were never
 *    button debt. `classNameOf` reading past its own attribute had been feeding several of those.
 * 2. 461 → 455 (lowered). WS8's D5/D16 overlay pass deleted `CampaignFormDialog.tsx` — campaigns
 *    create through the instant-create prompt and edit in the shared quick-edit drawer — taking its
 *    hoisted input-surface class string with it; moved the record timeline's overflow trigger onto
 *    `IconButton`, clearing that file's shape override, legacy size, and missing tooltip at once;
 *    and put the products browser on `RecordsRenderView`, whose own row menu replaced the
 *    hand-sized trigger the browser drew for itself.
 * 3. 455 → 448 (lowered). #1340's connected-accounts journey rebuilt `CaptureProviderCard.tsx`
 *    around the button system: the overflow menu that carried the card's five secondary jobs moved
 *    into the manage drawer, and the actions that stayed took context tiers instead of the `sm`
 *    sizes the card had been naming, clearing all seven of its findings.
 * 4. 448 → 447 (lowered). #1340's People & access destination removed the last remaining shape
 *    override from one of the members-panel actions.
 * 5. 447 → 430 (lowered). WS11's Radar redesign put the board's filter row on
 *    `SegmentedControl` and rebuilt the signal row on `Button`/`IconButton`, so both radar files left
 *    the ledger: one hand-rolled chip layer and sixteen shape overrides, icon sizes, and untooltipped
 *    icon buttons.
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
        "hand-rolled button surface → `<Button>` / `<IconButton>` from `components/ui`.",
    linkAsButton:
        "button-shaped link → `buttonVariants({ variant, size })` on the `<a>` / `<Link>`, or `<Button asChild>`.",
    hoistedClass:
        "hoisted button-surface class string → a `buttonVariants` call, or a variant on the primitive.",
    shapeOverride:
        "`className` re-deciding radius or height → drop it and pick the `size` context tier (`page`, `dialog`, `toolbar`, `inline`).",
    legacySize:
        "`size` outside the context scale → `page` | `dialog` | `toolbar` | `inline` (or their `icon-` forms).",
    iconOnlyWithoutTooltip:
        "icon-only `<Button>` with no guaranteed tooltip → `<IconButton label={…}>`, which makes the accessible name and the tooltip one string.",
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
/**
 * A reserved control height — the signal that separates a button from a chip. D4's law is one
 * height per context, so a control that names a height (`h-9`), a square (`size-8`), or vertical
 * padding of 6px or more is participating in that scale. A pill that hugs its text on `py-0.5` is a
 * chip, a tag, or a badge; those are a different control family and #509 Phase 3 owns them.
 */
const CONTROL_HEIGHT = /(?<![\w-])(?:[a-z-]+:)*(?:size-\d|h-\d|min-h-|py-(?:1\.5|2|2\.5|3|3\.5|4|5|6)(?![\d.])|p-(?:1\.5|2|2\.5|3|3\.5|4|5|6)(?![\d.]))/;
/** A height or radius decision taken at a `<Button>` call site. */
const SHAPE_OVERRIDE = /(?<![\w-])(?:[a-z-]+:)*(?:rounded(?:-[a-z]+)*-|h-\d|size-\d)/;
/**
 * A semantic that says this element is not an action: a foreign ARIA role, or the current/selected
 * state of a tab, nav item, or option. Those belong to their own patterns, not to the button system.
 */
const FOREIGN_SEMANTIC = /\brole="(?!button")|\baria-current[=\s]|\baria-selected[=\s]/;
/** An icon-only size on the raw primitive, where nothing guarantees the D4 tooltip. */
const ICON_SIZE = /\bsize="icon(?:-[a-z]+)?"/;

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

/**
 * The `className` attribute's own value, and nothing after it. Slicing to the end of the tag would
 * let a neighbouring `title`, `aria-label`, or handler donate the words the idioms look for, which
 * is how a plain `<button>` beside a `rounded-full` sibling attribute ends up ledgered.
 */
function classNameOf(tag) {
    const at = tag.search(/\bclassName\s*=/);
    if (at === -1) return "";
    const rest = tag.slice(tag.indexOf("=", at) + 1).trimStart();
    const opener = rest[0];
    if (opener === '"' || opener === "'") {
        const close = rest.indexOf(opener, 1);
        return close === -1 ? rest : rest.slice(1, close);
    }
    if (opener !== "{") return "";
    let depth = 0;
    let quote = null;
    for (let index = 0; index < rest.length; index += 1) {
        const character = rest[index];
        if (quote) {
            if (character === quote) quote = null;
        } else if (character === '"' || character === "'" || character === "`") {
            quote = character;
        } else if (character === "{") {
            depth += 1;
        } else if (character === "}") {
            depth -= 1;
            if (depth === 0) return rest.slice(1, index);
        }
    }
    return rest;
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
    if (SYSTEM_SURFACES.includes(file)) return found;

    const paintsAButton = (className) =>
        RADIUS.test(className) && SURFACE.test(className) && CONTROL_HEIGHT.test(className);

    for (const name of ["button", "div", "span"]) {
        for (const tag of tagBodies(source, name)) {
            if (name !== "button" && !/\brole="button"/.test(tag.text)) continue;
            if (FOREIGN_SEMANTIC.test(tag.text)) continue;
            if (paintsAButton(classNameOf(tag.text))) {
                found.push({ text: fragment(tag.text), idiom: "handRolled" });
            }
        }
    }

    for (const name of ["a", "Link"]) {
        for (const tag of tagBodies(source, name)) {
            if (FOREIGN_SEMANTIC.test(tag.text)) continue;
            if (paintsAButton(classNameOf(tag.text))) {
                found.push({ text: fragment(tag.text), idiom: "linkAsButton" });
            }
        }
    }

    for (const [identifier, body] of hoistedClassSources(source)) {
        if (!paintsAButton(body)) continue;
        if (!usedInClassName(source, identifier)) continue;
        found.push({ text: `${identifier} = ${fragment(body)}`, idiom: "hoistedClass" });
    }

    for (const name of ["Button", "IconButton"]) {
        for (const tag of tagBodies(source, name)) {
            const className = classNameOf(tag.text);
            if (SHAPE_OVERRIDE.test(className)) {
                found.push({ text: fragment(tag.text), idiom: "shapeOverride" });
            }
            const size = /\bsize="([a-z-]+)"/.exec(tag.text);
            if (size && !CONTEXT_SIZES.includes(size[1])) {
                found.push({ text: fragment(tag.text), idiom: "legacySize" });
            }
            if (name === "Button" && ICON_SIZE.test(tag.text) && !wrappedInTooltip(source, tag.index)) {
                found.push({ text: fragment(tag.text), idiom: "iconOnlyWithoutTooltip" });
            }
        }
    }

    return found;
}

/**
 * Class strings hoisted out of JSX — a module-scope `const`, or a helper that returns one. They are
 * where a hand-rolled button surface hides from a tag-shaped scanner: `pillClass` paints the whole
 * filter-pill layer from one function body, and a scanner that only reads `className=` attributes
 * would score every one of its call sites as clean.
 *
 * @returns {[string, string][]} identifier and the source text that may carry the classes
 */
function hoistedClassSources(source) {
    /** @type {[string, string][]} */
    const sources = [];
    for (const match of source.matchAll(/^(?:export\s+)?const\s+([A-Za-z_$][\w$]*)\s*(?::[^=]+)?=\s*([\s\S]*?);$/gm)) {
        sources.push([match[1], match[2]]);
    }
    for (const match of source.matchAll(
        /^(?:export\s+)?function\s+([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*(?::\s*string\s*)?\{([\s\S]*?)^\}/gm
    )) {
        sources.push([match[1], match[2]]);
    }
    return sources;
}

/** Whether an identifier is spent on a `className`, which is what makes a class string load-bearing. */
function usedInClassName(source, identifier) {
    const pattern = new RegExp(`className\\s*=\\s*\\{[^}]*\\b${identifier}\\b`);
    return pattern.test(source);
}

/**
 * Whether a tag sits directly inside a tooltip trigger. `IconButton` guarantees the D4 tooltip by
 * construction; a raw `<Button>` at an icon size only has one if a call site wrapped it, so the
 * scanner looks back over the opening tags that could plausibly be that wrapper.
 */
function wrappedInTooltip(source, index) {
    return /TooltipTrigger[^>]*>\s*$/.test(source.slice(Math.max(0, index - 400), index));
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
 * The ledger's total after the widening described above. It may fall. It rises only in a commit
 * that widens what the scanner catches — never to make room for new debt.
 */
export const BASELINE_HIGH_WATER_MARK = 430;
