import DiagnosticsPanelSkeleton from "@/app/components/diagnostics/DiagnosticsPanelSkeleton";
import { Skeleton } from "@/components/ui/skeleton";

const AUDIT_ROWS = 3;

/**
 * First-load stand-in for the organization's Audit & diagnostics: the page heading, the audit log's
 * list, then the diagnostics panel's own shared skeleton — the same one its legacy route draws, so
 * the section keeps one shape in both homes.
 */
export default function OrganizationAuditDiagnosticsLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: AUDIT_ROWS }, (_, row) => (
                        <li key={row} className="flex items-center justify-between gap-3 px-4 py-3">
                            <Skeleton className="h-4 w-48" />
                            <Skeleton className="h-3 w-16 shrink-0" />
                        </li>
                    ))}
                </ul>
            </section>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>
                <DiagnosticsPanelSkeleton scope="organization" />
            </section>
        </div>
    );
}
