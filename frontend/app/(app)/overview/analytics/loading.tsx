import { Skeleton } from '@/components/ui/skeleton';

function PanelSkeleton({ className, chart = 'h-64' }: { className?: string; chart?: string }) {
    return (
        <section className={`flex h-full flex-col rounded-2xl border border-border bg-card p-6${className ? ` ${className}` : ''}`}>
            <div className="flex items-center justify-between gap-4">
                <div className="space-y-2">
                    <Skeleton className="h-3 w-28" />
                    <Skeleton className="h-4 w-40" />
                </div>
                <Skeleton className="h-3 w-16" />
            </div>
            <Skeleton className={`mt-6 w-full ${chart}`} />
        </section>
    );
}

function KpiRowSkeleton() {
    return (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
                <div key={i} className="flex flex-col gap-4 rounded-2xl border border-border bg-card px-5 py-4">
                    <Skeleton className="h-3 w-20" />
                    <Skeleton className="h-8 w-24" />
                    <Skeleton className="h-3 w-16" />
                </div>
            ))}
        </div>
    );
}

function SectionHeadingSkeleton() {
    return (
        <div className="space-y-2">
            <Skeleton className="h-7 w-48" />
            <Skeleton className="h-4 w-80 max-w-full" />
        </div>
    );
}

export default function AnalyticsLoading() {
    return (
        <div className="mx-auto w-full max-w-[100rem] space-y-6 px-2 pb-12">
            <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
                <div className="space-y-2">
                    <Skeleton className="h-9 w-48" />
                    <Skeleton className="h-4 w-72" />
                </div>
                <Skeleton className="h-9 w-56 rounded-full" />
            </header>

            <SectionHeadingSkeleton />
            <KpiRowSkeleton />

            <SectionHeadingSkeleton />

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <PanelSkeleton className="lg:col-span-3" chart="h-72" />
                <PanelSkeleton className="lg:col-span-2" />
            </div>

            <SectionHeadingSkeleton />

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <PanelSkeleton className="lg:col-span-3" />
                <PanelSkeleton className="lg:col-span-2" chart="h-48" />
            </div>
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <PanelSkeleton className="lg:col-span-3" />
                <PanelSkeleton className="lg:col-span-2" chart="h-48" />
            </div>
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <PanelSkeleton className="lg:col-span-3" />
                <PanelSkeleton className="lg:col-span-2" chart="h-48" />
            </div>
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <PanelSkeleton className="lg:col-span-3" />
                <PanelSkeleton className="lg:col-span-2" chart="h-48" />
            </div>
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
                <PanelSkeleton className="lg:col-span-3" />
                <PanelSkeleton className="lg:col-span-2" chart="h-48" />
            </div>
        </div>
    );
}
