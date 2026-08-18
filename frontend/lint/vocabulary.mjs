import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";

/**
 * Banned-term model for the message-catalog vocabulary gate, derived from the canonical
 * glossary in `docs/PRODUCT.md` §4. Section 4 is the only source: every term the gate
 * enforces, and every term it deliberately does not enforce, is traced back to a row of
 * the glossary table or to an entry of the "Banned on all product surfaces" list.
 *
 * Items in §4 that carry a qualifier ("account (for the record)", "user (except
 * auth/sign-in/session contexts)") cannot be judged from a catalog value alone, so each
 * one needs an explicit decision in {@link CURATED_DECISIONS} — either a narrowed ban or
 * a documented skip. A qualified §4 item with no decision, or a decision that no longer
 * matches an §4 item, makes {@link buildVocabularyModel} throw, so §4 cannot drift away
 * from the generated model.
 */

const PACKAGE_ROOT = resolve(import.meta.dirname, "..");
const PRODUCT_GUIDE_PATH = resolve(PACKAGE_ROOT, "..", "docs", "PRODUCT.md");
const GENERATED_MODEL_PATH = resolve(import.meta.dirname, "vocabulary.generated.json");
const BASELINE_PATH = resolve(import.meta.dirname, "vocabulary-baseline.json");
const MESSAGES_ROOT = resolve(PACKAGE_ROOT, "messages");

const SECTION_HEADING = "## 4. Vocabulary";
const BANNED_LIST_MARKER = "**Banned on all product surfaces**";
const EMPTY_CELL = "—";
const BOLD_SPAN = /\*\*([^*]+)\*\*/g;
const CODE_SPAN = /`([^`]+)`/g;
const QUALIFIER_WORD = /\s(?:as|except|alone|when|only|unless)\s/;
const CJK_CHARACTER = /[぀-ヿ㐀-䶿一-鿿ｦ-ﾟ]/u;
const JAPANESE_MIN_OVERLAP = 2;

/** Locales whose message catalogs the gate scans. */
export const LOCALES = ["en", "ja"];

/**
 * Compliance surfaces exempt from the gate. §4 allows the statutory register on the
 * organization data-requests admin tooling and the public legal pages; the gate exempts
 * those surfaces wholesale rather than per term, so statutory copy that belongs there is
 * never reported. Individual terms still record their §4 "only where noted" carve-out in
 * `allowFiles`, and the gate honours the union of the two.
 */
export const ALLOWED_SURFACES = ["legal.json", "organization.json#OrgDataRequests"];

/**
 * Message surfaces outside the gate's scope. WS5 owns the workflow seam exclusively, so
 * its namespaces are excluded until that workstream lands; folding them back in is part
 * of WS5's completion.
 */
export const EXCLUDED_SURFACES = [
    "workflow-operations.json",
    "workspace.json#WorkflowsLayout",
    "workspace.json#WorkspaceRules",
    "workspace.json#WorkspaceWorkflows",
];

/** Namespace files that carry note surfaces, where JA must say メモ and never ノート. */
const NOTE_SURFACES = [
    "account.json",
    "actions.json",
    "activity.json",
    "dashboard.json",
    "notifications.json",
    "records.json",
];

/** Namespace files that carry marketing-delivery surfaces, where JA must not say 抑制. */
const MARKETING_SURFACES = ["campaigns.json", "unsubscribe.json"];

/**
 * @typedef {"en" | "ja"} TermLocale
 */

/**
 * A message surface: a namespace file (`"contacts.json"`) or one next-intl namespace
 * inside a file (`"workspace.json#WorkspaceRules"`).
 * @typedef {string} Surface
 */

/**
 * @typedef {"global" | {namespaces: Surface[]} | {excludeNamespaces: Surface[]}} TermScope
 */

/**
 * @typedef {object} SerializedPattern
 * @property {string} source
 * @property {string} flags
 */

/**
 * @typedef {object} BannedTerm
 * @property {string} id
 * @property {string} term
 * @property {TermLocale} locale
 * @property {TermScope} scope
 * @property {Surface[]} allowFiles
 * @property {string[]} canonicalExceptions
 * @property {SerializedPattern} pattern
 * @property {string[]} sources
 */

/**
 * @typedef {object} SkippedTerm
 * @property {string} id
 * @property {string} term
 * @property {TermLocale} locale
 * @property {string} reason
 * @property {string[]} sources
 */

/**
 * @typedef {object} VocabularyModel
 * @property {BannedTerm[]} terms
 * @property {SkippedTerm[]} skipped
 */

/**
 * @typedef {object} VocabularyItem
 * @property {string} text
 * @property {string} term
 * @property {TermLocale} locale
 * @property {boolean} qualified
 * @property {string} source
 */

/**
 * @typedef {object} CuratedDecision
 * @property {"ban" | "skip"} decision
 * @property {string} reason
 * @property {TermScope} [scope]
 * @property {Surface[]} [allowFiles]
 * @property {SerializedPattern} [pattern]
 */

/**
 * The explicit decision for every §4 item that a value-level scan cannot judge on its
 * own, keyed by the item exactly as §4 writes it. `skip` entries are the curated
 * skip-list: terms §4 bans in a sense the gate cannot detect without meaning analysis.
 * @type {Record<string, CuratedDecision>}
 */
export const CURATED_DECISIONS = {
    "温度 alone as the metric name": {
        decision: "ban",
        reason: "§4's generator note keeps the bare metric name banned and excepts the canonical 温度感／温度帯 carriers instead of dropping the ban.",
    },
    "Relationship Radar as the everyday label": {
        decision: "skip",
        reason: "§4 sanctions the full name as an occasional label in onboarding and marketing prose, which a catalog value cannot be told apart from an everyday label.",
    },
    "Profile (for this page)": {
        decision: "skip",
        reason: "Profile is correct for the account and member profile surfaces; only the My Work page must not be called one.",
    },
    "account (for the record)": {
        decision: "skip",
        reason: "account is correct for sign-in and billing senses; only the Company record must not be called one.",
    },
    "アカウント": {
        decision: "skip",
        reason: "アカウント is the canonical Japanese word for a sign-in account; only the Company record must not be called one.",
    },
    "People (as a label for contact lists)": {
        decision: "skip",
        reason: "§4 sanctions person/people inside explanatory prose; only contact-list labels must say Contact.",
    },
    "opportunity as a countable UI noun (intro suggestions are \"suggested intros\")": {
        decision: "skip",
        reason: "banned only as a countable UI noun, which needs grammatical analysis of the surrounding sentence.",
    },
    "商談 except as an example stage name": {
        decision: "skip",
        reason: "§4 sanctions 商談 as an example pipeline stage name.",
    },
    "organization (for workspace scope)": {
        decision: "skip",
        reason: "Organization is canonical on organization-admin surfaces and in prose stating a genuinely organization-scoped fact.",
    },
    "team (as a scope; fine informally for the humans)": {
        decision: "skip",
        reason: "banned only as a scope word; §4 keeps team fine informally for the humans.",
    },
    "user (except auth/sign-in/session contexts)": {
        decision: "skip",
        reason: "§4 sanctions user in auth, sign-in, and session contexts, which a catalog value cannot be classified into.",
    },
    "teammate (as a label; fine in informal prose)": {
        decision: "skip",
        reason: "§4 sanctions teammate in informal prose; only labels must say member.",
    },
    "rule (except as \"legacy automations\" inside the one migration screen while it exists)": {
        decision: "skip",
        reason: "§4 sanctions rule on the legacy-automations migration screen, and rule is correct in non-automation senses such as retention rules.",
    },
    "warm path (as a label)": {
        decision: "ban",
        reason: "D11 requires intro path on every surface and the catalogs hold only product copy, so no sanctioned prose usage of warm path remains.",
    },
    "ノート": {
        decision: "ban",
        scope: { namespaces: NOTE_SURFACES },
        reason: "banned on the note surfaces §4 governs; #1323 scopes the JA ban to note namespaces so unrelated katakana compounds elsewhere are not swept in.",
    },
    "note (for comments)": {
        decision: "skip",
        reason: "Note is the canonical name of the standalone record; only the Comment concept must not be called a note.",
    },
    "Note (as an activity type)": {
        decision: "skip",
        reason: "Note is the canonical record name; the activity-type fold is a picker and read-time rename, not a string the gate can identify by value.",
    },
    "to-do / todo as a standalone noun": {
        decision: "skip",
        reason: "\"To do\" is the sanctioned kanban column name; standalone-noun usage needs grammatical analysis.",
    },
    "delete (when the record is recoverable)": {
        decision: "skip",
        reason: "Delete is canonical for permanent removal; whether the record behind a string is recoverable is not visible in the value.",
    },
    "processing suspended": {
        decision: "ban",
        allowFiles: ALLOWED_SURFACES,
        reason: "§4 allows the statutory register only as a secondary admin hint and on compliance surfaces.",
    },
    "provision ceased": {
        decision: "ban",
        allowFiles: ALLOWED_SURFACES,
        reason: "§4 allows the statutory register only as a secondary admin hint and on compliance surfaces.",
    },
    "processing restrictions": {
        decision: "ban",
        allowFiles: ALLOWED_SURFACES,
        reason: "§4 allows the statutory register only as a secondary admin hint and on compliance surfaces.",
    },
    "restricted (unglossed) as headline copy": {
        decision: "skip",
        reason: "banned only when unglossed in headline copy; whether a gloss follows lives in neighbouring keys, not in the value.",
    },
    "抑制": {
        decision: "ban",
        scope: { namespaces: MARKETING_SURFACES },
        reason: "banned on the marketing-delivery surfaces §4 governs; #1323 scopes the JA ban to marketing namespaces.",
    },
    "\"at a glance\" on more than one surface": {
        decision: "skip",
        reason: "banned only as a phrase repeated across surfaces, which is a cross-file count rather than a value match.",
    },
    "Overview as a page name": {
        decision: "skip",
        reason: "Overview is fine in prose; page-name usage is a route decision WS4 owns.",
    },
    "canonical": {
        decision: "ban",
        reason: "§4 bans canonical as runtime taxonomy and the automation row bans it outright, with no sanctioned product-surface usage.",
    },
    "legacy": {
        decision: "skip",
        reason: "§4 sanctions \"legacy automations\" on the one migration screen while it exists.",
    },
    "RBAC": {
        decision: "ban",
        reason: "no sanctioned product-surface usage; the parenthetical only widens the entry to permission constants.",
    },
    "RULE_MANAGE": {
        decision: "ban",
        pattern: { source: "\\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\\b", flags: "u" },
        reason: "§4 bans permission constants as a class (\"RULE_MANAGE etc.\"), so the pattern matches the constant shape rather than the one example.",
    },
    "data subject": {
        decision: "ban",
        allowFiles: ALLOWED_SURFACES,
        reason: "§4 marks it allowed on compliance surfaces only.",
    },
    "cease of use": {
        decision: "ban",
        allowFiles: ALLOWED_SURFACES,
        reason: "§4 marks it allowed on compliance surfaces only.",
    },
    "third-party provision": {
        decision: "ban",
        allowFiles: ALLOWED_SURFACES,
        reason: "§4 marks it allowed on compliance surfaces only.",
    },
    "turn": {
        decision: "ban",
        scope: { namespaces: ["assistant.json"] },
        reason: "§4 bans turn in the assistant, where the word for a reply is \"answer\"; elsewhere turn is an ordinary English verb.",
    },
    "register": {
        decision: "skip",
        reason: "banned only as a noun for a list; register is the legitimate auth verb on sign-up and passkey surfaces.",
    },
    "correlation ID": {
        decision: "ban",
        reason: "§4 replaces it with Reference on every surface.",
    },
    "contact #42": {
        decision: "skip",
        reason: "an illustrative raw id; detecting raw ids in copy needs analysis of what the interpolated values hold.",
    },
    "Retention rule: {code}": {
        decision: "skip",
        reason: "an illustrative raw-code fallback; detecting them needs analysis of the interpolated value's shape, not of the message text.",
    },
    "Request failed (403)": {
        decision: "skip",
        reason: "an illustrative HTTP status; bare three-digit numerals are indistinguishable from legitimate counts and amounts.",
    },
};

/**
 * Japanese renderings of banned English terms that §4 states in English only. Each key
 * must be an §4 item, so the rendering stays attributable to the glossary.
 * @type {Record<string, {term: string, reason: string}>}
 */
export const JAPANESE_RENDERINGS = {
    deterministic: {
        term: "決定論的",
        reason: "#1323 gate 1 requires the JA pattern for the term §4 bans in English; §5 requires JA to be authored natively rather than left untranslated.",
    },
};

/**
 * Escapes a literal so it can be embedded in a regular expression.
 * @param {string} value
 * @returns {string}
 */
function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Reads the checked-in product guide.
 * @returns {string}
 */
export function readProductGuide() {
    return readFileSync(PRODUCT_GUIDE_PATH, "utf8");
}

/**
 * Extracts §4 of the product guide.
 * @param {string} markdown
 * @returns {string}
 */
export function vocabularySection(markdown) {
    const start = markdown.indexOf(SECTION_HEADING);
    if (start < 0) {
        throw new Error(`docs/PRODUCT.md no longer contains "${SECTION_HEADING}"`);
    }
    const rest = markdown.slice(start + SECTION_HEADING.length);
    const end = rest.search(/^## /m);
    return end < 0 ? rest : rest.slice(0, end);
}

/**
 * Splits a markdown table row into its cells.
 * @param {string} line
 * @returns {string[]}
 */
function tableCells(line) {
    return line.slice(1, line.lastIndexOf("|")).split("|").map((cell) => cell.trim());
}

/**
 * Reads the glossary table of §4.
 * @param {string} section
 * @returns {{concept: string, en: string, ja: string, neverSay: string}[]}
 */
export function glossaryRows(section) {
    const rows = section
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line.startsWith("|") && !/^\|[\s|-]+\|$/.test(line))
        .map(tableCells)
        .filter((cells) => cells.length === 4);
    const [header, ...body] = rows;
    if (!header || header[0] !== "Concept" || header[3] !== "Never say (on product surfaces)") {
        throw new Error("docs/PRODUCT.md §4 no longer opens with the expected glossary table header");
    }
    return body.map(([concept, en, ja, neverSay]) => ({ concept, en, ja, neverSay }));
}

/**
 * Splits a "Never say" cell into its items, ignoring separators inside parentheses.
 * @param {string} cell
 * @returns {string[]}
 */
function neverSayItems(cell) {
    if (cell === EMPTY_CELL) {
        return [];
    }
    const items = [];
    let depth = 0;
    let current = "";
    for (const character of cell) {
        if (character === "(") depth += 1;
        if (character === ")") depth -= 1;
        if (depth === 0 && (character === "," || character === ";")) {
            items.push(current);
            current = "";
            continue;
        }
        current += character;
    }
    items.push(current);
    return items.map((item) => item.trim()).filter((item) => item.length > 0);
}

/**
 * Reads the "Banned on all product surfaces" list of §4 as its `·`-separated entries.
 * @param {string} section
 * @returns {{text: string, terms: string[], qualified: boolean}[]}
 */
export function bannedListEntries(section) {
    const lines = section.split("\n").map((line) => line.trim());
    const marker = lines.findIndex((line) => line.startsWith(BANNED_LIST_MARKER));
    if (marker < 0) {
        throw new Error(`docs/PRODUCT.md §4 no longer contains the "${BANNED_LIST_MARKER}" list`);
    }
    const list = lines.slice(marker + 1).find((line) => line.includes("·"));
    if (!list) {
        throw new Error("docs/PRODUCT.md §4 no longer states the banned terms as a `·`-separated list");
    }
    return list.split("·").map((chunk) => {
        const text = chunk.trim();
        const terms = [...text.matchAll(CODE_SPAN)].map((match) => match[1]);
        const outside = text.replace(CODE_SPAN, " ").replace(/[\s/]+/g, " ").trim();
        return { text, terms, qualified: outside.length > 0 };
    });
}

/**
 * Reduces an §4 item to the term it bans, dropping the qualifier that follows it.
 * @param {string} item
 * @returns {string}
 */
function itemTerm(item) {
    if (item.startsWith("\"")) {
        const closing = item.indexOf("\"", 1);
        if (closing > 0) return item.slice(1, closing);
    }
    const cuts = [item.indexOf(" ("), item.search(QUALIFIER_WORD)].filter((index) => index > 0);
    const cut = cuts.length > 0 ? Math.min(...cuts) : item.length;
    return item.slice(0, cut).trim();
}

/**
 * @param {string} term
 * @returns {TermLocale}
 */
function termLocale(term) {
    return CJK_CHARACTER.test(term) ? "ja" : "en";
}

/**
 * Every banned item §4 states, in glossary order followed by the banned-terms list.
 * @param {string} section
 * @returns {VocabularyItem[]}
 */
export function vocabularyItems(section) {
    const items = [];
    for (const row of glossaryRows(section)) {
        for (const text of neverSayItems(row.neverSay)) {
            const term = itemTerm(text);
            items.push({
                text,
                term,
                locale: termLocale(term),
                qualified: text !== term,
                source: `§4 glossary row "${row.concept}"`,
            });
        }
    }
    for (const entry of bannedListEntries(section)) {
        for (const term of entry.terms) {
            items.push({
                text: term,
                term,
                locale: termLocale(term),
                qualified: entry.qualified,
                source: "§4 banned-on-all-product-surfaces list",
            });
        }
    }
    return items;
}

/**
 * The canonical terms §4 prescribes, read from the bold spans of the EN and JA columns.
 * A JA cell that states its terms without emphasis contributes its `／`-separated
 * segments instead.
 * @param {string} section
 * @returns {{en: string[], ja: string[]}}
 */
export function canonicalTerms(section) {
    const collected = { en: [], ja: [] };
    for (const row of glossaryRows(section)) {
        for (const cell of [row.en, row.ja]) {
            const bold = [...cell.matchAll(BOLD_SPAN)].map((match) => match[1].trim());
            const terms = bold.length > 0
                ? bold
                : cell.split(/[／/,]/).map((segment) => segment.trim()).filter((segment) => segment.length > 0);
            for (const term of terms) {
                collected[termLocale(term)].push(term);
            }
        }
    }
    return { en: [...new Set(collected.en)].sort(), ja: [...new Set(collected.ja)].sort() };
}

/**
 * Finds the canonical terms a banned term overlaps, and the text on either side of the
 * overlap that a match must not be followed or preceded by. An overlap is any alignment
 * of the banned term against a canonical term where at least `minOverlap` characters
 * agree, so 温度 inside 温度感 and the tail of 関係の温度 running into 温度感 are both found.
 * @param {string} term
 * @param {string[]} canonical
 * @param {number} minOverlap
 * @returns {{leading: string[], trailing: string[], canonicalExceptions: string[]}}
 */
export function overlappingCanonicalTerms(term, canonical, minOverlap) {
    const leading = new Set();
    const trailing = new Set();
    const exceptions = new Set();
    for (const candidate of canonical) {
        if (candidate === term) continue;
        for (let offset = 1 - term.length; offset < candidate.length; offset += 1) {
            const start = Math.max(0, offset);
            const end = Math.min(candidate.length, offset + term.length);
            if (end - start < minOverlap) continue;
            if (candidate.slice(start, end) !== term.slice(start - offset, end - offset)) continue;
            const before = candidate.slice(0, start);
            const after = candidate.slice(end);
            if (before.length === 0 && after.length === 0) continue;
            if (before.length > 0) leading.add(before);
            if (after.length > 0) trailing.add(after);
            exceptions.add(candidate);
        }
    }
    return {
        leading: [...leading].sort(),
        trailing: [...trailing].sort(),
        canonicalExceptions: [...exceptions].sort(),
    };
}

/**
 * Builds the pattern for an English term: word-bounded, case-insensitive, and tolerant
 * of a plural. Word boundaries already stop an English term from matching inside a
 * longer canonical term, so no canonical exception is needed.
 * @param {string} term
 * @returns {{pattern: SerializedPattern, canonicalExceptions: string[]}}
 */
function englishPattern(term) {
    const body = escapeRegExp(term).replace(/\s+/g, "\\s+");
    return { pattern: { source: `\\b${body}(?:e?s)?\\b`, flags: "iu" }, canonicalExceptions: [] };
}

/**
 * Builds the pattern for a Japanese term, excepting the canonical terms it overlaps
 * rather than dropping the ban, as §4's generator note requires.
 * @param {string} term
 * @param {string[]} canonical
 * @returns {{pattern: SerializedPattern, canonicalExceptions: string[]}}
 */
function japanesePattern(term, canonical) {
    const { leading, trailing, canonicalExceptions } = overlappingCanonicalTerms(term, canonical, JAPANESE_MIN_OVERLAP);
    const behind = leading.length > 0 ? `(?<!${leading.map(escapeRegExp).join("|")})` : "";
    const ahead = trailing.length > 0 ? `(?!${trailing.map(escapeRegExp).join("|")})` : "";
    return {
        pattern: { source: `${behind}${escapeRegExp(term)}${ahead}`, flags: "u" },
        canonicalExceptions,
    };
}

/**
 * @param {string} term
 * @param {TermLocale} locale
 * @param {{en: string[], ja: string[]}} canonical
 * @returns {{pattern: SerializedPattern, canonicalExceptions: string[]}}
 */
function buildPattern(term, locale, canonical) {
    return locale === "ja" ? japanesePattern(term, canonical.ja) : englishPattern(term);
}

/**
 * @param {BannedTerm | SkippedTerm} left
 * @param {BannedTerm | SkippedTerm} right
 * @returns {number}
 */
function byId(left, right) {
    return left.id < right.id ? -1 : left.id > right.id ? 1 : 0;
}

/**
 * Builds the banned-term model from §4 of the product guide.
 * @param {string} markdown
 * @returns {VocabularyModel}
 */
export function buildVocabularyModel(markdown) {
    const section = vocabularySection(markdown);
    const canonical = canonicalTerms(section);
    const items = vocabularyItems(section);
    const decided = new Set();
    /** @type {Map<string, BannedTerm>} */
    const terms = new Map();
    /** @type {Map<string, SkippedTerm>} */
    const skipped = new Map();

    for (const item of items) {
        const curated = Object.hasOwn(CURATED_DECISIONS, item.text) ? CURATED_DECISIONS[item.text] : undefined;
        if (curated) decided.add(item.text);
        if (!curated && item.qualified) {
            throw new Error(
                `docs/PRODUCT.md ${item.source} bans "${item.text}" with a qualifier the gate cannot judge from a message value. `
                + "Add an explicit ban or skip for it to CURATED_DECISIONS in frontend/lint/vocabulary.mjs.",
            );
        }
        const id = `${item.locale}:${item.term}`;
        if (curated?.decision === "skip") {
            const existing = skipped.get(id);
            if (existing) {
                existing.sources.push(item.source);
                continue;
            }
            skipped.set(id, { id, term: item.term, locale: item.locale, reason: curated.reason, sources: [item.source] });
            continue;
        }
        const existing = terms.get(id);
        if (existing) {
            existing.sources.push(item.source);
            continue;
        }
        const built = curated?.pattern
            ? { pattern: curated.pattern, canonicalExceptions: [] }
            : buildPattern(item.term, item.locale, canonical);
        terms.set(id, {
            id,
            term: item.term,
            locale: item.locale,
            scope: curated?.scope ?? "global",
            allowFiles: curated?.allowFiles ?? [],
            canonicalExceptions: built.canonicalExceptions,
            pattern: built.pattern,
            sources: [item.source],
        });
    }

    for (const [text, rendering] of Object.entries(JAPANESE_RENDERINGS)) {
        const source = items.find((item) => item.text === text);
        if (!source) {
            throw new Error(
                `JAPANESE_RENDERINGS in frontend/lint/vocabulary.mjs renders "${text}", which docs/PRODUCT.md §4 no longer bans.`,
            );
        }
        const id = `ja:${rendering.term}`;
        const built = japanesePattern(rendering.term, canonical.ja);
        terms.set(id, {
            id,
            term: rendering.term,
            locale: "ja",
            scope: "global",
            allowFiles: [],
            canonicalExceptions: built.canonicalExceptions,
            pattern: built.pattern,
            sources: [`${source.source} (Japanese rendering)`],
        });
    }

    for (const text of Object.keys(CURATED_DECISIONS)) {
        if (!decided.has(text)) {
            throw new Error(
                `CURATED_DECISIONS in frontend/lint/vocabulary.mjs decides "${text}", which docs/PRODUCT.md §4 no longer bans.`,
            );
        }
    }

    return { terms: [...terms.values()].sort(byId), skipped: [...skipped.values()].sort(byId) };
}

/**
 * Reads the generated model committed alongside this module.
 * @returns {VocabularyModel}
 */
export function loadVocabularyModel() {
    return JSON.parse(readFileSync(GENERATED_MODEL_PATH, "utf8"));
}

/**
 * Reads the committed baseline of known violations.
 * @returns {string[]}
 */
export function loadBaseline() {
    return JSON.parse(readFileSync(BASELINE_PATH, "utf8"));
}

/**
 * @param {Surface} surface
 * @param {string} file
 * @param {string} namespace
 * @returns {boolean}
 */
function matchesSurface(surface, file, namespace) {
    const [surfaceFile, surfaceNamespace] = surface.split("#");
    return surfaceFile === file && (surfaceNamespace === undefined || surfaceNamespace === namespace);
}

/**
 * @param {Surface[]} surfaces
 * @param {string} file
 * @param {string} namespace
 * @returns {boolean}
 */
function matchesAnySurface(surfaces, file, namespace) {
    return surfaces.some((surface) => matchesSurface(surface, file, namespace));
}

/**
 * Whether a surface is a compliance surface the gate exempts wholesale.
 * @param {string} file
 * @param {string} namespace
 * @returns {boolean}
 */
export function isAllowedSurface(file, namespace) {
    return matchesAnySurface(ALLOWED_SURFACES, file, namespace);
}

/**
 * Whether a surface belongs to the workflow seam WS5 owns, which the gate does not scan.
 * @param {string} file
 * @param {string} namespace
 * @returns {boolean}
 */
export function isExcludedSurface(file, namespace) {
    return matchesAnySurface(EXCLUDED_SURFACES, file, namespace);
}

/**
 * Whether a term applies to a message surface.
 * @param {TermScope} scope
 * @param {string} file
 * @param {string} namespace
 * @returns {boolean}
 */
export function scopeCovers(scope, file, namespace) {
    if (scope === "global") return true;
    if ("namespaces" in scope) return matchesAnySurface(scope.namespaces, file, namespace);
    return !matchesAnySurface(scope.excludeNamespaces, file, namespace);
}

/**
 * @typedef {object} MessageEntry
 * @property {string} locale
 * @property {string} file
 * @property {string} namespace
 * @property {string} keyPath
 * @property {string} value
 */

/**
 * @param {unknown} value
 * @returns {value is Record<string, unknown>}
 */
function isMessageTree(value) {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * @param {Record<string, unknown>} tree
 * @param {string} prefix
 * @returns {[string, string][]}
 */
function flattenEntries(tree, prefix) {
    return Object.entries(tree).flatMap(([key, value]) => {
        if (isMessageTree(value)) return flattenEntries(value, `${prefix}${key}.`);
        return typeof value === "string" ? [[`${prefix}${key}`, value]] : [];
    });
}

/**
 * Every string value of every message catalog the gate covers.
 * @returns {MessageEntry[]}
 */
export function messageEntries() {
    return LOCALES.flatMap((locale) => {
        const directory = join(MESSAGES_ROOT, locale);
        return readdirSync(directory)
            .filter((file) => file.endsWith(".json"))
            .sort()
            .flatMap((file) => {
                const parsed = JSON.parse(readFileSync(join(directory, file), "utf8"));
                if (!isMessageTree(parsed)) {
                    throw new Error(`messages/${locale}/${file} is not a JSON object`);
                }
                return flattenEntries(parsed, "").map(([keyPath, value]) => ({
                    locale,
                    file,
                    namespace: keyPath.split(".")[0],
                    keyPath,
                    value,
                }));
            });
    });
}

/**
 * @typedef {object} Violation
 * @property {string} entry
 * @property {string} locale
 * @property {string} file
 * @property {string} keyPath
 * @property {string} term
 * @property {string} match
 * @property {string} source
 */

/**
 * Scans the message catalogs for banned terms, honouring the WS5 exclusions, the
 * compliance allowlist, and each term's scope and carve-outs.
 * @param {VocabularyModel} model
 * @returns {Violation[]}
 */
export function scanMessageCatalogs(model) {
    const compiled = model.terms.map((term) => ({ term, expression: new RegExp(term.pattern.source, term.pattern.flags) }));
    const violations = [];
    for (const entry of messageEntries()) {
        if (isExcludedSurface(entry.file, entry.namespace)) continue;
        if (isAllowedSurface(entry.file, entry.namespace)) continue;
        for (const { term, expression } of compiled) {
            if (term.locale !== entry.locale) continue;
            if (!scopeCovers(term.scope, entry.file, entry.namespace)) continue;
            if (matchesAnySurface(term.allowFiles, entry.file, entry.namespace)) continue;
            const match = expression.exec(entry.value);
            if (!match) continue;
            violations.push({
                entry: `${entry.locale}/${entry.file}:${entry.keyPath}`,
                locale: entry.locale,
                file: entry.file,
                keyPath: entry.keyPath,
                term: term.term,
                match: match[0],
                source: term.sources.join("; "),
            });
        }
    }
    return violations;
}

/**
 * Formats a violation for a gate failure, naming the file, key path, matched term, and
 * the §4 row it came from.
 * @param {Violation} violation
 * @returns {string}
 */
export function describeViolation(violation) {
    return `${violation.locale}/${violation.file} → ${violation.keyPath}: "${violation.match}" is banned as "${violation.term}" by docs/PRODUCT.md ${violation.source}`;
}

/**
 * The baseline entries a scan produces, deduplicated and sorted for a stable diff.
 * @param {Violation[]} violations
 * @returns {string[]}
 */
export function baselineEntries(violations) {
    return [...new Set(violations.map((violation) => violation.entry))].sort();
}

/**
 * Reads a `<locale>/<file>:<key path>` baseline entry back into its message surface.
 * @param {string} entry
 * @returns {{locale: string, file: string, namespace: string, keyPath: string}}
 */
export function parseBaselineEntry(entry) {
    const separator = entry.indexOf(":");
    const [locale, file] = entry.slice(0, separator).split("/");
    const keyPath = entry.slice(separator + 1);
    if (!locale || !file || !keyPath) {
        throw new Error(`"${entry}" is not a <locale>/<file>:<key path> baseline entry`);
    }
    return { locale, file, namespace: keyPath.split(".")[0], keyPath };
}
