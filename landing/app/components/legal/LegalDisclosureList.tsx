import LegalPageShell from "./LegalPageShell";

/**
 * A single labeled row of a statutory disclosure: a term (the field name, e.g.
 * "事業者名") and its description (the value). `description` is plain text; blank
 * lines separate paragraphs.
 */
export type LegalDisclosureRow = {
    id: string;
    term: string;
    description: string;
};

/**
 * Props for {@link LegalDisclosureList}. All strings are expected to be already
 * localized by the calling server component.
 */
export type LegalDisclosureListProps = {
    title: string;
    updated: string;
    lede: string;
    notice: string;
    rows: LegalDisclosureRow[];
};

function renderValue(description: string, keyPrefix: string) {
    return description.split("\n\n").map((paragraph, index) => (
        <p key={`${keyPrefix}-${index}`} className={index === 0 ? undefined : "mt-2"}>
            {paragraph}
        </p>
    ));
}

/**
 * Presentational shell for a statutory disclosure expressed as labeled
 * term/value rows (e.g. 特定商取引法に基づく表記), inside the shared
 * {@link LegalPageShell}. Renders a semantic definition list as a hairline
 * ledger: the label sits quiet beside its value, stacking on narrow screens.
 * Server component — no motion.
 */
export default function LegalDisclosureList({ title, updated, lede, notice, rows }: LegalDisclosureListProps) {
    return (
        <LegalPageShell title={title} updated={updated} lede={lede} notice={notice}>
            <dl className="mt-12 divide-y divide-border border-y border-border">
                {rows.map((row) => (
                    <div key={row.id} className="grid gap-1.5 py-5 sm:grid-cols-[minmax(0,12rem)_1fr] sm:gap-8">
                        <dt className="text-sm font-medium text-muted-foreground text-pretty">{row.term}</dt>
                        <dd className="leading-relaxed text-foreground text-pretty">
                            {renderValue(row.description, row.id)}
                        </dd>
                    </div>
                ))}
            </dl>
        </LegalPageShell>
    );
}
