import { PageShell } from "@/app/components/PageShell";
import WorkflowRecipeDetailSkeleton from "@/app/components/settings/workflows/recipes/WorkflowRecipeDetailSkeleton";

export default function WorkflowRecipeLoading() {
    return (
        <PageShell>
            <WorkflowRecipeDetailSkeleton />
        </PageShell>
    );
}
