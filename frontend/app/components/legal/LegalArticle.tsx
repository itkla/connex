import { InformationCircleIcon } from "@heroicons/react/24/outline";

/**
 * A single titled section of a legal document. `body` is plain text; blank
 * lines separate paragraphs, and blocks whose lines all begin with "- " render
 * as bullet lists.
 */
export type LegalSection = {
    id: string;
    heading: string;
    body: string;
};

/**
 * Props for {@link LegalArticle}. All strings are expected to be already
 * localized by the calling server component.
 */
export type LegalArticleProps = {
    title: string;
    updated: string;
    lede: string;
    notice: string;
    tocLabel: string;
    sections: LegalSection[];
};

function renderBody(body: string, keyPrefix: string) {
    return body.split("\n\n").map((block, blockIndex) => {
        const lines = block.split("\n");
        const isList = lines.every((line) => line.startsWith("- "));
        if (isList) {
            return (
                <ul
                    key={`${keyPrefix}-${blockIndex}`}
                    className="mt-4 flex list-disc flex-col gap-2 pl-5 text-muted-foreground marker:text-border"
                >
                    {lines.map((line, lineIndex) => (
                        <li key={`${keyPrefix}-${blockIndex}-${lineIndex}`} className="leading-relaxed text-pretty">
                            {line.slice(2)}
                        </li>
                    ))}
                </ul>
            );
        }
        return (
            <p key={`${keyPrefix}-${blockIndex}`} className="mt-4 leading-relaxed text-muted-foreground text-pretty">
                {block}
            </p>
        );
    });
}

/**
 * Presentational shell for long-form legal / disclosure pages. Renders a draft
 * notice, a title with "last updated" metadata, an anchored table of contents,
 * and each section at a readable measure. Server component — no motion, since
 * these pages are informational and rarely visited.
 */
export default function LegalArticle({ title, updated, lede, notice, tocLabel, sections }: LegalArticleProps) {
    return (
        <section className="mx-auto max-w-3xl px-6 py-16 lg:px-8 lg:py-24">
            <div
                role="note"
                className="flex items-start gap-3 rounded-2xl border border-border bg-muted/60 px-4 py-3.5 text-sm leading-relaxed text-muted-foreground"
            >
                <InformationCircleIcon className="mt-0.5 size-5 shrink-0 text-brand-dark" aria-hidden="true" />
                <span className="text-pretty">{notice}</span>
            </div>

            <header className="mt-10">
                <h1 className="font-display text-[clamp(2.25rem,5vw,3.25rem)] leading-[1.1] tracking-[-0.01em] text-balance text-foreground">
                    {title}
                </h1>
                <p className="mt-4 text-sm text-muted-foreground">{updated}</p>
                <p className="mt-6 text-lg leading-relaxed text-foreground text-pretty">{lede}</p>
            </header>

            {sections.length > 3 && (
                <nav aria-label={tocLabel} className="mt-12 border-t border-border pt-8">
                    <p className="text-xs font-semibold uppercase tracking-[0.08em] text-muted-foreground">{tocLabel}</p>
                    <ol className="mt-4 flex flex-col gap-2 text-[15px]">
                        {sections.map((section, index) => (
                            <li key={section.id}>
                                <a
                                    href={`#${section.id}`}
                                    className="inline-flex gap-3 text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
                                >
                                    <span className="tabular-nums text-brand-dark">
                                        {String(index + 1).padStart(2, "0")}
                                    </span>
                                    {section.heading}
                                </a>
                            </li>
                        ))}
                    </ol>
                </nav>
            )}

            <div className="mt-12 flex flex-col gap-12">
                {sections.map((section) => (
                    <section key={section.id} id={section.id} className="scroll-mt-24">
                        <h2 className="font-display text-2xl leading-snug tracking-[-0.01em] text-balance text-foreground">
                            {section.heading}
                        </h2>
                        {renderBody(section.body, section.id)}
                    </section>
                ))}
            </div>
        </section>
    );
}
