"use client";

import { cn } from "@/lib/utils";

/**
 * The semantic states every diagnostics readout collapses to. Keeping one vocabulary across
 * capabilities, providers, job runs, and DNS results means an operator learns the colour once.
 */
export type DiagnosticTone = "ok" | "warn" | "bad" | "neutral";

const TONE_CLASS: Record<DiagnosticTone, string> = {
    ok: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
    warn: "bg-amber-500/10 text-amber-700 dark:text-amber-400",
    bad: "bg-destructive/10 text-destructive",
    neutral: "bg-muted text-muted-foreground",
};

/**
 * A compact, non-interactive state chip. The dot carries the tone redundantly with the label so
 * the readout never depends on colour alone.
 */
export function StatusPill({
    tone,
    label,
    className,
}: {
    tone: DiagnosticTone;
    label: string;
    className?: string;
}) {
    return (
        <span
            className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap",
                TONE_CLASS[tone],
                className,
            )}
        >
            <span aria-hidden className="size-1.5 rounded-full bg-current opacity-70" />
            {label}
        </span>
    );
}
