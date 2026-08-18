import { Skeleton } from "@/components/ui/skeleton";

/**
 * Stands in for the print sheet: the print control above the paper, then the document body itself —
 * title block, meta line, and the line-item table the generated document lays out.
 */
export default function DealDocumentPrintLoading() {
    return (
        <div className="min-h-full bg-muted/40 px-4 py-10">
            <div className="mx-auto mb-6 flex max-w-[52rem] items-center justify-end">
                <Skeleton className="h-9 w-24 rounded-full" />
            </div>

            <div className="mx-auto max-w-[52rem] rounded-2xl border border-border bg-card px-12 py-14 shadow-sm">
                <Skeleton className="h-3 w-24" />
                <Skeleton className="mt-4 h-10 w-2/3" />
                <Skeleton className="mt-4 h-4 w-1/2" />

                <div className="mt-12 space-y-2">
                    <Skeleton className="h-4 w-full" />
                    <Skeleton className="h-4 w-11/12" />
                    <Skeleton className="h-4 w-3/4" />
                </div>

                <div className="mt-12 space-y-3">
                    <div className="flex items-center gap-6 border-b border-border pb-3">
                        <Skeleton className="h-3 w-32" />
                        <Skeleton className="h-3 w-16" />
                        <Skeleton className="ml-auto h-3 w-20" />
                    </div>
                    {Array.from({ length: 4 }, (_, row) => (
                        <div key={row} className="flex items-center gap-6 py-1">
                            <Skeleton className="h-4 w-48" />
                            <Skeleton className="h-4 w-10" />
                            <Skeleton className="ml-auto h-4 w-24" />
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}
