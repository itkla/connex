import { Skeleton } from "@/components/ui/skeleton";

function ScopeSectionSkeleton({ rows }: { rows: number }) {
    return (
        <section className="space-y-3">
            <Skeleton className="h-3.5 w-40" />
            <ul className="gap-x-10 [&>li]:mb-1 [&>li]:break-inside-avoid xl:columns-2 2xl:columns-3">
                {Array.from({ length: rows }, (_, index) => (
                    <li key={index} className="px-3 py-2">
                        <Skeleton className="h-4 w-48" />
                    </li>
                ))}
            </ul>
        </section>
    );
}

export default function SettingsHomeLoading() {
    return (
        <div className="flex flex-col gap-8">
            <div className="space-y-2">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>
            <div className="grid gap-x-12 gap-y-6 lg:grid-cols-[minmax(11rem,14rem)_minmax(0,1fr)]">
                <div className="flex flex-col gap-6">
                    <Skeleton className="h-9 w-full rounded-full" />
                    <div className="hidden space-y-3 border-l border-border pl-4 lg:block">
                        <Skeleton className="h-4 w-24" />
                        <Skeleton className="h-4 w-28" />
                        <Skeleton className="h-4 w-24" />
                    </div>
                </div>
                <div className="flex flex-col gap-12">
                    <ScopeSectionSkeleton rows={5} />
                    <ScopeSectionSkeleton rows={6} />
                    <ScopeSectionSkeleton rows={5} />
                </div>
            </div>
        </div>
    );
}
