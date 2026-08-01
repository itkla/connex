import { Skeleton } from '@/components/ui/skeleton';

export default function ReportDocumentLoading() {
    return (
        <div className="min-h-full px-2 pb-16 pt-8">
            <div className="mx-auto w-full max-w-[100rem]">
                <div className="mb-6 flex flex-wrap items-end justify-between gap-4 rounded-2xl border border-border bg-card p-4">
                    <div className="flex flex-wrap items-end gap-3">
                        {Array.from({ length: 2 }).map((_, i) => (
                            <div key={i} className="space-y-1.5">
                                <Skeleton className="h-3.5 w-20" />
                                <Skeleton className="h-9 w-40 rounded-lg" />
                            </div>
                        ))}
                        <Skeleton className="h-9 w-24 rounded-md" />
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                        <Skeleton className="h-9 w-28 rounded-md" />
                        <Skeleton className="h-9 w-32 rounded-md" />
                        <Skeleton className="h-9 w-28 rounded-md" />
                        <Skeleton className="h-9 w-20 rounded-md" />
                    </div>
                </div>

                <div className="mx-auto max-w-6xl rounded-2xl border border-border bg-card px-6 py-10 sm:px-10 lg:px-16">
                    <Skeleton className="h-3 w-24" />
                    <Skeleton className="mt-4 h-12 w-2/3" />
                    <Skeleton className="mt-4 h-4 w-1/2" />
                    <div className="mt-12 space-y-3">
                        <Skeleton className="h-8 w-56" />
                        <Skeleton className="h-4 w-full" />
                        <Skeleton className="h-4 w-11/12" />
                        <Skeleton className="h-4 w-4/5" />
                    </div>
                    <div className="mt-12 grid grid-cols-1 gap-5 md:grid-cols-2">
                        <Skeleton className="h-80 rounded-2xl" />
                        <Skeleton className="h-80 rounded-2xl" />
                    </div>
                </div>
            </div>
        </div>
    );
}
