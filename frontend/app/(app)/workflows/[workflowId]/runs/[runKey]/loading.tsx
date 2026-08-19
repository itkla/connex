import { PageShell } from "@/app/components/PageShell";
import WorkflowRunDetailSkeleton from "@/app/components/settings/workflows/operations/WorkflowRunDetailSkeleton";

export default function WorkflowRunLoading() {
    return (
        <PageShell>
            <WorkflowRunDetailSkeleton />
        </PageShell>
    );
}
