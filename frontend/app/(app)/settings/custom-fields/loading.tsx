import { Skeleton } from "@/components/ui/skeleton";

export default function CustomFieldsLoading() {
    return (
        <div className="space-y-10">
            {Array.from({ length: 3 }, (_, section) => (
                <section key={section} className="space-y-3">
                    <div className="flex items-center justify-between gap-4">
                        <Skeleton className="h-3 w-20" />
                        <Skeleton className="h-8 w-28 rounded-md" />
                    </div>
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {Array.from({ length: 2 }, (_, row) => (
                            <li key={row} className="flex items-center gap-3 px-4 py-3.5">
                                <div className="flex-1 space-y-2">
                                    <Skeleton className="h-3.5 w-32" />
                                    <Skeleton className="h-3 w-48" />
                                </div>
                            </li>
                        ))}
                    </ul>
                </section>
            ))}
        </div>
    );
}
