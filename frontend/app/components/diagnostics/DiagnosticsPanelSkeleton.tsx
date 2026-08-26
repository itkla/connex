import { Skeleton } from "@/components/ui/skeleton";

import type { DiagnosticsScope } from "./DiagnosticsPanel";

const AGGREGATE_SECTION_COUNT = 4;

function SectionHeading({ withAction }: { withAction?: boolean }) {
    return (
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
            <div className="space-y-1.5">
                <Skeleton className="h-4 w-44" />
                <Skeleton className="h-3.5 w-80 max-w-full" />
            </div>
            {withAction ? <Skeleton className="h-8 w-24 shrink-0 rounded-full" /> : null}
        </div>
    );
}

/**
 * First-load stand-in for a diagnostics panel: the refresh control, the four aggregate sections —
 * profile capabilities, provider readiness, job runs, secret store — and the mail section.
 *
 * The mail section owns its own endpoint and renders immediately rather than waiting on the
 * aggregate, so it is drawn in its settled initial shape, not as rows of bones: at workspace scope
 * that is a header carrying the send-test action over an empty body, and at organization scope a
 * header over the single line explaining that the send test is workspace-only.
 */
export default function DiagnosticsPanelSkeleton({ scope }: { scope: DiagnosticsScope }) {
    return (
        <div className="space-y-10" aria-busy="true">
            <div className="flex items-center justify-end">
                <Skeleton className="h-8 w-24 rounded-full" />
            </div>

            {Array.from({ length: AGGREGATE_SECTION_COUNT }, (_, section) => (
                <section key={section} className="space-y-4">
                    <SectionHeading />
                    <div className="space-y-2">
                        <Skeleton className="h-9 w-full rounded-lg" />
                        <Skeleton className="h-9 w-4/5 rounded-lg" />
                        <Skeleton className="h-9 w-2/3 rounded-lg" />
                    </div>
                </section>
            ))}

            <section className="space-y-4">
                <SectionHeading withAction={scope === "workspace"} />
                {scope === "workspace" ? null : (
                    <div className="rounded-lg border border-dashed border-border bg-card/40 px-4 py-3">
                        <Skeleton className="h-4 w-72 max-w-full" />
                    </div>
                )}
            </section>
        </div>
    );
}
