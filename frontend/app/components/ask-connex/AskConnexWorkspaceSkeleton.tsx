import { Skeleton } from '@/components/ui/skeleton';

/**
 * The shape the Ask Connex workspace arrives in: the session rail on the left, a titled
 * conversation header, an alternating transcript, and the composer. Both workspace routes render
 * it, so a new chat and a deep-linked one paint the same bones instead of two near-copies.
 */
export default function AskConnexWorkspaceSkeleton() {
    return (
        <div aria-hidden className="flex h-full min-h-0 bg-background">
            <div className="hidden min-h-0 w-72 shrink-0 flex-col gap-3 border-r border-border bg-muted/30 p-4 md:flex">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-9 w-full rounded-lg" />
                <div className="space-y-2 pt-2">
                    <Skeleton className="h-10 w-full rounded-lg" />
                    <Skeleton className="h-10 w-11/12 rounded-lg" />
                    <Skeleton className="h-10 w-4/5 rounded-lg" />
                    <Skeleton className="h-10 w-10/12 rounded-lg" />
                </div>
            </div>
            <div className="flex min-w-0 flex-1 flex-col">
                <div className="flex shrink-0 items-center gap-2 border-b border-border px-5 py-3">
                    <Skeleton className="h-4 w-48" />
                </div>
                <div className="mx-auto w-full max-w-4xl flex-1 space-y-6 px-4 py-6">
                    <div className="ml-auto w-3/5 space-y-2">
                        <Skeleton className="h-3 w-full" />
                        <Skeleton className="h-3 w-2/3" />
                    </div>
                    <div className="w-11/12 space-y-2">
                        <Skeleton className="h-3 w-full" />
                        <Skeleton className="h-3 w-11/12" />
                        <Skeleton className="h-3 w-9/12" />
                        <Skeleton className="h-3 w-10/12" />
                    </div>
                    <div className="ml-auto w-2/5 space-y-2">
                        <Skeleton className="h-3 w-full" />
                    </div>
                </div>
                <div className="mx-auto w-full max-w-4xl shrink-0 p-3">
                    <Skeleton className="h-24 w-full rounded-2xl" />
                </div>
            </div>
        </div>
    );
}
