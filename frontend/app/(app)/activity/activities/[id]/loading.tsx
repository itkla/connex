import { Skeleton } from "@/components/ui/skeleton";

export default function Loading() {
    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
                <Skeleton className="h-4 w-28" />
                <div className="flex items-start gap-3.5">
                    <Skeleton className="size-10 rounded-full" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-8 w-2/3" />
                        <Skeleton className="h-4 w-40" />
                    </div>
                </div>
                <Skeleton className="h-24 w-full rounded-2xl" />
            </div>
        </div>
    );
}
