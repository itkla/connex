import { Skeleton } from "@/components/ui/skeleton";

export default function RulesLoading() {
    return (
        <div className="space-y-4">
            <div className="flex items-start justify-between gap-4">
                <div className="space-y-1">
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-4 w-72 max-w-full" />
                </div>
            </div>

            <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                {Array.from({ length: 3 }, (_, i) => (
                    <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                        <Skeleton className="h-5 w-9 shrink-0 rounded-full" />
                        <div className="flex-1 space-y-2">
                            <Skeleton className="h-3.5 w-32" />
                            <Skeleton className="h-3 w-52" />
                        </div>
                    </li>
                ))}
            </ul>
        </div>
    );
}
