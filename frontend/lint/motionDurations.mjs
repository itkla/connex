import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

/**
 * The motion-timing gate and its burndown inventory (WS10 / issue #1346).
 *
 * One rule: a transition or animation duration is a design decision, so it names a token
 * (`--motion-micro`, `--motion-standard`, `--motion-expressive`, or their `app/lib/motion.ts`
 * mirrors) rather than a number. The scanner catches the four idioms this codebase actually
 * writes, listed with their remediation in {@link REMEDIATION} — the Tailwind `duration-*` utility,
 * any `transition` / `animation` declaration (shorthand or `-duration` longhand, counted **per time
 * literal** so a three-part shorthand reports three), a `duration:` field in a `motion` transition
 * or Web Animations options object, and a camelCase `animationDuration` / `transitionDuration`
 * style property or JSX prop. A zero is not a timing choice (it *is* the reduced-motion escape
 * hatch), so `duration-0`, `duration: 0`, and `0s` pass.
 *
 * **Rule-widening notes.** The gate has been widened twice, each time exposing debt the narrower
 * rules had been hiding:
 *
 * 1. 220 → 236 (raised). The original rules matched only the `transition-duration` /
 *    `animation-duration` longhand, so CSS shorthand and camelCase style properties passed
 *    unseen — `app/globals.css` and `components/PixelCard.css` were satisfying the
 *    `TOKENIZED_SURFACES` zero-assertion vacuously.
 * 2. 236 → 231 (lowered). Two further holes: `.js`/`.jsx` files under the scan roots were never
 *    read at all, and a declaration counted as one hit however many timings it carried, so
 *    `transition: background-color 0.22s, border-color 0.22s, transform 0.15s` reported one
 *    instead of three. Widening added three findings and no new files; tokenizing the surfaces
 *    this workstream had already touched removed eight, so the mark **fell**.
 *
 * 3. 231 → 227 (lowered). WS8's button system replaced four hand-timed surfaces with the
 *    primitives: the records split button, the dashboard and deal-document menu triggers, and the
 *    analytics range control, whose thumb now rides `SegmentedControl`'s shared spring.
 *
 * 4. 227 → 226 (lowered). WS11's Radar redesign replaced the signal card's hand-timed disclosure
 *    chevron with the `--motion-*` tokens, so the last radar file left the ledger.
 *
 * Widening the scanner is the only sanctioned reason to raise the mark. New debt is not — and when
 * a widening commit also pays debt down, the mark follows the lower total, as in (2).
 *
 * **The burndown contract.** `loadBaseline()` returns the committed inventory of files that still
 * hard-code a duration, with the count each one carries — the denominator for every "no hard-coded
 * durations" claim in this workstream. A file's count may only fall; a file that reaches zero is
 * deleted from the ledger, never left at `0`. `BASELINE_HIGH_WATER_MARK` records the total when the
 * gate landed and must never be raised. `test/unit/motionDurations.test.ts` fails the build if a
 * file outside the ledger hard-codes a duration, if a ledger count grows, if a ledger entry is
 * already clean, or if the ledger names a file that no longer exists. The ledger is empty when the
 * sweep finishes.
 *
 * `TOKENIZED_SURFACES` is the opposite list and is permanent: the shared primitives WS10 put on the
 * tokens. They must stay at zero and must never reappear in the ledger.
 */

const PACKAGE_ROOT = resolve(import.meta.dirname, "..");
const BASELINE_PATH = resolve(import.meta.dirname, "motion-duration-baseline.json");

/** Directories scanned for hard-coded timings, relative to the frontend package root. */
export const SCAN_ROOTS = ["app", "components", "lib"];

/** File kinds that can carry a timing: Tailwind classes, motion transitions, and stylesheets. */
export const SCANNED_EXTENSIONS = [".css", ".js", ".jsx", ".ts", ".tsx"];

/**
 * Shared primitives converted to the motion tokens by WS10. Every overlay, menu, and press
 * interaction in the product inherits its timing from one of these, so they carry the system
 * rather than a number.
 */
export const TOKENIZED_SURFACES = [
    "app/components/motion/Rise.tsx",
    "components/ui/autocomplete.tsx",
    "components/ui/button.tsx",
    "components/ui/combobox.tsx",
    "components/ui/context-menu.tsx",
    "components/ui/dialog.tsx",
    "components/ui/drawer.tsx",
    "components/ui/dropdown-menu.tsx",
    "components/ui/hover-card.tsx",
    "components/ui/icon-button.tsx",
    "components/ui/message-scroller.tsx",
    "components/ui/popover.tsx",
    "components/ui/responsive-dialog.tsx",
    "components/ui/segmented-control.tsx",
    "components/ui/select.tsx",
    "components/ui/split-button.tsx",
    "components/ui/tabs.tsx",
    "components/ui/tooltip.tsx",
];

/**
 * What to write instead, per idiom. The mirrors differ by API family: `motion/react` takes seconds,
 * the Web Animations API and React style objects take milliseconds, and mixing them silently
 * animates a surface a thousand times too fast or too slow.
 */
export const REMEDIATION = {
    utility: "Tailwind `duration-*` utility → `duration-(--motion-micro|standard|expressive)`.",
    declaration:
        "CSS `transition`/`animation` timing → `var(--motion-micro|standard|expressive)`.",
    field:
        "`duration:` option → `durationMicro|durationStandard|durationExpressive` (seconds) for a `motion/react` transition, " +
        "or `durationMicroMs|durationStandardMs|durationExpressiveMs` (milliseconds) for `Element.animate`. Both from `app/lib/motion.ts`.",
    styleProperty:
        "`animationDuration`/`transitionDuration` prop or style property → `durationMicroMs|durationStandardMs|durationExpressiveMs` (milliseconds) from `app/lib/motion.ts`.",
};

/** The Tailwind `duration-150` / `duration-[220ms]` utility. `duration-0` and `duration-(--token)` pass. */
const UTILITY_PATTERN = /(?<![\w-])duration-(?:\[[^\]]+\]|[1-9]\d*)/g;

/**
 * A whole `transition:` / `animation:` shorthand or `transition-duration:` / `animation-duration:`
 * longhand declaration. Matching the declaration rather than its first timing is what lets
 * {@link TIME_LITERAL_PATTERN} count *every* timing inside it. The leading guard rejects
 * `-webkit-transition:` and `transition-property:`; excluding `{` and `}` keeps the match inside one
 * declaration and stops it swallowing a JS object literal.
 */
const TIMED_DECLARATION_PATTERN = /(?:^|[^-\w])(?:transition|animation)(?:-duration)?:[^;{}]*/g;

/** A CSS time literal. Captured so a zero — which is the reduced-motion escape hatch — can pass. */
const TIME_LITERAL_PATTERN = /(?<![\w.])(\d*\.?\d+)(?:ms|s)(?![\w-])/g;

/** A `duration: 0.25` field in a `motion` transition or a Web Animations options object. */
const FIELD_PATTERN = /(?<![\w.])duration:\s*(?:0?\.\d+|[1-9][\d_.]*)/g;

/** A camelCase `animationDuration={500}` JSX prop or `transitionDuration: "200ms"` style property. */
const STYLE_PROPERTY_PATTERN =
    /(?<![\w.])(?:animation|transition)Duration\s*[:=]\s*\{?\s*['"`]?(?:0?\.\d+|[1-9][\d.]*)/g;

const SIMPLE_PATTERNS = [
    ["utility", UTILITY_PATTERN],
    ["field", FIELD_PATTERN],
    ["styleProperty", STYLE_PROPERTY_PATTERN],
];

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

/**
 * The hard-coded timings a single file still carries. A `transition`/`animation` declaration
 * contributes one entry per non-zero time literal, so a three-part shorthand reports three.
 *
 * @returns {{ text: string, idiom: keyof typeof REMEDIATION }[]}
 */
export function scanFile(file) {
    const source = readFileSync(resolve(PACKAGE_ROOT, file), "utf8");
    /** @type {{ text: string, idiom: keyof typeof REMEDIATION }[]} */
    const found = [];

    for (const [idiom, pattern] of SIMPLE_PATTERNS) {
        for (const match of source.matchAll(pattern)) found.push({ text: match[0].trim(), idiom });
    }

    for (const declaration of source.matchAll(TIMED_DECLARATION_PATTERN)) {
        const text = declaration[0].trim();
        for (const literal of text.matchAll(TIME_LITERAL_PATTERN)) {
            if (Number.parseFloat(literal[1]) === 0) continue;
            found.push({ text: `${text.slice(0, text.indexOf(":") + 1)} … ${literal[0]}`, idiom: "declaration" });
        }
    }

    return found;
}

/** One `path (fragment → remediation)` line per hard-coded timing, for a gate failure message. */
export function describeFile(file) {
    return scanFile(file).map(({ text, idiom }) => `${file} (${text}) — ${REMEDIATION[idiom]}`);
}

/**
 * Every file that still hard-codes a timing, keyed by path, with the count it carries.
 *
 * @returns {Record<string, number>}
 */
export function scanMotionDurations() {
    /** @type {Record<string, number>} */
    const found = {};
    for (const file of scannedFiles()) {
        const matches = scanFile(file);
        if (matches.length > 0) found[file] = matches.length;
    }
    return found;
}

/**
 * The committed burndown ledger: file path to the number of hard-coded timings it still carries.
 *
 * @returns {Record<string, number>}
 */
export function loadBaseline() {
    return JSON.parse(readFileSync(BASELINE_PATH, "utf8"));
}

/**
 * The ledger's total when the gate landed, after the shorthand and style-property rules were added.
 * It may fall. It rises only in a commit that widens what the scanner catches — never to make room
 * for new debt.
 */
export const BASELINE_HIGH_WATER_MARK = 226;
