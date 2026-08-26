import { PageShell } from "@/app/components/PageShell";
import { WorkflowRecipeGallery } from "@/app/components/settings/workflows/recipes/WorkflowRecipeGallery";

export default function WorkflowRecipesPage() {
    return (
        <PageShell>
            <WorkflowRecipeGallery />
        </PageShell>
    );
}
