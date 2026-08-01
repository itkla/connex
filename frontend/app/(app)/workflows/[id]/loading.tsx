import { Skeleton } from "@/components/ui/skeleton";

export default function WorkflowEditorLoading() {
    return (
        <div className="space-y-4">
            <Skeleton className="h-10 w-full max-w-xl" />
            <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_380px]">
                <Skeleton className="hidden h-[480px] rounded-2xl lg:block" />
                <Skeleton className="h-[480px] rounded-2xl" />
            </div>
        </div>
    );
}
