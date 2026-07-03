import Link from "next/link";
import {
    ArrowRightIcon,
    ExclamationTriangleIcon,
    InformationCircleIcon,
    LightBulbIcon,
    SparklesIcon,
} from "@heroicons/react/24/outline";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { headingId } from "@/app/lib/docs/headings";
import { ILLUSTRATIONS } from "@/app/components/docs/illustrations";
import type {
    CalloutBlock,
    CardsBlock,
    DocBlock,
    FaqBlock,
    FeatureListBlock,
    IllustrationBlock,
    ProseBlock,
    ShortcutsBlock,
    StepsBlock,
    TableBlock,
    WarmthBand,
    WarmthLegendBlock,
} from "@/app/lib/docs/types";

const CALLOUT_STYLES: Record<
    CalloutBlock["variant"],
    { container: string; icon: string; title: string; Icon: React.ComponentType<{ className?: string }> }
> = {
    note: {
        container: "border-border bg-muted/50",
        icon: "text-muted-foreground",
        title: "text-foreground",
        Icon: InformationCircleIcon,
    },
    tip: {
        container: "border-brand/30 bg-brand-light",
        icon: "text-brand-dark",
        title: "text-brand-dark",
        Icon: LightBulbIcon,
    },
    warning: {
        container: "border-destructive/30 bg-destructive/10",
        icon: "text-destructive",
        title: "text-destructive",
        Icon: ExclamationTriangleIcon,
    },
    quirk: {
        container: "border-border bg-card",
        icon: "text-brand-dark",
        title: "text-foreground",
        Icon: SparklesIcon,
    },
};

const WARMTH_CLASS: Record<WarmthBand, string> = {
    hot: "bg-warmth-hot",
    warm: "bg-warmth-warm",
    cool: "bg-warmth-cool",
    cold: "bg-warmth-cold",
};

function toParagraphs(text: string | string[]): string[] {
    return Array.isArray(text) ? text : [text];
}

function BlockTitle({ children }: { children: React.ReactNode }) {
    return (
        <p className="mb-3 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
            {children}
        </p>
    );
}

function Prose({ block }: { block: ProseBlock }) {
    return (
        <div className="space-y-4">
            {toParagraphs(block.text).map((paragraph, index) => (
                <p key={index} className="text-[15px] leading-7 text-foreground/80">
                    {paragraph}
                </p>
            ))}
        </div>
    );
}

function FeatureList({ block }: { block: FeatureListBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <dl className={`grid gap-x-8 gap-y-5 ${block.columns === 1 ? "" : "sm:grid-cols-2"}`}>
                {block.items.map((item) => (
                    <div key={item.title} className="flex gap-3">
                        <span
                            className="mt-2 size-1.5 shrink-0 rounded-full bg-brand"
                            aria-hidden="true"
                        />
                        <div>
                            <dt className="text-sm font-medium text-foreground">{item.title}</dt>
                            <dd className="mt-1 text-sm leading-6 text-muted-foreground">
                                {item.description}
                            </dd>
                        </div>
                    </div>
                ))}
            </dl>
        </div>
    );
}

function Callout({ block }: { block: CalloutBlock }) {
    const style = CALLOUT_STYLES[block.variant];
    const Icon = style.Icon;
    return (
        <div className={`flex gap-3 rounded-xl border p-4 ${style.container}`}>
            <Icon className={`mt-0.5 size-5 shrink-0 ${style.icon}`} />
            <div className="min-w-0 space-y-1.5">
                {block.title ? (
                    <p className={`text-sm font-semibold ${style.title}`}>{block.title}</p>
                ) : null}
                {toParagraphs(block.body).map((paragraph, index) => (
                    <p key={index} className="text-sm leading-6 text-foreground/80">
                        {paragraph}
                    </p>
                ))}
            </div>
        </div>
    );
}

function Steps({ block }: { block: StepsBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <ol className="space-y-4">
                {block.items.map((item, index) => (
                    <li key={item.title} className="flex gap-4">
                        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-brand-light text-sm font-semibold tabular-nums text-brand-dark">
                            {index + 1}
                        </span>
                        <div className="pt-0.5">
                            <p className="text-sm font-medium text-foreground">{item.title}</p>
                            <p className="mt-1 text-sm leading-6 text-muted-foreground">
                                {item.description}
                            </p>
                        </div>
                    </li>
                ))}
            </ol>
        </div>
    );
}

function Shortcuts({ block }: { block: ShortcutsBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <div className="rounded-xl border border-border">
                {block.items.map((item) => (
                    <div
                        key={`${item.keys}-${item.action}`}
                        className="flex items-center justify-between gap-4 border-b border-border px-4 py-2.5 last:border-0"
                    >
                        <span className="text-sm text-muted-foreground">{item.action}</span>
                        <kbd className="rounded-md border border-border bg-muted px-2 py-0.5 font-mono text-xs text-foreground">
                            {item.keys}
                        </kbd>
                    </div>
                ))}
            </div>
        </div>
    );
}

function Faq({ block }: { block: FaqBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <Accordion type="single" collapsible className="rounded-xl border border-border px-4">
                {block.items.map((item, index) => (
                    <AccordionItem key={index} value={`faq-${index}`}>
                        <AccordionTrigger className="text-foreground">{item.question}</AccordionTrigger>
                        <AccordionContent className="text-muted-foreground">{item.answer}</AccordionContent>
                    </AccordionItem>
                ))}
            </Accordion>
        </div>
    );
}

function Cards({ block }: { block: CardsBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <div className="grid gap-4 sm:grid-cols-2">
                {block.items.map((item) => {
                    const inner = (
                        <>
                            <div className="flex items-start justify-between gap-3">
                                <p className="text-sm font-semibold text-foreground">{item.title}</p>
                                {item.href ? (
                                    <ArrowRightIcon className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5 group-hover:text-brand-dark motion-reduce:transition-none" />
                                ) : null}
                            </div>
                            <p className="mt-1.5 text-sm leading-6 text-muted-foreground">
                                {item.description}
                            </p>
                        </>
                    );
                    const className =
                        "group block rounded-xl border border-border bg-card p-5 transition-colors";
                    return item.href ? (
                        <Link
                            key={item.title}
                            href={item.href}
                            className={`${className} hover:border-brand/40 hover:bg-muted/40`}
                        >
                            {inner}
                        </Link>
                    ) : (
                        <div key={item.title} className={className}>
                            {inner}
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

function WarmthLegend({ block }: { block: WarmthLegendBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <dl className="grid gap-3 sm:grid-cols-2">
                {block.items.map((item) => (
                    <div
                        key={item.band}
                        className="flex gap-3 rounded-xl border border-border bg-card p-4"
                    >
                        <span
                            className={`mt-1 size-3 shrink-0 rounded-full ring-2 ring-inset ring-foreground/10 ${WARMTH_CLASS[item.band]}`}
                            aria-hidden="true"
                        />
                        <div>
                            <dt className="text-sm font-semibold text-foreground">{item.label}</dt>
                            <dd className="mt-1 text-sm leading-6 text-muted-foreground">
                                {item.description}
                            </dd>
                        </div>
                    </div>
                ))}
            </dl>
        </div>
    );
}

function DataTable({ block }: { block: TableBlock }) {
    return (
        <div>
            {block.title ? <BlockTitle>{block.title}</BlockTitle> : null}
            <div className="overflow-x-auto rounded-xl border border-border">
                <table className="w-full border-collapse text-left">
                    <thead>
                        <tr className="bg-muted">
                            {block.columns.map((column) => (
                                <th
                                    key={column}
                                    className="px-4 py-2.5 text-xs font-medium uppercase tracking-wide text-muted-foreground"
                                >
                                    {column}
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {block.rows.map((row, rowIndex) => (
                            <tr key={rowIndex} className="border-t border-border">
                                {row.map((cell, cellIndex) => (
                                    <td key={cellIndex} className="px-4 py-2.5 text-sm text-foreground/80">
                                        {cell}
                                    </td>
                                ))}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}

function Heading({ text, level, id }: { text: string; level: 2 | 3; id: string }) {
    if (level === 3) {
        return (
            <h3 id={id} className="group scroll-mt-24 text-lg font-semibold text-foreground">
                <a href={`#${id}`} className="inline-flex items-center gap-2 rounded outline-none focus-visible:ring-2 focus-visible:ring-brand">
                    {text}
                    <span className="text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 motion-reduce:transition-none">
                        #
                    </span>
                </a>
            </h3>
        );
    }
    return (
        <h2 id={id} className="group scroll-mt-24 font-display text-2xl text-foreground">
            <a href={`#${id}`} className="inline-flex items-center gap-2">
                {text}
                <span className="text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100">
                    #
                </span>
            </a>
        </h2>
    );
}

function Illustration({ block }: { block: IllustrationBlock }) {
    const Component = ILLUSTRATIONS[block.name];
    return (
        <figure>
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="flex items-center gap-1.5 border-b border-border bg-muted/40 px-4 py-2.5">
                    <span className="size-2 rounded-full bg-muted-foreground/30" aria-hidden="true" />
                    <span className="size-2 rounded-full bg-muted-foreground/30" aria-hidden="true" />
                    <span className="size-2 rounded-full bg-muted-foreground/30" aria-hidden="true" />
                </div>
                <div className="p-5 sm:p-6">
                    <Component />
                </div>
            </div>
            {block.caption ? (
                <figcaption className="mt-2.5 text-center text-xs text-muted-foreground">
                    {block.caption}
                </figcaption>
            ) : null}
        </figure>
    );
}

/**
 * Render a validated {@link DocBlock} array as the body of a docs article.
 * Switches on each block's discriminated `type`; heading anchors are computed
 * with the same {@link headingId} used by the "on this page" rail.
 */
export default function DocBlocks({ blocks }: { blocks: DocBlock[] }) {
    return (
        <div className="space-y-8">
            {blocks.map((block, index) => {
                switch (block.type) {
                    case "prose":
                        return <Prose key={index} block={block} />;
                    case "heading":
                        return (
                            <Heading
                                key={index}
                                text={block.text}
                                level={block.level ?? 2}
                                id={headingId(block.text, index)}
                            />
                        );
                    case "featureList":
                        return <FeatureList key={index} block={block} />;
                    case "callout":
                        return <Callout key={index} block={block} />;
                    case "steps":
                        return <Steps key={index} block={block} />;
                    case "shortcuts":
                        return <Shortcuts key={index} block={block} />;
                    case "faq":
                        return <Faq key={index} block={block} />;
                    case "cards":
                        return <Cards key={index} block={block} />;
                    case "warmthLegend":
                        return <WarmthLegend key={index} block={block} />;
                    case "table":
                        return <DataTable key={index} block={block} />;
                    case "illustration":
                        return <Illustration key={index} block={block} />;
                    default:
                        return null;
                }
            })}
        </div>
    );
}
