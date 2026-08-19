import { Skeleton } from '@/components/ui/skeleton';

export default function EditDocumentTemplateLoading() {
    return (
        <div className="min-h-full bg-background">
            <div className="sticky top-0 z-20 border-b border-border bg-background/85 backdrop-blur">
                <div className="flex w-full items-center gap-4 px-4 py-3 sm:px-6">
                    <Skeleton className="h-8 w-20 rounded-md" />
                    <div className="min-w-0 flex-1">
                        <Skeleton className="h-6 w-56 max-w-full" />
                    </div>
                    <Skeleton className="h-9 w-24 rounded-md" />
                </div>
            </div>

            <div className="grid w-full gap-8 px-4 py-6 sm:px-6 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)] lg:gap-10">
                <div>
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="flex flex-col gap-4 px-1 sm:px-6">
                        {Array.from({ length: 3 }).map((_, i) => (
                            <div key={i} className="grid gap-1.5">
                                <Skeleton className="h-3.5 w-20" />
                                <Skeleton className="h-9 w-full rounded-lg" />
                            </div>
                        ))}
                        <div className="flex items-start gap-2">
                            <Skeleton className="mt-0.5 size-4 rounded" />
                            <Skeleton className="h-4 w-32" />
                        </div>
                    </div>
                </div>

                <div className="min-w-0">
                    <div className="mb-3 flex h-8 items-center px-6">
                        <Skeleton className="h-3 w-24" />
                    </div>
                    <div className="rounded-2xl border border-border bg-muted/40 p-3 sm:p-6">
                        <div className="mx-auto max-w-[52rem] rounded-xl border border-border bg-card px-4 py-6 shadow-sm sm:px-10 sm:py-12">
                            <div className="mb-8 flex items-start justify-between gap-4">
                                <div className="min-w-0 flex-1 space-y-2">
                                    <Skeleton className="h-3 w-24" />
                                    <Skeleton className="h-8 w-3/4" />
                                </div>
                                <div className="hidden shrink-0 space-y-1.5 sm:block">
                                    <Skeleton className="ml-auto h-3 w-20" />
                                    <Skeleton className="ml-auto h-3 w-24" />
                                </div>
                            </div>
                            <div className="space-y-3">
                                <Skeleton className="h-4 w-full" />
                                <Skeleton className="h-4 w-11/12" />
                                <Skeleton className="h-4 w-4/5" />
                            </div>
                            <Skeleton className="mt-8 h-44 w-full rounded-lg" />
                        </div>
                        <div className="mt-3 flex justify-center">
                            <Skeleton className="h-3 w-64 max-w-full" />
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
