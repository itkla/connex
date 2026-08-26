import { notFound } from "next/navigation";

import { PageShell } from "@/app/components/PageShell";
import { WorkflowRecipeDetail } from "@/app/components/settings/workflows/recipes/WorkflowRecipeGallery";

const RECIPE_KEYS = new Set([
    "person-job-change-follow-up",
    "deal-won-handoff",
    "cooling-company-review",
]);

export default async function WorkflowRecipePage({ params }: { params: Promise<{ recipeKey: string }> }) {
    const { recipeKey } = await params;
    if (!RECIPE_KEYS.has(recipeKey)) notFound();
    return (
        <PageShell>
            <WorkflowRecipeDetail recipeKey={recipeKey} />
        </PageShell>
    );
}
