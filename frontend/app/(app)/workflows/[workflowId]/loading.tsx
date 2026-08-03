import { Skeleton } from "@/components/ui/skeleton";

export default function WorkflowEditorLoading() {
    return (
        <div className="flex min-h-[calc(100dvh-4rem)] flex-col bg-background">
            <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3">
                <Skeleton className="size-8 rounded-md" />
                <Skeleton className="h-9 w-64 max-w-full" />
                <Skeleton className="ml-auto h-9 w-32" />
            </div>
            <div className="grid min-h-0 flex-1 lg:grid-cols-[minmax(0,1fr)_24rem]">
                <Skeleton className="m-4 hidden rounded-2xl lg:block" />
                <Skeleton className="m-4 min-h-96 rounded-2xl" />
            </div>
        </div>
    );
}
