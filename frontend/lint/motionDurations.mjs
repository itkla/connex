import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

/**
 * The motion-timing gate and its burndown inventory (WS10 / issue #1346).
 *
 * One rule: a transition or animation duration is a design decision, so it names a token
 * (`--motion-micro`, `--motion-standard`, `--motion-expressive`, or their `app/lib/motion.ts`
 * mirrors) rather than a number. The scanner catches the five idioms this codebase actually
 * writes — the Tailwind `duration-*` utility, a longhand `transition-duration` /
 * `animation-duration` declaration, the `transition:` / `animation:` **shorthand** (where the
 * timing hides among the other components), a `duration:` field in a `motion` transition or Web
 * Animations options object, and a camelCase `animationDuration` / `transitionDuration` style
 * property or JSX prop. A literal zero is not a timing choice (it *is* the reduced-motion escape
 * hatch), so `duration-0` and `duration: 0` pass.
 *
 * **Rule-widening note.** The gate landed matching only the two longhand declarations, which let
 * CSS shorthand and camelCase style properties through — `app/globals.css` and
 * `components/PixelCard.css` were passing the `TOKENIZED_SURFACES` zero-assertion vacuously. The
 * shorthand, style-property, and JSX-prop patterns were added in the same workstream and
 * `BASELINE_HIGH_WATER_MARK` was raised once, from 220 to the corrected landing total, to record
 * the debt the narrow rules had been hiding. That is the only sanctioned reason to raise it.
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
export const SCANNED_EXTENSIONS = [".css", ".ts", ".tsx"];

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
    "components/ui/message-scroller.tsx",
    "components/ui/popover.tsx",
    "components/ui/responsive-dialog.tsx",
    "components/ui/select.tsx",
    "components/ui/tabs.tsx",
    "components/ui/tooltip.tsx",
];

/** The Tailwind `duration-150` / `duration-[220ms]` utility. `duration-0` and `duration-(--token)` pass. */
const UTILITY_PATTERN = /(?<![\w-])duration-(?:\[[^\]]+\]|[1-9]\d*)/g;

/** A longhand `transition-duration: 300ms` / `animation-duration: .4s` declaration. */
const DECLARATION_PATTERN = /(?:transition|animation)-duration:\s*[^;]*?[1-9][\d.]*m?s/g;

/**
 * The `transition: transform 0.7s …` / `animation: connexRise 0.7s …` shorthand, where the timing
 * hides among the other components. The leading guard rejects `-webkit-transition:` and
 * `transition-property:`; excluding `{` and `}` keeps it inside one declaration and stops it
 * swallowing a JS object literal.
 */
const SHORTHAND_PATTERN = /(?:^|[^-\w])(?:transition|animation):[^;{}]*?(?<![\w.])\d*\.?[1-9]\d*m?s/g;

/** A `duration: 0.25` field in a `motion` transition or a Web Animations options object. */
const FIELD_PATTERN = /(?<![\w.])duration:\s*(?:0?\.\d+|[1-9][\d_.]*)/g;

/** A camelCase `animationDuration={500}` JSX prop or `transitionDuration: "200ms"` style property. */
const STYLE_PROPERTY_PATTERN =
    /(?<![\w.])(?:animation|transition)Duration\s*[:=]\s*\{?\s*['"`]?(?:0?\.\d+|[1-9][\d.]*)/g;

const PATTERNS = [
    UTILITY_PATTERN,
    DECLARATION_PATTERN,
    SHORTHAND_PATTERN,
    FIELD_PATTERN,
    STYLE_PROPERTY_PATTERN,
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

/** The hard-coded timings a single file still carries, as the matched source fragments. */
export function scanFile(file) {
    const source = readFileSync(resolve(PACKAGE_ROOT, file), "utf8");
    return PATTERNS.flatMap((pattern) => [...source.matchAll(pattern)].map((match) => match[0].trim()));
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
export const BASELINE_HIGH_WATER_MARK = 236;
