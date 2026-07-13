import { Skeleton } from '@/components/ui/skeleton';

export default function GoalsLoading() {
    return (
        <div className="min-h-full bg-background px-2 pb-12 pt-8">
            <div className="mx-auto w-full max-w-[100rem] space-y-8">
                <div className="space-y-3">
                    <Skeleton className="h-4 w-28" />
                    <Skeleton className="h-10 w-64" />
                    <Skeleton className="h-4 w-full max-w-xl" />
                </div>
                <Skeleton className="h-80 w-full rounded-2xl" />
            </div>
        </div>
    );
}
