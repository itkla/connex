import { notFound } from "next/navigation";
import { headers } from "next/headers";

import { PageShell } from "@/app/components/PageShell";
import { WorkflowRecipeDetail } from "@/app/components/settings/workflows/recipes/WorkflowRecipeGallery";
import { getCapabilitiesResultFromCookie } from "@/app/lib/api";

const RECIPE_KEYS = new Set([
    "person-job-change-follow-up",
    "deal-won-handoff",
    "cooling-company-review",
]);

export default async function WorkflowRecipePage({ params }: { params: Promise<{ recipeKey: string }> }) {
    const { recipeKey } = await params;
    if (!RECIPE_KEYS.has(recipeKey)) notFound();
    const capabilities = await getCapabilitiesResultFromCookie((await headers()).get("cookie"));
    return (
        <PageShell>
            <WorkflowRecipeDetail recipeKey={recipeKey} definitionAuthoringEnabled={capabilities.ok && capabilities.data.workflowDefinitionSchemaVersion === 2} />
        </PageShell>
    );
}
