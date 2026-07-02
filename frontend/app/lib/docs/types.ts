/**
 * Typed content vocabulary for the docs reader.
 *
 * Article prose lives in the `docs` i18n namespace as arrays of these blocks,
 * authored per locale and read back through {@link ./read}. The renderer
 * ({@link ../../components/docs/DocBlocks}) switches on the discriminated
 * `type` field, so every block shape is validated at the i18n boundary before
 * it reaches a component.
 */

/** A single feature entry inside a {@link FeatureListBlock}. */
export type FeatureEntry = {
    title: string;
    description: string;
};

/** An ordered step inside a {@link StepsBlock}. */
export type StepEntry = {
    title: string;
    description: string;
};

/** A keyboard shortcut row inside a {@link ShortcutsBlock}. */
export type ShortcutEntry = {
    keys: string;
    action: string;
};

/** A question/answer pair inside a {@link FaqBlock}. */
export type FaqEntry = {
    question: string;
    answer: string;
};

/** A linkable card inside a {@link CardsBlock}. */
export type CardEntry = {
    title: string;
    description: string;
    href?: string;
};

/** The four temperature bands rendered by a {@link WarmthLegendBlock}. */
export type WarmthBand = "hot" | "warm" | "cool" | "cold";

/** A single band row inside a {@link WarmthLegendBlock}. */
export type WarmthEntry = {
    band: WarmthBand;
    label: string;
    description: string;
};

/** One or more body paragraphs. */
export type ProseBlock = {
    type: "prose";
    text: string | string[];
};

/** A section heading that becomes an anchor and an "on this page" entry. */
export type HeadingBlock = {
    type: "heading";
    text: string;
    level?: 2 | 3;
};

/** A scannable list of features, the workhorse for cataloging an area. */
export type FeatureListBlock = {
    type: "featureList";
    title?: string;
    columns?: 1 | 2;
    items: FeatureEntry[];
};

/** A highlighted note, tip, warning, or quirk callout. */
export type CalloutBlock = {
    type: "callout";
    variant: "note" | "tip" | "warning" | "quirk";
    title?: string;
    body: string | string[];
};

/** An ordered how-to sequence. */
export type StepsBlock = {
    type: "steps";
    title?: string;
    items: StepEntry[];
};

/** A keyboard shortcut reference table. */
export type ShortcutsBlock = {
    type: "shortcuts";
    title?: string;
    items: ShortcutEntry[];
};

/** A collapsible list of frequently asked questions. */
export type FaqBlock = {
    type: "faq";
    title?: string;
    items: FaqEntry[];
};

/** A grid of linkable cards, used for cross-links and category landings. */
export type CardsBlock = {
    type: "cards";
    title?: string;
    items: CardEntry[];
};

/** The relationship-intelligence warmth scale, rendered with domain tokens. */
export type WarmthLegendBlock = {
    type: "warmthLegend";
    title?: string;
    items: WarmthEntry[];
};

/** A generic data table. */
export type TableBlock = {
    type: "table";
    title?: string;
    columns: string[];
    rows: string[][];
};

/** Any renderable docs content block. */
export type DocBlock =
    | ProseBlock
    | HeadingBlock
    | FeatureListBlock
    | CalloutBlock
    | StepsBlock
    | ShortcutsBlock
    | FaqBlock
    | CardsBlock
    | WarmthLegendBlock
    | TableBlock;

/** The set of literal `type` discriminants a {@link DocBlock} can carry. */
export const DOC_BLOCK_TYPES: ReadonlySet<DocBlock["type"]> = new Set([
    "prose",
    "heading",
    "featureList",
    "callout",
    "steps",
    "shortcuts",
    "faq",
    "cards",
    "warmthLegend",
    "table",
]);
