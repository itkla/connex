import { InformationCircleIcon } from "@heroicons/react/24/outline";
import type { ReactNode } from "react";

/**
 * Props for {@link LegalPageShell}. All strings are expected to be already
 * localized by the calling server component.
 */
export type LegalPageShellProps = {
    title: string;
    updated: string;
    lede: string;
    notice: string;
    children: ReactNode;
};

/**
 * Shared chrome for long-form public legal / disclosure pages: a draft notice,
 * a serif title with "last updated" metadata, a lede, and a readable measure
 * for whatever body the page renders as {@link LegalPageShellProps.children}.
 * Server component — no motion, since these pages are informational and rarely
 * visited. Keep the visual language identical across every legal page.
 */
export default function LegalPageShell({ title, updated, lede, notice, children }: LegalPageShellProps) {
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

            {children}
        </section>
    );
}
