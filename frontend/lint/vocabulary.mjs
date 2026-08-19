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
const HAS_CODE_SPAN = /`[^`]+`/;
const QUALIFIER_WORD = /\s(?:as|except|alone|when|only|unless)\s/;
const CJK_CHARACTER = /[぀-ヿ㐀-䶿一-鿿ｦ-ﾟ]/u;
const JAPANESE_MIN_OVERLAP = 2;

/** Locales whose message catalogs the gate scans. */
export const LOCALES = ["en", "ja"];

/**
 * The compliance surfaces §4 names: the organization data-requests admin tooling and the
 * public legal pages. §4 allows the statutory register there **only where noted**, so
 * these surfaces are not exempt wholesale — a term is skipped here only when its own §4
 * entry carries the carve-out, which the model records in `allowFiles`. Every other
 * banned term stays active on these surfaces.
 */
export const ALLOWED_SURFACES = ["legal.json", "organization.json#OrgDataRequests"];

/**
 * The workflow seam: every namespace that names the automation object. §4's automation row
 * governs these surfaces, so `rule` — and its Japanese rendering — is banned here as the
 * name of that object. §4's one sanctioned use of the word lives on the legacy-automations
 * migration screen, which is not one of these namespaces and whose sanctioned wording
 * ("legacy automations") carries no banned term of its own.
 */
export const WORKFLOW_SURFACES = [
    "workflow-operations.json",
    "workspace.json#WorkflowAuthoring",
    "workspace.json#WorkflowsLayout",
    "workspace.json#WorkspaceWorkflows",
];

/** Namespace files that carry note surfaces, where JA must say メモ and never ノート. */
const NOTE_SURFACES = [
    "account.json",
    "actions.json",
    "activity.json",
    "dashboard.json",
    "docs.json",
    "notifications.json",
    "records.json",
];

/**
 * Namespace files that carry marketing-delivery surfaces, where JA must not say 抑制.
 * The public docs describe the same delivery behaviour, so they carry the term too.
 */
const MARKETING_SURFACES = ["campaigns.json", "docs.json", "unsubscribe.json"];

/**
 * Surfaces where the assistant speaks about its own replies, and where §4 replaces
 * "turn" with "answer". The strings live in the shared and organization catalogs as well
 * as the assistant's own namespace.
 */
const ASSISTANT_SURFACES = ["assistant.json", "common.json#AskConnex", "organization.json#OrgAi"];

/** Surfaces where "Relationship Radar" would be the everyday label rather than prose. */
const RADAR_LABEL_SURFACES = ["actions.json", "radar.json"];

/**
 * @typedef {"en" | "ja"} TermLocale
 */

/**
 * A message surface: a namespace file (`"contacts.json"`) or one next-intl namespace
 * inside a file (`"workspace.json#WorkspaceWorkflows"`).
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
 * @property {boolean} narrowed
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
        decision: "ban",
        scope: { namespaces: RADAR_LABEL_SURFACES },
        reason: "§4 sanctions the full name only in onboarding and marketing prose, so the ban narrows to the Radar surfaces themselves, where any use is the everyday label.",
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
        decision: "ban",
        pattern: { source: "アーカイブ済みアカウント", flags: "u" },
        reason: "アカウント is the canonical Japanese word for a sign-in account, so the ban narrows to the archived-company label, where it names the Company record.",
    },
    "People (as a label for contact lists)": {
        decision: "ban",
        pattern: { source: "^People$", flags: "u" },
        reason: "§4 sanctions person/people inside explanatory prose, so the ban narrows to a value that is nothing but the word — a label.",
    },
    "opportunity as a countable UI noun (intro suggestions are \"suggested intros\")": {
        decision: "ban",
        pattern: { source: "\\bOpportunit(?:y|ies)\\b", flags: "u" },
        reason: "banned as a countable UI noun, so the ban narrows to the capitalised label form and leaves lower-case prose alone.",
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
        decision: "ban",
        scope: { namespaces: WORKFLOW_SURFACES },
        reason: "§4 bans rule as the name of the automation object, so the ban covers the workflow namespaces where every use names it; elsewhere rule keeps its non-automation senses such as retention rules, and the sanctioned migration-screen wording is \"legacy automations\", which carries no banned term.",
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
    "to-do": {
        decision: "skip",
        reason: "\"To do\" is the sanctioned kanban column name; standalone-noun usage needs grammatical analysis.",
    },
    "todo as a standalone noun": {
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
        reason: "banned as a repetition across surfaces rather than as a value, so the ratchet over AT_A_GLANCE_SURFACES enforces it instead of a pattern.",
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
        scope: { namespaces: ASSISTANT_SURFACES },
        reason: "§4 bans turn in the assistant, where the word for a reply is \"answer\"; elsewhere turn is an ordinary English verb.",
    },
    "Assigned to": {
        decision: "ban",
        pattern: { source: "\\bAssigned\\s+to\\b", flags: "u" },
        reason: "§4's Never say governs labels while prose may vary, so the ban narrows to the capitalised label form and leaves \"assigned to you\" alone.",
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
 * must be an §4 item, so the rendering stays attributable to the glossary. A rendering may
 * narrow its pattern where the katakana word also carries an ordinary product sense, and
 * may narrow its scope where the English item it renders is itself scoped.
 * @type {Record<string, {term: string, reason: string, pattern?: SerializedPattern, scope?: TermScope}>}
 */
export const JAPANESE_RENDERINGS = {
    deterministic: {
        term: "決定論的",
        reason: "#1323 gate 1 requires the JA pattern for the term §4 bans in English; §5 requires JA to be authored natively rather than left untranslated.",
    },
    slug: {
        term: "スラッグ",
        reason: "the JA catalogs carry the banned engineering term as a katakana loan, which §5 rules out in favour of a native phrasing.",
    },
    node: {
        term: "ノード",
        reason: "the JA catalogs carry the banned engineering term as a katakana loan; it is unrelated to the separate ノート note ban.",
    },
    graph: {
        term: "グラフ",
        pattern: { source: "(?:リレーションシップ|関係性?)の?グラフ|グラフ表示", flags: "u" },
        reason: "グラフ is also the ordinary Japanese word for a chart, which §4 sanctions, so the ban narrows to the relationship-graph sense the English term carries.",
    },
    "correlation ID": {
        term: "相関ID",
        reason: "§4 replaces the concept with 参照コード, so the JA rendering of the banned term is banned with it.",
    },
    "token budget": {
        term: "トークン予算",
        reason: "§4 bans the LLM-internals term, and the JA catalogs state it directly rather than in human terms.",
    },
    "rule (except as \"legacy automations\" inside the one migration screen while it exists)": {
        term: "ルール",
        scope: { namespaces: WORKFLOW_SURFACES },
        reason: "ルール is the ordinary Japanese word for a rule in any sense, so the ban follows the English item onto the workflow namespaces, where it can only name the automation object.",
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
        .map((line) => {
            const cells = tableCells(line);
            if (cells.length !== 4) {
                throw new Error(`docs/PRODUCT.md §4 has a glossary row with ${cells.length} cells instead of 4: ${line}`);
            }
            return cells;
        });
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
    const parsed = [];
    for (const item of items) {
        for (const part of item.includes("(") ? [item] : item.split(/\s+\/\s+/)) {
            const trimmed = part.trim();
            if (trimmed.length > 0) parsed.push(trimmed);
        }
    }
    return parsed;
}

/**
 * Reads the "Banned on all product surfaces" block of §4. The block is every line after
 * its heading that names a term in backticks, however many lines it runs to and whether
 * or not a line carries a `·` separator; it ends at the first prose line that names no
 * term. A backticked term stated after the block would never be read, so it fails closed
 * rather than being dropped.
 * @param {string} section
 * @returns {{text: string, terms: string[], qualified: boolean}[]}
 */
export function bannedListEntries(section) {
    const lines = section.split("\n").map((line) => line.trim());
    const marker = lines.findIndex((line) => line.startsWith(BANNED_LIST_MARKER));
    if (marker < 0) {
        throw new Error(`docs/PRODUCT.md §4 no longer contains the "${BANNED_LIST_MARKER}" list`);
    }
    const block = [];
    let index = marker + 1;
    for (; index < lines.length; index += 1) {
        if (lines[index].length === 0) continue;
        if (!HAS_CODE_SPAN.test(lines[index])) break;
        block.push(lines[index]);
    }
    if (block.length === 0) {
        throw new Error("docs/PRODUCT.md §4 no longer states any banned term in backticks below its heading");
    }
    for (let after = index; after < lines.length; after += 1) {
        if (HAS_CODE_SPAN.test(lines[after])) {
            throw new Error(
                `docs/PRODUCT.md §4 names a term in backticks below the banned-terms list, where the generator cannot read it: ${lines[after]}`,
            );
        }
    }
    const entries = [];
    for (const line of block) {
        for (const chunk of line.split("·")) {
            const text = chunk.trim();
            const terms = [...text.matchAll(CODE_SPAN)].map((match) => match[1]);
            if (terms.length === 0) {
                throw new Error(`docs/PRODUCT.md §4 lists "${text}" as banned without naming a term in backticks`);
            }
            const outside = text.replace(CODE_SPAN, " ").replace(/[\s/]+/g, " ").trim();
            entries.push({ text, terms, qualified: outside.length > 0 });
        }
    }
    return entries;
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
 * The regular English forms of a word. §4 bans a term, not one spelling of it, so the
 * generated pattern accepts the term's inflections — "Purging" and "purged" are the same
 * violation as "purge". Stems that are ordinary words on their own ("project" behind
 * "projection", "admit" behind "admitted") are never accepted bare.
 * @param {string} word
 * @returns {string[]}
 */
export function inflections(word) {
    const forms = new Set([word]);
    const lower = word.toLowerCase();
    const sibilant = /(?:s|x|z|ch|sh)$/;
    if (lower.endsWith("e")) {
        forms.add(`${word}s`).add(`${word}d`).add(`${word.slice(0, -1)}ing`);
    } else if (/[^aeiou]y$/.test(lower)) {
        forms.add(`${word.slice(0, -1)}ies`);
    } else if (lower.endsWith("ion")) {
        const stem = word.slice(0, -3);
        forms.add(`${word}s`).add(`${stem}es`).add(`${stem}ed`).add(`${stem}ing`);
    } else if (/([bdglmnprt])\1ed$/.test(lower)) {
        const stem = word.slice(0, -3);
        forms.add(`${stem}s`).add(`${stem}${stem.slice(-1)}ing`);
    } else if (lower.endsWith("ed")) {
        const stem = word.slice(0, -2);
        forms.add(`${stem}s`).add(`${stem}ing`);
        if (sibilant.test(stem.toLowerCase())) forms.add(`${stem}es`);
    } else {
        forms.add(`${word}s`).add(`${word}es`).add(`${word}ed`).add(`${word}ing`);
        if (/[aeiou][bdglmnprt]$/.test(lower)) {
            forms.add(`${word}${word.slice(-1)}ed`).add(`${word}${word.slice(-1)}ing`);
        }
    }
    return [...forms].sort((left, right) => right.length - left.length || (left < right ? -1 : 1));
}

/**
 * Builds the pattern for an English term: word-bounded, case-insensitive, and matching
 * the inflections of its last word. Word boundaries already stop an English term from
 * matching inside a longer canonical term, so no canonical exception is needed.
 * @param {string} term
 * @returns {{pattern: SerializedPattern, canonicalExceptions: string[]}}
 */
function englishPattern(term) {
    const words = term.split(/\s+/);
    const lead = words.slice(0, -1).map(escapeRegExp).join("\\s+");
    const forms = inflections(words[words.length - 1]).map(escapeRegExp);
    const tail = forms.length > 1 ? `(?:${forms.join("|")})` : forms[0];
    return {
        pattern: { source: `\\b${lead.length > 0 ? `${lead}\\s+` : ""}${tail}\\b`, flags: "iu" },
        canonicalExceptions: [],
    };
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
            narrowed: curated?.pattern !== undefined,
            pattern: built.pattern,
            sources: [item.source],
        });
    }

    const itemsByText = new Map(items.map((item) => [item.text, item]));
    for (const [text, rendering] of Object.entries(JAPANESE_RENDERINGS)) {
        const source = itemsByText.get(text);
        if (!source) {
            throw new Error(
                `JAPANESE_RENDERINGS in frontend/lint/vocabulary.mjs renders "${text}", which docs/PRODUCT.md §4 no longer bans.`,
            );
        }
        const id = `ja:${rendering.term}`;
        const built = rendering.pattern
            ? { pattern: rendering.pattern, canonicalExceptions: [] }
            : japanesePattern(rendering.term, canonical.ja);
        terms.set(id, {
            id,
            term: rendering.term,
            locale: "ja",
            scope: rendering.scope ?? "global",
            allowFiles: [],
            canonicalExceptions: built.canonicalExceptions,
            narrowed: rendering.pattern !== undefined,
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
 * The largest the baseline may ever be. Fixing copy lowers it; nothing else may raise
 * it, so the WS3 burndown cannot be undone by re-baselining. Widening the rules — a new
 * §4 ban, a wider inflection, a newly scanned surface — legitimately surfaces violations
 * that were always there, and may raise this number **in the same commit that widens
 * them**, never on its own.
 */
export const BASELINE_HIGH_WATER_MARK = 384;

/**
 * The surfaces that still say "at a glance". §4 allows the phrase on one surface only,
 * and that is a count across files rather than something a pattern can see in a value,
 * so this list ratchets the same way the baseline does: entries leave it when the copy
 * is rewritten, and none may be added.
 */
export const AT_A_GLANCE_SURFACES = [
    "en/analytics.json:AnalyticsLayout.description",
    "en/analytics.json:AnalyticsPage.overviewTitle",
    "en/analytics.json:AnalyticsPage.subtitle",
    "en/calendar.json:CalendarLayout.description",
    "en/campaigns.json:CampaignDetail.glanceTitle",
    "en/dashboard.json:DashboardLayout.description",
    "en/docs.json:DocsActivity.articles.calendar-events.blocks[0].text",
    "en/docs.json:DocsActivity.articles.calendar-events.blocks[1].items[0].description",
    "en/docs.json:DocsActivity.articles.tasks.blocks[3].items[2].title",
    "en/docs.json:DocsDashboard.articles.home-dashboard.blocks[1].caption",
    "en/docs.json:DocsDashboard.articles.home-dashboard.blocks[2].title",
    "en/docs.json:DocsData.articles.filters-and-bulk.blocks[1].items[3].description",
    "en/docs.json:DocsLibrary.articles.files.blocks[2].items[5].title",
    "en/docs.json:DocsLibrary.articles.tags.blocks[1].items[0].description",
    "en/docs.json:DocsOverviewSuite.articles.analytics.blocks[0].text",
    "en/docs.json:DocsOverviewSuite.articles.analytics.blocks[3].items[3].description",
    "en/docs.json:DocsOverviewSuite.articles.introductions.blocks[1].items[1].description",
    "en/docs.json:DocsOverviewSuite.articles.relationship-map.blocks[4].items[0].title",
    "en/docs.json:DocsPreferences.articles.tips-and-quirks.blocks[1].items[3].description",
    "en/docs.json:DocsRecords.articles.deals-and-pipelines.blocks[2].items[3].title",
    "en/docs.json:DocsRecords.articles.deals-and-pipelines.blocks[5].text",
    "en/docs.json:DocsRecords.articles.deals-and-pipelines.blocks[6].items[1].description",
    "en/docs.json:DocsRecords.articles.table-and-grid.blocks[2].items[2].description",
    "en/docs.json:DocsRelationshipIntelligence.articles.warmth-and-temperature.blocks[0].text",
    "en/docs.json:DocsSettings.articles.audit-logs.blocks[5].title",
    "en/docs.json:DocsTutorials.articles.add-your-first-company.blocks[1].caption",
    "en/docs.json:DocsTutorials.articles.build-your-pipeline.blocks[3].items[0].description",
    "en/docs.json:DocsTutorials.articles.request-a-warm-intro.blocks[2].text",
    "en/me.json:MeLayout.description",
];

/**
 * The message entries that still carry the phrase §4 restricts to one surface.
 * @returns {string[]}
 */
export function atAGlanceEntries() {
    const found = [];
    for (const entry of messageEntries()) {
        if (entry.locale !== "en") continue;
        if (!/at a glance/i.test(entry.value)) continue;
        found.push(`${entry.locale}/${entry.file}:${entry.keyPath}`);
    }
    return found.sort();
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
 * Whether a surface belongs to the workflow seam, where §4's automation row governs the
 * word the seam is named after.
 * @param {string} file
 * @param {string} namespace
 * @returns {boolean}
 */
export function isWorkflowSurface(file, namespace) {
    return matchesAnySurface(WORKFLOW_SURFACES, file, namespace);
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
 * Whether a banned term is scanned against a message entry. A Japanese pattern runs against the
 * Japanese catalog only, while a Latin-script term is scanned in both — Japanese copy states
 * terms such as `ESP` and `RBAC` verbatim. The term's own scope and the §4 compliance carve-outs
 * it records in `allowFiles` narrow it from there.
 * @param {BannedTerm} term
 * @param {{locale: string, file: string, namespace: string}} entry
 * @returns {boolean}
 */
export function termApplies(term, entry) {
    if (term.locale === "ja" && entry.locale !== "ja") return false;
    if (!scopeCovers(term.scope, entry.file, entry.namespace)) return false;
    return !matchesAnySurface(term.allowFiles, entry.file, entry.namespace);
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
 * Flattens a message catalog to its string leaves. Arrays are message content too — the
 * public docs read theirs through `t.raw()` and render them as prose — so an array item
 * is keyed `${keyPath}[${index}]` and scanned like any other value.
 * @param {unknown} value
 * @param {string} keyPath
 * @returns {[string, string][]}
 */
function flattenEntries(value, keyPath) {
    if (typeof value === "string") return [[keyPath, value]];
    if (Array.isArray(value)) return value.flatMap((item, index) => flattenEntries(item, `${keyPath}[${index}]`));
    if (isMessageTree(value)) {
        return Object.entries(value).flatMap(([key, child]) =>
            flattenEntries(child, keyPath.length > 0 ? `${keyPath}.${key}` : key));
    }
    return [];
}

/**
 * The next-intl namespace a key path belongs to.
 * @param {string} keyPath
 * @returns {string}
 */
export function namespaceOf(keyPath) {
    return keyPath.split(".")[0].split("[")[0];
}

/**
 * Every string value of every message catalog the gate covers, including the strings
 * inside arrays.
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
                    namespace: namespaceOf(keyPath),
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
 * Scans every message catalog for banned terms, honouring each term's scope and the §4
 * compliance carve-outs it records in `allowFiles`. English patterns run against both
 * catalogs — a Latin term such as `ESP` or `RBAC` appears verbatim in Japanese copy —
 * while Japanese patterns run against the Japanese catalog only.
 * @param {VocabularyModel} model
 * @returns {Violation[]}
 */
export function scanMessageCatalogs(model) {
    const compiled = model.terms.map((term) => ({ term, expression: new RegExp(term.pattern.source, term.pattern.flags) }));
    const violations = [];
    for (const entry of messageEntries()) {
        for (const { term, expression } of compiled) {
            if (!termApplies(term, entry)) continue;
            const match = expression.exec(entry.value);
            if (!match) continue;
            violations.push({
                entry: `${entry.locale}/${entry.file}:${entry.keyPath}#${term.term}`,
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
 * Reads a `<locale>/<file>:<key path>#<term>` baseline entry back into the message
 * surface and the term it was flagged for. The term is part of the key so that adding a
 * second banned term to a string that is already awaiting a rewrite still fails the gate.
 * @param {string} entry
 * @returns {{locale: string, file: string, namespace: string, keyPath: string, term: string}}
 */
export function parseBaselineEntry(entry) {
    const separator = entry.indexOf(":");
    const marker = entry.indexOf("#", separator);
    const [locale, file] = entry.slice(0, separator).split("/");
    const keyPath = entry.slice(separator + 1, marker < 0 ? undefined : marker);
    const term = marker < 0 ? "" : entry.slice(marker + 1);
    if (!locale || !file || !keyPath || !term) {
        throw new Error(`"${entry}" is not a <locale>/<file>:<key path>#<term> baseline entry`);
    }
    return { locale, file, namespace: namespaceOf(keyPath), keyPath, term };
}
